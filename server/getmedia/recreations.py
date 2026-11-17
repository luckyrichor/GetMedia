"""AI 视频重建（P2g）：资源、队列与产物路径。

分工与旧视频项目一致：
- API 只做「创建资源 + 入队 + 查询」，**不 import 模型客户端**；
- ``getmedia-ai-worker``（``ai_worker.py``）领取 ``recreation_runs`` 执行；
- 没有配置模型时 ``capabilities.available=false``，相关接口 503，不假装能跑。
"""
from __future__ import annotations

import logging
import math
import os
import shutil
from pathlib import Path

from sqlalchemy import text
from sqlalchemy.engine import Engine

from .config import Settings
from .media import MediaError, MediaStore

log = logging.getLogger("getmedia.recreations")

KINDS = ("analyze", "generate")


def fmt_size(n: int) -> str:
    """给用户看的体积：0.5 GB / 180 MB / 12 KB。"""
    n = max(0, int(n))
    if n >= 1024 ** 3:
        return f"{n / 1024 ** 3:.1f} GB"
    if n >= 1024 ** 2:
        return f"{n / 1024 ** 2:.0f} MB"
    if n >= 1024:
        return f"{n / 1024:.0f} KB"
    return f"{n} B"


class RecreationError(Exception):
    def __init__(self, code: str, detail: str, status: int = 400):
        super().__init__(detail)
        self.code, self.detail, self.status = code, detail, status


