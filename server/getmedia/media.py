"""媒体库（服务端归档）与缓存配额。

只做"元数据 + 可选文件"的记账，不含平台解析：
- ``storage='metadata'``：只有一行记录，媒体始终由客户端当场解析；
- ``storage='archive'``：文件长期保存在 ``media_dir``，计入 owner 配额；
- ``storage='cache'``：文件在 ``cache_dir``，到期即可删除，计入网页缓存配额。

所有写操作都按 ``user_id`` 过滤；``updated_seq`` 单调递增，供 ``/v2/sync`` 做增量。
"""
from __future__ import annotations

import hashlib
import json
import logging
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Literal
from uuid import uuid4

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


CATEGORY_MAX = 200


def _clean_category(value: str | None) -> str | None:
    """类别路径规整：去掉空段与首尾空白，最长 200 字；规整后为空就是「未分类」。

    用路径（``音乐/古风``）而不是类别编号做跨设备身份：手机上的类别 id 是本地自增的，
    两台机器同一类别的编号几乎不可能相同，路径才是唯一能对齐的东西。
    """
    if not value:
        return None
    parts = [p.strip() for p in str(value).split("/")]
    out = "/".join(p for p in parts if p)
    return out[:CATEGORY_MAX] or None


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
        # 整理信息（schema v19）：收藏是布尔，类别是路径字符串（"音乐/古风"），
        # 空串表示未分类。用路径而不是编号，因为两台设备的类别编号互不相干。
        "favorite": bool(m["favorite"]),
        "category": m["category"] or "",
    }


