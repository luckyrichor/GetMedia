"""账号服务：注册、登录、会话/设备、恢复、同意。所有限流计数在独立短事务里落库。"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass

from sqlalchemy import text
from sqlalchemy.engine import Engine
from sqlalchemy.exc import IntegrityError

from . import ratelimit
from .config import Settings
from .errors import ApiError
from .legal import LegalStore
from .names import login_name_problem, normalize_login_name, password_problem
from .runtime import RuntimeStore
from .security import (PasswordHasher, invite_hash, new_id, new_recovery_code, new_token, recovery_hmac,
                       token_hash)

import hmac as _hmac

log = logging.getLogger("getmedia.service")

INVALID_CREDENTIALS = ApiError(401, "invalid_credentials", "登录名或密码不正确")
_TOUCH_AFTER = 60  # 秒；会话"最近使用"的写入节流


@dataclass(frozen=True)
class AuthCtx:
    user_id: str
    session_id: str
    channel: str
    tier: str
    login_name: str


def effective_tier(tier: str, expires_at: int | None, now: int) -> str:
    return "free" if expires_at is not None and expires_at <= now else tier


class AccountService:
    def __init__(self, engine: Engine, settings: Settings, hasher: PasswordHasher, legal: LegalStore,
                 runtime: RuntimeStore, clock=time.time):
        self.engine, self.s, self.hasher, self.legal, self.runtime, self.clock = engine, settings, hasher, legal, runtime, clock

    def now(self) -> int:
        return int(self.clock())

    # ---------- 基础 ----------
    def audit(self, conn, event: str, user_id: str | None, ip: str, path: str, result: str) -> None:
        conn.execute(text("INSERT INTO audit(ts,event,user_id,ip,path,result) VALUES(:t,:e,:u,:i,:p,:r)"),
                     {"t": self.now(), "e": event, "u": user_id, "i": ip[:64], "p": path[:128], "r": result[:64]})

    def _audit_own(self, event, user_id, ip, path, result):
        with self.engine.begin() as c:
            self.audit(c, event, user_id, ip, path, result)

    def _hit(self, key: str, limit_cfg: tuple) -> None:
        limit, window = limit_cfg
        with self.engine.begin() as c:
            ok, retry = ratelimit.hit(c, key, limit, window, self.now())
        if not ok:
            raise ApiError(429, "rate_limited", "操作太频繁，请稍后再试", headers={"Retry-After": str(retry)})

    def _retry_after(self, window: int) -> int:
        n = self.now()
        return max(1, n - n % window + window - n)

    # ---------- 协议 ----------
    def _consent_state(self, c, user_id: str, cur: dict) -> tuple[bool, dict]:
        # cur 必须在开事务之前取（legal.current() 可能写库登记新版本，嵌套会锁死）
        row = c.execute(text("SELECT ua_version,ua_sha256,pp_version,pp_sha256 FROM consents WHERE user_id=:u ORDER BY id DESC LIMIT 1"),
                        {"u": user_id}).first()
        ua, pp = cur["user_agreement"], cur["privacy_policy"]
        required = row is None or (row[0], row[1], row[2], row[3]) != (ua.version, ua.sha256, pp.version, pp.sha256)
        return required, {"user_agreement": ua.version, "privacy_policy": pp.version}

    def _check_consent(self, consent) -> None:
        cur = self.legal.current()
        for key, got in (("user_agreement", consent.user_agreement), ("privacy_policy", consent.privacy_policy)):
            want = cur[key]
            if got.version != want.version or got.sha256 != want.sha256:
                raise ApiError(409, "legal_outdated", "协议已更新，请阅读最新版本后重新同意")

    def _insert_consent(self, c, user_id: str, consent, now: int) -> None:
        c.execute(text("INSERT INTO consents(user_id,ua_version,ua_sha256,pp_version,pp_sha256,consented_at) VALUES(:u,:a,:b,:c,:d,:t)"),
                  {"u": user_id, "a": consent.user_agreement.version, "b": consent.user_agreement.sha256,
                   "c": consent.privacy_policy.version, "d": consent.privacy_policy.sha256, "t": now})

    # ---------- 会话 ----------
    def _devices(self, c, user_id: str, now: int, channel: str | None = None, current: str | None = None) -> list[dict]:
        q = ("SELECT id,channel,device_name,app_version,created_at,last_used_at FROM sessions "
             "WHERE user_id=:u AND revoked_at IS NULL AND expires_at>:n")
        args = {"u": user_id, "n": now}
        if channel:
            q += " AND channel=:ch"; args["ch"] = channel
        rows = c.execute(text(q + " ORDER BY last_used_at DESC"), args).all()
        return [{"id": r[0], "channel": r[1], "device": r[2], "app_version": r[3], "created_at": r[4],
                 "last_used_at": r[5], "current": r[0] == current} for r in rows]

    def _create_session(self, c, user_id: str, client, ip: str, now: int, evict_id: str | None = None) -> dict:
        limit = self.s.max_web_devices if client.channel == "web" else self.s.max_app_devices
        active = self._devices(c, user_id, now, client.channel)
        if len(active) >= limit:
            ids = {d["id"] for d in active}
            if evict_id and evict_id in ids:
                # 踢掉最久未用的以外，用户选定的那个；超出多个时依次保留最近使用
                c.execute(text("UPDATE sessions SET revoked_at=:n WHERE id=:i"), {"n": now, "i": evict_id})
            else:
                raise ApiError(409, "device_limit", "已登录的设备数量已达上限，请选择一台退出后继续",
                               extra={"devices": active})
        days = self.s.web_session_days if client.channel == "web" else self.s.app_session_days
        token, sid = new_token(), new_id("dev")
        exp = now + days * 86400
        c.execute(text("INSERT INTO sessions(id,user_id,token_hash,channel,device_name,app_version,created_at,last_used_at,expires_at,last_ip) "
                       "VALUES(:i,:u,:h,:ch,:d,:v,:n,:n,:e,:ip)"),
                  {"i": sid, "u": user_id, "h": token_hash(token), "ch": client.channel, "d": client.device, "v": client.app_version,
                   "n": now, "e": exp, "ip": ip[:64]})
        return {"token": token, "device_id": sid, "expires_at": exp}

    def authenticate(self, token: str, ip: str) -> AuthCtx | None:
        now = self.now()
        with self.engine.begin() as c:
            r = c.execute(text(
                "SELECT s.id,s.user_id,s.channel,s.last_used_at,u.tier,u.tier_expires_at,u.login_name "
                "FROM sessions s JOIN users u ON u.id=s.user_id "
                "WHERE s.token_hash=:h AND s.revoked_at IS NULL AND s.expires_at>:n AND u.status='active'"),
                {"h": token_hash(token), "n": now}).first()
            if r is None:
                return None
            if now - r[3] > _TOUCH_AFTER:
                days = self.s.web_session_days if r[2] == "web" else self.s.app_session_days
                c.execute(text("UPDATE sessions SET last_used_at=:n, expires_at=:e, last_ip=:ip WHERE id=:i"),
                          {"n": now, "e": now + days * 86400, "ip": ip[:64], "i": r[0]})
            return AuthCtx(r[1], r[0], r[2], effective_tier(r[4], r[5], now), r[6])

    def logout(self, ctx: AuthCtx, ip: str, path: str) -> None:
        with self.engine.begin() as c:
            c.execute(text("UPDATE sessions SET revoked_at=:n WHERE id=:i"), {"n": self.now(), "i": ctx.session_id})
            self.audit(c, "logout", ctx.user_id, ip, path, "ok")

    # ---------- 注册 ----------
    def register(self, req, ip: str, path: str = "/account/register") -> dict:
        self._hit(f"register:ip:{ip}", self.s.register_ip_limit)
        now = self.now()
        rt = self.runtime.get()
        need_invite = (not rt.registration_open) or rt.invite_required
        closed_err = ApiError(403, "registration_closed", "注册暂未开放")

        ihash = invite_hash(self.s.secret_key, req.invite_code) if req.invite_code else None
        invite_id = None
        if need_invite:
            with self.engine.begin() as c:
                r = c.execute(text("SELECT id FROM invites WHERE code_hash=:h AND used_by IS NULL AND (expires_at IS NULL OR expires_at>:n)"),
                              {"h": ihash or "", "n": now}).first()
            if r is None:
                raise closed_err if not rt.registration_open else ApiError(403, "invite_invalid", "邀请码无效或已使用")
            invite_id = r[0]

        if not req.age_confirmed:
            raise ApiError(422, "age_not_confirmed", "需确认已年满 18 周岁")
        norm = normalize_login_name(req.login_name)
        p = login_name_problem(norm)
        if p:
            raise ApiError(422, "invalid_login_name", p)
        p = password_problem(req.password, norm)
        if p:
            raise ApiError(422, "weak_password", p)
        self._check_consent(req.consent)
        with self.engine.begin() as c:
            if c.execute(text("SELECT 1 FROM users WHERE login_name_norm=:n"), {"n": norm}).first():
                raise ApiError(409, "name_taken", "该登录名已被使用")

        pw_hash = self.hasher.hash(req.password)  # 在事务之外做，避免长时间占住写锁
        uid, code = new_id("acct"), new_recovery_code()
        try:
            with self.engine.begin() as c:
                c.execute(text("INSERT INTO users(id,login_name,login_name_norm,password_hash,registered_channel,registered_version,created_at) "
                               "VALUES(:i,:n,:nn,:p,:ch,:v,:t)"),
                          {"i": uid, "n": norm, "nn": norm, "p": pw_hash, "ch": req.client.channel, "v": req.client.app_version, "t": now})
                c.execute(text("INSERT INTO recovery_codes(user_id,code_hmac,rotated_at) VALUES(:u,:h,:t)"),
                          {"u": uid, "h": recovery_hmac(self.s.secret_key, code), "t": now})
                self._insert_consent(c, uid, req.consent, now)
                if invite_id is not None:
                    n = c.execute(text("UPDATE invites SET used_by=:u, used_at=:t WHERE id=:i AND used_by IS NULL"),
                                  {"u": uid, "t": now, "i": invite_id}).rowcount
                    if n != 1:
                        raise ApiError(403, "invite_invalid", "邀请码无效或已使用")
                sess = self._create_session(c, uid, req.client, ip, now)
                self.audit(c, "register", uid, ip, path, "ok")
        except IntegrityError:
            raise ApiError(409, "name_taken", "该登录名已被使用") from None
        return {"session": sess, "account": {"id": uid, "login_name": norm, "tier": "free"}, "recovery_code": code}

    # ---------- 登录 ----------
    def login(self, req, ip: str, path: str = "/account/login") -> dict:
        norm = normalize_login_name(req.login_name)[:64]
        self._hit(f"login:ip:{ip}", self.s.login_ip_limit)
        fail_key = f"login:fail:{norm}"
        flimit, fwin = self.s.login_name_fail_limit
        with self.engine.begin() as c:
            fails = ratelimit.count(c, fail_key, fwin, self.now())
        if fails >= flimit:
            raise ApiError(429, "rate_limited", "操作太频繁，请稍后再试", headers={"Retry-After": str(self._retry_after(fwin))})

        with self.engine.begin() as c:
            u = c.execute(text("SELECT id,password_hash,status,tier,tier_expires_at,recovery_confirmed_at FROM users WHERE login_name_norm=:n"),
                          {"n": norm}).first()
        usable = u is not None and u[2] == "active"
        valid = self.hasher.verify(u[1] if usable else self.hasher.fake_hash, req.password)  # 恒定做一次哈希
        if not (usable and valid):
            with self.engine.begin() as c:
                ratelimit.hit(c, fail_key, flimit, fwin, self.now())
                self.audit(c, "login_fail", u[0] if u else None, ip, path, "bad_credentials")
            raise INVALID_CREDENTIALS

        now = self.now()
        cur_legal = self.legal.current()
        with self.engine.begin() as c:
            sess = self._create_session(c, u[0], req.client, ip, now, req.evict_device_id)
            required, versions = self._consent_state(c, u[0], cur_legal)
            self.audit(c, "login_ok", u[0], ip, path, req.client.channel)
        return {"session": sess,
                "account": {"id": u[0], "login_name": norm, "tier": effective_tier(u[3], u[4], now)},
                "consent_required": required, "legal_versions": versions,
                "recovery_confirmed": u[5] is not None}

    # ---------- 账号信息 ----------
    def me(self, ctx: AuthCtx) -> dict:
        cur_legal = self.legal.current()
        with self.engine.begin() as c:
            u = c.execute(text("SELECT tier_expires_at,recovery_confirmed_at,status FROM users WHERE id=:i"), {"i": ctx.user_id}).first()
            required, versions = self._consent_state(c, ctx.user_id, cur_legal)
        return {"id": ctx.user_id, "login_name": ctx.login_name, "tier": ctx.tier, "tier_expires_at": u[0],
                "consent_required": required, "legal_versions": versions,
                "recovery_confirmed": u[1] is not None, "status": u[2]}

    def consent(self, ctx: AuthCtx, consent, ip: str, path: str) -> None:
        self._check_consent(consent)
        with self.engine.begin() as c:
            self._insert_consent(c, ctx.user_id, consent, self.now())
            self.audit(c, "consent", ctx.user_id, ip, path, "ok")

    def recovery_ack(self, ctx: AuthCtx, ip: str, path: str) -> None:
        with self.engine.begin() as c:
            c.execute(text("UPDATE users SET recovery_confirmed_at=:n WHERE id=:i"), {"n": self.now(), "i": ctx.user_id})
            self.audit(c, "recovery_ack", ctx.user_id, ip, path, "ok")

    # ---------- 设备 ----------
    def list_devices(self, ctx: AuthCtx) -> list[dict]:
        with self.engine.begin() as c:
            return self._devices(c, ctx.user_id, self.now(), None, ctx.session_id)

    def revoke_device(self, ctx: AuthCtx, device_id: str, ip: str, path: str) -> None:
        with self.engine.begin() as c:
            n = c.execute(text("UPDATE sessions SET revoked_at=:n WHERE id=:i AND user_id=:u AND revoked_at IS NULL"),
                          {"n": self.now(), "i": device_id, "u": ctx.user_id}).rowcount
            if n != 1:
                raise ApiError(404, "not_found", "未找到")  # 他人的设备一律 404
            self.audit(c, "device_revoke", ctx.user_id, ip, path, "ok")

    def revoke_others(self, ctx: AuthCtx, ip: str, path: str) -> int:
        with self.engine.begin() as c:
            n = c.execute(text("UPDATE sessions SET revoked_at=:n WHERE user_id=:u AND id<>:i AND revoked_at IS NULL"),
                          {"n": self.now(), "u": ctx.user_id, "i": ctx.session_id}).rowcount
            self.audit(c, "revoke_others", ctx.user_id, ip, path, str(n))
        return n

    # ---------- 改密 / 恢复 ----------
    def change_password(self, ctx: AuthCtx, old: str, new: str, ip: str, path: str) -> None:
        self._hit(f"pwchange:{ctx.user_id}", (10, 3600))
        with self.engine.begin() as c:
            stored = c.execute(text("SELECT password_hash,login_name_norm FROM users WHERE id=:i"), {"i": ctx.user_id}).first()
        if not self.hasher.verify(stored[0], old):
            self._audit_own("password_change", ctx.user_id, ip, path, "bad_old")
            raise ApiError(401, "invalid_credentials", "当前密码不正确")
        p = password_problem(new, stored[1])
        if p:
            raise ApiError(422, "weak_password", p)
        new_hash = self.hasher.hash(new)
        with self.engine.begin() as c:
            c.execute(text("UPDATE users SET password_hash=:p WHERE id=:i"), {"p": new_hash, "i": ctx.user_id})
            c.execute(text("UPDATE sessions SET revoked_at=:n WHERE user_id=:u AND id<>:i AND revoked_at IS NULL"),
                      {"n": self.now(), "u": ctx.user_id, "i": ctx.session_id})
            self.audit(c, "password_change", ctx.user_id, ip, path, "ok")

    def recover(self, login_name: str, recovery_code: str, new_password: str, ip: str, path: str = "/account/recover") -> dict:
        norm = normalize_login_name(login_name)[:64]
        self._hit(f"recover:acct:{norm}", self.s.recover_account_limit)
        self._hit(f"recover:ip:{ip}", (20, 3600))
        with self.engine.begin() as c:
            u = c.execute(text("SELECT u.id,u.status,r.code_hmac FROM users u LEFT JOIN recovery_codes r ON r.user_id=u.id WHERE u.login_name_norm=:n"),
                          {"n": norm}).first()
        want = u[2] if u and u[1] == "active" and u[2] else "0" * 64
        good = _hmac.compare_digest(want, recovery_hmac(self.s.secret_key, recovery_code))
        if not (u and u[1] == "active" and good):
            self.hasher.verify(self.hasher.fake_hash, "x")  # 与成功路径耗时接近
            self._audit_own("recover", u[0] if u else None, ip, path, "bad_code")
            raise ApiError(401, "invalid_recovery", "登录名或恢复码不正确")
        p = password_problem(new_password, norm)
        if p:
            raise ApiError(422, "weak_password", p)
        new_hash = self.hasher.hash(new_password)
        code, now = new_recovery_code(), self.now()
        with self.engine.begin() as c:
            c.execute(text("UPDATE users SET password_hash=:p, recovery_confirmed_at=NULL WHERE id=:i"), {"p": new_hash, "i": u[0]})
            c.execute(text("UPDATE recovery_codes SET code_hmac=:h, rotated_at=:n WHERE user_id=:i"),
                      {"h": recovery_hmac(self.s.secret_key, code), "n": now, "i": u[0]})
            c.execute(text("UPDATE sessions SET revoked_at=:n WHERE user_id=:i AND revoked_at IS NULL"), {"n": now, "i": u[0]})
            self.audit(c, "recover", u[0], ip, path, "ok")
        return {"recovery_code": code}

    # ---------- 邀请码（管理 CLI 在 WP4 调用） ----------
    def create_invite(self, purpose: str = "", expires_at: int | None = None) -> str:
        from .security import new_invite_code
        code = new_invite_code()
        with self.engine.begin() as c:
            c.execute(text("INSERT INTO invites(code_hash,purpose,created_at,expires_at) VALUES(:h,:p,:n,:e)"),
                      {"h": invite_hash(self.s.secret_key, code), "p": purpose[:64], "n": self.now(), "e": expires_at})
        return code
