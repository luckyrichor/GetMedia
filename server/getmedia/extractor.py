"""平台媒体的解析与直链读取（服务端最小实现）。

职责边界：
- 只做「单条作品 → 直链」与「直链 → 字节流」，不做列表（列表在 ``creators/``）；
- B 站走公开 Web 接口（view + wbi playurl，``fnval=1`` 取混流 mp4，便于直接播放）；
- 抖音接口要 ``a_bogus`` 签名、裸请求 403，所以走 ``douyin_media`` 的浏览器桥接；
  桥接没开时明确报"请用手机端提取"，不猜测直链。

测试通过 ``FakeResolver`` 注入，不联网。
"""
from __future__ import annotations

import logging
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urljoin, urlsplit

import httpx

from . import douyin_media
from .creator_rt import settings
from .creator_wbi import _get_mixin_key, _parse_cookie_file, _sign

log = logging.getLogger("getmedia.extractor")

_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

API_VIEW = "https://api.bilibili.com/x/web-interface/view"
API_PLAYURL = "https://api.bilibili.com/x/player/wbi/playurl"

_BV = re.compile(r"(BV[0-9A-Za-z]{10})")
_AV = re.compile(r"/video/av(\d+)", re.I)
_DY = re.compile(r"(?:/video/|modal_id=|/note/)(\d{6,})")
_BILI_HOST = re.compile(r"(bilibili\.com|b23\.tv)", re.I)
_DY_HOST = re.compile(r"(douyin\.com|iesdouyin\.com)", re.I)
_URL = re.compile(r"https?://[^\s\"'<>]+", re.I)
_PAGE = re.compile(r"[?&]p=(\d{1,4})", re.I)

# 短链展开的白名单：只有这些主机可以被服务端主动 GET。
# b23.tv / bili2233.cn 是分享短链；跳转目标是 bilibili.com 系列。
_SHORT_HOSTS = frozenset({"b23.tv", "bili2233.cn"})
_REDIRECT_HOSTS = frozenset({"b23.tv", "bili2233.cn", "bilibili.com", "www.bilibili.com", "m.bilibili.com"})


def _host_of(url: str) -> str:
    return (urlsplit(url).hostname or "").lower()


def _page_of(text: str) -> int:
    m = _PAGE.search(text or "")
    return int(m.group(1)) if m else 1


def expand_short_link(url: str, client_factory, *, timeout: float = 10.0, max_hops: int = 5) -> str:
    """跟随 302 展开 b23.tv / bili2233.cn 短链，带 SSRF 防护。

    限制：只允许白名单主机、只允许 http/https 且端口是 80/443、最多 5 跳；
    任何一跳跳到白名单外都中止（不会去请求内网地址）。失败抛 ResolveError。
    """
    cur = url
    for _ in range(max_hops + 1):
        parts = urlsplit(cur)
        host = (parts.hostname or "").lower()
        if parts.scheme not in ("http", "https") or host not in _REDIRECT_HOSTS:
            raise ResolveError("unsupported_link", "短链跳转到了不受信任的地址", 400)
        if parts.port not in (None, 80, 443):
            raise ResolveError("unsupported_link", "短链跳转到了不受信任的地址", 400)
        if host not in _SHORT_HOSTS:
            return cur  # 已经展开到真地址，不再真的去抓视频页
        with client_factory(timeout=timeout, follow_redirects=False,
                            headers={"User-Agent": _UA}) as client:
            resp = client.get(cur)
        loc = resp.headers.get("location")
        if resp.status_code in (301, 302, 303, 307, 308) and loc:
            cur = urljoin(cur, loc)
            continue
        if resp.status_code >= 400:
            raise ResolveError("resolve_failed", f"短链展开失败：{resp.status_code}", 502)
        return str(resp.url)
    raise ResolveError("unsupported_link", "短链跳转次数过多", 400)


class ResolveError(Exception):
    def __init__(self, code: str, detail: str, status: int = 502):
        super().__init__(detail)
        self.code, self.detail, self.status = code, detail, status


@dataclass(frozen=True)
class MediaSource:
    url: str
    headers: dict = field(default_factory=dict)


