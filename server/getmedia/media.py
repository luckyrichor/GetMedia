"""媒体库（服务端归档）与缓存配额。

只做"元数据 + 可选文件"的记账，不含平台解析：
- ``storage='metadata'``：只有一行记录，媒体始终由客户端当场解析；
- ``storage='archive'``：文件长期保存在 ``media_dir``，计入 owner 配额；
- ``storage='cache'``：文件在 ``cache_dir``，到期即可删除，计入网页缓存配额。

所有写操作都按 ``user_id`` 过滤；``updated_seq`` 单调递增，供 ``/v2/sync`` 做增量。
"""
from __future__ import annotations

import hashlib
import logging
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Literal

from sqlalchemy import text
from sqlalchemy.engine import Engine

from . import ratelimit
from .config import Settings

log = logging.getLogger("getmedia.media")

STORAGES = ("metadata", "archive", "cache")
MEDIA_TYPES = ("audio", "video")


class MediaError(Exception):
    """带稳定 code 的业务错误，由 HTTP 层翻译。

    ``extra`` 里的键会平铺进错误响应体（如 409 带上冲突的 ``track``）；
    ``headers`` 直接给响应头（如 429 的 ``Retry-After``，客户端要靠它退避）。
    """

    def __init__(self, code: str, detail: str, status: int = 400, *,
                 extra: dict | None = None, headers: dict | None = None):
        super().__init__(detail)
        self.code, self.detail, self.status = code, detail, status
        self.extra = extra or {}
        self.headers = headers or {}


@dataclass(frozen=True)
class TrackFile:
    path: Path
    bytes: int


def _row_to_dict(row) -> dict:
    m = row._mapping
    return {
        "id": m["id"],
        "media_type": m["media_type"],
        "platform": m["platform"],
        "video_id": m["video_id"],
        "page": m["page"],
        "title": m["title"],
        "uploader": m["uploader"],
        "duration_sec": m["duration_sec"],
        "cover_url": m["cover_url"],
        "source_url": m["source_url"],
        "storage": m["storage"],
        "bytes": m["bytes"],
        "pinned": bool(m["pinned"]),
        "deleted": bool(m["deleted"]),
        "updated_seq": m["updated_seq"],
        "created_at": m["created_at"],
        "updated_at": m["updated_at"],
        "cache_expires_at": m["cache_expires_at"],
        "has_file": m["media_path"] is not None,
    }


_COLS = ("id, user_id, media_type, platform, video_id, page, title, uploader, duration_sec, "
         "cover_url, source_url, storage, media_path, bytes, sha256, pinned, deleted, "
         "updated_seq, created_at, updated_at, cache_expires_at, last_access_at")


