"""网页接口层 ↔ 服务端路由的一致性检查（P5a）。

目的：`web/shared/api.ts` 里写的每条 方法+路径 必须在 FastAPI 应用里真实存在，
避免出现"前端调一个不存在的接口，只有上线后才发现 404"。这是纯静态检查，不需要起服务。

另外守住两条不变式：
1. 网页不保存任何令牌（令牌只在 HttpOnly Cookie 里），不写 localStorage/sessionStorage；
2. 接口基址必须是同源相对路径，不允许出现绝对 URL（否则会绕过 nginx 的同源与 CSP）。
"""
from __future__ import annotations

import re
import shutil
from pathlib import Path

import pytest

from getmedia.api import create_app
from getmedia.config import testing_settings as _testing_settings

ROOT = Path(__file__).resolve().parents[2]
API_TS = ROOT / "web" / "shared" / "api.ts"

# request<X>('GET', '/path') / request<X>('DELETE', `/account/devices/${id}`)
_CALL = re.compile(r"request<[^>]*>\(\s*['\"]([A-Z]+)['\"],\s*['\"`]([^'\"`]+)['\"`]")


def _norm(path: str) -> str:
    """把 `${...}`（前端模板）与 `{param}`（FastAPI）都归一成 `{}`。"""
    path = re.sub(r"\$\{[^}]*\}", "{}", path)
    path = re.sub(r"\{[^}]*\}", "{}", path)
    return path.rstrip("/") or "/"


def _web_routes() -> set[tuple[str, str]]:
    assert API_TS.is_file(), "web/shared/api.ts 不存在"
    return {(m, _norm(p)) for m, p in _CALL.findall(API_TS.read_text(encoding="utf-8"))}


@pytest.fixture(scope="module")
def server_routes(tmp_path_factory) -> set[tuple[str, str]]:
    tmp = tmp_path_factory.mktemp("webcontract")
    legal = tmp / "legal"
    legal.mkdir()
    for f in ("user-agreement.md", "privacy-policy.md"):
        shutil.copy(ROOT / "docs" / "legal" / f, legal / f)
    app = create_app(_testing_settings(tmp, legal), start_workers=False)
    out = set()
    for r in app.routes:
        path = getattr(r, "path", None)
        if not path:
            continue
        for m in getattr(r, "methods", ()) or ():
            if m not in ("HEAD", "OPTIONS"):
                out.add((m, _norm(path)))
    return out


def test_api_ts_is_parseable():
    routes = _web_routes()
    # 防御"正则失效导致空集通过"：网页至少用到这些接口
    assert len(routes) >= 14, routes
    assert ("GET", "/v2/config") in routes
    assert ("POST", "/account/login") in routes
    assert ("POST", "/v2/feedback") in routes


def test_every_web_call_exists_on_server(server_routes):
    missing = sorted(_web_routes() - server_routes)
    assert not missing, f"网页调用了服务端不存在的接口：{missing}"


def test_web_client_does_not_persist_tokens():
    text = API_TS.read_text(encoding="utf-8")
    body = re.sub(r"/\*.*?\*/", "", text, flags=re.S)          # 注释里会提到"不写 localStorage"
    body = re.sub(r"//.*", "", body)
    for bad in ("localStorage", "sessionStorage", "Authorization", "Bearer "):
        assert bad not in body, f"接口层不应出现 {bad}"


def test_web_api_base_is_same_origin():
    text = API_TS.read_text(encoding="utf-8")
    assert "credentials: 'same-origin'" in text
    # 基址由 Vite 的 BASE_URL 推导，源码里不出现绝对地址
    assert "http://" not in text and "https://" not in text


# ---------------------------------------------------------------- P5b：owner 产物隔离
PUBLIC_DIST = ROOT / "web" / "public-app" / "dist"
OWNER_DIST = ROOT / "web" / "owner-app" / "dist"


def test_owner_app_is_its_own_build_with_its_own_base():
    public_vite = (ROOT / "web" / "public-app" / "vite.config.ts").read_text(encoding="utf-8")
    owner_vite = (ROOT / "web" / "owner-app" / "vite.config.ts").read_text(encoding="utf-8")
    assert "base: '/getmedia/'" in public_vite
    assert "base: '/getmedia/owner/'" in owner_vite
    # owner 产物是独立目录，不能写进 public 的 outDir
    assert "outDir: 'dist'" in owner_vite
    assert "../public-app" not in owner_vite


@pytest.mark.skipif(not PUBLIC_DIST.is_dir(), reason="先运行 npm run build（web/）")
def test_public_bundle_contains_no_owner_code():
    js = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in PUBLIC_DIST.rglob("*.js"))
    assert js, "public 产物里没有 JS"
    # 公开页只允许"链接到"管理台（一个 <a href>），不允许包含管理台的**任何代码**：
    # 探针路径、接口通道、构建标记都不能出现。
    for marker in ("config/owner", "/owner/api", "owner-app", "auth_request"):
        assert marker not in js, f"公开页产物里出现了 owner 代码标记：{marker}"
    # 公开页确实只是"链过去"（整页跳转），不是把它当 SPA 页面渲染：
    # 只出现站内相对路径 /owner/，由 href() 拼上部署前缀。
    assert "/owner/" in js


@pytest.mark.skipif(not OWNER_DIST.is_dir(), reason="先运行 npm run build（web/）")
def test_owner_bundle_uses_owner_channel():
    js = "\n".join(p.read_text(encoding="utf-8", errors="ignore") for p in OWNER_DIST.rglob("*.js"))
    html = (OWNER_DIST / "index.html").read_text(encoding="utf-8")
    assert "/owner/api" in js
    assert "config/owner" in js
    assert "noindex" in html
    assert "http://" not in html and "https://" not in html
