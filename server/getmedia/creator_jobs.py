"""博主批量的任务编排（P2b）。

服务器只负责"列作品 + 记账"：媒体仍由手机逐条提取（``phone``）或由网页按需缓存
（``cache``），任务表不存直链、不存 cookie。

三种选取方式共用一条「按发布时间倒序翻页」的循环：
- ``all``：翻到底；每 ``creator_max_pages_per_run`` 页停一次，游标已落库，可续；
- ``date_range``：当前页最旧一条早于 ``since`` 就停（列表本身按发布时间倒序）；
- ``latest_n``：入库条数达到 n 就停。

断点：``cursor_json={"start": N}`` 每页落库；暂停/重启/换设备都从这里续，
``UNIQUE(job_id, platform, video_id, page)`` 保证重复扫描不会产生重复条目。
"""
from __future__ import annotations

import json
import logging
import time
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
from typing import Callable, Iterable

import httpx
from sqlalchemy import text
from sqlalchemy.engine import Engine

from . import creator_service
from .config import Settings
from .creator_links import CreatorRef, Platform
from .creators import CreatorError, get_provider
from .creators.base import CreatorVideo

log = logging.getLogger("getmedia.creator_jobs")

TZ_CN = timezone(timedelta(hours=8))
MODES = ("all", "date_range", "latest_n")
ITEM_STATES = ("pending", "downloading", "done", "failed", "skipped")


class JobError(Exception):
    def __init__(self, code: str, detail: str, status: int = 400):
        super().__init__(detail)
        self.code, self.detail, self.status = code, detail, status


@dataclass(frozen=True)
class Selection:
    mode: str = "latest_n"
    since: int | None = None
    until: int | None = None
    n: int | None = None
    include_pinned: bool = True


def parse_day(value: str, *, end: bool = False) -> int:
    """``YYYY-MM-DD`` 按北京时间解释；end=True 取当日 23:59:59。"""
    try:
        d = date.fromisoformat(value)
    except ValueError:
        raise JobError("bad_date", f"日期格式必须是 YYYY-MM-DD：{value}") from None
    dt = datetime(d.year, d.month, d.day, tzinfo=TZ_CN)
    if end:
        dt = dt + timedelta(days=1) - timedelta(seconds=1)
    return int(dt.timestamp())


def _epoch(iso: str | None) -> int | None:
    if not iso:
        return None
    try:
        return int(datetime.fromisoformat(iso).timestamp())
    except ValueError:
        return None


def estimate_bytes(duration_sec: float | None, media_type: str, s: Settings) -> int:
    if not duration_sec:
        return 0
    rate = s.creator_video_mb_per_min if media_type == "video" else s.creator_audio_mb_per_min
    return int(duration_sec / 60 * rate * 1024**2)


def _default_resolve(url: str, platform: str | None = None) -> CreatorRef:
    return creator_service.resolve_ref(url, platform=platform)


def _default_window(ref: CreatorRef, start: int, count: int, cookie_file) -> object:
    return get_provider(ref.platform).fetch_window(ref, start=start, count=count, cookie_file=cookie_file)


def _default_pinned(ref: CreatorRef, cookie_file) -> set[str]:
    """B 站置顶作品：``x/space/top/arc`` 公开接口，不需要登录；失败就当作没有置顶。"""
    if ref.platform is not Platform.BILIBILI:
        return set()
    try:
        with httpx.Client(timeout=15.0, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                          "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Referer": f"https://space.bilibili.com/{ref.creator_id}/video",
        }, follow_redirects=True) as c:
            body = c.get("https://api.bilibili.com/x/space/top/arc", params={"vmid": ref.creator_id}).json()
    except Exception as exc:  # noqa: BLE001 - 置顶只是附加信息
        log.warning("取置顶失败：%s", type(exc).__name__)
        return set()
    data = body.get("data") or {}
    bvid = data.get("bvid") if isinstance(data, dict) else None
    return {bvid} if bvid else set()


