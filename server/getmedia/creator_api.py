"""博主主页批量提取的 HTTP 接口。

只有预览：GetMedia 的媒体文件始终由手机上的 ``DirectExtractor`` 提取，
服务端不落库、不下载、不做任务队列。这样既不需要新的表，也不会把
账号数据和媒体文件混在一起。

错误码沿用 GetMedia 的 ``ApiError``：code 稳定、detail 是可直接展示的中文。
"""
from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from typing import Literal

from fastapi import Depends
from pydantic import AliasChoices, BaseModel, ConfigDict, Field

from . import creator_service, ratelimit
from .creator_jobs import JobError, Selection, parse_day
from .creator_links import LinkError, require_numeric_id
from .creator_rt import settings
from .creators import CreatorAuthError, CreatorError, CreatorNotFound, CreatorUnavailable
from .creators.base import CreatorComment, CreatorProfile, CreatorVideo, VideoStats
from .errors import ApiError
from .service import AuthCtx

log = logging.getLogger("getmedia.creator_api")


class CreatorPreviewBody(BaseModel):
    model_config = ConfigDict(extra="forbid")

    url: str = Field(
        min_length=1,
        max_length=2000,
        validation_alias=AliasChoices("url", "input", "text"),
        description="博主主页链接、分享文案，或裸抖音号/B站UID/昵称",
    )
    platform: Literal["bilibili", "douyin"] | None = None
    start: int = Field(1, ge=1, le=500, description="1-based，最新一条为 1")
    count: int = Field(10, ge=1, le=50)
    include_stats: bool = Field(True, description="是否补齐点赞/评论/收藏等互动数据")
    include_comments: bool = Field(False, description="是否抓 B 站热门评论正文（需逐条请求，较慢）")
    comment_limit: int = Field(5, ge=1, le=20)
    # 批量选取：window 保持原来的序号窗口语义；其余四种会做有界扫描
    mode: Literal["window", "all", "date_range", "latest_n", "from_start"] = "window"
    since: str | None = Field(None, description="YYYY-MM-DD，北京时间，含当日")
    until: str | None = Field(None, description="YYYY-MM-DD，北京时间，含当日")
    n: int | None = Field(None, ge=1, le=100000, description="latest_n 的 n")
    start_n: int | None = Field(None, ge=1, le=500, description="from_start 的起点（普通作品序号，1=最新）")
    direction: Literal["newer", "older"] | None = Field(None, description="from_start 的方向")
    include_pinned: bool = Field(True, description="是否包含置顶作品（只影响筛选，不影响排序）")


class CreatorStatsOut(BaseModel):
    view_count: int | None = None
    like_count: int | None = None
    comment_count: int | None = None
    share_count: int | None = None
    favorite_count: int | None = None
    coin_count: int | None = None
    danmaku_count: int | None = None

    @classmethod
    def of(cls, stats: VideoStats) -> "CreatorStatsOut":
        return cls(
            view_count=stats.view_count,
            like_count=stats.like_count,
            comment_count=stats.comment_count,
            share_count=stats.share_count,
            favorite_count=stats.favorite_count,
            coin_count=stats.coin_count,
            danmaku_count=stats.danmaku_count,
        )


class CreatorCommentOut(BaseModel):
    author: str
    content: str
    like_count: int | None = None
    created_at: str | None = None
    reply_count: int | None = None

    @classmethod
    def of(cls, comment: CreatorComment) -> "CreatorCommentOut":
        return cls(
            author=comment.author,
            content=comment.content,
            like_count=comment.like_count,
            created_at=comment.created_at,
            reply_count=comment.reply_count,
        )


class CreatorVideoOut(BaseModel):
    position: int
    video_id: str
    title: str
    description: str | None = None
    source_url: str
    cover_url: str | None = None
    duration_sec: float | None = None
    published_at: str | None = None
    stats: CreatorStatsOut = Field(default_factory=CreatorStatsOut)
    comments: list[CreatorCommentOut] = Field(default_factory=list)
    comments_status: str | None = None
    is_pinned: bool = False
    pinned_order: int | None = None
    estimated_bytes: int = 0

    @classmethod
    def of(cls, video: CreatorVideo) -> "CreatorVideoOut":
        return cls(
            position=video.position,
            video_id=video.video_id,
            title=video.title,
            description=video.description,
            source_url=video.source_url,
            cover_url=video.cover_url,
            duration_sec=video.duration_sec,
            published_at=video.published_at,
            stats=CreatorStatsOut.of(video.stats),
            comments=[CreatorCommentOut.of(c) for c in video.comments],
            comments_status=video.comments_status,
        )


class CreatorProfileOut(BaseModel):
    platform: str
    creator_id: str
    name: str
    avatar_url: str | None = None
    url: str
    total_videos: int | None = None
    follower_count: int | None = None
    signature: str | None = None

    @classmethod
    def of(cls, profile: CreatorProfile) -> "CreatorProfileOut":
        return cls(
            platform=profile.platform.value,
            creator_id=profile.creator_id,
            name=profile.name,
            avatar_url=profile.avatar_url,
            url=profile.url,
            total_videos=profile.total_videos,
            follower_count=profile.follower_count,
            signature=profile.signature,
        )


class CreatorRangeOut(BaseModel):
    start: int
    count: int


class CreatorPreviewOut(BaseModel):
    profile: CreatorProfileOut
    range: CreatorRangeOut
    items: list[CreatorVideoOut]
    total: int | None = None
    has_more: bool = False
    fetched_at: str
    # 供诊断：configured / guest / none，不暴露任何 cookie 内容
    cookie_source: str
    mode: str = "window"
    estimated_bytes: int = 0


