"""定时抓取（P2c）：从 ``keywords.yaml`` 的 groups 改为 DB 表 + 接口。

设计上的两个取舍：
- 用 ``interval_minutes`` 而不是 cron 表达式：网页可安全改写，不需要解析器；
- 每次触发只是"建一个批量任务"，任务自己带游标与状态，定时器不追踪下载进度。
"""
from __future__ import annotations

import json
import logging
from datetime import datetime
from typing import Iterable

from sqlalchemy import text
from sqlalchemy.engine import Engine

from .config import Settings
from .creator_jobs import CreatorJobService, JobError, Selection, parse_day

log = logging.getLogger("getmedia.schedules")

MIN_INTERVAL = 15
MAX_INTERVAL = 7 * 24 * 60


class ScheduleError(Exception):
    def __init__(self, code: str, detail: str, status: int = 400):
        super().__init__(detail)
        self.code, self.detail, self.status = code, detail, status


_COLS = ("id, name, platform, input, mode, since_ts, until_ts, limit_n, interval_minutes,"
         " include_pinned, storage, media_type, enabled, next_run, last_run, last_status,"
         " last_job_id, created_at, updated_at")


class ScheduleService:
    def __init__(self, engine: Engine, settings: Settings, clock, *, jobs: CreatorJobService):
        self.engine, self.s, self.clock, self.jobs = engine, settings, clock, jobs

    def now(self) -> int:
        return int(self.clock())

    @staticmethod
    def _row(r) -> dict:
        m = r._mapping
        out = {k: m[k] for k in _COLS.replace("\n", " ").replace(",", " ").split()}
        out["include_pinned"] = bool(m["include_pinned"])
        out["enabled"] = bool(m["enabled"])
        return out

    def list(self, user_id: str) -> list[dict]:
        with self.engine.begin() as c:
            rows = c.execute(text(f"SELECT {_COLS} FROM schedules WHERE user_id=:u ORDER BY id"),
                             {"u": user_id}).all()
        return [self._row(r) for r in rows]

    def get(self, user_id: str, sid: int) -> dict:
        with self.engine.begin() as c:
            r = c.execute(text(f"SELECT {_COLS} FROM schedules WHERE id=:i AND user_id=:u"),
                          {"i": sid, "u": user_id}).first()
        if r is None:
            raise ScheduleError("schedule_not_found", "定时任务不存在", 404)
        return self._row(r)

    def create(self, user_id: str, *, name: str, platform: str, input_: str, mode: str,
               interval_minutes: int, n: int | None = None, since: str | None = None,
               until: str | None = None, include_pinned: bool = True, storage: str = "phone",
               media_type: str = "video") -> dict:
        if mode not in ("all", "date_range", "latest_n"):
            raise ScheduleError("bad_mode", "mode 只能是 all / date_range / latest_n")
        if not (MIN_INTERVAL <= interval_minutes <= MAX_INTERVAL):
            raise ScheduleError("bad_interval", f"interval_minutes 必须在 {MIN_INTERVAL}–{MAX_INTERVAL} 之间")
        try:
            since_ts = parse_day(since) if since else None
            until_ts = parse_day(until, end=True) if until else None
        except JobError as exc:
            raise ScheduleError("bad_date", exc.detail) from None
        if mode == "date_range" and since_ts is None:
            raise ScheduleError("bad_range", "date_range 需要 since")
        if mode == "latest_n" and not n:
            raise ScheduleError("bad_range", "latest_n 需要 n")
        now = self.now()
        try:
            with self.engine.begin() as c:
                c.execute(text(
                    "INSERT INTO schedules(user_id, name, platform, input, mode, since_ts, until_ts, limit_n,"
                    " interval_minutes, include_pinned, storage, media_type, enabled, next_run, created_at, updated_at)"
                    " VALUES(:u,:n,:p,:i,:m,:s,:t,:ln,:iv,:pin,:st,:mt,1,:nr,:now,:now)"),
                    {"u": user_id, "n": name[:80], "p": platform, "i": input_[:2000], "m": mode,
                     "s": since_ts, "t": until_ts, "ln": n, "iv": interval_minutes,
                     "pin": int(include_pinned), "st": storage, "mt": media_type,
                     "nr": now + interval_minutes * 60, "now": now})
                sid = int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())
        except Exception as exc:  # noqa: BLE001 - 唯一键冲突
            if "UNIQUE" in str(exc).upper():
                raise ScheduleError("duplicate_name", "同名定时任务已存在", 409) from None
            raise
        return self.get(user_id, sid)

    def patch(self, user_id: str, sid: int, **fields) -> dict:
        cur = self.get(user_id, sid)
        sets, params = [], {"i": sid, "u": user_id, "n": self.now()}
        allowed = ("name", "input", "mode", "interval_minutes", "limit_n", "include_pinned",
                   "storage", "media_type", "enabled")
        for k in allowed:
            if k in fields and fields[k] is not None:
                sets.append(f"{k}=:{k}")
                params[k] = int(fields[k]) if isinstance(fields[k], bool) else fields[k]
        try:
            if "since" in fields:
                sets.append("since_ts=:since_ts")
                params["since_ts"] = parse_day(fields["since"]) if fields["since"] else None
            if "until" in fields:
                sets.append("until_ts=:until_ts")
                params["until_ts"] = parse_day(fields["until"], end=True) if fields["until"] else None
        except JobError as exc:
            raise ScheduleError("bad_date", exc.detail) from None
        if not sets:
            raise ScheduleError("nothing_to_update", "没有要修改的字段")
        if "interval_minutes" in params and not (MIN_INTERVAL <= int(params["interval_minutes"]) <= MAX_INTERVAL):
            raise ScheduleError("bad_interval", f"interval_minutes 必须在 {MIN_INTERVAL}–{MAX_INTERVAL} 之间")
        if "interval_minutes" in params:
            sets.append("next_run=:nr"); params["nr"] = self.now() + int(params["interval_minutes"]) * 60
        with self.engine.begin() as c:
            c.execute(text(f"UPDATE schedules SET {', '.join(sets)}, updated_at=:n WHERE id=:i AND user_id=:u"),
                      params)
        return self.get(user_id, sid)

    def delete(self, user_id: str, sid: int) -> None:
        self.get(user_id, sid)
        with self.engine.begin() as c:
            c.execute(text("DELETE FROM schedules WHERE id=:i AND user_id=:u"), {"i": sid, "u": user_id})

    # ---------- 执行 ----------
    def _selection(self, sch: dict) -> Selection:
        return Selection(mode=sch["mode"], since=sch["since_ts"], until=sch["until_ts"],
                         n=sch["limit_n"], include_pinned=sch["include_pinned"])

    def run_once(self, user_id: str, sid: int, *, advance: bool = True) -> dict:
        sch = self.get(user_id, sid)
        return self._fire(sch, advance=advance)

    def _fire(self, sch: dict, *, advance: bool) -> dict:
        now = self.now()
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO schedule_runs(schedule_id, status, started_at) VALUES(:s,'running',:n)"),
                      {"s": sch["id"], "n": now})
            run_id = int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())
        try:
            job = self.jobs.create(self._owner_of(sch["id"]), url=sch["input"], selection=self._selection(sch),
                                   storage=sch["storage"], media_type=sch["media_type"],
                                   platform=sch["platform"])
            self.jobs.run(job["id"])
            final = self.jobs.get(self._owner_of(sch["id"]), job["id"])
            status = "done" if final["status"] == "done" else final["status"]
            with self.engine.begin() as c:
                c.execute(text("UPDATE schedule_runs SET job_id=:j, status=:st, finished_at=:n WHERE id=:r"),
                          {"j": job["id"], "st": status, "n": self.now(), "r": run_id})
                sets = "last_run=:n, last_status=:st, last_job_id=:j, updated_at=:n"
                params = {"n": now, "st": status, "j": job["id"], "i": sch["id"]}
                if advance:
                    # 从"应跑时间"往后推，避免手动 run-once 把节奏打乱或堆积
                    base = max(now, int(sch.get("next_run") or 0))
                    sets += ", next_run=:nr"
                    params["nr"] = base + int(sch["interval_minutes"]) * 60
                c.execute(text(f"UPDATE schedules SET {sets} WHERE id=:i"), params)
            return {"schedule_id": sch["id"], "job_id": job["id"], "status": status}
        except (JobError, Exception) as exc:  # noqa: BLE001
            message = str(exc)[:300]
            with self.engine.begin() as c:
                c.execute(text("UPDATE schedule_runs SET status='failed', message=:m, finished_at=:n WHERE id=:r"),
                          {"m": message, "n": self.now(), "r": run_id})
                c.execute(text("UPDATE schedules SET last_run=:n, last_status='failed', updated_at=:n WHERE id=:i"),
                          {"n": now, "i": sch["id"]})
            log.warning("定时任务 %s 执行失败：%s", sch["id"], type(exc).__name__)
            raise ScheduleError("schedule_run_failed", f"定时任务执行失败：{message}", 502) from exc

    def _owner_of(self, sid: int) -> str:
        with self.engine.begin() as c:
            return str(c.execute(text("SELECT user_id FROM schedules WHERE id=:i"), {"i": sid}).scalar_one())

    def tick(self) -> list[dict]:
        """由后台线程调用：把到期的任务各跑一次。"""
        now = self.now()
        with self.engine.begin() as c:
            due = [dict(r._mapping) for r in c.execute(
                text(f"SELECT {_COLS} FROM schedules WHERE enabled=1 AND (next_run IS NULL OR next_run<=:n)"
                     " ORDER BY next_run LIMIT 5"), {"n": now}).all()]
        out = []
        for sch in due:
            try:
                out.append(self._fire(sch, advance=True))
            except ScheduleError:
                continue
        return out

    def runs(self, user_id: str, sid: int, limit: int = 20) -> list[dict]:
        self.get(user_id, sid)
        with self.engine.begin() as c:
            rows = c.execute(text("SELECT id, job_id, status, message, started_at, finished_at"
                                  " FROM schedule_runs WHERE schedule_id=:s ORDER BY id DESC LIMIT :l"),
                             {"s": sid, "l": limit}).all()
        return [dict(r._mapping) for r in rows]


