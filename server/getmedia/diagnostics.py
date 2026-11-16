"""提取失败上报（P2f）。

手机端把自己失败的条目回传，服务端只做归类查询：不改库、不重试、不自动下载。
诊断信息里**不允许**出现 Cookie、直链签名或设备标识——长度与字段都在入口裁剪。
"""
from __future__ import annotations

import logging

from sqlalchemy import text
from sqlalchemy.engine import Engine

from .config import Settings

log = logging.getLogger("getmedia.diagnostics")


class ReportError(Exception):
    def __init__(self, code: str, detail: str, status: int = 400):
        super().__init__(detail)
        self.code, self.detail, self.status = code, detail, status


class DiagnosticsService:
    def __init__(self, engine: Engine, settings: Settings, clock):
        self.engine, self.s, self.clock = engine, settings, clock

    def now(self) -> int:
        return int(self.clock())

    def record(self, user_id: str, *, platform: str, video_id: str, stage: str, error_code: str,
               detail: str = "", client_version: str = "", track_id: int | None = None) -> dict:
        with self.engine.begin() as c:
            c.execute(text(
                "INSERT INTO extraction_failures(user_id, platform, video_id, track_id, stage, error_code,"
                " detail, client_version, created_at) VALUES(:u,:p,:v,:t,:s,:e,:d,:cv,:n)"),
                {"u": user_id, "p": platform[:32], "v": video_id[:64], "t": track_id, "s": stage[:32],
                 "e": error_code[:64], "d": detail[:2000], "cv": client_version[:32], "n": self.now()})
            rid = int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())
        return self.get(user_id, rid)

    def list(self, user_id: str, *, limit: int = 100, platform: str | None = None) -> list[dict]:
        sql = ("SELECT id, platform, video_id, track_id, stage, error_code, detail, client_version, created_at"
               " FROM extraction_failures WHERE user_id=:u")
        params: dict = {"u": user_id, "l": limit}
        if platform:
            sql += " AND platform=:p"
            params["p"] = platform
        sql += " ORDER BY id DESC LIMIT :l"
        with self.engine.begin() as c:
            return [dict(r._mapping) for r in c.execute(text(sql), params).all()]

    def get(self, user_id: str, report_id: int) -> dict:
        with self.engine.begin() as c:
            row = c.execute(text(
                "SELECT id, platform, video_id, track_id, stage, error_code, detail, client_version, created_at"
                " FROM extraction_failures WHERE id=:i AND user_id=:u"), {"i": report_id, "u": user_id}).first()
        if row is None:
            raise ReportError("report_not_found", "上报记录不存在", 404)
        return dict(row._mapping)

    def summary(self, user_id: str, days: int = 7) -> list[dict]:
        cut = self.now() - days * 86400
        with self.engine.begin() as c:
            rows = c.execute(text(
                "SELECT platform, error_code, COUNT(*) n FROM extraction_failures"
                " WHERE user_id=:u AND created_at>=:c GROUP BY platform, error_code ORDER BY n DESC"),
                {"u": user_id, "c": cut}).all()
        return [dict(r._mapping) for r in rows]


__all__ = ["DiagnosticsService", "ReportError"]