class MediaStore:
    def __init__(self, engine: Engine, settings: Settings, clock):
        self.engine, self.s, self.clock = engine, settings, clock

    def now(self) -> int:
        return int(self.clock())

    # ---------- 序号 ----------
    @staticmethod
    def _next_seq(c) -> int:
        return int(c.execute(text("SELECT COALESCE(MAX(updated_seq),0)+1 FROM tracks")).scalar_one())

    # ---------- 读 ----------
    def get(self, user_id: str, track_id: int, *, include_deleted: bool = False,
            include_file_hash: bool = False) -> dict:
        with self.engine.begin() as c:
            row = c.execute(text(f"SELECT {_COLS} FROM tracks WHERE id=:i AND user_id=:u"),
                            {"i": track_id, "u": user_id}).first()
        if row is None or (row._mapping["deleted"] and not include_deleted):
            raise MediaError("track_not_found", "媒体不存在", 404)
        result = _row_to_dict(row)
        if include_file_hash and row._mapping["media_path"] and row._mapping["sha256"]:
            result["sha256"] = row._mapping["sha256"]
        return result

    def list(self, user_id: str, *, media_type: str | None = None, since_seq: int = 0,
             limit: int = 200, include_deleted: bool = False) -> list[dict]:
        sql = f"SELECT {_COLS} FROM tracks WHERE user_id=:u AND updated_seq>:s"
        params: dict = {"u": user_id, "s": since_seq, "l": limit}
        if media_type:
            sql += " AND media_type=:m"
            params["m"] = media_type
        if not include_deleted:
            sql += " AND deleted=0"
        sql += " ORDER BY updated_seq ASC LIMIT :l"
        with self.engine.begin() as c:
            rows = c.execute(text(sql), params).all()
        return [_row_to_dict(r) for r in rows]

    def storage_used(self, storage: str, *, now: int | None = None) -> int:
        """当前占用：archive 全量（含 AI 素材与产物）；cache 只算未过期的。"""
        now = self.now() if now is None else now
        with self.engine.begin() as c:
            if storage == "cache":
                return int(c.execute(text(
                    "SELECT COALESCE(SUM(bytes),0) FROM tracks WHERE storage='cache' AND deleted=0 "
                    "AND (cache_expires_at IS NULL OR cache_expires_at>:n)"), {"n": now}).scalar_one())
            used = int(c.execute(text(
                "SELECT COALESCE(SUM(bytes),0) FROM tracks WHERE storage=:s AND deleted=0"),
                {"s": storage}).scalar_one())
        # AI 重建的源文件与产物不在 tracks 表里（在 media_dir/ai/<项目号>/），
        # 但按方案 §8.1 计入服务器库（archive）3 GiB，所以这里要一起算。
        if storage == "archive":
            used += self.ai_stats()["bytes"]
        return used

    def ai_stats(self) -> dict:
        """``media_dir/ai`` 下的文件数与字节数（按目录求和，不新增表字段）。"""
        root = Path(self.s.media_dir) / "ai"
        files = size = 0
        if root.is_dir():
            for p in root.rglob("*"):
                if not p.is_file():
                    continue
                try:
                    size += p.stat().st_size
                except OSError:  # 正在被 worker 写/删：跳过，不为此报错
                    continue
                files += 1
        return {"files": files, "bytes": size}

    def quota_of(self, storage: str) -> int:
        return self.s.archive_quota_bytes if storage == "archive" else self.s.cache_quota_bytes

    def ensure_room(self, storage: str, need_bytes: int) -> None:
        """只有 archive/cache 需要预先拒绝；metadata/phone 模式不落盘。"""
        if storage not in ("archive", "cache"):
            return
        quota = self.quota_of(storage)
        if quota <= 0:
            raise MediaError("storage_disabled", f"{storage} 模式未开启", 409)
        used = self.storage_used(storage)
        if used + max(0, need_bytes) > quota:
            if storage == "cache":
                self.prune_cache(force_bytes=used + need_bytes - quota)
                used = self.storage_used(storage)
            if used + max(0, need_bytes) > quota:
                label = "服务器库" if storage == "archive" else "网页缓存"
                raise MediaError("quota_exceeded", f"{label}空间不足，请先清理或改用其他存储方式", 409)

    # ---------- 写 ----------
    def upsert(self, user_id: str, *, media_type: str, platform: str, video_id: str, page: int,
               title: str, uploader: str | None = None, duration_sec: float | None = None,
               cover_url: str | None = None, source_url: str | None = None,
               storage: str = "metadata", pinned: bool | None = None) -> dict:
        if media_type not in MEDIA_TYPES:
            raise MediaError("bad_media_type", "media_type 只能是 audio 或 video")
        if storage not in STORAGES:
            raise MediaError("bad_storage", "storage 只能是 metadata / archive / cache")
        now = self.now()
        with self.engine.begin() as c:
            seq = self._next_seq(c)
            row = c.execute(text(
                "SELECT id, pinned, storage FROM tracks WHERE user_id=:u AND platform=:p AND video_id=:v AND page=:g"),
                {"u": user_id, "p": platform, "v": video_id, "g": page}).first()
            if row is None:
                c.execute(text(
                    "INSERT INTO tracks(user_id, media_type, platform, video_id, page, title, uploader, duration_sec,"
                    " cover_url, source_url, storage, pinned, updated_seq, created_at, updated_at)"
                    " VALUES(:u,:mt,:p,:v,:g,:t,:up,:d,:cv,:su,:st,:pin,:seq,:n,:n)"),
                    {"u": user_id, "mt": media_type, "p": platform, "v": video_id, "g": page, "t": title[:200],
                     "up": uploader, "d": duration_sec, "cv": cover_url, "su": source_url, "st": storage,
                     "pin": int(bool(pinned)) if pinned is not None else 0, "seq": seq, "n": now})
                track_id = int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())
            else:
                track_id = int(row._mapping["id"])
                # 已存在的行只补非空字段；pinned 只有显式传入才改（避免把用户手动的置顶洗掉）
                c.execute(text(
                    "UPDATE tracks SET media_type=:mt, title=:t, uploader=COALESCE(:up,uploader),"
                    " duration_sec=COALESCE(:d,duration_sec), cover_url=COALESCE(:cv,cover_url),"
                    " source_url=COALESCE(:su,source_url), storage=:st,"
                    " pinned=CASE WHEN :pin IS NULL THEN pinned ELSE :pin END, updated_seq=:seq, updated_at=:n"
                    " WHERE id=:i"),
                    {"mt": media_type, "t": title[:200], "up": uploader, "d": duration_sec, "cv": cover_url,
                     "su": source_url, "st": storage, "pin": (int(bool(pinned)) if pinned is not None else None),
                     "seq": seq, "n": now, "i": track_id})
            row = c.execute(text(f"SELECT {_COLS} FROM tracks WHERE id=:i"), {"i": track_id}).first()
        return _row_to_dict(row)

    def _hit_push(self, user_id: str) -> None:
        """手机端写入的账号级配额（默认 60 条/分钟）。

        与备份上传同一套落库固定窗口：重启不丢，**独立短事务**——被拒时不能把这个计数一起回滚掉。
        超限返回 429 + ``Retry-After``，客户端按它整批退避，而不是继续硬打。
        """
        limit, window = self.s.library_push_limit
        now = int(self.now())
        with self.engine.begin() as c:
            ok, retry = ratelimit.hit(c, f"library-push:{user_id}", limit, window, now)
        if not ok:
            raise MediaError("rate_limited", "同步太频繁，请稍后再试", 429,
                             headers={"Retry-After": str(retry)})

    def create_metadata(self, user_id: str, *, media_type: str, platform: str, video_id: str,
                        page: int, title: str, uploader: str | None = None,
                        duration_sec: float | None = None,
                        source_url: str | None = None) -> tuple[dict, bool]:
        """手机端"这条要进曲库"：新建一条仅元数据的行；**同键已存在就一个字都不改**。

        与 :meth:`upsert` 的差别只有一处，但很关键：服务器上的 ``title`` 是权威，手机上传只是
        告诉服务器"这条存在"。若这里照 ``upsert`` 那样覆盖标题，网页端改过的名字会被手机上的旧标题洗掉，
        还会白白递增 ``updated_seq``，让所有设备重拉一遍。

        墓碑行**不复活**：返回 409（``track_deleted``）并带上该行，由客户端弹"云端已删除"确认。
        复活会让另一台设备刚做的删除失效。返回 ``(track, created)``。
        """
        self._hit_push(user_id)
        if media_type not in MEDIA_TYPES:
            raise MediaError("bad_media_type", "media_type 只能是 audio 或 video")
        platform, video_id = (platform or "").strip(), (video_id or "").strip()
        if not platform or not video_id:
            raise MediaError("bad_key", "platform 与 video_id 不能为空")
        if len(platform) > 32 or len(video_id) > 128:
            raise MediaError("bad_key", "platform 或 video_id 过长")
        if page < 1 or page > 9999:
            raise MediaError("bad_page", "page 必须是 1-9999 的整数")
        clean_title = (title or "").strip()[:200]
        if not clean_title:
            raise MediaError("bad_title", "标题不能为空")

        params = {"u": user_id, "p": platform, "v": video_id, "g": page}
        with self.engine.begin() as c:
            row = c.execute(text(f"SELECT {_COLS} FROM tracks WHERE user_id=:u AND platform=:p "
                                 "AND video_id=:v AND page=:g"), params).first()
            if row is not None:
                track = _row_to_dict(row)
                if track["deleted"]:
                    raise MediaError("track_deleted", "该条目已被删除", 409, extra={"track": track})
                return track, False

            cap = int(getattr(self.s, "library_max_rows", 0) or 0)
            if cap > 0:
                used = int(c.execute(text("SELECT COUNT(*) FROM tracks WHERE user_id=:u AND deleted=0"),
                                     {"u": user_id}).scalar_one())
                if used >= cap:
                    raise MediaError("library_full", f"云端曲库已达上限（{cap} 条），请先在网页端清理", 409)

            seq = self._next_seq(c)
            now = self.now()
            c.execute(text(
                "INSERT INTO tracks(user_id, media_type, platform, video_id, page, title, uploader,"
                " duration_sec, source_url, storage, pinned, updated_seq, created_at, updated_at)"
                " VALUES(:u,:mt,:p,:v,:g,:t,:up,:d,:su,'metadata',0,:seq,:n,:n)"),
                {**params, "mt": media_type, "t": clean_title, "up": uploader,
                 "d": duration_sec, "su": source_url, "seq": seq, "n": now})
            track_id = int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())
            row = c.execute(text(f"SELECT {_COLS} FROM tracks WHERE id=:i"), {"i": track_id}).first()
        return _row_to_dict(row), True

    def patch(self, user_id: str, track_id: int, *, title: str | None = None,
              uploader: str | None = None, pinned: bool | None = None) -> dict:
        sets, params = [], {"i": track_id, "u": user_id, "n": self.now()}
        if title is not None:
            if not title.strip():
                raise MediaError("bad_title", "标题不能为空")
            sets.append("title=:t"); params["t"] = title.strip()[:200]
        if uploader is not None:
            sets.append("uploader=:up"); params["up"] = uploader.strip() or None
        if pinned is not None:
            sets.append("pinned=:pin"); params["pin"] = int(pinned)
        if not sets:
            raise MediaError("nothing_to_update", "没有要修改的字段")
        with self.engine.begin() as c:
            seq = self._next_seq(c)
            n = c.execute(text(f"UPDATE tracks SET {', '.join(sets)}, updated_seq=:seq, updated_at=:n "
                               "WHERE id=:i AND user_id=:u"), {**params, "seq": seq}).rowcount
            if not n:
                raise MediaError("track_not_found", "媒体不存在", 404)
        return self.get(user_id, track_id, include_deleted=True)

    def delete(self, user_id: str, track_id: int) -> dict:
        """逻辑删除：保留文件，同步时下发 tombstone。"""
        with self.engine.begin() as c:
            seq = self._next_seq(c)
            n = c.execute(text("UPDATE tracks SET deleted=1, updated_seq=:s, updated_at=:n "
                               "WHERE id=:i AND user_id=:u AND deleted=0"),
                          {"i": track_id, "u": user_id, "s": seq, "n": self.now()}).rowcount
            if not n:
                row = c.execute(text("SELECT id FROM tracks WHERE id=:i AND user_id=:u"),
                                {"i": track_id, "u": user_id}).first()
                if row is None:
                    raise MediaError("track_not_found", "媒体不存在", 404)
        return self.get(user_id, track_id, include_deleted=True)

    def restore(self, user_id: str, track_id: int) -> dict:
        with self.engine.begin() as c:
            seq = self._next_seq(c)
            n = c.execute(text("UPDATE tracks SET deleted=0, updated_seq=:s, updated_at=:n "
                               "WHERE id=:i AND user_id=:u AND deleted=1"),
                          {"i": track_id, "u": user_id, "s": seq, "n": self.now()}).rowcount
            if not n:
                row = c.execute(text("SELECT id FROM tracks WHERE id=:i AND user_id=:u"),
                                {"i": track_id, "u": user_id}).first()
                if row is None:
                    raise MediaError("track_not_found", "媒体不存在", 404)
        return self.get(user_id, track_id, include_deleted=True)

    # ---------- 文件 ----------
    def dir_for(self, storage: str) -> Path:
        return self.s.media_dir if storage == "archive" else self.s.cache_dir

    def file_for(self, user_id: str, track_id: int) -> TrackFile | None:
        with self.engine.begin() as c:
            row = c.execute(text("SELECT storage, media_path, bytes FROM tracks WHERE id=:i AND user_id=:u"),
                            {"i": track_id, "u": user_id}).first()
        if row is None:
            raise MediaError("track_not_found", "媒体不存在", 404)
        path = row._mapping["media_path"]
        if not path:
            return None
        full = self.dir_for(row._mapping["storage"]) / path
        if not full.is_file():
            return None
        return TrackFile(full, int(row._mapping["bytes"] or full.stat().st_size))

    def attach_file(self, user_id: str, track_id: int, *, storage: str, filename: str,
                    nbytes: int, sha256: str, ttl_seconds: int | None = None,
                    expected_seq: int | None = None) -> dict:
        now = self.now()
        expires = now + ttl_seconds if (storage == "cache" and ttl_seconds) else None
        if storage == "cache" and expires is None:
            expires = now + self.s.cache_ttl_seconds
        with self.engine.begin() as c:
            seq = self._next_seq(c)
            condition = " AND deleted=0 AND updated_seq=:expected" if expected_seq is not None else ""
            updated = c.execute(text("UPDATE tracks SET storage=:st, media_path=:p, bytes=:b, sha256=:h,"
                           " cache_expires_at=:e, updated_seq=:s, updated_at=:n, last_access_at=:n"
                           " WHERE id=:i AND user_id=:u" + condition),
                      {"st": storage, "p": filename, "b": nbytes, "h": sha256, "e": expires,
                       "s": seq, "n": now, "i": track_id, "u": user_id, "expected": expected_seq})
            if expected_seq is not None and updated.rowcount != 1:
                raise MediaError("target_changed", "原条目已变更，请重新打开", 409)
        return self.get(user_id, track_id, include_deleted=True)

    def touch(self, track_id: int) -> None:
        with self.engine.begin() as c:
            c.execute(text("UPDATE tracks SET last_access_at=:n WHERE id=:i"), {"n": self.now(), "i": track_id})

    @staticmethod
    def write_atomic(dest_dir: Path, filename: str, chunks: Iterable[bytes]) -> tuple[Path, int, str]:
        """写临时文件 → fsync → 原子改名；返回（最终路径, 字节数, sha256）。不产生半成品。"""
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest_dir.chmod(0o700)
        final = dest_dir / filename
        part = final.with_name(final.name + ".part")
        digest, size = hashlib.sha256(), 0
        try:
            with open(part, "wb") as f:
                for chunk in chunks:
                    if not chunk:
                        continue
                    f.write(chunk); digest.update(chunk); size += len(chunk)
                f.flush()
                import os as _os
                _os.fsync(f.fileno())
            part.chmod(0o600)
            part.replace(final)
        except Exception:
            part.unlink(missing_ok=True)
            raise
        return final, size, digest.hexdigest()

    def prune_cache(self, *, force_bytes: int = 0, now: int | None = None) -> dict:
        """先删过期，再按 last_access_at 淘汰到配额内；跳过最近 5 分钟被访问的文件。"""
        now = self.now() if now is None else now
        removed = {"rows": 0, "bytes": 0}
        with self.engine.begin() as c:
            expired = c.execute(text(
                "SELECT id, media_path, bytes FROM tracks WHERE storage='cache' AND deleted=0"
                " AND cache_expires_at IS NOT NULL AND cache_expires_at<=:n"), {"n": now}).all()
            keep_after = now - 300
            need = force_bytes
            if need > 0:
                rows = c.execute(text(
                    "SELECT id, media_path, bytes FROM tracks WHERE storage='cache' AND deleted=0"
                    " AND (cache_expires_at IS NULL OR cache_expires_at>:n)"
                    " AND (last_access_at IS NULL OR last_access_at<:k)"
                    " ORDER BY COALESCE(last_access_at, updated_at) ASC"), {"n": now, "k": keep_after}).all()
                freed = 0
                for r in rows:
                    if freed >= need:
                        break
                    expired.append(r)
                    freed += int(r._mapping["bytes"] or 0)
            for r in expired:
                tid, rel, nb = int(r._mapping["id"]), r._mapping["media_path"], int(r._mapping["bytes"] or 0)
                if rel:
                    p = self.s.cache_dir / rel
                    try:
                        if p.is_file():
                            size = p.stat().st_size
                            p.unlink()
                            removed["bytes"] += size
                    except OSError:
                        log.warning("缓存文件删除失败：%s", rel)
                seq = self._next_seq(c)
                c.execute(text("UPDATE tracks SET media_path=NULL, bytes=0, storage='metadata',"
                               " cache_expires_at=NULL, updated_seq=:s, updated_at=:n WHERE id=:i"),
                          {"s": seq, "n": now, "i": tid})
                removed["rows"] += 1
        return removed

    def remove_file(self, user_id: str, track_id: int) -> dict:
        tf = self.file_for(user_id, track_id)
        if tf is not None:
            try:
                tf.path.unlink()
            except OSError:
                raise MediaError("file_busy", "文件正在使用，稍后再试", 409) from None
        with self.engine.begin() as c:
            seq = self._next_seq(c)
            c.execute(text("UPDATE tracks SET media_path=NULL, bytes=0, storage='metadata',"
                           " cache_expires_at=NULL, updated_seq=:s, updated_at=:n WHERE id=:i AND user_id=:u"),
                      {"s": seq, "n": self.now(), "i": track_id, "u": user_id})
        return self.get(user_id, track_id, include_deleted=True)

    # ---------- 提取任务 ----------
    def job_create(self, user_id: str, *, media_type: str, storage: str, input_: str) -> int:
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO extract_jobs(user_id, media_type, storage, input, status, created_at)"
                           " VALUES(:u,:m,:s,:i,'queued',:n)"),
                      {"u": user_id, "m": media_type, "s": storage, "i": input_[:4000], "n": self.now()})
            return int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())

    def job_update(self, job_id: int, **fields) -> None:
        allowed = {"status", "error_code", "error_detail", "track_id", "platform", "video_id",
                   "started_at", "finished_at"}
        sets, params = [], {"i": job_id}
        for k, v in fields.items():
            if k not in allowed:
                continue
            sets.append(f"{k}=:{k}"); params[k] = v
        if not sets:
            return
        with self.engine.begin() as c:
            c.execute(text(f"UPDATE extract_jobs SET {', '.join(sets)} WHERE id=:i"), params)

    def job_get(self, user_id: str, job_id: int) -> dict:
        with self.engine.begin() as c:
            row = c.execute(text("SELECT * FROM extract_jobs WHERE id=:i AND user_id=:u"),
                            {"i": job_id, "u": user_id}).first()
        if row is None:
            raise MediaError("job_not_found", "任务不存在", 404)
        m = row._mapping
        return {k: m[k] for k in ("id", "media_type", "storage", "platform", "video_id", "status",
                                  "error_code", "error_detail", "track_id", "created_at",
                                  "started_at", "finished_at")}

    def jobs_list(self, user_id: str, limit: int = 50) -> list[dict]:
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT * FROM extract_jobs WHERE user_id=:u ORDER BY id DESC LIMIT :l"),
                             {"u": user_id, "l": limit}).all()
        return [{k: r._mapping[k] for k in ("id", "media_type", "storage", "platform", "video_id", "status",
                                            "error_code", "error_detail", "track_id", "created_at",
                                            "started_at", "finished_at")} for r in rows]

    def record_failure(self, *, user_id: str | None, platform: str | None, video_id: str | None,
                       track_id: int | None, stage: str, error_code: str, detail: str,
                       client_version: str = "") -> int:
        with self.engine.begin() as c:
            c.execute(text(
                "INSERT INTO extraction_failures(user_id, platform, video_id, track_id, stage, error_code,"
                " detail, client_version, created_at) VALUES(:u,:p,:v,:t,:s,:e,:d,:cv,:n)"),
                {"u": user_id, "p": platform, "v": video_id, "t": track_id, "s": stage[:32],
                 "e": error_code[:64], "d": detail[:2000], "cv": client_version[:32], "n": self.now()})
            return int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())

    # ---------- 维护 ----------
    def stats(self) -> dict:
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT storage, COUNT(*) n, COALESCE(SUM(bytes),0) b FROM tracks"
                                  " WHERE deleted=0 GROUP BY storage")).all()
        return {r._mapping["storage"]: {"tracks": int(r._mapping["n"]), "bytes": int(r._mapping["b"])}
                for r in rows}

    def purge_orphans(self) -> int:
        """删掉库里没有记录指向的媒体文件（迁移/异常退出后的清理）。"""
        with self.engine.begin() as c:
            known = {r[0] for r in c.execute(text("SELECT media_path FROM tracks WHERE media_path IS NOT NULL"))}
        n = 0
        for d in (self.s.media_dir, self.s.cache_dir):
            if not d.is_dir():
                continue
            for p in d.iterdir():
                if p.is_file() and p.name not in known and not p.name.endswith(".part"):
                    try:
                        p.unlink(); n += 1
                    except OSError:
                        pass
        return n


__all__ = ["MediaStore", "MediaError", "TrackFile", "STORAGES", "MEDIA_TYPES", "shutil"]
