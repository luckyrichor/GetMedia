"""博主批量的任务编排（P2b）。

服务器做两件事：先"列作品 + 记账"，再按存放方式决定要不要把文件落到服务器。

存放方式（``storage``）：
- ``metadata``：只入清单；文件在点下载时由服务器当场转发，不落盘；
- ``cache``：扫完后逐条把文件下到 ``cache_dir``，保留 7 天或按缓存配额淘汰；
- ``archive``：扫完后逐条把文件下到 ``media_dir``，长期保存，满了按存入时间删最旧的。

两种落盘池子与曲库共用一本配额账（见 ``media.MediaStore``），所以「服务器用了多少」
只有一个答案。任务表不存 cookie；``play_urls`` 是平台列表里给的短期签名地址。

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
import shutil
import threading
import time
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from datetime import date, datetime, timedelta, timezone
from typing import Callable, Iterable

import httpx
from sqlalchemy import text
from sqlalchemy.engine import Engine

from . import creator_formats, creator_service
from .config import Settings
from .creator_links import CreatorRef, Platform
from .creators import CreatorError, get_provider
from .creators.base import CreatorVideo
from .media import MediaError, MediaStore

log = logging.getLogger("getmedia.creator_jobs")

TZ_CN = timezone(timedelta(hours=8))
MODES = ("all", "date_range", "latest_n", "from_start")
ITEM_STATES = ("pending", "downloading", "done", "failed", "skipped")
# 真的会往服务器写文件的两种；``metadata``（以及历史值 ``phone``）只记账
DISK_STORAGES = ("cache", "archive")
STORAGES = ("metadata", "cache", "archive", "phone")

# 落盘时取流的 UA / Referer：与平台网页端一致，CDN 会看这两个头
_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36")
_REFERER = {"douyin": "https://www.douyin.com/", "bilibili": "https://www.bilibili.com/"}


class JobError(Exception):
    def __init__(self, code: str, detail: str, status: int = 400):
        super().__init__(detail)
        self.code, self.detail, self.status = code, detail, status


class StoreError(Exception):
    """落盘 / 取流失败（接口层只认这个基类，内部再分具体原因）。"""


class _StoreFailed(StoreError):
    """单条落盘失败（换下一个候选就好，不影响整个任务）。"""


class _LinksExpired(_StoreFailed):
    """存的播放地址一个都取不到（多半是签名过期），值得重扫一次换新地址。"""


@dataclass(frozen=True)
class Selection:
    mode: str = "latest_n"
    since: int | None = None
    until: int | None = None
    n: int | None = None
    include_pinned: bool = True
    # 「指定起点」：start 是剔除置顶后的普通作品序号（1=最新），direction 是 newer/older。
    start: int | None = None
    direction: str | None = None


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


_EXT_IN_URL = (".mp4", ".m4a", ".mp3", ".flac", ".wav", ".mkv", ".webm", ".mov", ".flv")


def _guess_ext(url: str, media_type: str) -> str:
    """从直链路径猜容器扩展名；猜不到就按媒体类型给默认值。"""
    path = url.split("?")[0].lower()
    for ext in _EXT_IN_URL:
        if path.endswith(ext):
            return ext.lstrip(".")
    return "m4a" if media_type == "audio" else "mp4"


def _item_row(row) -> dict:
    """条目行 → 接口对象：把 ``files_json`` 展开成 {格式: {path, bytes}}，并兼容 v14 的旧行。"""
    m = dict(row._mapping)
    try:
        raw = json.loads(m.pop("files_json", None) or "{}")
    except ValueError:
        raw = {}
    files = {k: v for k, v in raw.items() if isinstance(v, dict) and v.get("path")} \
        if isinstance(raw, dict) else {}
    if not files and m.get("media_path"):
        files = {"source": {"path": m["media_path"], "bytes": int(m.get("bytes") or 0)}}
    m["files"] = files
    m["is_pinned"] = bool(m.get("is_pinned"))
    m["pinned_order"] = m.get("pinned_order")
    # seq 是 SELECT 里窗口子查询算的普通作品全局序号；置顶为 NULL。
    m["seq"] = m.get("seq")
    m["stored"] = bool(files)
    return m


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


def _default_resolve(url: str, platform: str | None = None, user_id: str | None = None) -> CreatorRef:
    return creator_service.resolve_ref(url, platform=platform, user_id=user_id)


def _default_window(ref: CreatorRef, start: int, count: int, cookie_file) -> object:
    return get_provider(ref.platform).fetch_window(ref, start=start, count=count, cookie_file=cookie_file)


@contextmanager
def _http_open(url: str, platform: str):
    """打开上游取流；状态码不对或返回的不是媒体就按「这条失败」处理。"""
    headers = {"User-Agent": _UA}
    referer = _REFERER.get(platform)
    if referer:
        headers["Referer"] = referer
    with httpx.Client(timeout=httpx.Timeout(20.0, read=120.0), follow_redirects=True) as client:
        with client.stream("GET", url, headers=headers) as resp:
            if resp.status_code not in (200, 206):
                raise _StoreFailed(f"平台返回 HTTP {resp.status_code}")
            ctype = (resp.headers.get("content-type") or "").lower()
            if ctype.startswith(("application/json", "text/")):
                raise _StoreFailed("平台返回的不是媒体文件")
            yield resp.iter_bytes()


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
                 cookie_fn: Callable[..., tuple] | None = None,
                 store: MediaStore | None = None,
                 media_resolver=None,
                 opener: Callable[..., object] | None = None):
        self.engine, self.s, self.clock = engine, settings, clock
        self.resolve_fn = resolve_fn or _default_resolve
        self.window_fn = window_fn or _default_window
        self.pinned_fn = pinned_fn or _default_pinned
        self.cookie_fn = cookie_fn or creator_service.cookie_file_for
        # 落盘用：配额账本与目录（与曲库共用），以及 B 站「现解析」用的媒体解析器
        self.store = store or MediaStore(engine, settings, clock)
        self.media_resolver = media_resolver
        # 取流入口（可替换，测试里给假数据）
        self.opener = opener or _http_open
        # 「下载时发现直链全失效 → 自动重扫换地址」的冷却表：同一任务在窗口期内只排一次，
        # 免得几个人同时点下载就反复开浏览器（一次要滚到几百条，几十秒，还吃风控配额）。
        self._refresh_lock = threading.Lock()
        self._refresh_at: dict[int, float] = {}

    def now(self) -> int:
        return int(self.clock())

    # ---------- 任务 CRUD ----------
    def create(self, user_id: str, *, url: str, selection: Selection, storage: str = "metadata",
               media_type: str = "video", platform: str | None = None,
               formats: list[str] | None = None, source_input: str | None = None) -> dict:
        if selection.mode not in MODES:
            raise JobError("bad_mode", "mode 只能是 all / date_range / latest_n / from_start")
        if storage not in STORAGES:
            raise JobError("bad_storage", "storage 只能是 metadata / cache / archive")
        # 历史值 phone（旧版「手机下载」）等同于只记清单；新任务统一写成 metadata
        if storage == "phone":
            storage = "metadata"
        if storage in DISK_STORAGES and self.store.quota_of(storage) <= 0:
            raise JobError("storage_disabled", "该存放方式当前未开启，换个方式试试", 409)
        if selection.mode == "date_range" and selection.since is None:
            raise JobError("bad_range", "date_range 需要 since")
        if selection.mode == "latest_n" and not selection.n:
            raise JobError("bad_range", "latest_n 需要 n")
        if selection.mode == "from_start":
            if not selection.start or selection.start < 1:
                raise JobError("bad_range", "from_start 需要一个正整数起点")
            if selection.start > self.s.creator_max_start:
                raise JobError("bad_range", f"起点不能超过 {self.s.creator_max_start}")
            if selection.direction not in ("newer", "older"):
                raise JobError("bad_range", "direction 只能是 newer 或 older")
        try:
            picked = creator_formats.normalize(media_type, formats)
        except ValueError as exc:
            raise JobError("bad_formats", str(exc)) from exc
        try:
            ref = self.resolve_fn(url, platform, user_id)
        except CreatorError as exc:
            raise JobError("creator_not_found", str(exc), 404) from exc
        except Exception as exc:  # noqa: BLE001
            raise JobError("creator_input", str(exc) or "链接无法识别") from exc

        now = self.now()
        with self.engine.begin() as c:
            c.execute(text(
                "INSERT INTO creator_jobs(user_id, platform, creator_id, creator_name, source_url, mode,"
                " since_ts, until_ts, limit_n, start_n, direction, include_pinned, storage, media_type, formats,"
                " source_input, status, cursor_json, created_at, updated_at)"
                " VALUES(:u,:p,:cid,'',:src,:m,:s,:t,:n,:sn,:dir,:pin,:st,:mt,:fm,:in,'pending','{}',:now,:now)"),
                {"u": user_id, "p": ref.platform.value, "cid": ref.creator_id, "src": ref.url,
                 "m": selection.mode, "s": selection.since, "t": selection.until, "n": selection.n,
                 "sn": selection.start, "dir": selection.direction,
                 "pin": int(selection.include_pinned), "st": storage, "mt": media_type,
                 "fm": json.dumps(picked), "in": (source_input or url)[:500], "now": now})
            job_id = int(c.execute(text("SELECT last_insert_rowid()")).scalar_one())
        return self.get(user_id, job_id)

    _JOB_COLS = ("id, platform, creator_id, creator_name, source_url, mode, since_ts, until_ts, limit_n,"
                 " start_n, direction, include_pinned, storage, media_type, status, pause_reason,"
                 " cursor_json, total, done,"
                 " failed, estimated_bytes, formats, source_input, created_at, updated_at,"
                 " started_at, finished_at,"
                 " (SELECT COUNT(*) FROM creator_items i WHERE i.job_id=creator_jobs.id"
                 "   AND i.media_path IS NOT NULL) AS stored,"
                 " (SELECT COALESCE(SUM(i.bytes),0) FROM creator_items i WHERE i.job_id=creator_jobs.id"
                 "   AND i.media_path IS NOT NULL) AS store_bytes")

    @staticmethod
    def _job(row) -> dict:
        m = row._mapping
        out = {k: m[k] for k in ("id", "platform", "creator_id", "creator_name", "source_url", "mode",
                                 "since_ts", "until_ts", "limit_n", "start_n", "direction",
                                 "storage", "media_type", "status",
                                 "pause_reason", "total", "done", "failed", "estimated_bytes",
                                 "created_at", "updated_at", "started_at", "finished_at",
                                 "stored", "store_bytes", "source_input")}
        out["include_pinned"] = bool(m["include_pinned"])
        try:
            out["formats"] = json.loads(m["formats"] or "null") or creator_formats.defaults_for(
                m["media_type"] or "video")
        except ValueError:
            out["formats"] = creator_formats.defaults_for(m["media_type"] or "video")
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
        """把失败条目重置为 pending，并让任务重新可跑。

        已经扫到列表末尾的任务，游标越过最后一条，再跑一次什么也碰不到——这种时候改成
        「从第一条重扫、只刷新播放地址」：平台在列表里给的直链会失效，只有重扫能换新的。
        还在中途（暂停 / 失败）的任务仍然按原游标续扫，不去动已入库的地址。

        判定「列表是否扫完」：``done`` 一定扫完了；``cancelled`` 要看游标里有没有
        ``scan_done`` 标记（用户扫完后又取消的任务会有这个标记，它的地址同样会过期，
        也应重扫换地址）。还在中途（暂停 / 失败）的任务仍然按原游标续扫，不去动已入库的地址。

        ``rescan=True`` 时不改任务状态：那是维护动作，跑完 ``run(refresh=True)`` 会把状态
        原样放回。只有真正要续跑（``rescan=False``）才置回 ``pending`` 并清掉 ``finished_at``。
        """
        job = self._owned(user_id, job_id)
        total = int(job["total"] or 0)
        cursor = job["cursor"] or {}
        scanned_all = job["status"] == "done" or bool(cursor.get("scan_done"))
        rescan = total > 0 and scanned_all
        with self.engine.begin() as c:
            n = c.execute(text("UPDATE creator_items SET download_state='pending', error=NULL,"
                               " store_error=NULL, updated_at=:n"
                               " WHERE job_id=:j AND download_state='failed'"),
                          {"n": self.now(), "j": job_id}).rowcount
            if rescan:
                # 维护模式只换地址：状态和 finished_at 都不动（run(refresh=True) 跑完会原样放回），
                # 否则一个「已取消」的任务会先变成「待开始」，再被标成「已完成」。
                c.execute(text("UPDATE creator_jobs SET failed=0, updated_at=:n WHERE id=:j"),
                          {"n": self.now(), "j": job_id})
            else:
                c.execute(text("UPDATE creator_jobs SET status='pending', pause_reason=NULL, failed=0,"
                               " finished_at=NULL, updated_at=:n WHERE id=:j"),
                          {"n": self.now(), "j": job_id})
        return {"requeued": int(n), "rescan": rescan, "job": self.get(user_id, job_id)}

    def request_refresh(self, user_id: str, job_id: int, *, cooldown: float = 90.0) -> str:
        """下载撞上「直链全失效」时，给这个任务排一次重扫换地址。

        返回 ``started`` / ``busy`` / ``noop``：

        - ``started``：这次真的排上了，调用方应把 ``run(refresh=True)`` 丢到后台；
        - ``busy``：冷却期内已经排过（或任务正在跑），别重复开浏览器；
        - ``noop``：任务没跑完 / 没条目，重扫没有意义（这时缺的是后续作品，不是地址）。

        只有已经扫到列表末尾的 ``done`` 任务才需要换地址；中途暂停或失败的任务应当
        按游标续扫，不能拿重扫把游标冲掉。
        """
        job = self._owned(user_id, job_id)
        total = int(job["total"] or 0)
        start = int((job["cursor"] or {}).get("start") or 1)
        # 正在跑（或刚被别的请求排上、已回到 pending）：已经在换地址了，别重复开浏览器
        if job["status"] in ("running", "pending"):
            return "busy"
        if job["status"] != "done" or total <= 0 or start - 1 < total:
            return "noop"
        now = time.time()
        with self._refresh_lock:
            if now - self._refresh_at.get(job_id, 0.0) < cooldown:
                return "busy"
            self._refresh_at[job_id] = now
        out = self.retry(user_id, job_id)
        return "started" if out["rescan"] else "noop"

    # ---------- 条目 ----------
    def items(self, user_id: str, job_id: int, *, state: str | None = None, limit: int = 100,
              offset: int = 0) -> dict:
        self._owned(user_id, job_id)
        # ``seq`` 是普通作品（非置顶）在发布时间倒序里的全局序号，从 1 开始，跨页稳定；
        # 置顶作品 seq 为 NULL。用相关子查询数「排在它前面的普通作品数 + 1」，
        # 不依赖分页窗口，翻页、换每页条数序号都不变。带 state 过滤时不编号（手机端逐条领取不需要序号）。
        seq_sql = "NULL AS seq"
        order_extra = "is_pinned DESC, pinned_order ASC, published_at DESC, id ASC"
        if not state:
            seq_sql = (
                "CASE WHEN is_pinned THEN NULL ELSE"
                " (SELECT COUNT(*) FROM creator_items c2 WHERE c2.job_id=creator_items.job_id"
                "   AND c2.is_pinned=0 AND"
                "   (c2.published_at > creator_items.published_at OR"
                "    (c2.published_at = creator_items.published_at AND c2.id < creator_items.id))) + 1"
                " END AS seq")
        sql = ("SELECT id, video_id, platform, page, title, duration_sec, published_at, is_pinned,"
               " pinned_order, cover_url, source_url, download_state, track_id, error, bytes,"
               " store_error, stored_at, added_at, files_json, (media_path IS NOT NULL) AS stored,"
               f" {seq_sql}"
               " FROM creator_items WHERE job_id=:j")
        params: dict = {"j": job_id, "l": limit, "o": offset}
        if state:
            if state not in ITEM_STATES:
                raise JobError("bad_state", "未知的条目状态")
            sql += " AND download_state=:st"
            params["st"] = state
        # 置顶作品独立成组：先按 is_pinned 把置顶放最前，置顶之间按平台顺序（pinned_order），
        # 普通作品按发布时间倒序；同时间再按 id 兜底保证稳定。
        sql += (f" ORDER BY {order_extra} LIMIT :l OFFSET :o")
        with self.engine.begin() as c:
            rows = c.execute(text(sql), params).all()
            total = int(c.execute(text("SELECT COUNT(*) FROM creator_items WHERE job_id=:j"),
                                  {"j": job_id}).scalar_one())
        return {"items": [_item_row(r) for r in rows],
                "total": total, "limit": limit, "offset": offset}

    def item(self, user_id: str, job_id: int, video_id: str, *, page: int = 1) -> dict:
        """单条作品，供「下载到本机」用。带 play_urls：平台在列表里给的短期直链。"""
        self._owned(user_id, job_id)
        with self.engine.begin() as c:
            row = c.execute(text(
                "SELECT id, video_id, platform, page, title, duration_sec, source_url,"
                " play_urls, play_urls_at, media_path, files_json, bytes, store_error"
                " FROM creator_items"
                " WHERE job_id=:j AND video_id=:v AND page=:g"),
                {"j": job_id, "v": video_id, "g": page}).first()
        if row is None:
            raise JobError("item_not_found", "任务里没有这条作品", 404)
        return _item_row(row)

    def set_item_state(self, user_id: str, job_id: int, video_id: str, *, state: str,                       error: str | None = None, track_id: int | None = None) -> dict:
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
                    " duration_sec, published_at, is_pinned, cover_url, source_url, download_state,"
                    " added_at, updated_at) VALUES(:j,:p,:v,1,:t,:d,:pub,:pin,:cv,:su,'pending',:n,:n)"),
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
                media_type: str = "video", user_id: str | None = None) -> dict:
        """预览：只扫到 ``max_items`` 条（或自然停止），不落库。

        「全部作品」不再逐页跳扫：平台列表接口只有游标顺序翻页，按「绝对起点」跳页时
        第二次请求就要翻到第 2N 条，抖音桥接单次抓不到那么深，会误报「不足以覆盖起点」。
        所以 ``all`` 只扫**一批**（一次 ``fetch_window``），抓多少算多少、如实回 ``has_more``；
        真正翻到底交给建任务后的 ``run()`` 循环（它每页落库游标，可续扫）。
        """
        cookie_file, _src = self.cookie_fn(ref.platform, user_id)
        pinned = self.pinned_fn(ref, cookie_file)
        picked: list[dict] = []
        total = None
        profile = None
        has_more = False
        if selection.mode == "all":
            window = self.window_fn(ref, 1, min(self.s.creator_max_items, max_items), cookie_file)
            total = getattr(window, "total", total)
            profile = getattr(window, "profile", None)
            for v in window.items:
                item = self._select_one(v, selection, pinned)
                if item is None:
                    continue
                picked.append(item)
                if len(picked) >= max_items:
                    break
            has_more = bool(getattr(window, "has_more", False)) or len(picked) >= max_items
        else:
            start, pages = 1, 0
            normal_seen = 0
            while pages < 10 and len(picked) < max_items:
                try:
                    window = self.window_fn(ref, start, min(self.s.creator_page_size, 20), cookie_file)
                except CreatorError as exc:
                    # 已经拿到一部分时不要把整次预览判死：抓不到更深就如实说「还有更多」。
                    if picked:
                        log.info("预览在起点 %s 处停止：%s", start, type(exc).__name__)
                        has_more = True
                        break
                    raise
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
                    # 「指定起点」：序号只对普通作品计数，置顶独立不占序号、不受起点限制。
                    if selection.mode == "from_start" and not item["is_pinned"]:
                        normal_seen += 1
                        if selection.direction == "newer":
                            if normal_seen > selection.start:
                                stop = True
                                break
                        else:  # older
                            if normal_seen < selection.start:
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
            p["estimated_bytes"] = estimate_bytes(p["duration_sec"], media_type, self.s)
        # 置顶作品独立成组：置顶在前（按平台顺序），普通作品按发布时间倒序。
        picked.sort(key=lambda p: (
            0 if p.get("is_pinned") else 1,          # 置顶在前
            p.get("pinned_order") if p.get("pinned_order") is not None else 1 << 30,
            -(p.get("published_at") or 0),           # 发布时间倒序
        ))
        return {"items": picked[:max_items], "total": total, "profile": profile,
                "has_more": has_more,
                "estimated_bytes": sum(p["estimated_bytes"] for p in picked[:max_items])}

    def _select_one(self, v: CreatorVideo, sel: Selection, pinned: set[str]) -> dict | None:
        pub = _epoch(v.published_at)
        # 置顶识别：抖音在新数据模型里直接带 is_pinned/pinned_order；B 站靠 pinned set。
        is_pinned = bool(v.is_pinned) or v.video_id in pinned
        pinned_order = v.pinned_order
        if not is_pinned:
            raw = v.prefetched or {}
            try:
                if int(raw.get("is_top") or 0):
                    is_pinned = True
            except (TypeError, ValueError):
                pass
            # prefetched 兜底识别出的置顶没有平台顺序，排在已有序号的置顶之后。
            if is_pinned and pinned_order is None:
                pinned_order = 1 << 30
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
                "published_at": pub, "is_pinned": is_pinned, "pinned_order": pinned_order,
                "cover_url": v.cover_url, "source_url": v.source_url, "platform": v.platform.value,
                "play_urls": list(v.play_urls)}

    # ---------- 执行 ----------
    def run(self, job_id: int, *, max_pages: int | None = None, refresh: bool = False) -> dict:
        """扫描循环；可由 BackgroundTasks 调用。幂等：完成的、取消的、暂停的直接返回。

        ``refresh=True`` 是维护模式（由已跑完任务的重试触发）：从第一条重新扫列表，
        只把 ``play_urls`` 换成最新地址，不新增条目、不动游标、不改变已有计数。
        """
        with self.engine.begin() as c:
            row = c.execute(text(f"SELECT {self._JOB_COLS}, user_id FROM creator_jobs WHERE id=:i"),
                            {"i": job_id}).first()
        if row is None:
            raise JobError("job_not_found", "任务不存在", 404)
        job = self._job(row)
        user_id = row._mapping["user_id"]
        # 维护模式（refresh）可以跑在已完成的任务上：它只换直链，跑完把状态原样放回去
        if job["status"] in ("done", "cancelled", "paused") and not refresh:
            return job
        prev_status = job["status"]

        ref = CreatorRef(Platform(job["platform"]), job["creator_id"], job["source_url"])
        cookie_file, _src = self.cookie_fn(ref.platform, user_id)
        sel = Selection(mode=job["mode"], since=job["since_ts"], until=job["until_ts"],
                        n=job["limit_n"], include_pinned=job["include_pinned"],
                        start=job.get("start_n"), direction=job.get("direction"))
        start = 1 if refresh else int((job["cursor"] or {}).get("start") or 1)
        limit_pages = max_pages if max_pages is not None else self.s.creator_max_pages_per_run
        # 「全部作品」一次抓够：抖音的作品列表只能靠浏览器桥接，每次窗口都要从头
        # 重新滚到 need 深度，按 20 条一页翻 300 条会做十几轮重复抓取（几分钟）。
        # 其余模式仍按 creator_page_size 小步走，latest_n/date_range 要尽早停。
        page_size = int(self.s.creator_page_size)
        if refresh or job["mode"] == "all":
            page_size = max(page_size, int(getattr(self.s, "creator_scan_batch", 0) or 0))
        # 还没刷到地址的条目；扫到全部命中就收工，不必翻到列表末尾
        left = self._item_ids(job_id) if refresh else set()
        # 列表是否已经扫完（扫描阶段结束时写进游标）。扫完后再跑只是落盘，不必重开浏览器；
        # 中途暂停的任务没有这个标记，仍按游标续扫。
        drained = not refresh and bool((job["cursor"] or {}).get("scan_done"))
        need_store = job["storage"] in DISK_STORAGES and not refresh

        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET status='running', started_at=COALESCE(started_at,:n),"
                           " updated_at=:n WHERE id=:i"), {"n": self.now(), "i": job_id})

        paused_reason = None
        pages = 0
        # 「指定起点」按普通作品序号（剔除置顶）定位，需要跨页累计已扫描的普通作品数。
        normal_seen = 0
        try:
            if refresh and not left:
                # 没有待换地址的条目，什么也不用做；状态放回去，别顺手标成完成
                self._restore_status(job_id, prev_status)
                return self.get(user_id, job_id)
            pinned = set() if (refresh or drained) else self.pinned_fn(ref, cookie_file)
            while not drained and pages < limit_pages:
                window = self.window_fn(ref, start, page_size, cookie_file)
                if not window.items:
                    break
                self._remember_profile(job_id, getattr(window, "profile", None))
                if refresh:
                    self._refresh_links(job_id, window.items)
                    left.difference_update(v.video_id for v in window.items)
                    stop = not left
                else:
                    stop, normal_seen = self._absorb(
                        job_id, window.items, sel, pinned, job["media_type"], normal_seen=normal_seen)
                start += len(window.items)
                pages += 1
                if not refresh:
                    self._save_cursor(job_id, start, window.items)
                if stop:
                    break
                if not window.has_more:
                    break
            else:
                # 刷新地址是一次性维护动作：扫到上限就收工，别把任务挂成「分段暂停」。
                # drained 时循环根本没进（列表早就扫完了），不算分段。
                if not drained:
                    paused_reason = None if refresh else "segment_limit"
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
        elif refresh:
            # 维护模式只换地址，不改任务终态。原来这里会 _finish('done')，于是一个
            # 「已取消、一条都没存过」的任务点一次「刷新地址」就变成「已完成」，
            # 而落盘阶段从头到尾没跑过（done=0、stored=0，界面上却显示成功）。
            if self._status(job_id) == "running":
                self._restore_status(job_id, prev_status)
        else:
            # 清单齐了再落盘：缓存/长期保存模式要逐条把文件下到服务器，这一阶段可能要跑很久，
            # 期间每处理一条都会看一次任务状态，暂停/取消能立刻停。
            self._mark_scan_done(job_id, start)
            if need_store:
                self._store_items(job_id, user_id)
            if self._status(job_id) == "running":
                # 落盘模式下「一条文件都没存下来」不能报成功：以前只看状态就标完成，
                # 结果界面上是「已完成 + 0 / 292」，用户以为存好了，其实什么都没下。
                after = self.get(user_id, job_id) or {}
                empty_store = (need_store
                               and int(after.get("total") or 0) > 0
                               and int(after.get("stored") or 0) == 0)
                if empty_store:
                    self._finish(job_id, "failed", reason="落盘阶段没有处理任何条目")
                else:
                    self._finish(job_id, "done")
        return self.get(user_id, job_id)

    def _remember_profile(self, job_id: int, profile) -> None:
        """第一次拿到博主资料时把昵称写进任务：任务卡要显示「谁的作品」，不能只有一个长 URL。"""
        name = (getattr(profile, "name", "") or "").strip()[:100]
        if not name:
            return
        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET creator_name=:n WHERE id=:i AND creator_name=''"),
                      {"n": name, "i": job_id})

    def _item_ids(self, job_id: int) -> set[str]:
        """该任务已入库的作品 id，用于刷新模式判断「还要扫多少」。"""
        with self.engine.begin() as c:
            return {r[0] for r in c.execute(
                text("SELECT video_id FROM creator_items WHERE job_id=:j"), {"j": job_id}).all()}

    def _refresh_links(self, job_id: int, videos: list[CreatorVideo]) -> int:
        """只更新已有条目的播放地址；不插入、不改标题/时长/计数。返回真正变了的条数。"""
        now = self.now()
        updated = 0
        with self.engine.begin() as c:
            for v in videos:
                urls = list(v.play_urls or ())
                if not urls:
                    continue
                r = c.execute(text(
                    "UPDATE creator_items SET play_urls=:pu, play_urls_at=:n, updated_at=:n"
                    " WHERE job_id=:j AND platform=:p AND video_id=:v AND page=1"
                    " AND (play_urls IS NULL OR play_urls<>:pu)"),
                    {"pu": json.dumps(urls, ensure_ascii=False), "n": now, "j": job_id,
                     "p": v.platform.value, "v": v.video_id})
                updated += r.rowcount
        return updated

    def _absorb(self, job_id: int, videos: list[CreatorVideo], sel: Selection,
                pinned: set[str], media_type: str, normal_seen: int = 0) -> tuple[bool, int]:
        """写入本页；返回 (是否达到停止条件, 更新后的普通作品累计序号)。

        ``normal_seen`` 是跨页累计的「已扫描到的普通作品数」（剔除置顶，按发布时间倒序，
        1=最新），供「指定起点」模式跨页定位起点，其余模式不依赖它。
        """
        now = self.now()
        added_secs = 0.0
        stop = False
        with self.engine.begin() as c:
            have = int(c.execute(text("SELECT COUNT(*) FROM creator_items WHERE job_id=:j"),
                                 {"j": job_id}).scalar_one() or 0)
            added = 0
            for v in videos:
                if sel.mode == "latest_n" and sel.n and have + added >= sel.n:
                    stop = True
                    break
                item = self._select_one(v, sel, pinned)
                if item is None:
                    continue
                # 「指定起点」：序号只对普通作品计数；置顶作品独立、不占序号、不受起点限制。
                if sel.mode == "from_start" and not item["is_pinned"]:
                    normal_seen += 1
                    if sel.direction == "newer":
                        # 往最新：取 [1, start]，扫过起点就停
                        if normal_seen > sel.start:
                            stop = True
                            break
                    else:  # older
                        # 往最久：取 [start, 末尾]，起点之前的普通作品跳过
                        if normal_seen < sel.start:
                            continue
                r = c.execute(text(
                    "INSERT OR IGNORE INTO creator_items(job_id, platform, video_id, page, title,"
                    " duration_sec, published_at, is_pinned, pinned_order, cover_url, source_url,"
                    " download_state, added_at, updated_at)"
                    " VALUES(:j,:p,:v,1,:t,:d,:pub,:pin,:pord,:cv,:su,'pending',:n,:n)"),
                    {"j": job_id, "p": item["platform"], "v": item["video_id"], "t": item["title"][:200],
                     "d": item["duration_sec"], "pub": item["published_at"], "pin": int(item["is_pinned"]),
                     "pord": item.get("pinned_order"), "cv": item["cover_url"], "su": item["source_url"],
                     "n": now})
                if r.rowcount:
                    added += 1
                    added_secs += float(item["duration_sec"] or 0)
                # 播放地址单独补：不能并进上面的 INSERT，否则 upsert 的 rowcount 也会计 1，
                # added 会虚高、latest_n 提前停止。重扫时总是换成最新地址（越新过期越晚）。
                urls = item.get("play_urls") or []
                if urls:
                    c.execute(text(
                        "UPDATE creator_items SET play_urls=:pu, play_urls_at=:n"
                        " WHERE job_id=:j AND platform=:p AND video_id=:v AND page=1"
                        " AND (play_urls IS NULL OR play_urls<>:pu)"),
                        {"pu": json.dumps(urls, ensure_ascii=False), "n": now, "j": job_id,
                         "p": item["platform"], "v": item["video_id"]})
            self._refresh_counters(c, job_id, now)
            if added_secs:
                c.execute(text("UPDATE creator_jobs SET estimated_bytes=estimated_bytes+:b WHERE id=:j"),
                          {"b": estimate_bytes(added_secs, media_type, self.s), "j": job_id})
            total = int(c.execute(text("SELECT COUNT(*) FROM creator_items WHERE job_id=:j"),
                                  {"j": job_id}).scalar_one() or 0)

        if sel.mode == "latest_n" and sel.n:
            return total >= sel.n, normal_seen
        if sel.mode == "date_range" and sel.since is not None:
            pubs = [p for p in (_epoch(v.published_at) for v in videos) if p is not None]
            # 列表按发布时间倒序：本页最旧一条已经早于 since，后面只会更早
            return bool(pubs) and min(pubs) < sel.since, normal_seen
        if sel.mode == "from_start":
            return stop, normal_seen
        return False, normal_seen

    def _save_cursor(self, job_id: int, start: int, videos: list[CreatorVideo]) -> None:
        pubs = [_epoch(v.published_at) for v in videos]
        pubs = [p for p in pubs if p is not None]
        cur = {"start": start, "last_published_at": min(pubs) if pubs else None}
        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET cursor_json=:c, updated_at=:n WHERE id=:i"),
                      {"c": json.dumps(cur), "n": self.now(), "i": job_id})

    def _mark_scan_done(self, job_id: int, start: int) -> None:
        """给游标打上「列表扫完了」：之后的重跑只做落盘，不会再去平台翻列表。"""
        with self.engine.begin() as c:
            row = c.execute(text("SELECT cursor_json FROM creator_jobs WHERE id=:i"),
                            {"i": job_id}).first()
            try:
                cur = json.loads((row._mapping["cursor_json"] if row else "") or "{}")
            except ValueError:
                cur = {}
            cur["start"] = start
            cur["scan_done"] = True
            c.execute(text("UPDATE creator_jobs SET cursor_json=:c, updated_at=:n WHERE id=:i"),
                      {"c": json.dumps(cur), "n": self.now(), "i": job_id})

    # ---------- 落盘（服务器缓存 / 服务器存储） ----------
    def _status(self, job_id: int) -> str:
        with self.engine.begin() as c:
            return str(c.execute(text("SELECT status FROM creator_jobs WHERE id=:i"),
                                 {"i": job_id}).scalar_one())

    def _next_unstored(self, job_id: int) -> dict | None:
        with self.engine.begin() as c:
            row = c.execute(text(
                "SELECT id, video_id, platform, title, source_url, play_urls, duration_sec"
                " FROM creator_items"
                " WHERE job_id=:j AND media_path IS NULL AND store_error IS NULL"
                " ORDER BY published_at DESC, id ASC LIMIT 1"), {"j": job_id}).first()
        return dict(row._mapping) if row is not None else None

    def _store_items(self, job_id: int, user_id: str) -> dict:
        """把还没落盘的条目逐条下到服务器。

        这是整个任务里最慢的一段（按体积算时间），所以每处理一条都重读一次状态：
        用户点暂停/取消就立即收手，已下完的保留，下次继续。
        """
        job = self.get(user_id, job_id)
        storage = job["storage"]
        if storage not in DISK_STORAGES:
            return {"stored": 0, "failed": 0}
        ok = bad = 0
        # 整个任务只自动重扫一次：抖音的直链是扫描那一刻签的，几百条要下几小时，
        # 靠后那批往往已经过期；换一次地址能把剩下整批都刷新，不必每条都开浏览器。
        rescanned = False
        while self._status(job_id) == "running":
            item = self._next_unstored(job_id)
            if item is None:
                break
            try:
                size = self._store_one(job, item, user_id)
            except _LinksExpired as exc:
                if rescanned or not self._rescan_links(job_id, user_id):
                    bad += 1
                    self._mark_store_failed(job_id, item["id"],
                                            f"地址已过期，重扫后仍取不到（{exc}）"[:200])
                    continue
                rescanned = True
                fresh = self._reload_item(job_id, item["id"]) or item
                try:
                    size = self._store_one(job, fresh, user_id)
                except _StoreFailed as exc2:
                    bad += 1
                    self._mark_store_failed(job_id, item["id"], str(exc2)[:200])
                    continue
                except Exception as exc2:  # noqa: BLE001 - 一条失败不该拖垮整个任务
                    bad += 1
                    log.warning("批量落盘异常（条目 %s）：%s", item["id"], type(exc2).__name__)
                    self._mark_store_failed(job_id, item["id"], "下载失败，稍后重试")
                    continue
            except _StoreFailed as exc:
                bad += 1
                self._mark_store_failed(job_id, item["id"], str(exc)[:200])
            except Exception as exc:  # noqa: BLE001 - 一条失败不该拖垮整个任务
                bad += 1
                log.warning("批量落盘异常（条目 %s）：%s", item["id"], type(exc).__name__)
                self._mark_store_failed(job_id, item["id"], "下载失败，稍后重试")
            else:
                if size:
                    ok += 1
        if ok or bad:
            log.info("批量任务 %s 落盘结束：成功 %s 条，失败 %s 条", job_id, ok, bad)
        return {"stored": ok, "failed": bad}

    def _store_one(self, job: dict, item: dict, user_id: str) -> int:
        """下一条：先把原始文件取回来，再按选中的每个格式各产出一份。返回总字节数。"""
        storage = job["storage"]
        media_type = job["media_type"] or "video"
        formts = job.get("formats") or creator_formats.defaults_for(media_type)
        base = self.store.dir_for(storage)
        stem = f"c{item['id']}-{item['video_id']}"
        src, src_ext = self._fetch_source(item, base, stem, media_type, user_id)
        try:
            files, total = self._produce(src, base, stem, src_ext, formts, storage)
        finally:
            src.unlink(missing_ok=True)
        return self._attach(item["id"], job["id"], storage, files, total, formts[0])

    @staticmethod
    def _play_urls(item: dict) -> list[str]:
        try:
            raw = json.loads(item.get("play_urls") or "[]")
        except (TypeError, ValueError):
            return []
        return [u for u in raw if isinstance(u, str) and u.startswith("http")]

    def _fetch_source(self, item: dict, base, stem: str, media_type: str, user_id: str) -> tuple:
        """把原始文件下到 ``<stem>.src``；返回（路径, 扩展名）。全失败时区分「地址过期」与「本来没地址」。"""
        urls = self._play_urls(item)
        last = "没有可用的播放地址"
        for url in urls:
            try:
                return self._download(url, item["platform"], base, stem), _guess_ext(url, media_type)
            except _StoreFailed as exc:
                last = str(exc)
                continue
        # B 站有「拿作品页现解析」的路子：每次都能换出新地址，不依赖扫描时存的那份
        if item["platform"] == "bilibili" and item.get("source_url") and self.media_resolver is not None:
            try:
                media = self.media_resolver.resolve(item["source_url"], media_type=media_type,
                                                    user_id=user_id)
                source = media.sources[0]
            except Exception as exc:  # noqa: BLE001
                raise _StoreFailed(f"解析失败：{type(exc).__name__}") from exc
            if not media.sources:
                raise _StoreFailed("平台没有返回可播放地址")
            return self._download(source, "bilibili", base, stem), (media.ext or "mp4")
        # 抖音没有现解析的路子（要开浏览器，一条几十秒），只能重扫列表换地址；
        # 列表里本来就没地址（``urls`` 为空）另说，那种重扫也救不回来。
        if urls:
            raise _LinksExpired(last)
        raise _StoreFailed(last)

    def _download(self, url: str, platform: str, base, stem: str):
        """取一路流写到 ``<stem>.src``（临时名，转完就删）。"""
        with self.opener(url, platform) as chunks:
            path, size, _sha = self.store.write_atomic(base, f"{stem}.src", chunks)
        if size <= 0:
            path.unlink(missing_ok=True)
            raise _StoreFailed("上游文件为空")
        return path

    def _produce(self, src, base, stem: str, src_ext: str, formts: list[str],
                 storage: str) -> tuple[dict, int]:
        """按每个格式产出文件；任何一种失败就把这一条已产出的文件删干净。"""
        files: dict[str, dict] = {}
        total = 0
        try:
            for fmt in formts:
                name = f"{stem}.{creator_formats.ext_of(fmt, src_ext)}"
                out = base / name
                if fmt == "source":
                    shutil.copyfile(src, out)
                else:
                    creator_formats.convert(src, out, fmt)
                size = out.stat().st_size if out.is_file() else 0
                if size <= 0:
                    raise _StoreFailed(f"{fmt.upper()} 转换出来是空文件")
                files[fmt] = {"path": name, "bytes": size}
                total += size
            # 落盘前腾地方：缓存删过期/最久没用的，长期保存按时间删最旧的
            self.store.ensure_room(storage, total)
        except creator_formats.FormatError as exc:
            self._drop_files(base, files)
            raise _StoreFailed(str(exc)) from exc
        except MediaError as exc:
            self._drop_files(base, files)
            raise _StoreFailed(exc.detail) from exc
        except Exception:
            self._drop_files(base, files)
            raise
        return files, total

    @staticmethod
    def _drop_files(base, files: dict) -> None:
        for meta in files.values():
            try:
                (base / meta["path"]).unlink(missing_ok=True)
            except OSError:
                pass

    def render_format(self, user_id: str, job_id: int, video_id: str, fmt: str,
                      workdir) -> tuple:
        """临时产出一个格式（条目没落盘、或这个格式没存过时用）。返回（路径, 扩展名）。"""
        job = self.get(user_id, job_id)
        item = self.item(user_id, job_id, video_id)
        media_type = job["media_type"] or "video"
        base = Path(workdir)
        base.mkdir(parents=True, exist_ok=True)
        stem = f"c{item['id']}-{video_id}"
        src, src_ext = self._fetch_source(item, base, stem, media_type, user_id)
        try:
            if fmt == "source":
                out = base / f"{stem}.{src_ext}"
                src.replace(out)
                return out, src_ext
            out = base / f"{stem}.{creator_formats.ext_of(fmt, src_ext)}"
            creator_formats.convert(src, out, fmt)
        finally:
            src.unlink(missing_ok=True)
        return out, creator_formats.ext_of(fmt, src_ext)

    def _reload_item(self, job_id: int, item_id: int) -> dict | None:
        """重扫之后重新读一条，拿到换过的 ``play_urls``。"""
        with self.engine.begin() as c:
            row = c.execute(text(
                "SELECT id, video_id, platform, title, source_url, play_urls, duration_sec"
                " FROM creator_items WHERE job_id=:j AND id=:i"), {"j": job_id, "i": item_id}).first()
        return dict(row._mapping) if row is not None else None

    def _rescan_links(self, job_id: int, user_id: str) -> bool:
        """落盘时撞上地址失效 → 重扫一次列表，把条目的播放地址换成新签的。

        只做「换地址」：不新增条目、不动计数与游标（与 ``run(refresh=True)`` 同一套写法）。
        抖音的列表必须走浏览器桥接，一次调用就能拿到 ``creator_scan_batch`` 深度内的全部作品，
        翻到列表末尾就停。返回是否真的换到了新地址。
        """
        job = self.get(user_id, job_id)
        ref = CreatorRef(Platform(job["platform"]), job["creator_id"], job["source_url"])
        cookie_file, _src = self.cookie_fn(ref.platform, user_id)
        page_size = max(int(self.s.creator_page_size),
                        int(getattr(self.s, "creator_scan_batch", 0) or 0))
        start, pages, updated = 1, 0, 0
        try:
            while pages < self.s.creator_max_pages_per_run and self._status(job_id) == "running":
                window = self.window_fn(ref, start, page_size, cookie_file)
                if not window.items:
                    break
                updated += self._refresh_links(job_id, window.items)
                start += len(window.items)
                pages += 1
                if not window.has_more:
                    break
        except Exception as exc:  # noqa: BLE001 - 重扫失败就按原错误报，不拖垮整个任务
            log.warning("批量任务 %s 自动重扫失败：%s", job_id, type(exc).__name__)
            return False
        if updated:
            log.info("批量任务 %s 自动重扫：更新 %s 条播放地址", job_id, updated)
        return updated > 0

    def _stream_to(self, url: str, platform: str, storage: str, filename: str) -> tuple[int, str]:
        """下载到本地文件（临时名 → 原子改名），返回（字节数, 相对文件名）。"""
        with self.opener(url, platform) as chunks:
            path, size, _sha = self.store.write_atomic(self.store.dir_for(storage), filename, chunks)
        if size <= 0:
            path.unlink(missing_ok=True)
            raise _StoreFailed("上游文件为空")
        try:
            # 落盘前腾地方：缓存删过期/最久没用的，长期保存按时间删最旧的
            self.store.ensure_room(storage, size)
        except MediaError as exc:
            path.unlink(missing_ok=True)
            raise _StoreFailed(exc.detail) from exc
        return size, filename

    def _attach(self, item_id: int, job_id: int, storage: str, files: dict,
                total: int, primary: str) -> int:
        """登记这一条的多个格式文件；``media_path`` 仍指向主格式，老接口照常能用。"""
        now = self.now()
        expires = now + int(self.s.creator_cache_ttl_seconds) if storage == "cache" else None
        with self.engine.begin() as c:
            c.execute(text(
                "UPDATE creator_items SET media_path=:p, files_json=:f, bytes=:b, stored_at=:n,"
                " store_expires_at=:e, download_state='done', store_error=NULL, error=NULL,"
                " updated_at=:n WHERE id=:i"),
                {"p": files[primary]["path"], "f": json.dumps(files, ensure_ascii=False),
                 "b": total, "n": now, "e": expires, "i": item_id})
            self._refresh_counters(c, job_id, now)
        return total

    def _mark_store_failed(self, job_id: int, item_id: int, reason: str) -> None:
        with self.engine.begin() as c:
            c.execute(text(
                "UPDATE creator_items SET download_state='failed', store_error=:r, updated_at=:n"
                " WHERE id=:i"), {"r": reason, "n": self.now(), "i": item_id})
            self._refresh_counters(c, job_id, self.now())

    def _finish(self, job_id: int, status: str, *, reason: str | None = None) -> None:
        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET status=:s, pause_reason=:r, finished_at=:n,"
                           " updated_at=:n WHERE id=:i"),
                      {"s": status, "r": reason, "n": self.now(), "i": job_id})

    def _restore_status(self, job_id: int, status: str) -> None:
        """维护模式跑完把状态放回进来时的样子：不动 finished_at，也不改 pause_reason。"""
        with self.engine.begin() as c:
            c.execute(text("UPDATE creator_jobs SET status=:s, updated_at=:n WHERE id=:i"),
                      {"s": status, "n": self.now(), "i": job_id})


__all__ = ["CreatorJobService", "Selection", "JobError", "StoreError", "MODES", "ITEM_STATES",
           "parse_day", "estimate_bytes"]
