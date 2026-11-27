"""权限矩阵：匿名 / free / full / admin × 全部路由。

这组测试是"重型功能只在服务端判权"的证据。规矩很简单：
- 每条路由要么登记在 FREE_ROUTES，要么挂完整版级依赖，不存在第三种；
- 完整版路由：匿名 401、free 403、full 与 admin 至少能过鉴权；
- 管理员路由：匿名 401、free/full 403、admin 能过；
- 免费路由不能因为等级把 free 用户挡在外面（除非业务自己要求登录）。
"""
from __future__ import annotations

import re

import pytest
from fastapi.routing import APIRoute

from conftest import hdr, make_owner, register
from getmedia.tiers import FREE_ROUTES, _tiers_of, unprotected_routes, whitelisted_but_missing

_PARAM = re.compile(r"\{[^}]+\}")
FULL = {"full", "admin"}   # 完整版能力（含管理员）
ADMIN = {"admin"}          # 仅管理员


def _routes(app):
    out = []
    for r in app.routes:
        if not isinstance(r, APIRoute):
            continue
        for m in sorted(r.methods - {"HEAD", "OPTIONS"}):
            out.append((m, r.path, r))
    return out


def _call(env, method, path, token=None):
    path = _PARAM.sub("x", path)
    headers = hdr(token) if token else {}
    kwargs = {"headers": headers}
    if method in ("POST", "PUT", "PATCH"):
        kwargs["json"] = {}
    return env.client.request(method, path, **kwargs)


def test_no_unguarded_route(env):
    """启动自检的显式断言：任何新路由都必须落在白名单或 owner 依赖里。"""
    assert unprotected_routes(env.app) == []


def test_whitelist_has_no_missing_routes(env):
    assert whitelisted_but_missing(env.app) == []


def test_every_free_route_actually_exists(env):
    have = {(m, p) for m, p, _ in _routes(env.app)}
    missing = [f"{m} {p}" for m, p in FREE_ROUTES if (m, p) not in have]
    assert missing == []


def test_full_routes_are_not_whitelisted(env):
    owner = {(m, p) for m, p, r in _routes(env.app) if (_tiers_of(r) or set()) & FULL}
    assert owner, "至少应该有一条完整版路由"
    assert not (owner & FREE_ROUTES), f"完整版路由被错误放进了免费白名单：{owner & FREE_ROUTES}"


def test_admin_routes_exist_and_are_not_free(env):
    admin = {(m, p) for m, p, r in _routes(env.app) if (_tiers_of(r) or set()) == ADMIN}
    assert admin, "至少应该有一条管理员路由"
    assert not (admin & FREE_ROUTES)


def test_creator_batch_is_owner_only(open_reg):
    """博主批量从免费版移出：免费白名单里不能再出现 /v2/creators/*。"""
    assert not [x for x in FREE_ROUTES if "/v2/creators" in x[1]]
    free_token = register(open_reg).json()["session"]["token"]
    r = open_reg.client.get("/v2/creators/limits", headers=hdr(free_token))
    assert r.status_code == 403
    assert r.json()["code"] == "forbidden_tier"


@pytest.mark.parametrize("role", ["anonymous", "free", "full"])
def test_matrix_full_routes(open_reg, role):
    """三条身份走一遍全部完整版路由，只验证鉴权层，不关心业务结果。"""
    token = None
    if role == "free":
        token = register(open_reg).json()["session"]["token"]
    elif role == "full":
        token = make_owner(open_reg, name="boss")

    full_routes = [(m, p) for m, p, r in _routes(open_reg.app) if (_tiers_of(r) or set()) & FULL
                   and not (_tiers_of(r) or set()) == ADMIN]
    assert full_routes

    for method, path in full_routes:
        r = _call(open_reg, method, path, token)
        if role == "anonymous":
            assert r.status_code == 401, f"{method} {path} 匿名应 401，实际 {r.status_code}"
        elif role == "free":
            assert r.status_code == 403, f"{method} {path} free 应 403，实际 {r.status_code}"
            assert r.json()["code"] == "forbidden_tier"
        else:
            assert r.status_code not in (401, 403), f"{method} {path} full 被挡：{r.status_code}"


@pytest.mark.parametrize("role", ["anonymous", "free", "full"])
def test_matrix_admin_routes(open_reg, role):
    """管理员路由：匿名 401、free/full 403。admin 的行为由 test_admin_api 覆盖。"""
    token = None
    if role == "free":
        token = register(open_reg).json()["session"]["token"]
    elif role == "full":
        token = make_owner(open_reg, name="boss")

    admin_routes = [(m, p) for m, p, r in _routes(open_reg.app) if (_tiers_of(r) or set()) == ADMIN]
    assert admin_routes
    for method, path in admin_routes:
        r = _call(open_reg, method, path, token)
        if role == "anonymous":
            assert r.status_code == 401, f"{method} {path} 匿名应 401，实际 {r.status_code}"
        else:
            assert r.status_code == 403, f"{method} {path} {role} 应 403，实际 {r.status_code}"


def test_free_routes_usable_by_free_account(open_reg):
    token = register(open_reg).json()["session"]["token"]
    for path in ("/v2/config", "/v2/legal", "/account/me", "/account/devices"):
        r = open_reg.client.get(path, headers=hdr(token))
        assert r.status_code == 200, f"{path} 对 free 应可用，实际 {r.status_code}"


def test_public_config_has_no_owner_fields(open_reg):
    body = open_reg.client.get("/v2/config").json()
    assert body["cache_ttl_seconds"] > 0
    assert body["cache_quota_bytes"] > 0
    for leaked in ("archive_quota_bytes", "creator", "credential_vault"):
        assert leaked not in body


def test_owner_config_requires_owner(open_reg):
    assert open_reg.client.get("/v2/config/owner").status_code == 401
    free_token = register(open_reg).json()["session"]["token"]
    assert open_reg.client.get("/v2/config/owner", headers=hdr(free_token)).status_code == 403
    owner_token = make_owner(open_reg, name="boss")
    r = open_reg.client.get("/v2/config/owner", headers=hdr(owner_token))
    assert r.status_code == 200
    body = r.json()
    assert body["archive_quota_bytes"] == 5 * 1024**3
    assert body["cache_ttl_seconds"] == 86400
    assert body["credential_vault"] is True