_COLS = ("id, user_id, media_type, platform, video_id, page, title, uploader, duration_sec, "
         "cover_url, source_url, storage, media_path, bytes, sha256, pinned, deleted, "
         "updated_seq, created_at, updated_at, cache_expires_at, last_access_at, favorite, category")


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
        """当前占用：archive 全量（含 AI 素材与产物）；cache 只算未过期的。

        批量提取落下的文件也算进同一个池子——服务器只有一块盘，配额必须只有一本账。
        """
        now = self.now() if now is None else now
        with self.engine.begin() as c:
            # **不看 ``deleted``**：墓碑行只要还挂着文件就仍占着盘。平台条目被删后
            # 缓存文件要留到过期（另一台设备可能还在播），这段占用必须算进去，
            # 否则配额会越用越"虚"，最后写不进去却显示还有空间。
            if storage == "cache":
                used = int(c.execute(text(
                    "SELECT COALESCE(SUM(bytes),0) FROM tracks WHERE storage='cache' "
                    "AND (cache_expires_at IS NULL OR cache_expires_at>:n)"), {"n": now}).scalar_one())
            else:
                used = int(c.execute(text(
                    "SELECT COALESCE(SUM(bytes),0) FROM tracks WHERE storage=:s"),
                    {"s": storage}).scalar_one())
            used += self._creator_used(c, storage, now)
        # AI 重建的源文件与产物不在 tracks 表里（在 media_dir/ai/<项目号>/），
        # 但按方案 §8.1 计入服务器库（archive）配额，所以这里要一起算。
        if storage == "archive":
            used += self.ai_stats()["bytes"]
        return used

    @staticmethod
    def _creator_used(c, storage: str, now: int) -> int:
        sql = ("SELECT COALESCE(SUM(i.bytes),0) FROM creator_items i"
               " JOIN creator_jobs j ON j.id=i.job_id"
               " WHERE j.storage=:s AND i.media_path IS NOT NULL")
        params = {"s": storage}
        if storage == "cache":
            sql += " AND (i.store_expires_at IS NULL OR i.store_expires_at>:n)"
            params["n"] = now
        return int(c.execute(text(sql), params).scalar_one())

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
        """落盘前腾地方：缓存删过期/最久没用的；归档满了按存入时间删最旧的。

        两种池子都**不再直接拒绝**（用户明确要求：归档满了也要删），只有实在腾不出来
        （比如单个文件就比整个配额大）才报错。
        """
        if storage not in ("archive", "cache"):
            return
        quota = self.quota_of(storage)
        if quota <= 0:
            raise MediaError("storage_disabled", f"{storage} 模式未开启", 409)
        need = max(0, int(need_bytes))
        used = self.storage_used(storage)
        if used + need > quota:
            over = used + need - quota
            if storage == "cache":
                self.prune_cache(force_bytes=over)
            else:
                self.evict_archive(over)
            used = self.storage_used(storage)
        if used + need > quota:
            label = "服务器存储" if storage == "archive" else "服务器缓存"
            raise MediaError("quota_exceeded", f"{label}空间不足：单个文件比配额还大，换个存储方式试试", 409)

    # ---------- 淘汰 ----------
    def _file_rows(self, c, storage: str, *, now: int, expired: bool) -> list[dict]:
        """tracks 与批量条目里属于该池子的文件，统一成可比的行。

        tracks 一侧**不看 ``deleted``**：墓碑行的文件还在盘上，淘汰时必须能被挑中，
        否则被删的平台条目会永远占着缓存池（local 条目的文件在删除时已即时清掉）。
        """
        rows: list[dict] = []
        if storage == "cache":
            track_sql = ("SELECT id, media_path, bytes, COALESCE(last_access_at, updated_at) AS at"
                         " FROM tracks WHERE storage='cache'")
            item_sql = ("SELECT i.id, i.media_path, i.bytes, i.files_json, COALESCE(i.stored_at, 0) AS at"
                        " FROM creator_items i JOIN creator_jobs j ON j.id=i.job_id"
                        " WHERE j.storage='cache' AND i.media_path IS NOT NULL")
            if expired:
                track_sql += " AND cache_expires_at IS NOT NULL AND cache_expires_at<=:n"
                item_sql += " AND i.store_expires_at IS NOT NULL AND i.store_expires_at<=:n"
            else:
                track_sql += (" AND (cache_expires_at IS NULL OR cache_expires_at>:n)"
                              " AND (last_access_at IS NULL OR last_access_at<:k)")
                item_sql += " AND (i.store_expires_at IS NULL OR i.store_expires_at>:n)"
        else:
            track_sql = ("SELECT id, media_path, bytes, COALESCE(created_at, 0) AS at"
                         " FROM tracks WHERE storage='archive' AND media_path IS NOT NULL")
            item_sql = ("SELECT i.id, i.media_path, i.bytes, i.files_json, COALESCE(i.stored_at, 0) AS at"
                        " FROM creator_items i JOIN creator_jobs j ON j.id=i.job_id"
                        " WHERE j.storage='archive' AND i.media_path IS NOT NULL")
        params = {"n": now, "k": now - 300}
        for kind, sql in (("track", track_sql), ("item", item_sql)):
            for r in c.execute(text(sql), params).all():
                m = r._mapping
                rows.append({"kind": kind, "id": int(m["id"]), "path": m["media_path"],
                             "files": _files_of(m.get("files_json")),
                             "bytes": int(m["bytes"] or 0), "at": int(m["at"] or 0)})
        rows.sort(key=lambda r: r["at"])
        return rows

    def evict_archive(self, free_bytes: int, *, now: int | None = None) -> dict:
        """归档池满了：按「存入时间」从旧到新删，腾出 ``free_bytes``。

        曲库条目只删文件、保留清单（自动降级为 metadata）；批量条目保留条目本身，
        之后还能再下载（会重新拉一遍）。AI 素材不在淘汰范围内。
        """
        now = self.now() if now is None else now
        removed = {"rows": 0, "bytes": 0, "tracks": 0, "items": 0}
        if free_bytes <= 0:
            return removed
        with self.engine.begin() as c:
            for r in self._file_rows(c, "archive", now=now, expired=False):
                if removed["bytes"] >= free_bytes:
                    break
                self._drop_one(c, r, "archive")
                removed["bytes"] += r["bytes"]
                removed["rows"] += 1
                removed["tracks" if r["kind"] == "track" else "items"] += 1
        return removed

    def _unlink(self, base: Path, rel: str) -> None:
        """删一个落盘文件；失败只记日志（记录已经决定删了，不能因为文件被占用而回滚）。"""
        p = base / rel
        try:
            if p.is_file():
                p.unlink()
        except OSError:
            log.warning("文件删除失败：%s", rel)

    def user_file_names(self, user_id: str) -> set[str]:
        """注销前收集这个账号名下的所有落盘文件名（曲库条目 + 批量条目）。

        必须先取名字、再删记录：`DELETE FROM users` 会把 tracks / creator_items 一起级联删掉，
        删完就再也找不到哪几个文件属于这个账号，文件只能一直占着盘。
        """
        names: set[str] = set()
        with self.engine.begin() as c:
            names |= {r[0] for r in c.execute(
                text("SELECT media_path FROM tracks WHERE user_id=:u AND media_path IS NOT NULL"),
                {"u": user_id})}
            for mp, raw in c.execute(text(
                    "SELECT ci.media_path, ci.files_json FROM creator_items ci"
                    " JOIN creator_jobs cj ON cj.id=ci.job_id WHERE cj.user_id=:u"), {"u": user_id}):
                if mp:
                    names.add(mp)
                names |= {m["path"] for m in _files_of(raw).values() if m.get("path")}
        return names

    def drop_names(self, names: Iterable[str]) -> int:
        """按文件名把盘上的文件删掉（曲库与批量条目共用两个目录，逐目录试）。"""
        n = 0
        for base in (self.s.media_dir, self.s.cache_dir):
            for name in names:
                try:
                    (base / name).unlink()
                    n += 1
                except OSError:
                    pass
        return n

    def _drop_one(self, c, row: dict, storage: str) -> None:
        """删掉一个落盘文件并清掉记录里的文件字段（条目本身保留）。"""
        base = self.dir_for(storage)
        paths = {row["path"]} if row["path"] else set()
        paths |= {meta["path"] for meta in row.get("files", {}).values() if meta.get("path")}
        for rel in paths:
            p = base / rel
            try:
                if p.is_file():
                    p.unlink()
            except OSError:
                log.warning("%s 文件删除失败：%s", storage, rel)
        now = self.now()
        if row["kind"] == "track":
            seq = self._next_seq(c)
            c.execute(text("UPDATE tracks SET media_path=NULL, bytes=0, storage='metadata',"
                           " cache_expires_at=NULL, updated_seq=:s, updated_at=:n WHERE id=:i"),
                      {"s": seq, "n": now, "i": row["id"]})
        else:
            c.execute(text("UPDATE creator_items SET media_path=NULL, files_json=NULL, bytes=0,"
                           " stored_at=NULL, store_expires_at=NULL, download_state='pending',"
                           " store_error='服务器空间已满，文件已清理（可重新下载）', updated_at=:n"
                           " WHERE id=:i"), {"n": now, "i": row["id"]})

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
              uploader: str | None = None, pinned: bool | None = None,
              favorite: bool | None = None, category: str | None = None,
              category_set: bool = False) -> dict:
        """改条目自身字段。

        ``category_set`` 是"请求体里出现过 category"的标记：``None`` 与 ``""`` 都表示
        **清空类别**，而"没传这个字段"表示不动它——Pydantic 的 ``model_fields_set`` 能区分，
        所以这里要一个显式开关，不能靠 ``category is not None`` 猜。
        """
        sets, params = [], {"i": track_id, "u": user_id, "n": self.now()}
        if title is not None:
            if not title.strip():
                raise MediaError("bad_title", "标题不能为空")
            sets.append("title=:t"); params["t"] = title.strip()[:200]
        if uploader is not None:
            sets.append("uploader=:up"); params["up"] = uploader.strip() or None
        if pinned is not None:
            sets.append("pinned=:pin"); params["pin"] = int(pinned)
        if favorite is not None:
            sets.append("favorite=:fav"); params["fav"] = int(bool(favorite))
        if category_set:
            sets.append("category=:cat"); params["cat"] = _clean_category(category)
        if not sets:
            raise MediaError("nothing_to_update", "没有要修改的字段")
        with self.engine.begin() as c:
            seq = self._next_seq(c)
            n = c.execute(text(f"UPDATE tracks SET {', '.join(sets)}, updated_seq=:seq, updated_at=:n "
                               "WHERE id=:i AND user_id=:u"), {**params, "seq": seq}).rowcount
            if not n:
                raise MediaError("track_not_found", "媒体不存在", 404)
        return self.get(user_id, track_id, include_deleted=True)

    def delete_for_sync(self, user_id: str, track_id: int, expected_seq: int) -> dict:
        """手机同步专用删除：带版本前置条件；平台归档行受保护，``local`` 行可以删。

        与旧 :meth:`delete` 的差别：
        - 需要 ``If-Match`` 版本，条件写在同一句 SQL 里，检查和更新之间没有空档；
        - 平台条目的 ``archive`` 行直接 409 ``archive_protected``：存储文件是服务器长期
          保留的资产，手机上的一次删除不能把它从网页正常列表里抹掉；
        - **``platform=local`` 的行不受这条保护**：它的文件本来就是这台手机传上去的备份，
          手机端删条目就该连云端备份一起删（用户明确要求"删除不可逆"）；
        - 已经是墓碑时返回当前行（200），重复重放幂等；
        - 旧网页接口的 ``delete`` 行为完全不变（由它自己按平台分叉）。
        """
        if expected_seq <= 0:
            raise MediaError("bad_version", "条目版本不正确")
        with self.engine.begin() as c:
            row = c.execute(text(f"SELECT {_COLS} FROM tracks WHERE id=:i AND user_id=:u"),
                            {"i": track_id, "u": user_id}).first()
            if row is None:
                raise MediaError("track_not_found", "媒体不存在", 404)
            current = _row_to_dict(row)
            # 已经删了：重放同一请求不产生任何新效果，直接成功（响应丢失后的重试靠这条）
            if current["deleted"]:
                return current
            local = (current["platform"] or "").lower() == "local"
            if current["storage"] == "archive" and not local:
                raise MediaError("archive_protected", "云端归档条目不能从手机端移除；如需删除请在网页端处理归档",
                                 409, extra={"track": current})
            if current["updated_seq"] != expected_seq:
                raise MediaError("track_version_conflict", "云端条目已变化，请刷新后重试",
                                 412, extra={"track": current})
            if local and row._mapping["media_path"]:
                self._unlink(self.dir_for(current["storage"]), row._mapping["media_path"])
            clear = ", media_path=NULL, bytes=0, sha256=NULL, storage='metadata', cache_expires_at=NULL" \
                if local else ""
            seq = self._next_seq(c)
            n = c.execute(text(
                f"UPDATE tracks SET deleted=1, updated_seq=:s, updated_at=:n{clear}"
                " WHERE id=:i AND user_id=:u AND deleted=0 AND updated_seq=:e"),
                {"i": track_id, "u": user_id, "s": seq, "n": self.now(), "e": expected_seq}).rowcount
            if n != 1:
                # 条件写没命中：把真实状态读出来，按同一套错误码回答（并发修改不会静默成功）
                latest = c.execute(text(f"SELECT {_COLS} FROM tracks WHERE id=:i AND user_id=:u"),
                                   {"i": track_id, "u": user_id}).first()
                if latest is None:
                    raise MediaError("track_not_found", "媒体不存在", 404)
                now = _row_to_dict(latest)
                if now["deleted"]:
                    return now
                if now["storage"] == "archive" and (now["platform"] or "").lower() != "local":
                    raise MediaError("archive_protected", "云端归档条目不能从手机端移除；如需删除请在网页端处理归档",
                                     409, extra={"track": now})
                raise MediaError("track_version_conflict", "云端条目已变化，请刷新后重试",
                                 412, extra={"track": now})
        return self.get(user_id, track_id, include_deleted=True)

    def patch_title_for_sync(self, user_id: str, track_id: int, title: str,
                             expected_seq: int) -> dict:
        """手机同步专用改名：只改标题，带版本前置条件；墓碑返回 409 ``track_deleted``。

        旧网页的 :meth:`patch` 保持无条件、可改多种字段的行为，两者互不影响。
        """
        if expected_seq <= 0:
            raise MediaError("bad_version", "条目版本不正确")
        clean = (title or "").strip()
        if not clean:
            raise MediaError("bad_title", "标题不能为空")
        clean = clean[:200]
        with self.engine.begin() as c:
            row = c.execute(text(f"SELECT {_COLS} FROM tracks WHERE id=:i AND user_id=:u"),
                            {"i": track_id, "u": user_id}).first()
            if row is None:
                raise MediaError("track_not_found", "媒体不存在", 404)
            current = _row_to_dict(row)
            if current["deleted"]:
                raise MediaError("track_deleted", "该条目已被删除", 409, extra={"track": current})
            if current["updated_seq"] != expected_seq:
                raise MediaError("track_version_conflict", "云端条目已变化，请刷新后重试",
                                 412, extra={"track": current})
            seq = self._next_seq(c)
            n = c.execute(text(
                "UPDATE tracks SET title=:t, updated_seq=:s, updated_at=:n"
                " WHERE id=:i AND user_id=:u AND deleted=0 AND updated_seq=:e"),
                {"t": clean, "s": seq, "n": self.now(), "i": track_id, "u": user_id,
                 "e": expected_seq}).rowcount
            if n != 1:
                latest = c.execute(text(f"SELECT {_COLS} FROM tracks WHERE id=:i AND user_id=:u"),
                                   {"i": track_id, "u": user_id}).first()
                if latest is None:
                    raise MediaError("track_not_found", "媒体不存在", 404)
                now = _row_to_dict(latest)
                if now["deleted"]:
                    raise MediaError("track_deleted", "该条目已被删除", 409, extra={"track": now})
                raise MediaError("track_version_conflict", "云端条目已变化，请刷新后重试",
                                 412, extra={"track": now})
        return self.get(user_id, track_id, include_deleted=True)

    def delete(self, user_id: str, track_id: int) -> dict:
        """删除条目：平台条目只写墓碑，``platform=local`` 连文件一起真删。

        平台条目（B 站/抖音）的文件是缓存或中转产物，删记录时留着让它按过期规则回收；
        记录本身必须留成墓碑，否则另一台设备会把它当新条目推回来。

        ``local`` 条目存的是用户自己导入的原件备份（archive）。这种文件没有任何地方能重新
        拉出来，留着就是**永久占配额又永远没人读**，所以删除即真删，并清空 ``media_path`` /
        ``bytes`` / ``sha256``。手机上的那份副本归手机自己管，服务器不碰。
        """
        with self.engine.begin() as c:
            row = c.execute(text("SELECT platform, media_path, storage, deleted FROM tracks"
                                 " WHERE id=:i AND user_id=:u"),
                            {"i": track_id, "u": user_id}).first()
            if row is None:
                raise MediaError("track_not_found", "媒体不存在", 404)
            m = row._mapping
            local = (m["platform"] or "").lower() == "local"
            if local and m["media_path"] and not m["deleted"]:
                self._unlink(self.dir_for(m["storage"]), m["media_path"])
            seq = self._next_seq(c)
            clear = ", media_path=NULL, bytes=0, sha256=NULL, storage='metadata', cache_expires_at=NULL" \
                if local else ""
            c.execute(text(f"UPDATE tracks SET deleted=1, updated_seq=:s, updated_at=:n{clear}"
                           " WHERE id=:i AND user_id=:u AND deleted=0"),
                      {"i": track_id, "u": user_id, "s": seq, "n": self.now()})
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

    def save_upload(self, user_id: str, track_id: int, *, ext: str,
                    chunks: Iterable[bytes]) -> dict:
        """手机上传某条目的原件，落到 ``archive`` 并记账（``platform=local`` 专用）。

        顺序是"先落临时文件、再腾地方、最后原子改名"：
        上传前不知道多大，而 ``ensure_room`` 必须拿到准数才能决定淘汰多少。临时文件以
        ``.part`` 结尾，``purge_orphans`` 会跳过它。

        已经归档过的行**直接返回**（幂等）：客户端可能因为响应丢失重传同一个文件，
        重复写只会白占一份空间、还多一次 ``updated_seq``。
        """
        track = self.get(user_id, track_id)
        if track["has_file"] and track["storage"] == "archive":
            return track
        dest = self.dir_for("archive")
        dest.mkdir(parents=True, exist_ok=True)
        dest.chmod(0o700)
        part = dest / f".upload-{uuid4().hex}.part"
        digest, size = hashlib.sha256(), 0
        try:
            with open(part, "wb") as f:
                for chunk in chunks:
                    if not chunk:
                        continue
                    f.write(chunk)
                    digest.update(chunk)
                    size += len(chunk)
                f.flush()
                import os as _os
                _os.fsync(f.fileno())
            if size <= 0:
                raise MediaError("empty_file", "上传的文件是空的", 400)
            self.ensure_room("archive", size)
            filename = f"{track_id}-{uuid4().hex}.{ext}"
            part.chmod(0o600)
            part.replace(dest / filename)
        except Exception:
            part.unlink(missing_ok=True)
            raise
        return self.attach_file(user_id, track_id, storage="archive", filename=filename,
                                nbytes=size, sha256=digest.hexdigest())

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
        """先删过期的，再按最近访问/存入时间淘汰到配额内；最近 5 分钟动过的先留着。

        曲库与批量任务共用这个缓存池，所以两边一起删。
        """
        now = self.now() if now is None else now
        removed = {"rows": 0, "bytes": 0, "tracks": 0, "items": 0}
        with self.engine.begin() as c:
            victims = self._file_rows(c, "cache", now=now, expired=True)
            freed = sum(r["bytes"] for r in victims)
            if freed < force_bytes:
                for r in self._file_rows(c, "cache", now=now, expired=False):
                    if freed >= force_bytes:
                        break
                    victims.append(r)
                    freed += r["bytes"]
            for r in victims:
                self._drop_one(c, r, "cache")
                removed["bytes"] += r["bytes"]
                removed["rows"] += 1
                removed["tracks" if r["kind"] == "track" else "items"] += 1
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

    def creator_stats(self) -> dict:
        """批量任务落在服务器上的文件（与曲库同池，但分开列，便于看清构成）。"""
        with self.engine.begin() as c:
            rows = c.execute(text(
                "SELECT j.storage s, COUNT(*) n, COALESCE(SUM(i.bytes),0) b"
                " FROM creator_items i JOIN creator_jobs j ON j.id=i.job_id"
                " WHERE i.media_path IS NOT NULL GROUP BY j.storage")).all()
        return {r._mapping["s"]: {"items": int(r._mapping["n"]), "bytes": int(r._mapping["b"])}
                for r in rows}

    def purge_orphans(self) -> int:
        """删掉库里没有记录指向的媒体文件（迁移/异常退出后的清理）。

        批量任务的文件也在同一批目录里，必须一起算「有记录」，否则一次清理就会把
        「服务器存储」的全删了。
        """
        with self.engine.begin() as c:
            known = {r[0] for r in c.execute(text("SELECT media_path FROM tracks WHERE media_path IS NOT NULL"))}
            known |= {r[0] for r in c.execute(
                text("SELECT media_path FROM creator_items WHERE media_path IS NOT NULL"))}
            for (raw,) in c.execute(text(
                    "SELECT files_json FROM creator_items WHERE files_json IS NOT NULL")):
                known |= {meta["path"] for meta in _files_of(raw).values() if meta.get("path")}
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


def _files_of(raw: str | None) -> dict:
    """条目里的多格式清单（{格式: {path, bytes}}）；旧行没有这个字段就是空。"""
    if not raw:
        return {}
    try:
        data = json.loads(raw)
    except ValueError:
        return {}
    return {k: v for k, v in data.items() if isinstance(v, dict) and v.get("path")} \
        if isinstance(data, dict) else {}


__all__ = ["MediaStore", "MediaError", "TrackFile", "STORAGES", "MEDIA_TYPES", "shutil"]