def import_legacy(service: ScheduleService, user_id: str, path, *, default_platform: str = "bilibili") -> int:
    """把旧 ``keywords.yaml`` 的 groups 导入成 schedules（只读解析，幂等）。

    YAML 里没有平台字段，统一按 ``default_platform`` 导入；同名任务已存在则跳过。
    迁移阶段由 CLI 调用，服务启动不自动执行。
    """
    try:
        import yaml  # type: ignore
    except ImportError:  # pragma: no cover - 只在没装 PyYAML 的机器上
        log.warning("未安装 PyYAML，跳过 keywords.yaml 导入")
        return 0
    from pathlib import Path

    p = Path(path)
    if not p.is_file():
        return 0
    data = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
    groups: Iterable[dict] = data.get("groups") or []
    added = 0
    for g in groups:
        urls = [u for u in (g.get("urls") or []) if isinstance(u, str)]
        for idx, url in enumerate(urls):
            name = f"{g.get('name') or 'legacy'}-{idx + 1}"[:80]
            try:
                service.create(user_id, name=name, platform=default_platform, input_=url,
                               mode="latest_n", interval_minutes=max(MIN_INTERVAL, 360),
                               n=int(g.get("top_n") or 10),
                               include_pinned=True, storage="phone", media_type="video")
                added += 1
            except ScheduleError:
                continue
    return added


__all__ = ["ScheduleService", "ScheduleError", "import_legacy", "MIN_INTERVAL", "MAX_INTERVAL"]
