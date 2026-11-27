"""管理员后台：账号列表、改等级、重置密码、停用/恢复、撤销注销、邀请码。

全部挂在 ``require_tier("admin")`` 下（全站唯一 admin）。nginx 不需要额外配置：
管理台走 ``/getmedia/owner/api/``，前缀被剥掉后落到这里的 ``/v2/admin/...``。

几条硬规则（见 docs/PLAN-tier-admin.local.md）：
- 不能把任何账号改成 admin，也不能降级/停用 admin；
- 改等级、重置密码、停用都必须吊销目标账号全部会话；
- 邀请码明文只在生成响应里出现一次，库里只有 HMAC。
"""
from __future__ import annotations

import math
from typing import Literal, Optional

from fastapi import Depends, FastAPI, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy import text
from sqlalchemy.engine import Engine

from .errors import ApiError
from .names import normalize_login_name, password_problem
from .service import AccountService, add_months
from .tiers import TIER_ADMIN, TIER_FREE, TIER_FULL

_SORTS = {
    "created_at": "created_at",
    "last_seen_at": "last_seen_at",
    "tracks": "media_total",
    "expires_at": "tier_expires_at",
    "login_name": "login_name",
}

_BASE_SQL = """
SELECT u.id AS id, u.login_name AS login_name, u.tier AS tier,
       u.tier_expires_at AS tier_expires_at, u.status AS status, u.created_at AS created_at,
       (SELECT MAX(s.last_used_at) FROM sessions s
         WHERE s.user_id=u.id AND s.revoked_at IS NULL AND s.expires_at>:now) AS last_seen_at,
       (SELECT COUNT(*) FROM sessions s
         WHERE s.user_id=u.id AND s.revoked_at IS NULL AND s.expires_at>:now) AS active_sessions,
       (SELECT COUNT(*) FROM tracks t
         WHERE t.user_id=u.id AND t.deleted=0 AND t.media_type='audio') AS audio,
       (SELECT COUNT(*) FROM tracks t
         WHERE t.user_id=u.id AND t.deleted=0 AND t.media_type='video') AS video,
       (SELECT COALESCE(SUM(t.bytes),0) FROM tracks t
         WHERE t.user_id=u.id AND t.deleted=0) AS bytes,
       (SELECT COUNT(*) FROM playlists p
         WHERE p.user_id=u.id AND p.deleted=0) AS playlists,
       (SELECT d.requested_at FROM deletion_requests d
         WHERE d.user_id=u.id AND d.executed_at IS NULL) AS deletion_requested_at,
       (SELECT d.execute_at FROM deletion_requests d
         WHERE d.user_id=u.id AND d.executed_at IS NULL) AS deletion_execute_at
  FROM users u
"""


class TierChangeReq(BaseModel):
    tier: Literal["free", "full"]
    duration: Literal["6m", "permanent"] = "6m"


class PasswordResetReq(BaseModel):
    password: str = Field(min_length=1, max_length=256)


class StatusReq(BaseModel):
    status: Literal["disabled", "active"]


class InviteCreateReq(BaseModel):
    count: int = Field(default=1, ge=1, le=50)


