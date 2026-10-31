"""HTTP 层（WP1：健康检查、协议、账号）。路径为服务端内部路径；对外前缀由 nginx 处理。"""
from __future__ import annotations

import ipaddress
import logging
import re
import time
from typing import Literal, Optional

from fastapi import Depends, FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator
from starlette.exceptions import HTTPException as StarletteHTTPException

from .alerts import AlertSink, LogAlertSink
from .config import Settings
from .db import make_engine, migrate
from .errors import ApiError
from .legal import LegalStore
from .release import ReleaseStore
from .runtime import RuntimeStore
from .tiers import unprotected_routes
from .security import PasswordHasher
from .service import AccountService, AuthCtx

log = logging.getLogger("getmedia.api")

SESSION_HEADER = "X-GetMedia-Session"
CSRF_HEADER = "X-GetMedia-CSRF"
COOKIE_NAME = "__Secure-gm_session"
_CTRL = re.compile(r"[\x00-\x1f\x7f]")


def _clean(v, n):
    return _CTRL.sub("", v)[:n] if isinstance(v, str) else v


class ClientInfo(BaseModel):
    channel: Literal["android", "web"]
    app_version: str = ""
    os: str = ""
    device: str = ""

    @field_validator("app_version", mode="before")
    @classmethod
    def _v(cls, v): return _clean(v, 32)

    @field_validator("os", "device", mode="before")
    @classmethod
    def _o(cls, v): return _clean(v, 64)


class DocConsent(BaseModel):
    version: int
    sha256: str = Field(max_length=64)


class Consent(BaseModel):
    user_agreement: DocConsent
    privacy_policy: DocConsent


class RegisterReq(BaseModel):
    login_name: str = Field(max_length=128)
    password: str = Field(max_length=256)
    invite_code: Optional[str] = Field(default=None, max_length=64)
    age_confirmed: bool
    consent: Consent
    client: ClientInfo


class LoginReq(BaseModel):
    login_name: str = Field(max_length=128)
    password: str = Field(max_length=256)
    client: ClientInfo
    evict_device_id: Optional[str] = Field(default=None, max_length=64)


class HeartbeatReq(BaseModel):
    client: ClientInfo


class PasswordReq(BaseModel):
    old_password: str = Field(max_length=256)
    new_password: str = Field(max_length=256)


class RecoverReq(BaseModel):
    login_name: str = Field(max_length=128)
    recovery_code: str = Field(max_length=64)
    new_password: str = Field(max_length=256)


def client_ip(request: Request, trusted: tuple[str, ...]) -> str:
    peer = request.client.host if request.client else ""
    if peer in trusted:
        xff = request.headers.get("x-forwarded-for", "")
        last = xff.split(",")[-1].strip()
        try:
            return str(ipaddress.ip_address(last))
        except ValueError:
            pass
    return peer


