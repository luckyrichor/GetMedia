"""博主窗口的编排层。

与旧音频项目的区别：旧版把任务表与下载流程绑在一起；GetMedia 的任务表只记录状态，
下载与入库由媒体库那套接口负责。
服务端只负责「输入 → 博主主页标识 → 一页作品列表（含互动数据）」，媒体仍由
手机上的 ``DirectExtractor`` 逐条提取。因此这里是纯读操作，不写数据库。
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from . import creator_guest
from .creator_links import CreatorRef, LinkError, Platform, find_urls, parse_creator_input, resolve_creator
from .creator_rt import settings
from .creators import CreatorAuthError, CreatorNotFound, CreatorRangeError, CreatorUnavailable, get_provider
from .creators.base import CreatorVideo, CreatorWindow

log = logging.getLogger("getmedia.creator")


@dataclass
class PreviewResult:
    ref: CreatorRef
    window: CreatorWindow
    fetched_at: str
    cookie_source: str


def validate_range(start: int, count: int) -> tuple[int, int]:
    if start < 1:
        raise CreatorRangeError("start 必须从 1 开始（最新一条为 1）")
    if start > settings.creator_max_start:
        raise CreatorRangeError(f"start 不能超过 {settings.creator_max_start}")
    if count < 1 or count > settings.creator_max_items:
        raise CreatorRangeError(f"count 必须在 1..{settings.creator_max_items} 之间")
    return start, count


def cookie_file_for(platform: Platform) -> tuple[Path | None, str]:
    """返回（cookie 文件, 来源）。

    优先用运维放在 ``creator-cookie-dir`` 里的 ``<platform>.txt``（登录凭证），
    没有就现取一份访客 cookie。两者都拿不到时返回 None —— 平台适配器会带着
    空 jar 继续尝试，由它决定是否需要提示用户授权。
    """
    configured = Path(settings.creator_cookie_dir) / f"{platform.value}.txt"
    try:
        if configured.exists() and configured.stat().st_size > 0:
            return configured, "configured"
    except OSError:
        pass
    try:
        guest = creator_guest.ensure_guest_cookies(platform)
    except Exception as exc:  # 访客预热失败不应阻断请求
        log.warning("%s 访客 cookie 预热异常: %s", platform.value, exc)
        guest = None
    return (guest, "guest") if guest else (None, "none")


def _platform_hint(value: str | None) -> Platform | None:
    if not value:
        return None
    try:
        return Platform(str(value).strip().lower())
    except ValueError as exc:
        raise LinkError(f"不支持的平台：{value}") from exc


def resolve_ref(text: str, *, platform: str | None = None) -> CreatorRef:
    """URL / 分享文案 / 抖音号 / B 站 UID / 昵称 → CreatorRef。"""
    hint = _platform_hint(platform)

    try:
        ref = resolve_creator(text, timeout=settings.http_timeout, max_redirects=settings.max_redirects)
    except LinkError as url_error:
        # 输入里已经有链接但不是博主主页（例如单条视频），不要再当昵称猜。
        if find_urls(text):
            raise
        query = parse_creator_input(text, platform=hint)
        if query.platform is None:
            if query.kind == "numeric":
                raise LinkError(
                    "请注明平台：抖音号：<号码> 或 B站UID：<号码>；也可以直接传博主主页链接"
                ) from url_error
            platforms = [Platform.BILIBILI, Platform.DOUYIN]
        else:
            platforms = [query.platform]

        errors: list[str] = []
        for candidate in platforms:
            provider = get_provider(candidate)
            cookie_file, _ = cookie_file_for(candidate)
            try:
                return provider.resolve(query.value, cookie_file=cookie_file)
            except CreatorAuthError:
                if query.platform is not None:
                    raise
                errors.append(f"{candidate.value}: 需要平台登录")
            except CreatorNotFound as exc:
                errors.append(f"{candidate.value}: {exc}")
            except CreatorUnavailable as exc:
                errors.append(f"{candidate.value}: {exc}")
                if query.platform is not None:
                    raise

        raise CreatorNotFound("没有找到对应博主：" + "；".join(errors))

    if hint is not None and ref.platform is not hint:
        raise LinkError(f"链接是 {ref.platform.value}，但 platform 指定了 {hint.value}")
    return ref


def preview(
    text: str,
    *,
    start: int,
    count: int,
    include_stats: bool = True,
    include_comments: bool = False,
    comment_limit: int = 5,
    platform: str | None = None,
) -> PreviewResult:
    start, count = validate_range(start, count)
    ref = resolve_ref(text, platform=platform)
    provider = get_provider(ref.platform)
    cookie_file, cookie_source = cookie_file_for(ref.platform)

    window = provider.fetch_window(ref, start=start, count=count, cookie_file=cookie_file)

    if include_stats or include_comments:
        for video in window.items:
            _enrich(provider, video, include_stats, include_comments, comment_limit, cookie_file)
            if settings.creator_request_interval:
                time.sleep(settings.creator_request_interval)

    return PreviewResult(
        ref=ref,
        window=window,
        fetched_at=datetime.now(timezone.utc).isoformat(),
        cookie_source=cookie_source,
    )


def _enrich(provider, video: CreatorVideo, include_stats: bool, include_comments: bool, comment_limit: int, cookie_file) -> None:
    """补齐互动数据；单条失败只记日志，不整批失败。"""
    try:
        provider.enrich(
            video,
            include_stats=include_stats,
            include_comments=include_comments,
            comment_limit=max(1, min(comment_limit, 20)),
            cookie_file=cookie_file,
        )
    except CreatorAuthError:
        raise
    except Exception as exc:  # noqa: BLE001 - 互动数据是附加信息
        log.warning("博主 %s 视频 %s 互动数据不可用: %s", video.creator_id, video.video_id, exc)