def register(app: FastAPI, require_admin, engine: Engine, svc: AccountService, clock=None) -> None:
    def ip_of(request: Request) -> str:
        return request.client.host if request.client else ""

    def _user(c, uid: str):
        return c.execute(text("SELECT id,login_name,login_name_norm,tier,tier_expires_at,status FROM users WHERE id=:u"),
                         {"u": uid}).first()

    def _revoke_all(c, uid: str, now: int) -> int:
        return c.execute(text("UPDATE sessions SET revoked_at=:n WHERE user_id=:u AND revoked_at IS NULL"),
                         {"n": now, "u": uid}).rowcount

    # ---------- 账号列表 ----------
    @app.get("/v2/admin/accounts")
    def list_accounts(request: Request, ctx=Depends(require_admin), q: str = "", tier: str = "",
                      status: str = "", expiry: str = "", created_from: Optional[int] = None,
                      created_to: Optional[int] = None, has_data: Optional[int] = None,
                      sort: str = "created_at", order: str = "desc",
                      page: int = 1, size: int = 50):
        now = svc.now()
        where, params = [], {"now": now}
        if q.strip():
            where.append("login_name LIKE :q")
            params["q"] = f"%{normalize_login_name(q.strip())[:64]}%"
        wanted = [t for t in (x.strip() for x in tier.split(",")) if t]
        bad = [t for t in wanted if t not in (TIER_FREE, TIER_FULL, TIER_ADMIN)]
        if bad:
            raise ApiError(422, "invalid_tier", "等级筛选值不合法")
        if wanted:
            keys = []
            for i, t in enumerate(wanted):
                params[f"t{i}"] = t
                keys.append(f":t{i}")
            where.append(f"tier IN ({','.join(keys)})")
        if status:
            if status not in ("active", "disabled", "pending_deletion"):
                raise ApiError(422, "invalid_status", "状态筛选值不合法")
            where.append("status=:st")
            params["st"] = status
        if expiry == "permanent":
            where.append("tier_expires_at IS NULL")
        elif expiry == "soon":
            params["soon"] = now + 30 * 86400
            where.append("tier_expires_at IS NOT NULL AND tier_expires_at>:now AND tier_expires_at<=:soon")
        elif expiry == "expired":
            where.append("tier_expires_at IS NOT NULL AND tier_expires_at<=:now")
        elif expiry:
            raise ApiError(422, "invalid_expiry", "到期筛选值不合法")
        if created_from is not None:
            where.append("created_at>=:cf")
            params["cf"] = int(created_from)
        if created_to is not None:
            where.append("created_at<=:ct")
            params["ct"] = int(created_to)
        inner = f"SELECT *, (audio+video) AS media_total FROM ({_BASE_SQL}) WHERE " + (" AND ".join(where) or "1=1")
        if has_data is not None:
            inner = f"SELECT * FROM ({inner}) WHERE media_total" + (">0" if has_data else "=0")

        col = _SORTS.get(sort, "created_at")
        direction = "ASC" if order.lower() == "asc" else "DESC"
        size = max(1, min(int(size), 200))
        page = max(1, int(page))
        with engine.begin() as c:
            total = c.execute(text(f"SELECT COUNT(*) FROM ({inner})"), params).scalar_one()
            rows = c.execute(text(f"SELECT * FROM ({inner}) ORDER BY {col} {direction}, id ASC LIMIT :lim OFFSET :off"),
                             {**params, "lim": size, "off": (page - 1) * size}).all()
            s = c.execute(text(
                "SELECT COALESCE(SUM(tier='free'),0), COALESCE(SUM(tier='full'),0), COALESCE(SUM(tier='admin'),0),"
                " COALESCE(SUM(tier_expires_at IS NOT NULL AND tier_expires_at>:now AND tier_expires_at<=:soon),0),"
                " COALESCE(SUM(status='disabled'),0), COALESCE(SUM(status='pending_deletion'),0)"
                " FROM users"), {"now": now, "soon": now + 30 * 86400}).one()

        items = []
        for r in rows:
            m = r._mapping
            deletion = None
            if m["deletion_execute_at"] is not None:
                left = int(math.ceil((m["deletion_execute_at"] - now) / 86400))
                deletion = {"requested_at": m["deletion_requested_at"], "execute_at": m["deletion_execute_at"],
                            "days_left": max(0, left)}
            items.append({
                "id": m["id"], "login_name": m["login_name"], "tier": m["tier"],
                "tier_expires_at": m["tier_expires_at"], "status": m["status"], "created_at": m["created_at"],
                "last_seen_at": m["last_seen_at"], "active_sessions": m["active_sessions"],
                "audio": m["audio"], "video": m["video"], "media_total": m["media_total"],
                "playlists": m["playlists"], "bytes": m["bytes"],
                "deletion": deletion, "admin": m["tier"] == TIER_ADMIN,
            })
        return {"total": total, "page": page, "size": size,
                "summary": {"free": s[0], "full": s[1], "admin": s[2], "expiring_30d": s[3],
                            "disabled": s[4], "pending_deletion": s[5]},
                "items": items}

    # ---------- 改等级 ----------
    @app.post("/v2/admin/accounts/{uid}/tier")
    def change_tier(uid: str, req: TierChangeReq, request: Request, ctx=Depends(require_admin)):
        now = svc.now()
        with engine.begin() as c:
            u = _user(c, uid)
            if u is None:
                raise ApiError(404, "not_found", "未找到该账号")
            if u.tier == TIER_ADMIN:
                raise ApiError(409, "cannot_change_admin", "管理员账号不能变更等级")
            if u.status != "active":
                raise ApiError(409, "account_not_active", "账号当前不是正常状态，请先恢复")
            exp = None if (req.tier == TIER_FREE or req.duration == "permanent") else add_months(now, 6)
            c.execute(text("UPDATE users SET tier=:t, tier_expires_at=:e WHERE id=:u"),
                      {"t": req.tier, "e": exp, "u": uid})
            revoked = _revoke_all(c, uid, now)
            svc.audit(c, "admin_tier_change", uid, ip_of(request), request.url.path,
                      f"{u.tier}->{req.tier}" + ("/permanent" if req.duration == "permanent" else ""))
        return {"ok": True, "tier": req.tier, "tier_expires_at": exp, "revoked_sessions": revoked}

    # ---------- 重置密码 ----------
    @app.post("/v2/admin/accounts/{uid}/password")
    def reset_password(uid: str, req: PasswordResetReq, request: Request, ctx=Depends(require_admin)):
        svc._hit(f"admin_pw:{ctx.user_id}", (20, 3600))
        with engine.begin() as c:
            u = _user(c, uid)
        if u is None:
            raise ApiError(404, "not_found", "未找到该账号")
        p = password_problem(req.password, u.login_name_norm)
        if p:
            raise ApiError(422, "weak_password", p)
        new_hash = svc.hasher.hash(req.password)  # 事务外做，避免长时间占写锁
        now = svc.now()
        with engine.begin() as c:
            c.execute(text("UPDATE users SET password_hash=:p WHERE id=:u"), {"p": new_hash, "u": uid})
            revoked = _revoke_all(c, uid, now)
            svc.audit(c, "admin_password_reset", uid, ip_of(request), request.url.path, "ok")
        return {"ok": True, "revoked_sessions": revoked}

    # ---------- 停用 / 恢复 ----------
    @app.post("/v2/admin/accounts/{uid}/status")
    def change_status(uid: str, req: StatusReq, request: Request, ctx=Depends(require_admin)):
        svc._hit(f"admin_status:{ctx.user_id}", (60, 3600))
        now = svc.now()
        with engine.begin() as c:
            u = _user(c, uid)
            if u is None:
                raise ApiError(404, "not_found", "未找到该账号")
            if req.status == "disabled":
                if u.tier == TIER_ADMIN:
                    raise ApiError(409, "cannot_disable_admin", "管理员账号不能停用")
                if u.status == "pending_deletion":
                    raise ApiError(409, "account_pending_deletion", "账号正在注销中，不能停用")
                c.execute(text("UPDATE users SET status='disabled' WHERE id=:u"), {"u": uid})
                revoked = _revoke_all(c, uid, now)
                svc.audit(c, "admin_disable", uid, ip_of(request), request.url.path, "ok")
            else:
                if u.status == "pending_deletion":
                    raise ApiError(409, "account_pending_deletion", "账号正在注销中，请用撤销注销")
                c.execute(text("UPDATE users SET status='active' WHERE id=:u AND status='disabled'"), {"u": uid})
                revoked = 0  # 不恢复旧会话，用户必须重新登录
                svc.audit(c, "admin_enable", uid, ip_of(request), request.url.path, "ok")
        return {"ok": True, "status": req.status, "revoked_sessions": revoked}

    # ---------- 撤销注销（冷静期内） ----------
    @app.post("/v2/admin/accounts/{uid}/restore-deletion")
    def restore_deletion(uid: str, request: Request, ctx=Depends(require_admin)):
        with engine.begin() as c:
            u = _user(c, uid)
            if u is None:
                raise ApiError(404, "already_deleted", "账号已删除，无法恢复")
            n = c.execute(text("DELETE FROM deletion_requests WHERE user_id=:u AND executed_at IS NULL"),
                          {"u": uid}).rowcount
            if not n:
                raise ApiError(409, "already_deleted", "没有待执行的注销请求")
            c.execute(text("UPDATE users SET status='active' WHERE id=:u AND status='pending_deletion'"), {"u": uid})
            svc.audit(c, "admin_restore_deletion", uid, ip_of(request), request.url.path, "ok")
        return {"ok": True, "status": "active"}

    # ---------- 邀请码 ----------
    @app.post("/v2/admin/invites", status_code=201)
    def create_invites(req: InviteCreateReq, request: Request, ctx=Depends(require_admin)):
        codes = svc.create_invites(req.count)
        with engine.begin() as c:
            svc.audit(c, "admin_invite_create", ctx.user_id, ip_of(request), request.url.path, str(len(codes)))
        return {"codes": codes, "count": len(codes), "expires_at": svc.now() + 86400}

    @app.get("/v2/admin/invites")
    def list_invites(ctx=Depends(require_admin), status: str = "", page: int = 1, size: int = 50):
        now = svc.now()
        where, params = [], {"now": now}
        if status == "unused":
            where.append("i.used_by IS NULL AND (i.expires_at IS NULL OR i.expires_at>:now)")
        elif status == "used":
            where.append("i.used_by IS NOT NULL")
        elif status == "expired":
            where.append("i.used_by IS NULL AND i.expires_at IS NOT NULL AND i.expires_at<=:now")
        elif status:
            raise ApiError(422, "invalid_status", "状态筛选值不合法")
        cond = (" WHERE " + " AND ".join(where)) if where else ""
        size = max(1, min(int(size), 200))
        page = max(1, int(page))
        with engine.begin() as c:
            total = c.execute(text(f"SELECT COUNT(*) FROM invites i{cond}"), params).scalar_one()
            rows = c.execute(text(
                "SELECT i.id,i.created_at,i.expires_at,i.used_at,u.login_name FROM invites i "
                f"LEFT JOIN users u ON u.id=i.used_by{cond} ORDER BY i.created_at DESC, i.id DESC LIMIT :lim OFFSET :off"),
                {**params, "lim": size, "off": (page - 1) * size}).all()
        items = []
        for r in rows:
            used, exp = r[3] is not None, r[2]
            state = "used" if used else ("expired" if (exp is not None and exp <= now) else "unused")
            items.append({"id": r[0], "created_at": r[1], "expires_at": exp, "used_at": r[3],
                          "used_by": r[4], "state": state})
        return {"total": total, "page": page, "size": size, "items": items}
