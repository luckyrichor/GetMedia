"""AI 视频重建（P2g）：资源、队列与产物路径。

分工与旧 GetVideo 一致：
- API 只做「创建资源 + 入队 + 查询」，**不 import 模型客户端**；
- ``getmedia-ai-worker``（``ai_worker.py``）领取 ``recreation_runs`` 执行；
- 没有配置模型时 ``capabilities.available=false``，相关接口 503，不假装能跑。
"""
from __future__ import annotations

import logging
import os
import shutil
from pathlib import Path

from sqlalchemy import text
from sqlalchemy.engine import Engine

from .config import Settings
from .media import MediaError, MediaStore

log = logging.getLogger("getmedia.recreations")

KINDS = ("analyze", "generate")


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
                "reason": None if self.s.ai_enabled else "服务端未配置模型，AI 重建暂不可用"}

    def _require_available(self) -> None:
        if not self.s.ai_enabled:
            raise RecreationError("ai_unconfigured", "服务端未配置模型，AI 重建暂不可用", 503)

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

    def _row(self, r) -> dict:
        m = r._mapping
        return {k: m[k] for k in ("id", "title", "source_track_id", "source_path", "description",
                                  "status", "created_at", "updated_at")}

    def get(self, user_id: str, pid: int) -> dict:
        with self.engine.begin() as c:
            row = c.execute(text("SELECT id, title, source_track_id, source_path, description, status,"
                                 " created_at, updated_at FROM recreations WHERE id=:i AND user_id=:u"),
                            {"i": pid, "u": user_id}).first()
            if row is None:
                raise RecreationError("recreation_not_found", "重建项目不存在", 404)
            runs = c.execute(text("SELECT id, kind, status, progress, error_code, created_at, started_at,"
                                  " finished_at FROM recreation_runs WHERE recreation_id=:i ORDER BY id DESC"),
                             {"i": pid}).all()
        out = self._row(row)
        out["runs"] = [dict(r._mapping) for r in runs]
        return out

    def list(self, user_id: str, limit: int = 50) -> list[dict]:
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT id, title, source_track_id, source_path, description, status,"
                                  " created_at, updated_at FROM recreations WHERE user_id=:u"
                                  " ORDER BY id DESC LIMIT :l"), {"u": user_id, "l": limit}).all()
        return [self._row(r) for r in rows]

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

    def source_file(self, user_id: str, pid: int) -> Path:
        rec = self.get(user_id, pid)
        if not rec["source_path"]:
            raise RecreationError("source_missing", "还没有源视频", 409)
        p = Path(self.s.media_dir) / rec["source_path"]
        if not p.is_file():
            raise RecreationError("source_missing", "源文件已不在服务器上", 404)
        return p


__all__ = ["RecreationService", "RecreationError", "KINDS", "shutil"]
