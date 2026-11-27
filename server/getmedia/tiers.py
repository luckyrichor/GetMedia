"""等级判权骨架：默认拒绝。

规则：新增路由默认归完整版。免费可达路由必须逐条登记在 FREE_ROUTES；
其余路由必须挂 require_tier(...) 且不含 "free"。`unprotected_routes` 在启动时与测试里各检查一遍。

等级取值只有三个：
- ``free``  免费版
- ``full``  完整版（可带到期时间；旧库里的 ``owner`` 由迁移 v20 改写）
- ``admin`` 管理员（全站唯一、永久）
"""
from __future__ import annotations

from fastapi import FastAPI
from fastapi.routing import APIRoute

TIER_FREE = "free"
TIER_FULL = "full"
TIER_ADMIN = "admin"

# 完整版能力（含管理员）——`require_tier(*FULL_TIERS)`
FULL_TIERS: tuple[str, ...] = (TIER_FULL, TIER_ADMIN)
VALID_TIERS: frozenset[str] = frozenset({TIER_FREE, TIER_FULL, TIER_ADMIN})

# (方法, 服务端内部路径)。免费账号（含匿名）能访问的全部接口。
FREE_ROUTES: frozenset[tuple[str, str]] = frozenset({
    ("GET", "/health"),
    ("GET", "/v2/config"),
    ("GET", "/v2/legal"),
    ("POST", "/v2/heartbeat"),
    ("POST", "/v2/feedback"),
    ("POST", "/account/register"),
    ("POST", "/account/login"),
    ("POST", "/account/logout"),
    ("GET", "/account/me"),
    ("DELETE", "/account/me"),
    ("POST", "/account/consent"),
    ("POST", "/account/recovery/ack"),
    ("GET", "/account/devices"),
    ("DELETE", "/account/devices/{device_id}"),
    ("POST", "/account/devices/revoke-others"),
    ("POST", "/account/password"),
    ("POST", "/account/recover"),
    # 邀请码兑换：免费账号也要能升级，登录态由 auth 校验
    ("POST", "/account/redeem-invite"),
    # 曲库清单备份：免费账号也要用（重装后捞回清单），登录态由 auth 校验
    ("GET", "/account/metadata-backups"),
    ("POST", "/account/metadata-backups"),
    ("GET", "/account/metadata-backups/{backup_id}"),
    ("DELETE", "/account/metadata-backups/{backup_id}"),
    ("DELETE", "/account/metadata-backups"),
    # 抖音开放平台 OAuth 回调：浏览器跳转过来没有会话；未配置时返回 501
    ("GET", "/account/platform-auth/douyin/callback"),
})


def _tiers_of(route: APIRoute) -> set[str] | None:
    found: set[str] | None = None
    deps = list(route.dependant.dependencies)
    stack = list(deps)
    while stack:
        d = stack.pop()
        t = getattr(d.call, "_required_tiers", None)
        if t is not None:
            found = (found or set()) | set(t)
        stack.extend(d.dependencies)
    return found


def unprotected_routes(app: FastAPI, free: frozenset = FREE_ROUTES) -> list[str]:
    """返回"既不在免费白名单、又没挂完整版级依赖"的路由（应为空）。"""
    bad = []
    for r in app.routes:
        if not isinstance(r, APIRoute):
            continue
        for m in sorted(r.methods - {"HEAD", "OPTIONS"}):
            if (m, r.path) in free:
                continue
            tiers = _tiers_of(r)
            if not tiers:
                bad.append(f"{m} {r.path}")
            elif "free" in tiers:
                bad.append(f"{m} {r.path}（等级依赖里不应出现 free）")
            else:
                unknown = sorted(set(tiers) - VALID_TIERS)
                if unknown:
                    bad.append(f"{m} {r.path}（未知等级：{'|'.join(unknown)}）")
    return bad


def whitelisted_but_missing(app: FastAPI, free: frozenset = FREE_ROUTES) -> list[str]:
    """白名单里登记了、但应用里不存在的路由（WP3/WP4 完成前允许存在，仅供报告）。"""
    have = {(m, r.path) for r in app.routes if isinstance(r, APIRoute) for m in r.methods}
    return sorted(f"{m} {p}" for m, p in free if (m, p) not in have)