class CreatorJobService:
    def __init__(self, engine: Engine, settings: Settings, clock, *,
                 resolve_fn: Callable[..., CreatorRef] | None = None,
                 window_fn: Callable[..., object] | None = None,
                 pinned_fn: Callable[..., set[str]] | None = None,
                 cookie_fn: Callable[..., tuple] | None = None):
        self.engine, self.s, self.clock = engine, settings, clock
        self.resolve_fn = resolve_fn or _default_resolve
        self.window_fn = window_fn or _default_window
        self.pinned_fn = pinned_fn or _default_pinned
        self.cookie_fn = cookie_fn or creator_service.cookie_file_for

    def now(self) -> int:
        return int(self.clock())

    # ---------- 任务 CRUD ----------
    def create(self, user_id: str, *, url: str, selection: Selection, storage: str = "phone",
               media_type: str = "video", platform: str | None = None) -> dict:
        if selection.mode not in MODES:
            raise JobError("bad_mode", "mode 只能是 all / date_range / latest_n")
        if storage not in ("phone", "metadata", "archive", "cache"):
            raise JobError("bad_storage", "storage 只能是 phone / metadata / archive / cache")
        if selection.mode == "date_range" and selection.since is None:
            raise JobError("bad_range", "date_range 需要 since")
        if selection.mode == "latest_n" and not selection.n:
            raise JobError("bad_range", "latest_n 需要 n")
        try:
            ref = self.resolve_fn(url, platform)
        except CreatorError as exc:
            raise JobError("creator_not_found", str(exc), 404) from exc
        except Exception as exc:  # noqa: BLE001
            raise JobError("creator_input", str(exc) or "链接无法识别") from exc

        now = self.now()
        with self.engine.begin() as c:
            c.execute(text(
                "INSERT INTO creator_jobs(user_id, platform, creator_id, creator_name, source_url, mode,"
                " since_ts, until_ts, limit_n, include_pinned, storage, media_type, status, cursor_json,"
                " created_at, updated_at) VALUES(:u,:p,:cid,'',:src,:m,:s,:t,:n,:pin,:st,:mt,'pending','{}',:now,:now)"),
                {"u": user_id, "p": ref.platform.value, "cid": ref.creator_id, "src": ref.url,
                 "m": selection.mode, "s": selection.since, "t": selection.until, "n": selection.n,
                 "pin": int(selection.include_pinned), "st": storage, "mt": media_type, "now": now})
            job_id = int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())
        return self.get(user_id, job_id)

    _JOB_COLS = ("id, platform, creator_id, creator_name, source_url, mode, since_ts, until_ts, limit_n,"
                 " include_pinned, storage, media_type, status, pause_reason, cursor_json, total, done,"
                 " failed, estimated_bytes, created_at, updated_at, started_at, finished_at")

    @staticmethod
    def _job(row) -> dict:
        m = row._mapping
        out = {k: m[k] for k in ("id", "platform", "creator_id", "creator_name", "source_url", "mode",
                                 "since_ts", "until_ts", "limit_n", "storage", "media_type", "status",
                                 "pause_reason", "total", "done", "failed", "estimated_bytes",
                                 "created_at", "updated_at", "started_at", "finished_at")}
        out["include_pinned"] = bool(m["include_pinned"])
        try:
            out["cursor"] = json.loads(m["cursor_json"] or "{}")
        except ValueError:
            out["cursor"] = {}
        return out

    def get(self, user_id: str, job_id: int) -> dict:
        with self.engine.begin() as c:
            row = c.execute(text(f"SELECT {self._JOB_COLS} FROM creator_jobs WHERE id=:i AND user_id=:u"),
                            {"i": job_id, "u": user_id}).first()
        if row is None:
            raise JobError("job_not_found", "任务不存在", 404)
        return self._job(row)

    def list_jobs(self, user_id: str, *, status: str | None = None, limit: int = 50) -> list[dict]:
        sql = f"SELECT {self._JOB_COLS} FROM creator_jobs WHERE user_id=:u"
        params: dict = {"u": user_id, "l": limit}
        if status:
            sql += " AND status=:st"
            params["st"] = status
        sql += " ORDER BY id DESC LIMIT :l"
        with self.engine.begin() as c:
            return [self._job(r) for r in c.execute(text(sql), params).all()]

    def _owned(self, user_id: str, job_id: int) -> dict:
        return self.get(user_id, job_id)

    # ---------- 状态机 ----------
    def pause(self, user_id: str, job_id: int, reason: str = "user") -> dict:
        job = self._owned(user_id, job_id)
        if job["status"] in ("done", "cancelled"):
            raise JobError("job_finished", "任务已结束，无法暂停", 409)
        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET status='paused', pause_reason=:r, updated_at=:n"
                           " WHERE id=:i AND user_id=:u AND status IN ('pending','running','paused')"),
                      {"r": reason, "n": self.now(), "i": job_id, "u": user_id})
        return self.get(user_id, job_id)

    def resume(self, user_id: str, job_id: int) -> dict:
        job = self._owned(user_id, job_id)
        if job["status"] in ("done", "cancelled"):
            raise JobError("job_finished", "任务已结束，无法继续", 409)
        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET status='pending', pause_reason=NULL, updated_at=:n"
                           " WHERE id=:i AND user_id=:u"), {"n": self.now(), "i": job_id, "u": user_id})
        return self.get(user_id, job_id)

    def cancel(self, user_id: str, job_id: int) -> dict:
        self._owned(user_id, job_id)
        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET status='cancelled', pause_reason=NULL,"
                           " finished_at=:n, updated_at=:n WHERE id=:i AND user_id=:u"),
                      {"n": self.now(), "i": job_id, "u": user_id})
        return self.get(user_id, job_id)

    def retry(self, user_id: str, job_id: int) -> dict:
        """把失败条目重置为 pending，并让任务重新可跑。"""
        self._owned(user_id, job_id)
        with self.engine.begin() as c:
            n = c.execute(text("UPDATE creator_items SET download_state='pending', error=NULL,"
                               " updated_at=:n WHERE job_id=:j AND download_state='failed'"),
                          {"n": self.now(), "j": job_id}).rowcount
            c.execute(text("UPDATE creator_jobs SET status='pending', pause_reason=NULL, failed=0,"
                           " finished_at=NULL, updated_at=:n WHERE id=:j"),
                      {"n": self.now(), "j": job_id})
        return {"requeued": int(n), "job": self.get(user_id, job_id)}

    # ---------- 条目 ----------
    def items(self, user_id: str, job_id: int, *, state: str | None = None, limit: int = 100,
              offset: int = 0) -> dict:
        self._owned(user_id, job_id)
        sql = ("SELECT id, video_id, platform, page, title, duration_sec, published_at, is_pinned,"
               " cover_url, source_url, download_state, track_id, error FROM creator_items WHERE job_id=:j")
        params: dict = {"j": job_id, "l": limit, "o": offset}
        if state:
            if state not in ITEM_STATES:
                raise JobError("bad_state", "未知的条目状态")
            sql += " AND download_state=:st"
            params["st"] = state
        sql += " ORDER BY published_at DESC, id ASC LIMIT :l OFFSET :o"
        with self.engine.begin() as c:
            rows = c.execute(text(sql), params).all()
            total = int(c.execute(text("SELECT COUNT(*) FROM creator_items WHERE job_id=:j"),
                                  {"j": job_id}).scalar_one())
        return {"items": [dict(r._mapping) | {"is_pinned": bool(r._mapping["is_pinned"])} for r in rows],
                "total": total, "limit": limit, "offset": offset}

    def set_item_state(self, user_id: str, job_id: int, video_id: str, *, state: str,
                       error: str | None = None, track_id: int | None = None) -> dict:
        self._owned(user_id, job_id)
        if state not in ITEM_STATES:
            raise JobError("bad_state", "state 只能是 pending/downloading/done/failed/skipped")
        with self.engine.begin() as c:
            n = c.execute(text(
                "UPDATE creator_items SET download_state=:st, error=:e,"
                " track_id=COALESCE(:t, track_id), updated_at=:n WHERE job_id=:j AND video_id=:v"),
                {"st": state, "e": (error or "")[:500] or None, "t": track_id, "n": self.now(),
                 "j": job_id, "v": video_id}).rowcount
            if not n:
                raise JobError("item_not_found", "任务里没有这条作品", 404)
            self._refresh_counters(c, job_id, self.now())
        return self.get(user_id, job_id)

    def import_items(self, user_id: str, job_id: int, items: Iterable[dict]) -> dict:
        """手机端已拿到列表时回传元数据（服务器列不可用时的兜底）。"""
        self._owned(user_id, job_id)
        added = 0
        now = self.now()
        with self.engine.begin() as c:
            for it in items:
                vid = str(it.get("video_id") or "").strip()
                if not vid:
                    continue
                r = c.execute(text(
                    "INSERT OR IGNORE INTO creator_items(job_id, platform, video_id, page, title,"
                    " duration_sec, published_at, is_pinned, cover_url, source_url, download_state, updated_at)"
                    " VALUES(:j,:p,:v,1,:t,:d,:pub,:pin,:cv,:su,'pending',:n)"),
                    {"j": job_id, "p": it.get("platform") or "unknown", "v": vid,
                     "t": str(it.get("title") or "")[:200], "d": it.get("duration_sec"),
                     "pub": it.get("published_at"), "pin": int(bool(it.get("is_pinned"))),
                     "cv": it.get("cover_url"), "su": str(it.get("source_url") or ""), "n": now})
                added += r.rowcount
            self._refresh_counters(c, job_id, now)
        return {"added": added, "job": self.get(user_id, job_id)}

    @staticmethod
    def _refresh_counters(c, job_id: int, now: int) -> None:
        row = c.execute(text(
            "SELECT COUNT(*) total,"
            " SUM(CASE WHEN download_state='done' THEN 1 ELSE 0 END) done,"
            " SUM(CASE WHEN download_state='failed' THEN 1 ELSE 0 END) failed,"
            " COALESCE(SUM(CASE WHEN download_state='done' THEN COALESCE(duration_sec,0) ELSE 0 END),0) secs"
            " FROM creator_items WHERE job_id=:j"), {"j": job_id}).first()
        m = row._mapping
        c.execute(text("UPDATE creator_jobs SET total=:t, done=:d, failed=:f, updated_at=:n WHERE id=:j"),
                  {"t": int(m["total"] or 0), "d": int(m["done"] or 0), "f": int(m["failed"] or 0),
                   "n": now, "j": job_id})

    # ---------- 列表扫描 ----------
    def preview(self, ref: CreatorRef, selection: Selection, *, max_items: int = 100,
                media_type: str = "video") -> dict:
        """预览：只扫到 ``max_items`` 条（或自然停止），不落库。"""
        cookie_file, _src = self.cookie_fn(ref.platform)
        pinned = self.pinned_fn(ref, cookie_file)
        picked: list[dict] = []
        start, pages = 1, 0
        total = None
        profile = None
        while pages < 10 and len(picked) < max_items:
            window = self.window_fn(ref, start, min(self.s.creator_page_size, 20), cookie_file)
            total = getattr(window, "total", total)
            profile = profile or getattr(window, "profile", None)
            if not window.items:
                break
            stop = False
            for v in window.items:
                item = self._select_one(v, selection, pinned)
                if item is None:
                    if selection.mode == "date_range":
                        stop = True
                    continue
                picked.append(item)
                if selection.mode == "latest_n" and selection.n and len(picked) >= selection.n:
                    stop = True
                    break
                if len(picked) >= max_items:
                    stop = True
                    break
            start += len(window.items)
            pages += 1
            if stop or not window.has_more:
                break
        for p in picked:
            p["estimated_bytes"] = estimate_bytes(p["duration_sec"], "video", self.s)
        return {"items": picked[:max_items], "total": total, "profile": profile,
                "estimated_bytes": sum(p["estimated_bytes"] for p in picked[:max_items])}

    def _select_one(self, v: CreatorVideo, sel: Selection, pinned: set[str]) -> dict | None:
        pub = _epoch(v.published_at)
        is_pinned = v.video_id in pinned
        if not is_pinned:
            raw = v.prefetched or {}
            try:
                is_pinned = bool(int(raw.get("is_top") or 0))
            except (TypeError, ValueError):
                is_pinned = False
        if is_pinned and not sel.include_pinned:
            return None
        if sel.mode == "date_range":
            if pub is None:
                return None
            if sel.since is not None and pub < sel.since:
                return None
            if sel.until is not None and pub > sel.until:
                return None
        return {"video_id": v.video_id, "title": v.title, "duration_sec": v.duration_sec,
                "published_at": pub, "is_pinned": is_pinned, "cover_url": v.cover_url,
                "source_url": v.source_url, "platform": v.platform.value}

    # ---------- 执行 ----------
    def run(self, job_id: int, *, max_pages: int | None = None) -> dict:
        """扫描循环；可由 BackgroundTasks 调用。幂等：完成的、取消的、暂停的直接返回。"""
        with self.engine.begin() as c:
            row = c.execute(text(f"SELECT {self._JOB_COLS}, user_id FROM creator_jobs WHERE id=:i"),
                            {"i": job_id}).first()
        if row is None:
            raise JobError("job_not_found", "任务不存在", 404)
        job = self._job(row)
        user_id = row._mapping["user_id"]
        if job["status"] in ("done", "cancelled", "paused"):
            return job

        ref = CreatorRef(Platform(job["platform"]), job["creator_id"], job["source_url"])
        cookie_file, _src = self.cookie_fn(ref.platform)
        sel = Selection(mode=job["mode"], since=job["since_ts"], until=job["until_ts"],
                        n=job["limit_n"], include_pinned=job["include_pinned"])
        start = int((job["cursor"] or {}).get("start") or 1)
        limit_pages = max_pages if max_pages is not None else self.s.creator_max_pages_per_run

        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET status='running', started_at=COALESCE(started_at,:n),"
                           " updated_at=:n WHERE id=:i"), {"n": self.now(), "i": job_id})

        paused_reason = None
        pages = 0
        try:
            pinned = self.pinned_fn(ref, cookie_file)
            while pages < limit_pages:
                window = self.window_fn(ref, start, self.s.creator_page_size, cookie_file)
                if not window.items:
                    break
                stop = self._absorb(job_id, window.items, sel, pinned, job["media_type"])
                start += len(window.items)
                pages += 1
                self._save_cursor(job_id, start, window.items)
                if stop:
                    break
                if not window.has_more:
                    break
            else:
                paused_reason = "segment_limit"
        except CreatorError as exc:
            self._finish(job_id, "failed", reason=str(exc)[:200])
            return self.get(user_id, job_id)
        except Exception as exc:  # noqa: BLE001
            log.warning("批量任务 %s 异常：%s", job_id, type(exc).__name__)
            self._finish(job_id, "failed", reason="扫描失败，请稍后重试")
            return self.get(user_id, job_id)

        if paused_reason:
            with self.engine.begin() as c:
                c.execute(text("UPDATE creator_jobs SET status='paused', pause_reason=:r, updated_at=:n"
                               " WHERE id=:i"), {"r": paused_reason, "n": self.now(), "i": job_id})
        else:
            self._finish(job_id, "done")
        return self.get(user_id, job_id)

    def _absorb(self, job_id: int, videos: list[CreatorVideo], sel: Selection,
                pinned: set[str], media_type: str) -> bool:
        """写入本页；返回 True 表示已达到停止条件。"""
        now = self.now()
        added_secs = 0.0
        with self.engine.begin() as c:
            have = int(c.execute(text("SELECT COUNT(*) FROM creator_items WHERE job_id=:j"),
                                 {"j": job_id}).scalar_one() or 0)
            added = 0
            for v in videos:
                if sel.mode == "latest_n" and sel.n and have + added >= sel.n:
                    break
                item = self._select_one(v, sel, pinned)
                if item is None:
                    continue
                r = c.execute(text(
                    "INSERT OR IGNORE INTO creator_items(job_id, platform, video_id, page, title,"
                    " duration_sec, published_at, is_pinned, cover_url, source_url, download_state, updated_at)"
                    " VALUES(:j,:p,:v,1,:t,:d,:pub,:pin,:cv,:su,'pending',:n)"),
                    {"j": job_id, "p": item["platform"], "v": item["video_id"], "t": item["title"][:200],
                     "d": item["duration_sec"], "pub": item["published_at"], "pin": int(item["is_pinned"]),
                     "cv": item["cover_url"], "su": item["source_url"], "n": now})
                if r.rowcount:
                    added += 1
                    added_secs += float(item["duration_sec"] or 0)
            self._refresh_counters(c, job_id, now)
            if added_secs:
                c.execute(text("UPDATE creator_jobs SET estimated_bytes=estimated_bytes+:b WHERE id=:j"),
                          {"b": estimate_bytes(added_secs, media_type, self.s), "j": job_id})
            total = int(c.execute(text("SELECT COUNT(*) FROM creator_items WHERE job_id=:j"),
                                  {"j": job_id}).scalar_one() or 0)

        if sel.mode == "latest_n" and sel.n:
            return total >= sel.n
        if sel.mode == "date_range" and sel.since is not None:
            pubs = [p for p in (_epoch(v.published_at) for v in videos) if p is not None]
            # 列表按发布时间倒序：本页最旧一条已经早于 since，后面只会更早
            return bool(pubs) and min(pubs) < sel.since
        return False

    def _save_cursor(self, job_id: int, start: int, videos: list[CreatorVideo]) -> None:
        pubs = [_epoch(v.published_at) for v in videos]
        pubs = [p for p in pubs if p is not None]
        cur = {"start": start, "last_published_at": min(pubs) if pubs else None}
        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET cursor_json=:c, updated_at=:n WHERE id=:i"),
                      {"c": json.dumps(cur), "n": self.now(), "i": job_id})

    def _finish(self, job_id: int, status: str, *, reason: str | None = None) -> None:
        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET status=:s, pause_reason=:r, finished_at=:n,"
                           " updated_at=:n WHERE id=:i"),
                      {"s": status, "r": reason, "n": self.now(), "i": job_id})


__all__ = ["CreatorJobService", "Selection", "JobError", "MODES", "ITEM_STATES", "parse_day", "estimate_bytes"]