@dataclass(frozen=True)
class ResolvedMedia:
    platform: str
    video_id: str
    title: str
    duration_sec: float | None
    sources: tuple[MediaSource, ...]
    ext: str = "mp4"
    uploader: str | None = None
    cover_url: str | None = None
    source_url: str | None = None
    page: int = 1
    estimated_bytes: int = 0
    media_type: str | None = None


class MediaResolver:
    """默认实现：B 站走公开接口；抖音走浏览器桥接（未开启时交回手机端）。"""

    platform_name = "bilibili"

    def __init__(self, *, client_factory=None, cookie_dir: Path | None = None):
        self._client_factory = client_factory or (lambda **kw: httpx.Client(**kw))
        self._cookie_dir = cookie_dir

    # ---------- 输入识别 ----------
    @staticmethod
    def sniff(text: str) -> tuple[str, str] | None:
        """从链接/分享文案里取（平台, 作品号）；识别不到返回 None。"""
        t = (text or "").strip()
        if not t:
            return None
        if _BILI_HOST.search(t):
            m = _BV.search(t)
            if m:
                return "bilibili", m.group(1)
            m = _AV.search(t)
            if m:
                return "bilibili", "av" + m.group(1)
            return None
        if _DY_HOST.search(t):
            m = _DY.search(t)
            if m:
                return "douyin", m.group(1)
            return None
        m = _BV.search(t)
        if m:
            return "bilibili", m.group(1)
        return None

    def cookie_file(self, platform: str, user_id: str | None = None) -> Path | None:
        """优先用该用户自己上传的 ``<platform>.<user_id>.txt``，没有就退回运维/默认的
        ``<platform>.txt``；再没有就现取一份访客 Cookie。

        B 站机房 IP 对 ``x/web-interface/view`` 的裸请求会直接 412，
        带上 ``buvid3`` 才放行——这也是 ``creators/`` 里同样的做法。
        """
        if self._cookie_dir is None:
            self._cookie_dir = settings.creator_cookie_dir
        if user_id:
            own = Path(self._cookie_dir) / f"{platform}.{user_id}.txt"
            if own.is_file() and own.stat().st_size > 0:
                return own
        p = Path(self._cookie_dir) / f"{platform}.txt"
        if p.is_file() and p.stat().st_size > 0:
            return p
        try:
            from .creator_guest import ensure_guest_cookies
            from .creator_links import Platform

            return ensure_guest_cookies(Platform(platform))
        except Exception as exc:  # noqa: BLE001 - 拿不到访客 cookie 就空手请求，由平台决定
            log.warning("%s 访客 cookie 预热失败：%s", platform, type(exc).__name__)
            return None

    # ---------- 解析 ----------
    def expand(self, text: str) -> str:
        """分享文案里如果带 b23.tv 短链，先展开成真地址；其他情况原样返回。"""
        m = _URL.search(text or "")
        if not m:
            return text
        raw = m.group(0).rstrip(".,;)]}")
        if _host_of(raw) not in _SHORT_HOSTS:
            return text
        try:
            final = expand_short_link(raw, self._client_factory)
        except (ResolveError, httpx.HTTPError) as exc:
            log.warning("短链展开失败：%s", getattr(exc, "code", type(exc).__name__))
            return text  # 展开失败不致命：交给 sniff 报 unsupported_link
        return text.replace(m.group(0), final, 1)

    def resolve(self, text: str, *, media_type: str = "video", user_id: str | None = None) -> ResolvedMedia:
        expanded = self.expand(text)
        found = self.sniff(expanded)
        if found is None:
            raise ResolveError("unsupported_link", "识别不出作品链接；请传单条作品链接或 BV 号", 400)
        platform, vid = found
        if platform == "douyin":
            return self._resolve_douyin(vid, media_type=media_type, user_id=user_id)
        page = _page_of(expanded)
        return self._resolve_bilibili(vid, media_type=media_type,
                                      target_page=page if page > 1 else None, user_id=user_id)

    def _resolve_douyin(self, aweme_id: str, *, media_type: str,
                        user_id: str | None = None) -> ResolvedMedia:
        """抖音单条：走 Playwright 打开作品页，截获详情响应里的播放直链。

        接口直连要 ``a_bogus`` 签名，裸请求一律 403（服务器上实测），所以只能开浏览器。
        桥接没开（``GETMEDIA_DOUYIN_BROWSER``）时保持原来的 503，不静默降级——
        "服务端能不能取"必须是显式配置，而不是碰运气。
        """
        if not settings.douyin_browser:
            raise ResolveError(
                "platform_needs_phone",
                "抖音直链需要授权 Cookie 与浏览器桥接；请在手机端提取，或先在网页端完成抖音授权",
                503,
            )
        started = time.monotonic()
        try:
            info = douyin_media.resolve(
                aweme_id,
                self.cookie_file("douyin", user_id),
                timeout=float(settings.douyin_browser_timeout),
                headless=bool(settings.douyin_browser_headless),
            )
        except douyin_media.DouyinMediaError as exc:
            log.warning("抖音解析失败（%s）：%s", exc.code, exc)
            raise ResolveError(exc.code, str(exc), 502) from exc
        headers = {"User-Agent": douyin_media.UA, "Referer": "https://www.douyin.com/"}
        log.info("抖音解析完成：%s，%d 个直链，耗时 %.1fs",
                 aweme_id, len(info["urls"]), time.monotonic() - started)
        return ResolvedMedia(
            platform="douyin",
            video_id=aweme_id,
            title=info["title"] or aweme_id,
            duration_sec=info["duration_sec"],
            sources=tuple(MediaSource(url=u, headers=dict(headers)) for u in info["urls"]),
            ext="mp4",
            uploader=info["uploader"],
            cover_url=info["cover_url"],
            source_url=f"https://www.douyin.com/video/{aweme_id}",
            media_type=media_type,
        )

    def resolve_target(self, text: str, *, media_type: str, page: int,
                       user_id: str | None = None) -> ResolvedMedia:
        found = self.sniff(text)
        if found is None or found[0] != "bilibili":
            return self.resolve(text, media_type=media_type, user_id=user_id)
        pages_in_link = re.findall(r"[?&]p=([^&#\s]+)", text)
        if any(value != str(page) for value in pages_in_link):
            raise ResolveError("target_mismatch", "链接中的分集与目标不符", 409)
        return self._resolve_bilibili(found[1], media_type=media_type, target_page=page, user_id=user_id)

    def _resolve_bilibili(self, vid: str, *, media_type: str, target_page: int | None = None,
                          user_id: str | None = None) -> ResolvedMedia:
        params = {"bvid": vid} if vid.startswith("BV") else {"aid": vid[2:]}
        cookies = _parse_cookie_file(self.cookie_file("bilibili", user_id))
        headers = {"User-Agent": _UA, "Referer": "https://www.bilibili.com/"}
        with self._client_factory(timeout=settings.http_timeout, headers=headers,
                                  cookies=cookies, follow_redirects=True) as client:
            view = client.get(API_VIEW, params=params)
            body = view.json() if view.status_code < 400 else {}
            if body.get("code") != 0 or not body.get("data"):
                raise ResolveError("resolve_failed", f"B 站没有返回该作品：{body.get('message') or view.status_code}", 404)
            data = body["data"]
            if target_page is not None:
                pages = data.get("pages") or []
                selected = next((p for p in pages if p.get("page") == target_page), None)
                if selected is None or not selected.get("cid"):
                    raise ResolveError("target_mismatch", "B 站未返回目标分集", 409)
                cid = selected["cid"]
                duration = selected.get("duration")
            else:
                cid = data.get("cid")
                pages = data.get("pages") or []
                if not cid:
                    cid = pages[0].get("cid") if pages else None
                duration = data.get("duration")
            if not cid:
                raise ResolveError("resolve_failed", "该作品没有可播放的分片")

            query = {"bvid": data.get("bvid") or vid, "cid": cid, "fnval": 1, "fnver": 0, "fourk": 1}
            try:
                key = _get_mixin_key(client)
                query = _sign(query, key)
            except Exception as exc:  # noqa: BLE001 - 签名失败时退回未签名请求，由平台决定
                log.warning("playurl 签名失败，改用未签名请求：%s", type(exc).__name__)
            play = client.get(API_PLAYURL, params=query)
            playbody = play.json() if play.status_code < 400 else {}
            if playbody.get("code") != 0:
                raise ResolveError("playurl_failed",
                                   f"取播放地址失败：{playbody.get('message') or play.status_code}", 502)
            durl = (playbody.get("data") or {}).get("durl") or []
            if not durl:
                raise ResolveError("playurl_empty", "平台没有返回可下载的分片地址", 502)
            first = durl[0]
            urls = [first.get("url")] + list(first.get("backup_url") or [])
            src_headers = {"User-Agent": _UA, "Referer": "https://www.bilibili.com/",
                           "Origin": "https://www.bilibili.com"}
            sources = tuple(MediaSource(u, src_headers) for u in urls if u)
            return ResolvedMedia(
                platform="bilibili",
                video_id=data.get("bvid") or vid,
                title=(data.get("title") or "")[:200] or "未命名作品",
                duration_sec=float(duration or 0) or None,
                sources=sources,
                ext="m4s" if media_type == "audio" else "mp4",
                uploader=(data.get("owner") or {}).get("name"),
                cover_url=data.get("pic"),
                source_url=f"https://www.bilibili.com/video/{data.get('bvid') or vid}"
                           + (f"?p={target_page}" if target_page and target_page > 1 else ""),
                page=target_page or 1,
                estimated_bytes=int(first.get("size") or 0),
                media_type=media_type,
            )

    # ---------- 读取 ----------
    def open_stream(self, source: MediaSource, *, range_header: str | None = None) -> httpx.Response:
        headers = dict(source.headers)
        if range_header:
            headers["Range"] = range_header
        client = self._client_factory(timeout=settings.http_timeout, follow_redirects=True)
        req = client.build_request("GET", source.url, headers=headers)
        return client.send(req, stream=True)


