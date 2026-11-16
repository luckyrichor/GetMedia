"""平台授权的 HTTP 层（P2e）。"""
from __future__ import annotations

import logging
from typing import Literal

from fastapi import Depends, Request
from pydantic import BaseModel, ConfigDict, Field

from .errors import ApiError
from .platform_auth import AuthError, PlatformAuthService
from .service import AuthCtx

log = logging.getLogger("getmedia.platform_auth_api")


class StartBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    platform: Literal["bilibili", "douyin"]
    method: Literal["webview", "cookie"] = "webview"


class SessionBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    session_id: str = Field(min_length=4, max_length=64)


class CookieBody(BaseModel):
    model_config = ConfigDict(extra="forbid")
    session_id: str = Field(min_length=4, max_length=64)
    cookie: str = Field(min_length=1, max_length=16384)


def register(app, guard, engine, clock, *, vault, oauth_configured: bool = False) -> PlatformAuthService:
    service = PlatformAuthService(engine, app.state.settings, clock, vault)
    app.state.platform_auth = service

    def _err(exc: AuthError) -> None:
        raise ApiError(exc.status, exc.code, exc.detail) from exc

    @app.get("/account/platforms")
    def platforms(_ctx: AuthCtx = Depends(guard)):
        return {"platforms": service.platforms(), "oauth_configured": oauth_configured}

    @app.get("/account/platform-bindings")
    def bindings(ctx: AuthCtx = Depends(guard)):
        return {"bindings": service.bindings(ctx.user_id),
                "credentials": service.credential_state(ctx.user_id)}

    @app.post("/account/platform-auth/start")
    def start(body: StartBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.start(ctx.user_id, body.platform, body.method)
        except AuthError as exc:
            _err(exc)

    @app.post("/account/platform-auth/status")
    def status(body: SessionBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.status(ctx.user_id, body.session_id)
        except AuthError as exc:
            _err(exc)

    @app.post("/account/platform-auth/cookie")
    def submit_cookie(body: CookieBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.complete_cookie(ctx.user_id, body.session_id, body.cookie)
        except AuthError as exc:
            _err(exc)

    @app.post("/account/platform-auth/complete")
    def complete(body: CookieBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.complete_cookie(ctx.user_id, body.session_id, body.cookie)
        except AuthError as exc:
            _err(exc)

    @app.post("/account/platform-auth/cancel")
    def cancel(body: SessionBody, ctx: AuthCtx = Depends(guard)):
        try:
            return service.cancel(ctx.user_id, body.session_id)
        except AuthError as exc:
            _err(exc)

    @app.post("/account/platform-bindings/{platform}/unbind")
    def unbind(platform: str, ctx: AuthCtx = Depends(guard)):
        try:
            return service.unbind(ctx.user_id, platform)
        except AuthError as exc:
            _err(exc)

    # 抖音开放平台 OAuth 回调：未配置时明确 501，不假装可用。
    # 匿名可达（浏览器跳转过来没有会话），因此登记在 FREE_ROUTES。
    @app.get("/account/platform-auth/douyin/callback")
    def douyin_callback(request: Request):
        if not oauth_configured:
            raise ApiError(501, "oauth_unconfigured", "抖音开放平台授权尚未配置，请使用网页登录或导入 Cookie")
        return {"detail": "回调已收到，请回到应用查看绑定状态", "state": request.query_params.get("state", "")}

    return service


__all__ = ["register", "StartBody", "SessionBody", "CookieBody"]