def _raise(exc: Exception) -> None:
    if isinstance(exc, (LinkError, creator_service.CreatorRangeError)):
        raise ApiError(400, "creator_input", str(exc)) from exc
    if isinstance(exc, CreatorNotFound):
        raise ApiError(404, "creator_not_found", str(exc)) from exc
    if isinstance(exc, CreatorAuthError):
        # 与平台授权失效保持同一语义：需要用户去平台重新授权
        raise ApiError(428, "creator_auth", "平台需要重新授权：" + str(exc)) from exc
    if isinstance(exc, CreatorUnavailable):
        raise ApiError(503, "creator_unavailable", str(exc)) from exc
    if isinstance(exc, CreatorError):
        raise ApiError(502, "creator_failed", str(exc)) from exc
    raise exc


def register(app, guard, engine, clock, *, jobs=None) -> None:
    """在 create_app 内部直接把路由挂到 app 上。

    不用 APIRouter + include_router：当前 FastAPI 会把 include 的路由包成
    ``_IncludedRouter`` 延迟展开，``tiers.unprotected_routes`` 的启动自检
    按 APIRoute 遍历会漏掉它们。直接注册保持「新增路由必须过白名单检查」
    这条约束继续有效。

    ``guard`` 是 ``require_tier("owner")``：博主批量属于完整版能力，
    免费版只保留"暂未开放"占位，因此这里不是 ``auth`` 而是 owner 级依赖。
    """

    def _hit(ctx: AuthCtx) -> None:
        limit, window = settings.creator_preview_limit
        with engine.begin() as conn:
            ok, retry = ratelimit.hit(conn, f"creator:user:{ctx.user_id}", limit, window, clock())
        if not ok:
            raise ApiError(429, "rate_limited", "查询太频繁，请稍后再试", headers={"Retry-After": str(retry)})


    def _batch_preview(body: CreatorPreviewBody, user_id: str) -> CreatorPreviewOut:
        """三种选取方式的有界扫描：不落库，只给预览与空间预估。"""
        if jobs is None:
            raise ApiError(503, "creator_jobs_unavailable", "批量任务服务未启用")
        try:
            ref = creator_service.resolve_ref(body.url, platform=body.platform, user_id=user_id)
            selection = Selection(
                mode=body.mode,
                since=parse_day(body.since) if body.since else None,
                until=parse_day(body.until, end=True) if body.until else None,
                n=body.n,
                include_pinned=body.include_pinned,
                start=body.start_n,
                direction=body.direction,
            )
            out = jobs.preview(ref, selection, user_id=user_id)
        except (JobError, LinkError) as exc:
            raise ApiError(400, "creator_input", str(exc)) from exc
        except Exception as exc:  # noqa: BLE001
            log.warning("批量预览失败: %s", type(exc).__name__)
            _raise(exc)
        profile = out.get("profile")
        if profile is None:
            raise ApiError(404, "creator_not_found", "没有读到该博主的公开作品")
        items = [
            CreatorVideoOut(
                position=i + 1, video_id=row["video_id"], title=row["title"],
                source_url=row["source_url"], cover_url=row.get("cover_url"),
                duration_sec=row.get("duration_sec"),
                published_at=(datetime.fromtimestamp(row["published_at"], timezone.utc).isoformat()
                              if row.get("published_at") else None),
                is_pinned=bool(row.get("is_pinned")),
                pinned_order=row.get("pinned_order"),
                estimated_bytes=int(row.get("estimated_bytes") or 0),
            )
            for i, row in enumerate(out["items"])
        ]
        return CreatorPreviewOut(
            profile=CreatorProfileOut.of(profile),
            range=CreatorRangeOut(start=1, count=len(items)),
            items=items,
            total=out.get("total"),
            has_more=bool(out.get("has_more")),
            fetched_at=datetime.now(timezone.utc).isoformat(),
            cookie_source="configured",
            mode=body.mode,
            estimated_bytes=int(out.get("estimated_bytes") or 0),
        )

    @app.post("/v2/creators/preview", response_model=CreatorPreviewOut)
    def preview(body: CreatorPreviewBody, ctx: AuthCtx = Depends(guard)):
        _hit(ctx)
        try:
            require_numeric_id(body.platform, body.url)
        except LinkError as exc:
            raise ApiError(400, "creator_input", str(exc)) from exc
        if body.mode != "window":
            return _batch_preview(body, ctx.user_id)
        try:
            result = creator_service.preview(
                body.url,
                start=body.start,
                count=body.count,
                include_stats=body.include_stats,
                include_comments=body.include_comments,
                comment_limit=body.comment_limit,
                platform=body.platform,
                user_id=ctx.user_id,
            )
        except ApiError:
            raise
        except Exception as exc:  # 统一翻译成稳定的 code，不把堆栈暴露给客户端
            log.warning("博主预览失败: %s", type(exc).__name__)
            _raise(exc)

        window = result.window
        return CreatorPreviewOut(
            profile=CreatorProfileOut.of(window.profile),
            range=CreatorRangeOut(start=body.start, count=body.count),
            items=[CreatorVideoOut.of(video) for video in window.items],
            total=window.total,
            has_more=window.has_more,
            fetched_at=result.fetched_at,
            cookie_source=result.cookie_source,
            mode="window",
        )

    @app.get("/v2/creators/limits")
    def limits(ctx: AuthCtx = Depends(guard)):
        """给手机端展示真实的窗口上限，不在客户端硬编码。"""
        return {"max_items": settings.creator_max_items, "max_start": settings.creator_max_start}

