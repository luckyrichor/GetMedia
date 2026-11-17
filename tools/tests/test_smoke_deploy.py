"""`tools/smoke_deploy.py` 的行为测试：用本地假服务端模拟 nginx + 应用。

只验证判断逻辑（404 是否带特征、owner 200 是否成立、公开 config 漏字段能不能抓到），
不连外网、不读 /proc、不碰 nginx —— 主机类检查在 Plan(host_check=False) 下不执行。
"""
from __future__ import annotations

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import smoke_deploy as S  # noqa: E402

NGINX_404 = (b"<html><head><title>404 Not Found</title></head><body>"
             b"<center><h1>404 Not Found</h1></center><hr><center>nginx</center></body></html>")
OWNER_HTML = (b"<!doctype html><html><head><meta name=\"robots\" content=\"noindex,nofollow\">"
              b"<script src=\"/getmedia/owner/assets/index.js\"></script></head>"
              b"<body><div id=\"app\"></div></body></html>")


class Handler(BaseHTTPRequestHandler):
    leak_header = False   # 404 上多带一个 X-GM-Owner（模拟边缘写错）
    leak_config = False   # 公开 config 里塞了 owner 字段（模拟接口漏内容）

    def log_message(self, *args):  # 静音
        pass

    def _send(self, code: int, body: bytes = b"", ctype: str = "text/html", extra=()):
        self.send_response(code)
        if ctype:
            self.send_header("Content-Type", ctype)
        for k, v in extra:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):  # noqa: N802 - 标准签名
        path = self.path.split("?")[0]
        cookie = self.headers.get("Cookie", "")
        owner = "s=owner" in cookie
        free = "s=free" in cookie

        if path == "/getmedia/api/health":
            return self._send(200, b'{"ok": true}', "application/json")
        if path == "/getmedia/":
            return self._send(200, b"<!doctype html><div id=app></div>")
        if path == "/getmedia":
            return self._send(308, b"", None, extra=[("Location", "/getmedia/")])
        if path == "/getmedia/api/v2/config":
            body = {"cache_ttl_seconds": 86400, "cache_quota_bytes": 5368709120}
            if self.leak_config:
                body["archive_quota_bytes"] = 3221225472
                body["credential_vault"] = True
            return self._send(200, json.dumps(body).encode(), "application/json")
        if path == "/getmedia/api/v2/tracks":
            return self._send(404, NGINX_404)
        if path == "/getmedia/api/account/me":
            if owner:
                return self._send(200, b'{"tier": "owner"}', "application/json")
            if free:
                return self._send(200, b'{"tier": "free"}', "application/json")
            return self._send(401, b'{"detail": "unauthenticated"}', "application/json")

        if path == "/getmedia/owner/api/v2/config/owner":
            if owner:
                return self._send(200, b'{"archive_quota_bytes": 3221225472, "credential_vault": true}',
                                  "application/json", extra=[("X-GM-Owner", "200")])
            extra = [("X-GM-Owner", "401")] if self.leak_header else []
            return self._send(404, NGINX_404, extra=extra)
        if path == "/getmedia/owner/api/v2/tracks":
            return self._send(200, b'{"items": [], "count": 0}', "application/json") if owner \
                else self._send(404, NGINX_404)
        if path == "/getmedia/owner/api/v2/recreations/capabilities":
            return self._send(200, b'{"available": false, "reason": "x", "modes": [], "max_upload_bytes": 1}',
                              "application/json") if owner else self._send(404, NGINX_404)
        if path == "/getmedia/owner/":
            return self._send(200, OWNER_HTML) if owner else self._send(404, NGINX_404)

        # 公开页有 SPA 回退（try_files … /getmedia/index.html）：/getmedia/<随机> 是 200，不是 404。
        # 冒烟脚本必须用 /getmedia/api/<随机> 当"边缘 404"的参照，这一条由 handler 行为守住。
        if path.startswith("/getmedia/api/"):
            return self._send(404, NGINX_404)
        if path.startswith("/getmedia/"):
            return self._send(200, b"<!doctype html><div id=app></div>")

        return self._send(404, NGINX_404)


@pytest.fixture
def site():
    made: list[ThreadingHTTPServer] = []

    def start(**opts) -> str:
        handler = type("H", (Handler,), opts)
        srv = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        made.append(srv)
        return f"http://127.0.0.1:{srv.server_address[1]}"

    yield start
    for srv in made:
        srv.shutdown()
        srv.server_close()


