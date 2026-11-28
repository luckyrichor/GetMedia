"""用真实 nginx + 假上游验证 deploy/nginx 模板（沙箱没有 nginx 时整体跳过）。

模拟一个"共用的 443 块"：里面先有两个别的项目的 location，再 include GetMedia 的 snippet，
以此验证 GetMedia 不会抢走别人的路径，也不改变别人的行为。
"""
from __future__ import annotations

import http.client
import os
import shutil
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
NGINX = shutil.which("nginx") or ("/usr/sbin/nginx" if Path("/usr/sbin/nginx").exists() else None)
pytestmark = pytest.mark.skipif(NGINX is None, reason="沙箱无 nginx")


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Upstream:
    """记录收到的请求；读完整个请求体，返回 200 JSON。

    `/v2/config/owner` 例外：它是 owner 通道的认证探针，按 Cookie 回 401/403/200，
    用来验证边缘（auth_request）的判定，不需要真跑应用。
    """

    def __init__(self):
        self.seen: list[dict] = []
        outer = self

        class H(BaseHTTPRequestHandler):
            def _do(self):
                n = int(self.headers.get("Content-Length") or 0)
                got = 0
                while got < n:
                    got += len(self.rfile.read(min(65536, n - got)))
                outer.seen.append({"method": self.command, "path": self.path, "headers": {k.lower(): v for k, v in self.headers.items()}, "body": got})
                if self.path.split("?")[0] == "/v2/config/owner":
                    cookie = self.headers.get("Cookie") or ""
                    if "gm_tier=owner" in cookie:
                        code, body = 200, b'{"cache_ttl_seconds":86400}'
                    elif "gm_tier=free" in cookie:
                        code, body = 403, b'{"detail":"forbidden"}'
                    else:
                        code, body = 401, b'{"detail":"unauthenticated"}'
                    self.send_response(code)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                body = b'{"ok":true}'
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            do_GET = do_POST = do_DELETE = do_PUT = _do

            def log_message(self, *a):
                pass

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def stop(self):
        self.srv.shutdown()
        self.srv.server_close()


