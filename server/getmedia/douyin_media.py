"""抖音单条作品的**浏览器解析**（给网页端「只存元数据」的条目取流用）。

为什么不用接口直连：抖音的 ``/aweme/v1/web/aweme/detail/`` 要 ``a_bogus`` 签名，
裸请求一律 403（服务器上用现成 Cookie 实测过）。``creators/douyin.py`` 早就为此接了
Playwright 桥接，这里复用同一套思路，只做一件事：打开作品页、截获详情响应、
把 ``video.play_addr`` 的直链交回去。

三条约束：

1. **只在明确需要时开浏览器**（``settings.douyin_browser`` 为真、且平台是抖音）；
   关掉时调用方仍按原来的 503 处理，不静默降级。
2. **结果缓存**：直链有时效，缓存 10 分钟；同一作品在这期间重复播放不再开浏览器。
3. **串行**：一台机器同时开多个 Chromium 很快就把内存吃光，用一把进程内锁排队。
"""

from __future__ import annotations

import logging
import threading
import time
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
)

#: 直链有效期通常以小时计；10 分钟足够避开"每次播放都开浏览器"，又不会拿到过期地址。
CACHE_TTL = 600.0

_cache: dict[str, tuple[float, dict[str, Any]]] = {}
_lock = threading.Lock()

_BROWSER_HINT = "服务器未安装 Playwright/Chromium；抖音条目暂时只能在手机端提取"


class DouyinMediaError(RuntimeError):
    """带用户可读文案的解析失败。"""

    def __init__(self, message: str, *, code: str = "douyin_resolve_failed"):
        super().__init__(message)
        self.code = code


def _parse_cookies(path: Path | None) -> dict[str, str]:
    """Netscape 格式 → dict；拿不到就当没有 Cookie（页面仍可能加载出详情）。"""
    out: dict[str, str] = {}
    if path is None:
        return out
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError:
        return out
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 7:
            out[parts[5]] = parts[6]
    return out


def _first(node: Any) -> str | None:
    if isinstance(node, dict):
        urls = node.get("url_list") or []
        if isinstance(urls, list):
            for u in urls:
                if isinstance(u, str) and u.startswith("http"):
                    return u
    return None


def _urls_of(node: Any) -> list[str]:
    """一个 ``{url_list: [...]}`` 节点里的全部 http 地址（保序）。"""
    if not isinstance(node, dict):
        return []
    return [u for u in (node.get("url_list") or []) if isinstance(u, str) and u.startswith("http")]


def _collect_urls(video: dict) -> tuple[str, ...]:
    """主播放地址优先，码率列表与下载地址做备份（顺序稳定、去重）。"""
    urls: list[str] = list(_urls_of(video.get("play_addr")))
    for item in video.get("bit_rate") or []:
        if isinstance(item, dict):
            urls += _urls_of(item.get("play_addr"))
    for key in ("play_addr_h264", "download_addr"):
        urls += _urls_of(video.get(key))
    seen: set[str] = set()
    ordered: list[str] = []
    for u in urls:
        if u not in seen:
            seen.add(u)
            ordered.append(u)
    return tuple(ordered)


def _detail_from_page(aweme_id: str, cookies: dict[str, str], *, timeout: float,
                      headless: bool) -> dict[str, Any]:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError as exc:  # pragma: no cover - 依赖缺失时才走这里
        raise DouyinMediaError(_BROWSER_HINT, code="douyin_browser_missing") from exc

    captured: dict[str, Any] = {}
    page_error: list[str] = []

    def on_response(response) -> None:
        url = response.url or ""
        if "/aweme/v1/web/aweme/detail/" not in url:
            return
        try:
            data = response.json()
        except Exception:  # noqa: BLE001 - 非 JSON 响应直接忽略
            return
        if not isinstance(data, dict):
            return
        aweme = data.get("aweme_detail") or {}
        if isinstance(aweme, dict) and aweme.get("aweme_id"):
            captured.update(aweme)

    with sync_playwright() as playwright:
        launch: dict[str, Any] = {
            "headless": headless,
            "args": ["--disable-blink-features=AutomationControlled",
                     "--disable-dev-shm-usage", "--no-sandbox"],
        }
        browser = playwright.chromium.launch(**launch)
        try:
            context = browser.new_context(
                user_agent=UA, locale="zh-CN", viewport={"width": 1536, "height": 864},
            )
            # 有登录 Cookie 才注入：访客 Cookie 在部分出口上反而更容易触发验证码
            if cookies.get("sessionid"):
                context.add_cookies([
                    {"name": k, "value": v, "domain": ".douyin.com", "path": "/",
                     "secure": True, "sameSite": "Lax"}
                    for k, v in cookies.items()
                ])
            page = context.new_page()
            page.on("response", on_response)
            try:
                page.goto(f"https://www.douyin.com/video/{aweme_id}",
                          wait_until="domcontentloaded", timeout=int(timeout * 1000))
            except Exception as exc:  # noqa: BLE001 - 页面慢/超时不等于失败，详情可能已截获
                page_error.append(f"{type(exc).__name__}: {str(exc)[:80]}")
            deadline = time.monotonic() + timeout
            while not captured and time.monotonic() < deadline:
                page.wait_for_timeout(500)
            if not captured:
                try:
                    title = (page.title() or "").strip()
                except Exception:  # noqa: BLE001
                    title = ""
                if title:
                    page_error.append(f"页面标题：{title[:40]}")
            context.close()
        finally:
            browser.close()

    if not captured:
        detail = "；".join(page_error) if page_error else "页面没有加载出作品详情"
        raise DouyinMediaError(
            f"抖音没有返回这条作品的详情（{detail}）。可能是作品已删除、需要登录，或触发了验证码。",
            code="douyin_detail_empty",
        )
    return captured


def resolve(aweme_id: str, cookie_file: Path | None, *, timeout: float = 30.0,
            headless: bool = True, use_cache: bool = True) -> dict[str, Any]:
    """解析一条抖音作品，返回 ``{urls, title, duration_sec, cover_url, uploader}``。

    抛 [DouyinMediaError]，调用方负责翻译成 HTTP 错误。
    """
    key = str(aweme_id)
    if use_cache:
        hit = _cache.get(key)
        if hit and hit[0] > time.monotonic():
            return hit[1]

    cookies = _parse_cookies(cookie_file)
    with _lock:
        # 排队期间可能已经有人解析好了，再查一次
        if use_cache:
            hit = _cache.get(key)
            if hit and hit[0] > time.monotonic():
                return hit[1]
        aweme = _detail_from_page(key, cookies, timeout=timeout, headless=headless)

    video = aweme.get("video") or {}
    urls = _collect_urls(video if isinstance(video, dict) else {})
    if not urls:
        raise DouyinMediaError("抖音没有返回可播放的地址（可能是不公开的作品）",
                               code="douyin_no_play_url")
    duration_ms = video.get("duration") if isinstance(video, dict) else None
    result = {
        "urls": urls,
        "title": (aweme.get("desc") or "").strip(),
        "duration_sec": (duration_ms / 1000.0) if isinstance(duration_ms, (int, float)) else None,
        "cover_url": _first(video.get("cover")) if isinstance(video, dict) else None,
        "uploader": ((aweme.get("author") or {}).get("nickname") or None),
    }
    if use_cache:
        _cache[key] = (time.monotonic() + CACHE_TTL, result)
    return result


def clear_cache() -> None:
    """测试与运维用：丢掉所有已缓存的直链。"""
    _cache.clear()