def run(base: str, *, free: str | None = None, owner: str | None = None) -> list[S.Check]:
    return S.run(S.Plan(base=base, timeout=5, free_cookie=free, owner_cookie=owner,
                        unit="getmedia-api", host_check=False))


def status(checks: list[S.Check], needle: str) -> str:
    for c in checks:
        if needle in c.name:
            return c.status
    raise AssertionError(f"没有这一项：{needle}；实到 {[c.name for c in checks]}")


def failures(checks: list[S.Check]) -> list[str]:
    return [f"{c.name}: {c.detail}" for c in checks if c.status == S.FAIL]


def test_anonymous_happy_path(site):
    base = site()
    checks = run(base)
    assert failures(checks) == []
    assert status(checks, "健康检查") == S.PASS
    assert status(checks, "公开 config") == S.PASS
    # 匿名：owner API 与页面都必须 404，且与白名单外路径逐字节一致
    assert status(checks, "匿名：owner API 返回 404") == S.PASS
    assert status(checks, "匿名：404 与白名单外路径一致") == S.PASS
    assert status(checks, "匿名：管理台页面 404") == S.PASS
    # 没给 Cookie 的两类只能跳过
    assert status(checks, "free：owner 通道 404") == S.SKIP
    assert status(checks, "owner：owner 通道 200") == S.SKIP


def test_owner_cookie_gets_200(site):
    base = site()
    checks = run(base, owner="s=owner")
    assert failures(checks) == []
    assert status(checks, "owner：会话有效") == S.PASS
    assert status(checks, "owner：owner API 200") == S.PASS
    assert status(checks, "owner：管理台页面 200") == S.PASS
    assert status(checks, "owner：owner 通道到应用") == S.PASS
    assert status(checks, "owner：AI 能力接口") == S.PASS
    # 带 owner Cookie 时匿名检查仍然成立（同一个站点两种身份各测一遍）
    assert status(checks, "匿名：owner API 返回 404") == S.PASS


def test_free_cookie_is_still_404(site):
    base = site()
    checks = run(base, free="s=free")
    assert failures(checks) == []
    assert status(checks, "free：会话有效") == S.PASS
    assert status(checks, "free：owner API 返回 404") == S.PASS
    assert status(checks, "free：管理台页面 404") == S.PASS


def test_wrong_tier_cookie_is_reported(site):
    base = site()
    # 把 owner 的 Cookie 传给 --free-cookie：tier 对不上必须红
    checks = run(base, free="s=owner")
    assert status(checks, "free：tier 匹配") == S.FAIL


def test_leaky_404_header_is_caught(site):
    base = site(leak_header=True)
    checks = run(base)
    assert status(checks, "匿名：owner API 返回 404") == S.FAIL


def test_public_config_leak_is_caught(site):
    base = site(leak_config=True)
    checks = run(base)
    assert status(checks, "公开 config") == S.FAIL


def test_owner_page_that_redirects_is_caught(site):
    """未登录时 302 到登录页 = 泄露"这里需要登录"，必须判失败。"""
    class Redirect(Handler):
        def do_GET(self):  # noqa: N802
            if self.path.split("?")[0] == "/getmedia/owner/":
                return self._send(302, b"", None, extra=[("Location", "/getmedia/login?next=/getmedia/owner/")])
            return Handler.do_GET(self)

    srv = ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        checks = run(f"http://127.0.0.1:{srv.server_address[1]}")
        assert status(checks, "匿名：管理台页面 404") == S.FAIL
    finally:
        srv.shutdown()
        srv.server_close()


def test_cookie_file_formats(tmp_path):
    netscape = tmp_path / "ns.cookie"
    netscape.write_text(
        "# Netscape HTTP Cookie File\n"
        "#HttpOnly_.example.com\tTRUE\t/\tTRUE\t0\t__Secure-gm_session\tabc123\n",
        encoding="utf-8")
    assert S.read_cookie_file(str(netscape)) == "__Secure-gm_session=abc123"

    plain = tmp_path / "plain.cookie"
    plain.write_text("a=1; b=2\n", encoding="utf-8")
    assert S.read_cookie_file(str(plain)) == "a=1; b=2"


def test_refuses_base_with_prefix():
    # 传了带 /getmedia 的 base 时直接拒绝（避免拼出 /getmedia/getmedia）
    assert S.main(["--base", "https://example.com/getmedia"]) == 2


def test_http_requires_explicit_flag():
    assert S.main(["--base", "http://127.0.0.1:9"]) == 2