class Site:
    def __init__(self, tmp: Path, upstream: Upstream):
        self.tmp, self.up = tmp, upstream
        self.www, self.logs = tmp / "www", tmp / "logs"
        for d in ("public/assets", "download", "owner/assets"):
            (self.www / d).mkdir(parents=True)
        self.logs.mkdir()
        (self.www / "public/index.html").write_text("<!doctype html><title>GM-INDEX</title>")
        (self.www / "public/assets/app.abc123.js").write_text("console.log(1)")
        (self.www / "owner/index.html").write_text("<!doctype html><title>GM-OWNER</title>")
        (self.www / "owner/assets/owner.js").write_text("console.log('owner')")
        (self.www / "download/getmedia-0.1.0.apk").write_bytes(b"PK\x03\x04apk")
        (self.www / "download/notes.html").write_text("<script>alert(1)</script>")
        deploy = ROOT / "deploy/nginx"
        prefix = (deploy / "getmedia-prefix.conf").read_text()
        prefix = (prefix.replace("/var/www/getmedia/", f"{self.www}/").replace("/var/log/nginx/", f"{self.logs}/")
                  .replace("127.0.0.1:8700", f"127.0.0.1:{upstream.port}"))
        (tmp / "getmedia-http.conf").write_text((deploy / "getmedia-http.conf").read_text())
        (tmp / "getmedia-prefix.conf").write_text(prefix)
        self.port = free_port()
        user = "user root;" if os.geteuid() == 0 else ""
        (tmp / "nginx.conf").write_text(f"""
{user}
pid {tmp}/nginx.pid;
error_log {self.logs}/error.log;
worker_processes 1;
events {{}}
http {{
    access_log off;
    client_body_temp_path {tmp}/tmp/body; proxy_temp_path {tmp}/tmp/proxy; fastcgi_temp_path {tmp}/tmp/fcgi;
    uwsgi_temp_path {tmp}/tmp/uwsgi; scgi_temp_path {tmp}/tmp/scgi;
    # 同机其他项目的 http 级配置（有自己的 limit_req，且用的是默认 503）
    limit_req_zone $binary_remote_addr zone=other_zone:1m rate=1r/s;
    include {tmp}/getmedia-http.conf;
    server {{
        listen 127.0.0.1:{self.port};
        server_name _;
        client_max_body_size 1m;
        location ^~ /getaudio/ {{ limit_req zone=other_zone burst=1 nodelay; proxy_pass http://127.0.0.1:{upstream.port}; }}
        location ~ ^/getvideo/.*$ {{ default_type text/plain; return 200 "video-ok"; }}
        include {tmp}/getmedia-prefix.conf;
    }}
}}
""")
        self.proc = None

    def conf(self) -> list[str]:
        return [NGINX, "-c", str(self.tmp / "nginx.conf"), "-e", str(self.logs / "error.log")]

    def test_config(self):
        (self.tmp / "tmp").mkdir(exist_ok=True)
        return subprocess.run(self.conf() + ["-t"], capture_output=True, text=True)

    def start(self):
        self.proc = subprocess.Popen(self.conf() + ["-g", "daemon off;"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:
                socket.create_connection(("127.0.0.1", self.port), timeout=0.2).close()
                return
            except OSError:
                time.sleep(0.05)
        raise RuntimeError("nginx 未能启动：" + (self.logs / "error.log").read_text())

    def stop(self):
        if self.proc:
            self.proc.terminate()
            self.proc.wait(timeout=10)

    def req(self, method, path, body=None, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=20)
        try:
            c.request(method, path, body=body, headers=headers or {})
            r = c.getresponse()
            data = r.read()
            return r.status, {k.lower(): v for k, v in r.getheaders()}, data
        finally:
            c.close()

    def hits(self):
        return list(self.up.seen)


@pytest.fixture
def site(tmp_path):
    up = Upstream()
    s = Site(tmp_path, up)
    res = s.test_config()
    assert res.returncode == 0, res.stderr
    s.start()
    yield s
    s.stop()
    up.stop()


def test_nginx_config_is_valid_inside_a_shared_443_style_block(tmp_path):
    up = Upstream()
    try:
        res = Site(tmp_path, up).test_config()
        assert res.returncode == 0, res.stderr
    finally:
        up.stop()


# ---------------------------------------------------------------- 接口：白名单、前缀剥离、头
def test_free_api_path_is_forwarded_with_prefix_stripped_and_headers_overwritten(site):
    st, h, body = site.req("GET", "/getmedia/api/health?x=1", headers={"X-Forwarded-For": "6.6.6.6", "Cookie": "__Secure-gm_session=C0OKIE",
                                                                       "X-GetMedia-Session": "S3SSION", "Origin": "https://evil.invalid"})
    assert st == 200 and body == b'{"ok":true}'
    seen = site.hits()[-1]
    assert seen["path"] == "/health?x=1"                                     # 前缀被剥掉，查询串保留
    assert seen["headers"]["x-forwarded-for"] == "127.0.0.1"                 # 客户端自填的被覆盖，不是追加
    assert seen["headers"]["x-forwarded-proto"] == "https"
    assert seen["headers"]["cookie"] == "__Secure-gm_session=C0OKIE"         # 会话凭据正常透传给应用
    assert seen["headers"]["x-getmedia-session"] == "S3SSION"
    for k, v in {"content-security-policy": "default-src 'self'", "x-content-type-options": "nosniff", "referrer-policy": "no-referrer",
                 "x-robots-tag": "noindex, nofollow", "cache-control": "no-store"}.items():
        assert v in h[k], k
    assert "access-control-allow-origin" not in h                            # 不开 CORS


@pytest.mark.parametrize("method,path", [
    ("POST", "/getmedia/api/health"),                              # 路径在白名单，但方法不对
    ("DELETE", "/getmedia/api/v2/config"),
    ("GET", "/getmedia/api/v2/owner-only-thing"),                  # 不在白名单（想象中的 owner 接口）
    ("GET", "/getmedia/api/docs"),
    ("GET", "/getmedia/api/openapi.json"),
    ("GET", "/getmedia/api/account/login"),                        # 登录只允许 POST
    ("DELETE", "/getmedia/api/account/devices/dev_XYZ"),           # 设备 id 格式不符
    ("DELETE", "/getmedia/api/account/devices/dev_0123456789abcdef/extra"),
    ("GET", "/getmedia/api/health/"),                              # 多一个斜杠也不放行
    ("PUT", "/getmedia/api/account/me"),
])
def test_non_whitelisted_api_requests_get_404_and_never_reach_the_app(site, method, path):
    st, _, _ = site.req(method, path, body=b"{}" if method in ("POST", "PUT") else None)
    assert st == 404 and site.hits() == []


@pytest.mark.parametrize("method,path", [
    ("GET", "/getmedia/api/v2/config"), ("GET", "/getmedia/api/v2/legal"), ("GET", "/getmedia/api/account/me"),
    ("GET", "/getmedia/api/account/devices"), ("DELETE", "/getmedia/api/account/me"),
    ("POST", "/getmedia/api/account/devices/revoke-others"), ("POST", "/getmedia/api/account/recovery/ack"),
    ("DELETE", "/getmedia/api/account/devices/dev_0123456789abcdef"), ("POST", "/getmedia/api/v2/heartbeat"),
])
def test_whitelisted_api_requests_reach_the_app_with_internal_paths(site, method, path):
    st, _, _ = site.req(method, path, body=b"{}" if method == "POST" else None)
    assert st == 200
    assert site.hits()[-1]["path"] == path.replace("/getmedia/api", "")


def test_encoded_dot_segments_do_not_bypass_the_whitelist(site):
    # %2e%2e 解码规范化后不再位于 /getmedia/api/ 下，只会落到静态页（SPA 回退），到不了应用
    st, _, body = site.req("GET", "/getmedia/api/%2e%2e/v2/owner-only")
    assert site.hits() == [] and (st == 404 or b"GM-INDEX" in body)


# ---------------------------------------------------------------- 请求体大小
def test_body_size_limits(site):
    ok = site.req("POST", "/getmedia/api/v2/feedback", body=b"x" * (13 * 1024 * 1024), headers={"Content-Type": "multipart/form-data; boundary=b"})
    assert ok[0] == 200 and site.hits()[-1]["body"] == 13 * 1024 * 1024        # 反馈：13 MB 放行（上限 14m）
    big = site.req("POST", "/getmedia/api/v2/feedback", body=b"x" * (15 * 1024 * 1024), headers={"Content-Type": "multipart/form-data; boundary=b"})
    assert big[0] == 413
    n = len(site.hits())
    other = site.req("POST", "/getmedia/api/account/login", body=b"x" * (2 * 1024 * 1024), headers={"Content-Type": "application/json"})
    assert other[0] == 413 and len(site.hits()) == n                           # 其他接口 1m 上限，超限不到应用


def test_owner_upload_channel_takes_bodies_well_over_the_shared_server_limit(site):
    """手机上传本机导入的原件（`POST /v2/tracks/{id}/file`）必须能过 owner 通道。

    回归：夹具的 server 块里带着同机其他项目留下的 `client_max_body_size 1m`。
    认证子请求 `/_getmedia_owner_check` 若没显式放开就会继承它 —— 子请求不读 body，
    但大小检查在 location 匹配之后、与是否转发 body 无关，于是**主请求**先被判 413，
    `auth_request` 拿到 413 后只回 500（实测 0.1.41 归档 162 MB 视频时踩到）。
    """
    n = 3 * 1024 * 1024                                                        # 3 MB，远超 server 块的 1m
    st, _, _ = site.req("POST", "/getmedia/owner/api/v2/tracks/79/file", body=b"x" * n,
                        headers={"Cookie": "gm_tier=owner", "Content-Type": "application/octet-stream"})
    assert st == 200
    assert site.hits()[-1]["path"] == "/v2/tracks/79/file"                      # 前缀剥掉，整个 body 都到了应用
    assert site.hits()[-1]["body"] == n


def test_owner_channel_hides_itself_even_when_the_body_is_huge(site):
    """匿名带大 body 打 owner 通道：仍是那个 404，不能因为 413 而暴露通道存在。"""
    n = 3 * 1024 * 1024
    st, h, body = site.req("POST", "/getmedia/owner/api/v2/tracks/79/file", body=b"x" * n,
                           headers={"Content-Type": "application/octet-stream"})
    assert st == 404 and "x-gm-owner" not in h
    assert all(hit["path"].startswith("/v2/config/owner") for hit in site.hits())


# ---------------------------------------------------------------- 限流
def test_auth_rate_limit_returns_429_and_is_scoped(site):
    codes = [site.req("POST", "/getmedia/api/account/login", body=b"{}", headers={"Content-Type": "application/json"})[0] for _ in range(16)]
    assert codes[0] == 200 and 429 in codes
    assert site.req("GET", "/getmedia/api/health")[0] == 200                   # 限的是认证类接口，不影响 /health
    assert site.req("GET", "/getmedia/")[0] == 200                              # 也不影响网页


def test_feedback_rate_limit(site):
    codes = [site.req("POST", "/getmedia/api/v2/feedback", body=b"{}", headers={"Content-Type": "multipart/form-data; boundary=b"})[0] for _ in range(8)]
    assert 429 in codes and codes[0] == 200


# ---------------------------------------------------------------- 访问日志
def test_access_log_contains_no_credentials_or_query_strings(site):
    site.req("GET", "/getmedia/api/health?token=QUERYSECRET", headers={"Cookie": "__Secure-gm_session=COOKIESECRET", "X-GetMedia-Session": "SESSIONSECRET",
                                                                       "Authorization": "Bearer AUTHSECRET", "Referer": "https://x.invalid/?r=REFSECRET"})
    site.req("GET", "/getmedia/?q=PAGEQUERY")
    time.sleep(0.2)
    log = (site.logs / "getmedia.access.log").read_text()
    assert 'GET /getmedia/api/health"' in log and 'GET /getmedia/"' in log      # 记了方法与路径
    for secret in ("QUERYSECRET", "COOKIESECRET", "SESSIONSECRET", "AUTHSECRET", "REFSECRET", "PAGEQUERY", "?"):
        assert secret not in log, secret
    assert log.startswith("127.0.0.1 [")


# ---------------------------------------------------------------- 网页与下载
def test_redirect_static_spa_fallback_and_assets(site):
    st, h, _ = site.req("GET", "/getmedia")
    assert st == 308 and h["location"].endswith("/getmedia/")
    st, h, body = site.req("GET", "/getmedia/")
    assert st == 200 and b"GM-INDEX" in body and h["cache-control"] == "no-cache"
    assert site.req("GET", "/getmedia/account/security")[2].count(b"GM-INDEX") == 1   # SPA 路由回退到 index.html
    st, h, _ = site.req("GET", "/getmedia/assets/app.abc123.js")
    assert st == 200 and "immutable" in h["cache-control"] and h["x-content-type-options"] == "nosniff"
    assert site.req("GET", "/getmedia/assets/missing.js")[0] == 404                   # 缺失资源不回退成 HTML
    for k in ("content-security-policy", "referrer-policy", "x-robots-tag"):
        assert k in site.req("GET", "/getmedia/")[1]
    assert site.hits() == []                                                          # 网页请求不碰应用


def test_download_serves_apk_only_as_apk_and_has_no_listing(site):
    st, h, body = site.req("GET", "/getmedia/download/getmedia-0.1.0.apk")
    assert st == 200 and h["content-type"] == "application/vnd.android.package-archive" and body.startswith(b"PK")
    st, h, _ = site.req("GET", "/getmedia/download/notes.html")                        # 目录里误放 html 也不会按 html 执行
    assert st == 200 and h["content-type"] == "application/octet-stream" and "default-src 'none'" in h["content-security-policy"]
    assert site.req("GET", "/getmedia/download/")[0] == 404                           # 无目录列表
    assert site.req("GET", "/getmedia/download/nope.apk")[0] == 404


# ---------------------------------------------------------------- 不影响同一 443 块里的其他项目
def test_other_projects_in_the_same_server_block_are_unaffected(site):
    assert site.req("GET", "/getaudio/x")[2] == b'{"ok":true}'                       # 别的项目照常代理
    assert site.req("GET", "/getvideo/x.json")[2] == b"video-ok"                      # 其他项目的正则 location 仍然生效
    # 其他项目自己的 limit_req 仍用 nginx 默认状态码 503，没有被 GetMedia 的 limit_req_status 429 改掉
    # （注意：夹具里必须真的代理；return 在 rewrite 阶段就结束请求，根本走不到 limit_req）
    codes = {site.req("GET", "/getaudio/x")[0] for _ in range(6)}
    assert 503 in codes and 429 not in codes


# ---------------------------------------------------------------- owner 通道：一律 404
OWNER_PATHS = [
    "/getmedia/owner/",                       # 管理台首页
    "/getmedia/owner/index.html",
    "/getmedia/owner/creators",               # 前端路由的深链接
    "/getmedia/owner/api/v2/config/owner",    # 接口通道
    "/getmedia/owner/api/v2/tracks",
]


@pytest.mark.parametrize("path", OWNER_PATHS)
def test_anonymous_owner_paths_are_404_and_never_reach_the_app(site, path):
    """匿名访问 /getmedia/owner/ 与 /getmedia/owner/api/* 都是 404（不区分"要登录"）。"""
    st, h, body = site.req("GET", path)
    assert st == 404
    assert "x-gm-owner" not in h
    assert b"GM-OWNER" not in body and b'{"ok":true}' not in body
    # 只有认证子请求打到上游；目标路径没有被转发（匿名根本走不到应用）
    assert site.hits() and all(hit["path"].startswith("/v2/config/owner") for hit in site.hits())


@pytest.mark.parametrize("path", OWNER_PATHS)
def test_free_user_owner_paths_are_404_too(site, path):
    """已登录但不是 owner：与匿名完全一样的 404。"""
    st, h, body = site.req("GET", path, headers={"Cookie": "gm_tier=free"})
    assert st == 404
    assert "x-gm-owner" not in h
    assert b"GM-OWNER" not in body and b'{"ok":true}' not in body
    assert all(hit["path"].startswith("/v2/config/owner") for hit in site.hits())


def test_owner_404_is_indistinguishable_from_a_missing_file(site):
    """隐藏用的 404 与"文件本来就不存在"的 404 完全一致：状态码、响应体、没有额外响应头。"""
    _, ref_h, ref = site.req("GET", "/getmedia/download/absent.apk")
    st, h, body = site.req("GET", "/getmedia/owner/api/v2/tracks")
    assert st == 404 and body == ref
    assert h["content-type"] == ref_h["content-type"]
    assert "x-gm-owner" not in h


def test_owner_sees_the_console_and_api_with_prefix_stripped(site):
    st, _, body = site.req("GET", "/getmedia/owner/", headers={"Cookie": "gm_tier=owner"})
    assert st == 200 and b"GM-OWNER" in body
    st, _, body = site.req("GET", "/getmedia/owner/creators", headers={"Cookie": "gm_tier=owner"})
    assert st == 200 and b"GM-OWNER" in body                                  # 深链接回退到 index.html
    st, _, body = site.req("GET", "/getmedia/owner/api/v2/config/owner?x=1", headers={"Cookie": "gm_tier=owner"})
    assert st == 200 and b"cache_ttl_seconds" in body                          # 探针路由本身也可直连（owner 才通）
    st, _, body = site.req("GET", "/getmedia/owner/api/v2/tracks?x=1", headers={"Cookie": "gm_tier=owner"})
    assert st == 200 and body == b'{"ok":true}'
    assert site.hits()[-1]["path"] == "/v2/tracks?x=1"                          # 前缀被剥掉，查询串保留
