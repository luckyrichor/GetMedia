"""等级判权骨架：默认拒绝。

规则：新增路由默认归 owner。免费可达路由必须逐条登记在 FREE_ROUTES；
其余路由必须挂 require_tier(...) 且不含 "free"。`unprotected_routes` 在启动时与测试里各检查一遍。
"""
from __future__ import annotations

from fastapi import FastAPI
from fastapi.routing import APIRoute

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
    """返回"既不在免费白名单、又没挂 owner 级依赖"的路由（应为空）。"""
    bad = []
    for r in app.routes:
        if not isinstance(r, APIRoute):
            continue
        for m in sorted(r.methods - {"HEAD", "OPTIONS"}):
            if (m, r.path) in free:
                continue
            tiers = _tiers_of(r)
            if not tiers or "free" in tiers:
                bad.append(f"{m} {r.path}")
    return bad


def whitelisted_but_missing(app: FastAPI, free: frozenset = FREE_ROUTES) -> list[str]:
    """白名单里登记了、但应用里不存在的路由（WP3/WP4 完成前允许存在，仅供报告）。"""
    have = {(m, r.path) for r in app.routes if isinstance(r, APIRoute) for m in r.methods}
    return sorted(f"{m} {p}" for m, p in free if (m, p) not in have)