def create_app(settings: Settings, *, clock=time.time, alerts: AlertSink | None = None) -> FastAPI:
    alerts = alerts or LogAlertSink()
    engine = make_engine(settings.database_path)
    migrate(engine, settings.database_path, settings.backup_dir, clock())
    legal = LegalStore(engine, settings.legal_dir, alerts, clock)
    legal.load_initial()  # 协议文件缺失/首部无法解析 → 抛错，拒绝启动
    hasher = PasswordHasher(settings.argon_memory_kib, settings.argon_iterations, settings.hash_concurrency)
    runtime = RuntimeStore(settings.runtime_file)
    release = ReleaseStore(settings.release_file)
    svc = AccountService(engine, settings, hasher, legal, runtime, clock)

    app = FastAPI(title="GetMedia", docs_url=None, redoc_url=None, openapi_url=None)
    app.state.settings, app.state.engine, app.state.svc = settings, engine, svc
    app.state.legal, app.state.runtime, app.state.alerts, app.state.release = legal, runtime, alerts, release

    # ---------- 错误与通用头 ----------
    @app.exception_handler(ApiError)
    async def _api_error(_r, e: ApiError):
        return JSONResponse({"detail": e.detail, "code": e.code, **e.extra}, status_code=e.status, headers=e.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation(_r, _e):
        return JSONResponse({"detail": "提交的内容格式不正确", "code": "validation_error"}, status_code=422)

    @app.exception_handler(StarletteHTTPException)
    async def _http(_r, e: StarletteHTTPException):
        text = {404: "未找到", 405: "不支持的请求方法"}.get(e.status_code, "请求失败")
        return JSONResponse({"detail": text, "code": f"http_{e.status_code}"}, status_code=e.status_code)

    @app.middleware("http")
    async def _headers(request: Request, call_next):
        resp = await call_next(request)
        resp.headers["Cache-Control"] = "no-store"
        resp.headers["X-Content-Type-Options"] = "nosniff"
        return resp

    # ---------- 依赖 ----------
    def ip_of(request: Request) -> str:
        return client_ip(request, settings.trusted_proxies)

    def auth(request: Request) -> AuthCtx:
        token = request.headers.get(SESSION_HEADER)
        via_cookie = False
        if not token:
            token, via_cookie = request.cookies.get(COOKIE_NAME), True
        if not token:
            raise ApiError(401, "unauthenticated", "请先登录")
        if via_cookie and request.method not in ("GET", "HEAD", "OPTIONS"):
            origin = request.headers.get("origin", "")
            host = request.headers.get("host", "")
            if not request.headers.get(CSRF_HEADER) or origin != f"https://{host}":
                raise ApiError(403, "csrf", "请求来源校验失败")
        ctx = svc.authenticate(token, ip_of(request))
        if ctx is None:
            raise ApiError(401, "unauthenticated", "登录已失效，请重新登录")
        return ctx

    def require_tier(*tiers: str):
        """路由级依赖：账号等级不在 tiers 内 → 403 并写审计。"""
        def dep(request: Request, ctx: AuthCtx = Depends(auth)) -> AuthCtx:
            if ctx.tier not in tiers:
                svc._audit_own("forbidden_tier", ctx.user_id, ip_of(request), request.url.path, f"need={'|'.join(tiers)}")
                raise ApiError(403, "forbidden_tier", "当前账号无权使用该功能")
            return ctx
        dep._required_tiers = tuple(tiers)  # type: ignore[attr-defined]
        return dep
    app.state.require_tier = require_tier

    def with_session(resp_body: dict, channel: str, response: Response) -> dict:
        """App 在响应体拿令牌；网页只通过 HttpOnly Cookie 持有，响应体不含令牌。"""
        sess = resp_body["session"]
        if channel == "web":
            response.set_cookie(COOKIE_NAME, sess.pop("token"), max_age=sess["expires_at"] - svc.now(), path=settings.cookie_path,
                                secure=True, httponly=True, samesite="strict")
        return resp_body

    # ---------- 路由 ----------
    @app.get("/health")
    def health():
        return {"ok": True}

    @app.get("/v2/config")
    def config():
        rt, rel = runtime.get(), release.get()
        return {"registration_open": rt.registration_open, "invite_required": rt.invite_required,
                "maintenance": rt.maintenance, "min_version": rel["min_version"], "latest": rel["latest"],
                "server_time": svc.now()}

    @app.post("/v2/heartbeat", status_code=204)
    def heartbeat(req: HeartbeatReq, ctx: AuthCtx = Depends(auth)):
        svc.heartbeat(ctx, req.client)
        return Response(status_code=204)

    @app.get("/v2/legal")
    def get_legal():
        return {"documents": legal.public()}

    @app.post("/account/register", status_code=201)
    def register(req: RegisterReq, request: Request, response: Response):
        out = svc.register(req, ip_of(request))
        return with_session(out, req.client.channel, response)

    @app.post("/account/login")
    def login(req: LoginReq, request: Request, response: Response):
        out = svc.login(req, ip_of(request))
        return with_session(out, req.client.channel, response)

    @app.post("/account/logout", status_code=204)
    def logout(request: Request, ctx: AuthCtx = Depends(auth)):
        svc.logout(ctx, ip_of(request), request.url.path)
        resp = Response(status_code=204)
        if ctx.channel == "web":
            resp.delete_cookie(COOKIE_NAME, path=settings.cookie_path, secure=True, httponly=True, samesite="strict")
        return resp

    @app.get("/account/me")
    def me(ctx: AuthCtx = Depends(auth)):
        return svc.me(ctx)

    @app.post("/account/consent", status_code=204)
    def consent(c: Consent, request: Request, ctx: AuthCtx = Depends(auth)):
        svc.consent(ctx, c, ip_of(request), request.url.path)
        return Response(status_code=204)

    @app.post("/account/recovery/ack", status_code=204)
    def recovery_ack(request: Request, ctx: AuthCtx = Depends(auth)):
        svc.recovery_ack(ctx, ip_of(request), request.url.path)
        return Response(status_code=204)

    @app.get("/account/devices")
    def devices(ctx: AuthCtx = Depends(auth)):
        return {"devices": svc.list_devices(ctx)}

    @app.post("/account/devices/revoke-others")
    def revoke_others(request: Request, ctx: AuthCtx = Depends(auth)):
        return {"revoked": svc.revoke_others(ctx, ip_of(request), request.url.path)}

    @app.delete("/account/devices/{device_id}", status_code=204)
    def revoke_device(device_id: str, request: Request, ctx: AuthCtx = Depends(auth)):
        svc.revoke_device(ctx, device_id, ip_of(request), request.url.path)
        return Response(status_code=204)

    @app.post("/account/password", status_code=204)
    def password(req: PasswordReq, request: Request, ctx: AuthCtx = Depends(auth)):
        svc.change_password(ctx, req.old_password, req.new_password, ip_of(request), request.url.path)
        return Response(status_code=204)

    @app.post("/account/recover")
    def recover(req: RecoverReq, request: Request):
        return svc.recover(req.login_name, req.recovery_code, req.new_password, ip_of(request))

    bad = unprotected_routes(app)
    if bad:  # 默认拒绝：有未登记的路由则拒绝启动
        raise RuntimeError(f"以下路由既不在免费白名单、也未挂 owner 级依赖：{bad}")
    return app