class FakeResolver:
    """测试用：固定返回一段可预测字节，不联网。"""

    payload = b"0123456789abcdef" * 8  # 128 B

    def __init__(self, *, fail: bool = False, title: str = "示例作品"):
        self.fail, self.title, self.calls = fail, title, []

    def resolve(self, text: str, *, media_type: str = "video", user_id: str | None = None) -> ResolvedMedia:
        self.calls.append(("resolve", text, media_type, user_id))
        if self.fail:
            raise ResolveError("resolve_failed", "测试注入的解析失败", 502)
        vid = (text.rstrip("/").rsplit("/", 1)[-1] or "BVtest0001")[:16]
        return ResolvedMedia(
            platform="bilibili", video_id=vid, title=self.title, duration_sec=12.5,
            sources=(MediaSource("https://example.invalid/v.mp4", {"Referer": "https://example.invalid"}),),
            uploader="测试作者", cover_url=None, source_url=f"https://example.invalid/{vid}",
            estimated_bytes=len(self.payload),
        )

    def open_stream(self, source: MediaSource, *, range_header: str | None = None):
        start, end = 0, len(self.payload) - 1
        status = 200
        if range_header and range_header.startswith("bytes="):
            spec = range_header[6:].split(",")[0]
            a, _, b = spec.partition("-")
            if a:
                start = int(a); end = int(b) if b else end
            elif b:
                start = max(0, len(self.payload) - int(b))
            status = 206
            end = min(end, len(self.payload) - 1)
        chunk = self.payload[start:end + 1]
        return httpx.Response(status, content=chunk,
                              headers={"Content-Range": f"bytes {start}-{end}/{len(self.payload)}",
                                       "Accept-Ranges": "bytes"})


def iter_source(resolver, source: MediaSource, range_header: str | None = None):
    """把解析器返回的响应包成可迭代字节块，并保证关闭连接。"""
    resp = resolver.open_stream(source, range_header=range_header)
    try:
        if resp.status_code >= 400:
            raise ResolveError("media_upstream_failed", f"上游返回 {resp.status_code}", 502)
        yield from resp.iter_bytes()
    finally:
        resp.close()


__all__ = ["MediaResolver", "FakeResolver", "ResolveError", "ResolvedMedia", "MediaSource", "iter_source"]