class RecreationService:
    def __init__(self, engine: Engine, settings: Settings, clock, *, media: MediaStore | None = None):
        self.engine, self.s, self.clock, self.media = engine, settings, clock, media

    def now(self) -> int:
        return int(self.clock())

    # ---------- 能力 ----------
    def capabilities(self) -> dict:
        return {"available": bool(self.s.ai_enabled), "modes": list(KINDS),
                "max_upload_bytes": self.s.ai_max_upload_bytes,
                "retention_days": int(self.s.ai_retention_days),
                "storage": self._storage(),
                "reason": None if self.s.ai_enabled else "服务端未配置模型，AI 重建暂不可用"}

    def _storage(self) -> dict:
        """服务器库配额（archive 已含 AI）：页面用它画配额条、提示剩余空间。"""
        if self.media is None:
            return {"quota": 0, "used": 0, "ai_bytes": 0, "ai_files": 0}
        ai = self.media.ai_stats()
        return {"quota": self.media.quota_of("archive"), "used": self.media.storage_used("archive"),
                "ai_bytes": ai["bytes"], "ai_files": ai["files"]}

    def _require_available(self) -> None:
        if not self.s.ai_enabled:
            raise RecreationError("ai_unconfigured", "服务端未配置模型，AI 重建暂不可用", 503)

    def _ensure_room(self, need: int) -> None:
        """上传前判服务器库配额（archive，含 AI 自己的占用），超了直接拒绝、不落盘。"""
        if self.media is None:
            return
        quota = self.media.quota_of("archive")
        if quota <= 0:
            raise RecreationError("storage_disabled", "服务器库未开启", 409)
        used = self.media.storage_used("archive")
        if used + max(0, need) > quota:
            # 文案与手机端、管理台共用同一句：三段（原因 / 现状 / 怎么办），不要各写一版。
            raise RecreationError(
                "quota_exceeded",
                f"服务器库空间不足（AI 素材与产物计入 {fmt_size(quota)}）："
                f"已用 {fmt_size(used)} / {fmt_size(quota)}，本次需要 {fmt_size(need)}。"
                "先清理缓存或旧项目。",
                409,
            )

    # ---------- 资源 ----------
    def _dir(self, pid: int) -> Path:
        d = Path(self.s.media_dir) / "ai" / str(pid)
        d.mkdir(parents=True, exist_ok=True)
        d.chmod(0o700)
        return d

    def create_upload(self, user_id: str, *, filename: str, data: bytes) -> dict:
        if not data:
            raise RecreationError("empty_file", "上传内容为空")
        if len(data) > self.s.ai_max_upload_bytes:
            raise RecreationError("file_too_large",
                                  f"文件不能超过 {self.s.ai_max_upload_bytes // 1024**2} MB", 413)
        self._ensure_room(len(data))  # 先判配额：拒绝时不留项目行、不写盘
        now = self.now()
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO recreations(user_id, title, status, created_at, updated_at)"
                           " VALUES(:u,:t,'draft',:n,:n)"),
                      {"u": user_id, "t": (filename or "未命名")[:120], "n": now})
            pid = int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())
        ext = Path(filename or "").suffix.lower()
        if ext not in (".mp4", ".mov", ".mkv", ".webm", ".m4v"):
            ext = ".mp4"
        dest = self._dir(pid) / f"source{ext}"
        part = dest.with_name(dest.name + ".part")
        with open(part, "wb") as f:
            f.write(data)
            f.flush(); os.fsync(f.fileno())
        part.chmod(0o600)
        part.replace(dest)
        with self.engine.begin() as c:
            c.execute(text("UPDATE recreations SET source_path=:p, updated_at=:n WHERE id=:i"),
                      {"p": f"ai/{pid}/{dest.name}", "n": now, "i": pid})
        return self.get(user_id, pid)

    def create_from_track(self, user_id: str, track_id: int) -> dict:
        if self.media is None:
            raise RecreationError("media_unavailable", "媒体库未启用", 503)
        try:
            track = self.media.get(user_id, track_id)
            tf = self.media.file_for(user_id, track_id)
        except MediaError as exc:
            raise RecreationError(exc.code, exc.detail, exc.status) from exc
        if tf is None:
            raise RecreationError("source_missing", "该条目在服务器上没有文件，请先用 archive 模式提取", 409)
        if track["media_type"] != "video":
            raise RecreationError("not_video", "只有视频条目能用于重建", 400)
        now = self.now()
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO recreations(user_id, title, source_track_id, source_path, status,"
                           " created_at, updated_at) VALUES(:u,:t,:tid,:p,'draft',:n,:n)"),
                      {"u": user_id, "t": track["title"][:120], "tid": track_id,
                       "p": track.get("media_path") or None, "n": now})
            pid = int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())
        return self.get(user_id, pid)

    def _row(self, r, now: int) -> dict:
        m = r._mapping
        out = {k: m[k] for k in ("id", "title", "source_track_id", "source_path", "description",
                                 "status", "created_at", "updated_at")}
        out.update(self._expiry(out, now))
        return out

    def _expiry(self, rec: dict, now: int) -> dict:
        """保留期：从最后一次活动（``updated_at``）起算，到期由 ``gc`` 删文件、留记录。"""
        days = int(self.s.ai_retention_days)
        expires_at = int(rec["updated_at"]) + days * 86400
        return {"expires_at": expires_at,
                "days_left": max(0, math.ceil((expires_at - now) / 86400)),
                "retention_days": days}

    def get(self, user_id: str, pid: int) -> dict:
        now = self.now()
        with self.engine.begin() as c:
            row = c.execute(text("SELECT id, title, source_track_id, source_path, description, status,"
                                 " created_at, updated_at FROM recreations WHERE id=:i AND user_id=:u"),
                            {"i": pid, "u": user_id}).first()
            if row is None:
                raise RecreationError("recreation_not_found", "重建项目不存在", 404)
            runs = c.execute(text("SELECT id, kind, status, progress, error_code, created_at, started_at,"
                                  " finished_at FROM recreation_runs WHERE recreation_id=:i ORDER BY id DESC"),
                             {"i": pid}).all()
        out = self._row(row, now)
        out["runs"] = [dict(r._mapping) for r in runs]
        return out

    def list(self, user_id: str, limit: int = 50) -> list[dict]:
        now = self.now()
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT id, title, source_track_id, source_path, description, status,"
                                  " created_at, updated_at FROM recreations WHERE user_id=:u"
                                  " ORDER BY id DESC LIMIT :l"), {"u": user_id, "l": limit}).all()
        return [self._row(r, now) for r in rows]

    def set_description(self, user_id: str, pid: int, description: str) -> dict:
        self.get(user_id, pid)
        with self.engine.begin() as c:
            c.execute(text("UPDATE recreations SET description=:d, updated_at=:n WHERE id=:i AND user_id=:u"),
                      {"d": description[:4000], "n": self.now(), "i": pid, "u": user_id})
        return self.get(user_id, pid)

    # ---------- 队列 ----------
    def enqueue(self, user_id: str, pid: int, kind: str) -> dict:
        self._require_available()
        if kind not in KINDS:
            raise RecreationError("bad_kind", "kind 只能是 analyze 或 generate")
        rec = self.get(user_id, pid)
        if not rec["source_path"]:
            raise RecreationError("source_missing", "还没有源视频", 409)
        if kind == "generate" and not (rec.get("description") or "").strip():
            raise RecreationError("description_required", "生成前需要先有描述（analyze 或手填）", 409)
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO recreation_runs(recreation_id, kind, status, created_at)"
                           " VALUES(:p,:k,'queued',:n)"), {"p": pid, "k": kind, "n": self.now()})
            rid = int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())
            c.execute(text("UPDATE recreations SET status=:s, updated_at=:n WHERE id=:p"),
                      {"s": "queued", "n": self.now(), "p": pid})
        return {"recreation_id": pid, "run_id": rid, "kind": kind, "status": "queued"}

    def cancel_run(self, user_id: str, pid: int, rid: int) -> dict:
        self.get(user_id, pid)
        with self.engine.begin() as c:
            n = c.execute(text("UPDATE recreation_runs SET status='cancelled', finished_at=:n"
                               " WHERE id=:r AND recreation_id=:p AND status IN ('queued','running')"),
                          {"n": self.now(), "r": rid, "p": pid}).rowcount
        if not n:
            raise RecreationError("run_not_cancellable", "该任务无法取消（已完成或不存在）", 409)
        return {"run_id": rid, "status": "cancelled"}

    def output_file(self, user_id: str, pid: int, rid: int) -> Path:
        self.get(user_id, pid)
        with self.engine.begin() as c:
            row = c.execute(text("SELECT output_path, status FROM recreation_runs WHERE id=:r"
                                 " AND recreation_id=:p"), {"r": rid, "p": pid}).first()
        if row is None or row._mapping["status"] != "done" or not row._mapping["output_path"]:
            raise RecreationError("output_not_ready", "产物还没生成好", 409)
        p = Path(self.s.media_dir) / row._mapping["output_path"]
        if not p.is_file():
            raise RecreationError("output_missing", "产物文件已不在服务器上", 404)
        return p

    def save_output(self, user_id: str, pid: int, rid: int) -> dict:
        """标记"已保存到手机"，只改状态，不删除产物（保留期到期后统一清）。"""
        p = self.output_file(user_id, pid, rid)
        with self.engine.begin() as c:
            c.execute(text("UPDATE recreations SET status='saved', updated_at=:n WHERE id=:p AND user_id=:u"),
                      {"n": self.now(), "p": pid, "u": user_id})
        return {"recreation_id": pid, "run_id": rid, "saved": True, "bytes": p.stat().st_size}

    # ---------- worker 侧 ----------
    def claim(self) -> dict | None:
        with self.engine.begin() as c:
            row = c.execute(text(
                "SELECT id, recreation_id, kind, attempts FROM recreation_runs"
                " WHERE status='queued' ORDER BY id LIMIT 1")).first()
            if row is None:
                return None
            rid = int(row._mapping["id"])
            done = c.execute(text("UPDATE recreation_runs SET status='running', started_at=:n,"
                                  " attempts=attempts+1 WHERE id=:r AND status='queued'"),
                             {"n": self.now(), "r": rid}).rowcount
            if not done:
                return None
        return {"run_id": rid, "recreation_id": int(row._mapping["recreation_id"]),
                "kind": row._mapping["kind"], "attempts": int(row._mapping["attempts"]) + 1}

    def finish(self, rid: int, status: str, *, output_path: str | None = None,
               error_code: str | None = None) -> None:
        with self.engine.begin() as c:
            c.execute(text("UPDATE recreation_runs SET status=:s, output_path=:o, error_code=:e,"
                           " progress=:pr, finished_at=:n WHERE id=:r"),
                      {"s": status, "o": output_path, "e": error_code,
                       "pr": 100 if status == "done" else 0, "n": self.now(), "r": rid})
            if status in ("done", "failed", "cancelled"):
                c.execute(text("UPDATE recreations SET status=:s, updated_at=:n WHERE id="
                               "(SELECT recreation_id FROM recreation_runs WHERE id=:r)"),
                          {"s": status, "n": self.now(), "r": rid})

    def reset_stale(self, *, older_than_sec: int | None = None) -> int:
        """worker 启动时把明显卡死的 running 重置为 queued（最多重试 3 次）。"""
        cut = self.now() - (older_than_sec or self.s.ai_timeout_sec * 2)
        with self.engine.begin() as c:
            return c.execute(text(
                "UPDATE recreation_runs SET status=CASE WHEN attempts>=3 THEN 'failed' ELSE 'queued' END,"
                " error_code=CASE WHEN attempts>=3 THEN 'too_many_attempts' ELSE error_code END,"
                " started_at=NULL WHERE status='running' AND started_at<:c"), {"c": cut}).rowcount

    # ---------- 保留期清理 ----------
    @staticmethod
    def _dir_size(d: Path) -> int:
        if not d.is_dir():
            return 0
        total = 0
        for p in d.rglob("*"):
            if p.is_file():
                try:
                    total += p.stat().st_size
                except OSError:  # worker 正在写
                    continue
        return total

    def gc(self, *, days: int | None = None, dry_run: bool = False) -> dict:
        """删掉过期项目的**文件**，保留记录并把状态置 ``expired``。

        三条约束：
        - 只动 ``updated_at`` 早于 N 天的项目（N 默认取 ``GETMEDIA_AI_RETENTION_DAYS``）；
        - 有 queued/running 任务的不动——删了文件会让 worker 半路失败，等它跑完；
        - 只删 ``media_dir/ai/<项目号>/``，源来自媒体库的项目其真身（tracks 的文件）不受影响。
        """
        days = int(days or self.s.ai_retention_days)
        cut = self.now() - max(1, days) * 86400
        with self.engine.begin() as c:
            rows = c.execute(text(
                "SELECT id FROM recreations r WHERE updated_at < :cut AND status != 'expired'"
                " AND NOT EXISTS (SELECT 1 FROM recreation_runs x WHERE x.recreation_id=r.id"
                " AND x.status IN ('queued','running')) ORDER BY id"), {"cut": cut}).all()
            ids = [int(r._mapping["id"]) for r in rows]
        projects, freed = 0, 0
        for pid in ids:
            d = Path(self.s.media_dir) / "ai" / str(pid)
            freed += self._dir_size(d)
            projects += 1
            if dry_run:
                continue
            if d.is_dir():
                shutil.rmtree(d, ignore_errors=True)
            with self.engine.begin() as c:
                c.execute(text("UPDATE recreations SET status='expired', source_path=NULL WHERE id=:i"),
                          {"i": pid})
        if projects:
            log.info("AI 保留期清理：%d 个项目，%s%s", projects, fmt_size(freed),
                     "（演练，未删）" if dry_run else "")
        return {"projects": projects, "freed_bytes": freed, "days": days, "dry_run": dry_run}

    def source_file(self, user_id: str, pid: int) -> Path:
        rec = self.get(user_id, pid)
        if not rec["source_path"]:
            raise RecreationError("source_missing", "还没有源视频", 409)
        p = Path(self.s.media_dir) / rec["source_path"]
        if not p.is_file():
            raise RecreationError("source_missing", "源文件已不在服务器上", 404)
        return p


__all__ = ["RecreationService", "RecreationError", "KINDS", "fmt_size", "shutil"]
