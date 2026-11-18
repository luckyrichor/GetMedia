#!/usr/bin/env python3
"""部署后冒烟检查（只读、可重复）。

设计约束：

- **只读**：只发 GET，不发 POST/PUT/DELETE/PATCH，不写库、不写文件、不改 nginx 配置；
  nginx 用 `nginx -T`（打印配置），systemd 用 `show`/`is-active`，都是只读命令。
- **不自己登录**：登录会向库里写一条会话。会话 Cookie 由使用者先取得（浏览器或 curl）后存成文件，
  用 `--free-cookie` / `--owner-cookie` 传进来；不传就跳过需要身份的检查。
- **不打印密钥**：读 `/proc/<pid>/environ` 时只数每个键出现了几次（0/1），值一个字节都不输出。
- **可重复**：随机路径每次不同，检查之间无先后依赖，跑多少遍结果一致（会话过期除外）。

覆盖：健康检查、公开页与公开配置、匿名/free/owner 对 owner 通道与 `/getmedia/owner/` 的 404/200、
nginx 四个 location（三个入口）、密钥是否加载、TLS 证书到期日。

用法：

    # 在服务器上（读进程环境、查 nginx 需要 root；不在服务器上跑也可以，相关项会 SKIP）
    sudo python3 tools/smoke_deploy.py --base https://example.com

    # 带上会话 Cookie（可只带其中一个）
    sudo python3 tools/smoke_deploy.py --base https://example.com \
        --free-cookie /root/smoke-free.cookie --owner-cookie /root/smoke-owner.cookie

    # 本机演练（http 需要显式允许）
    python3 tools/smoke_deploy.py --base http://127.0.0.1:8080 --allow-http

    # 机器可读
    python3 tools/smoke_deploy.py --base https://example.com --json

Cookie 文件两种格式都认：curl/浏览器导出的 Netscape 格式（制表符分隔，含 `#HttpOnly_` 前缀行），
或者一行一个 `name=value` / `a=b; c=d`。

Cookie 文件的规矩（照做，别图省事）：

- 只放 `/root/`，`chmod 600`、`chown root:root`；不要放 `/tmp`、`/home`、网站目录或任何会被备份/同步的位置。
- **用完立刻删除**：`shred -u /root/smoke-*.cookie`；取 Cookie 的那条命令历史也一并清。
- 不进仓库、不贴聊天、不写进日志。本脚本不回显 Cookie，报错信息里也只给状态码。

退出码 = FAIL 的条数（0 表示没有失败；SKIP 不算失败，上限 120）。
"""
from __future__ import annotations

import argparse
import json
import secrets
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

PASS, FAIL, SKIP = "PASS", "FAIL", "SKIP"

BASE_PREFIX = "/getmedia"
OWNER_PROBE = f"{BASE_PREFIX}/owner/api/v2/config/owner"
OWNER_PAGE = f"{BASE_PREFIX}/owner/"
PUBLIC_CONFIG = f"{BASE_PREFIX}/api/v2/config"
PUBLIC_HEALTH = f"{BASE_PREFIX}/api/health"
PUBLIC_ME = f"{BASE_PREFIX}/api/account/me"
NOT_WHITELISTED = f"{BASE_PREFIX}/api/v2/tracks"

# 只查这几个键在不在；GETMEDIA_LLM_API_KEY 缺省是允许的（纯规则应答）。
SECRET_KEYS = ("GETMEDIA_SECRET_KEY", "GETMEDIA_CRED_KEY")
OPTIONAL_KEYS = ("GETMEDIA_LLM_API_KEY",)

# 公开 config 里绝不该出现的 owner 字段（出现即说明配置接口漏了内容）
CONFIG_LEAK_KEYS = ("archive_quota_bytes", "credential_vault", "creator")


@dataclass
class Check:
    name: str
    status: str
    detail: str = ""


@dataclass
class Resp:
    status: int
    headers: dict[str, str]
    body: bytes

    def header(self, name: str) -> str:
        return self.headers.get(name.lower(), "")

    def json(self) -> dict:
        try:
            data = json.loads(self.body.decode("utf-8", "replace"))
        except ValueError:
            return {}
        return data if isinstance(data, dict) else {}


class NetError(Exception):
    """连不上/超时。只有这一类会中断后续网络检查。"""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """不跟随跳转：302 要能被看见（未登录访问管理台不该出现跳转）。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102 - 标准签名
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def fetch(url: str, *, cookie: str | None = None, timeout: float = 10.0) -> Resp:
    req = urllib.request.Request(url, method="GET")
    req.add_header("User-Agent", "getmedia-smoke/1.0 (+read-only)")
    req.add_header("Accept", "*/*")
    if cookie:
        req.add_header("Cookie", cookie)
    try:
        with _OPENER.open(req, timeout=timeout) as r:
            return Resp(r.status, {k.lower(): v for k, v in r.headers.items()}, r.read())
    except urllib.error.HTTPError as exc:
        return Resp(exc.code, {k.lower(): v for k, v in exc.headers.items()}, exc.read())
    except (urllib.error.URLError, TimeoutError, ConnectionError, ssl.SSLError, OSError) as exc:
        raise NetError(str(exc)) from exc


@dataclass
class Client:
    base: str
    timeout: float = 10.0
    free_cookie: str | None = None
    owner_cookie: str | None = None

    def get(self, path: str, *, cookie: str | None = None) -> Resp:
        return fetch(self.base + path, cookie=cookie, timeout=self.timeout)


# ---------------------------------------------------------------- Cookie 文件

def read_cookie_file(path: str) -> str:
    """把 Netscape / name=value 两种格式都读成 `Cookie:` 头的值。"""
    text = Path(path).read_text(encoding="utf-8", errors="replace")
    pairs: list[tuple[str, str]] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("#HttpOnly_"):
            line = line[len("#HttpOnly_"):]
        elif line.startswith("#"):
            continue
        if "\t" in line:
            f = line.split("\t")
            if len(f) >= 7 and f[5]:
                pairs.append((f[5], f[6]))
            continue
        for part in line.split(";"):
            part = part.strip()
            if "=" in part:
                name, value = part.split("=", 1)
                if name.strip():
                    pairs.append((name.strip(), value.strip()))
    # 同名取最后一个（文件通常是覆盖写）
    merged: dict[str, str] = {}
    for name, value in pairs:
        merged[name] = value
    return "; ".join(f"{k}={v}" for k, v in merged.items())


# ---------------------------------------------------------------- 网络检查

def check_public(c: Client) -> list[Check]:
    out: list[Check] = []
    try:
        r = c.get(PUBLIC_HEALTH)
    except NetError as exc:
        return [Check("健康检查", FAIL, f"连不上 {c.base}{PUBLIC_HEALTH}：{exc}")]

    ok = r.status == 200 and r.json().get("ok") is True
    out.append(Check("健康检查", PASS if ok else FAIL, f"HTTP {r.status}  {r.body[:80].decode('utf-8', 'replace')}"))

    # 公开页与 308
    try:
        page = c.get(f"{BASE_PREFIX}/")
        html_ok = page.status == 200 and "text/html" in page.header("content-type")
        out.append(Check("公开页 GET /getmedia/", PASS if html_ok else FAIL,
                         f"HTTP {page.status}  {page.header('content-type') or '无 content-type'}"))
        redirect = c.get(BASE_PREFIX)
        moved = redirect.status in (301, 308) and redirect.header("location").endswith(f"{BASE_PREFIX}/")
        out.append(Check("GET /getmedia 跳 /getmedia/", PASS if moved else FAIL,
                         f"HTTP {redirect.status}  Location={redirect.header('location') or '无'}"))
    except NetError as exc:
        out.append(Check("公开页", FAIL, str(exc)))

    # 公开 config：能读，且不含 owner 字段
    try:
        cfg = c.get(PUBLIC_CONFIG)
        data = cfg.json()
        has_ttl = isinstance(data.get("cache_ttl_seconds"), int)
        leaks = [k for k in CONFIG_LEAK_KEYS if k in data]
        if cfg.status != 200:
            out.append(Check("公开 config", FAIL, f"HTTP {cfg.status}，期望 200"))
        elif leaks:
            out.append(Check("公开 config 不含 owner 字段", FAIL, f"出现了 {leaks}"))
        else:
            out.append(Check("公开 config", PASS if has_ttl else FAIL,
                             "HTTP 200，含 cache_ttl_seconds，无 archive/credential/creator"))
    except NetError as exc:
        out.append(Check("公开 config", FAIL, str(exc)))

    # 白名单外：无论是否登录都必须 404（边缘 map 拦）
    try:
        r = c.get(NOT_WHITELISTED)
        out.append(Check("白名单外接口 404", PASS if r.status == 404 else FAIL, f"HTTP {r.status}"))
    except NetError as exc:
        out.append(Check("白名单外接口 404", FAIL, str(exc)))
    return out


def check_owner_hidden(c: Client, label: str, cookie: str | None) -> list[Check]:
    """匿名或 free：owner 通道与页面都必须是 404，且与"真不存在"逐字节一致。"""
    out: list[Check] = []
    try:
        api = c.get(OWNER_PROBE, cookie=cookie)
        page = c.get(OWNER_PAGE, cookie=cookie)
    except NetError as exc:
        return [Check(f"{label}：owner 通道", FAIL, str(exc))]

    bad_headers = [h for h in ("x-gm-owner", "location", "www-authenticate") if api.header(h)]
    looks_json = "json" in api.header("content-type")
    leaked = [w for w in ("owner", "登录", "管理台") if w.encode().lower() in api.body.lower()]
    api_ok = api.status == 404 and not bad_headers and not looks_json and not leaked
    detail = f"HTTP {api.status}"
    if bad_headers:
        detail += f"，多余响应头 {bad_headers}"
    if looks_json:
        detail += "，返回了 JSON"
    if leaked:
        detail += f"，响应体出现 {leaked}"
    out.append(Check(f"{label}：owner API 返回 404", PASS if api_ok else FAIL, detail))

    # 与另一个"边缘自己 return 404"的响应对比：状态码与响应体必须逐字节一致。
    # 注意不能用 /getmedia/<随机>：公开页有 SPA 回退（try_files … /index.html），那里会返回 200。
    try:
        probe = f"{BASE_PREFIX}/api/not-a-real-path-{secrets.token_hex(6)}"
        ghost = c.get(probe, cookie=cookie)
        same = ghost.status == api.status and ghost.body == api.body
        out.append(Check(f"{label}：404 与白名单外路径一致", PASS if same else FAIL,
                         f"owner={api.status}/{len(api.body)}B  参照={ghost.status}/{len(ghost.body)}B"))
    except NetError as exc:
        out.append(Check(f"{label}：404 与白名单外路径一致", SKIP, str(exc)))

    redirects = page.status in (301, 302, 303, 307, 308)
    out.append(Check(
        f"{label}：管理台页面 404 且不跳登录",
        PASS if page.status == 404 and not redirects else FAIL,
        f"HTTP {page.status}" + (f"  Location={page.header('location')}" if redirects else ""),
    ))
    return out


def check_identity(c: Client, label: str, cookie: str) -> tuple[list[Check], str | None]:
    """确认 Cookie 是活的，并取出 tier。返回 (检查项, tier)。"""
    try:
        me = c.get(PUBLIC_ME, cookie=cookie)
    except NetError as exc:
        return [Check(f"{label}：会话有效", FAIL, str(exc))], None
    if me.status != 200:
        return [Check(f"{label}：会话有效", FAIL, f"GET /account/me → HTTP {me.status}（Cookie 过期？）")], None
    tier = me.json().get("tier")
    return [Check(f"{label}：会话有效", PASS, f"tier={tier}")], tier


def check_owner_ok(c: Client) -> list[Check]:
    out: list[Check] = []
    cookie = c.owner_cookie
    try:
        api = c.get(OWNER_PROBE, cookie=cookie)
        data = api.json()
        good = (api.status == 200 and api.header("x-gm-owner") == "200"
                and isinstance(data.get("archive_quota_bytes"), int) and data["archive_quota_bytes"] > 0)
        out.append(Check(
            "owner：owner API 200", PASS if good else FAIL,
            f"HTTP {api.status}  X-GM-Owner={api.header('x-gm-owner') or '缺失'}  "
            f"archive_quota={data.get('archive_quota_bytes')}",
        ))

        page = c.get(OWNER_PAGE, cookie=cookie)
        html = page.body.decode("utf-8", "replace")
        page_ok = page.status == 200 and "noindex" in html and f"{BASE_PREFIX}/owner/assets/" in html
        out.append(Check("owner：管理台页面 200", PASS if page_ok else FAIL,
                         f"HTTP {page.status}  noindex={'noindex' in html}  "
                         f"assets={'/getmedia/owner/assets/' in html}"))

        tracks = c.get(f"{BASE_PREFIX}/owner/api/v2/tracks?since_seq=0&limit=1", cookie=cookie)
        out.append(Check("owner：owner 通道到应用", PASS if tracks.status == 200 else FAIL,
                         f"GET /v2/tracks → HTTP {tracks.status}"))
    except NetError as exc:
        out.append(Check("owner：owner 通道", FAIL, str(exc)))
    return out


def check_ai_unavailable(c: Client) -> list[Check]:
    """AI 默认关闭时，owner 拿到的 capabilities 应是 available=false（不是 500）。"""
    try:
        r = c.get(f"{BASE_PREFIX}/owner/api/v2/recreations/capabilities", cookie=c.owner_cookie)
    except NetError as exc:
        return [Check("owner：AI 能力接口", SKIP, str(exc))]
    data = r.json()
    if r.status != 200:
        return [Check("owner：AI 能力接口", FAIL, f"HTTP {r.status}")]
    return [Check("owner：AI 能力接口", PASS,
                  f"available={data.get('available')}  本期只读属正常" if data.get("available") is False
                  else f"available={data.get('available')}")]


# ---------------------------------------------------------------- 本机检查

def _run(cmd: list[str]) -> tuple[int, str]:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=20, check=False)
    except (FileNotFoundError, subprocess.SubprocessError) as exc:
        return 127, str(exc)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def check_service(unit: str) -> tuple[list[Check], int]:
    out: list[Check] = []
    code, active = _run(["systemctl", "is-active", unit])
    if code == 127:
        return [Check("systemd：API 运行中", SKIP, "本机没有 systemctl（不在服务器上？）")], 0
    out.append(Check(f"systemd：{unit}", PASS if active.strip() == "active" else FAIL,
                     active.strip() or f"退出码 {code}"))
    code, pid_raw = _run(["systemctl", "show", "-p", "MainPID", "--value", unit])
    try:
        pid = int(pid_raw.strip() or "0")
    except ValueError:
        pid = 0
    if pid <= 0:
        out.append(Check("systemd：MainPID", FAIL, "拿不到主进程号（单元没在跑？）"))
    return out, pid


def check_secrets(pid: int) -> list[Check]:
    """只数键，不读值。"""
    if pid <= 0:
        return [Check("密钥已加载", SKIP, "没有 MainPID，跳过")]
    path = Path(f"/proc/{pid}/environ")
    try:
        raw = path.read_bytes()
    except (OSError, PermissionError) as exc:
        return [Check("密钥已加载", SKIP, f"读不了 {path}（需要 root 或同用户）：{exc.__class__.__name__}")]
    entries = [e for e in raw.split(b"\0") if b"=" in e]
    counts = {k: sum(1 for e in entries if e.startswith(k.encode() + b"=")) for k in SECRET_KEYS + OPTIONAL_KEYS}

    missing = [k for k in SECRET_KEYS if counts[k] != 1]
    out = [Check("密钥已加载（只数键）", PASS if not missing else FAIL,
                 "  ".join(f"{k}={counts[k]}" for k in SECRET_KEYS)
                 + ("  缺少：" + ",".join(missing) if missing else ""))]
    llm = counts[OPTIONAL_KEYS[0]]
    out.append(Check("模型密钥（可选）", PASS,
                     f"{OPTIONAL_KEYS[0]}={llm}" + ("（未配置 → Agent 走规则应答，属预期）" if llm == 0 else "")))
    return out


def check_nginx() -> list[Check]:
    code, dump = _run(["nginx", "-T"])
    if code == 127:
        return [Check("nginx：三入口", SKIP, "本机没有 nginx（不在服务器上？）")]
    if code != 0 and "getmedia" not in dump:
        return [Check("nginx：三入口", SKIP, f"nginx -T 不可用（退出码 {code}）")]

    need = {
        "公开页 /getmedia/": "location ^~ /getmedia/",
        "公开 API /getmedia/api/": "location ^~ /getmedia/api/",
        "owner API /getmedia/owner/api/": "location ^~ /getmedia/owner/api/",
        "owner 页面 /getmedia/owner/": "location ^~ /getmedia/owner/",
        "404 兜底 @gm_owner_hidden": "location @gm_owner_hidden",
        "边缘探针 /_getmedia_owner_check": "location = /_getmedia_owner_check",
    }
    missing = [name for name, token in need.items() if token not in dump]
    probes = dump.count("auth_request /_getmedia_owner_check;")
    hidden = "location @gm_owner_hidden" in dump and "return 404;" in dump.split("location @gm_owner_hidden", 1)[-1][:200]
    ok = not missing and probes >= 2 and hidden
    return [Check("nginx：三入口", PASS if ok else FAIL,
                  "四个 location 就位  auth_request×%d  404 兜底=%s%s" % (
                      probes, "是" if hidden else "否",
                      "  缺：" + ",".join(missing) if missing else ""))]


def check_cert(host: str, port: int, timeout: float = 8.0) -> list[Check]:
    out: list[Check] = []
    # 1) 链与主机名校验（默认上下文）
    try:
        ctx = ssl.create_default_context()
        with socket.create_connection((host, port), timeout=timeout) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as ss:
                ss.getpeercert()
        out.append(Check("证书：链与主机名", PASS, f"{host}:{port} 校验通过"))
    except ssl.SSLCertVerificationError as exc:
        out.append(Check("证书：链与主机名", FAIL, f"校验失败：{exc.verify_message}"))
    except (OSError, ssl.SSLError) as exc:
        out.append(Check("证书：链与主机名", FAIL, f"连不上 {host}:{port}：{exc}"))
        return out

    # 2) 到期日。**这一项不允许静默 SKIP**：读不到到期日就当失败报出来，否则等于假绿。
    #    坑（2026-11-17 实测）：verify_mode=CERT_NONE 时 getpeercert() 只返回 {}，
    #    所以这里先按 CERT_REQUIRED + 关 hostname 校验取解析结果；失败再退到二进制形式解码。
    expires: float | None = None
    reason = ""
    try:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_REQUIRED
        with socket.create_connection((host, port), timeout=timeout) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as ss:
                cert = ss.getpeercert() or {}
        not_after = cert.get("notAfter")
        if not_after:
            expires = ssl.cert_time_to_seconds(not_after)
        else:
            reason = "CERT_REQUIRED 下 getpeercert() 仍为空"
    except (ssl.SSLError, OSError) as exc:
        reason = f"{type(exc).__name__}: {exc}"

    if expires is None:
        # 退路：取 DER 再解码（证书过期/自签时 CERT_REQUIRED 会失败，这条路仍能读日期）
        try:
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            with socket.create_connection((host, port), timeout=timeout) as sock:
                with ctx.wrap_socket(sock, server_hostname=host) as ss:
                    der = ss.getpeercert(binary_form=True)
            if der:
                with tempfile.NamedTemporaryFile("wb", suffix=".pem", delete=False) as fh:
                    fh.write(ssl.DER_cert_to_PEM_cert(der).encode())
                    pem_path = fh.name
                try:
                    decoded = ssl._ssl._test_decode_cert(pem_path)  # 标准库内部的 PEM 解码，仅本机只读用
                    not_after = decoded.get("notAfter")
                    if not_after:
                        expires = ssl.cert_time_to_seconds(not_after)
                finally:
                    Path(pem_path).unlink(missing_ok=True)
            if expires is None and not reason:
                reason = "二进制形式也解不出 notAfter"
        except (ssl.SSLError, OSError) as exc:
            reason = reason or f"{type(exc).__name__}: {exc}"

    if expires is None:
        out.append(Check("证书：到期日", FAIL,
                         f"读不到到期日，按失败计（{reason or '未知原因'}）"))
        return out

    hours = (expires - time.time()) / 3600
    when = time.strftime("%Y-%m-%d %H:%M UTC", time.gmtime(expires))
    if hours < 0:
        out.append(Check("证书：到期日", FAIL, f"已过期（{when}）"))
    elif hours < 36:
        out.append(Check("证书：到期日", FAIL,
                         f"只剩 {hours:.1f} 小时（{when} 到期），续期没跑或没 reload"))
    else:
        out.append(Check("证书：到期日", PASS, f"剩 {hours / 24:.1f} 天（{when} 到期）"))
    return out


# ---------------------------------------------------------------- 主流程

@dataclass
class Plan:
    base: str
    timeout: float
    free_cookie: str | None
    owner_cookie: str | None
    unit: str
    host_check: bool = True
    tls_host: str | None = None
    tls_port: int = 443


def run(plan: Plan) -> list[Check]:
    out: list[Check] = []
    client = Client(plan.base, timeout=plan.timeout,
                    free_cookie=plan.free_cookie, owner_cookie=plan.owner_cookie)

    out += check_public(client)

    if plan.free_cookie:
        ident, tier = check_identity(client, "free", plan.free_cookie)
        out += ident
        if tier and tier != "free":
            out.append(Check("free：tier 匹配", FAIL, f"这个 Cookie 的 tier 是 {tier}，不是 free"))
        out += check_owner_hidden(client, "free", plan.free_cookie)
    else:
        out.append(Check("free：owner 通道 404", SKIP, "未提供 --free-cookie"))

    if plan.owner_cookie:
        ident, tier = check_identity(client, "owner", plan.owner_cookie)
        out += ident
        if tier and tier != "owner":
            out.append(Check("owner：tier 匹配", FAIL, f"这个 Cookie 的 tier 是 {tier}，不是 owner"))
        else:
            out += check_owner_ok(client)
            out += check_ai_unavailable(client)
    else:
        out.append(Check("owner：owner 通道 200", SKIP, "未提供 --owner-cookie"))

    # 匿名永远要在最后测一次（前面带 Cookie 的请求不影响它，但顺序上更清楚）
    out += check_owner_hidden(client, "匿名", None)

    if plan.host_check:
        svc, pid = check_service(plan.unit)
        out += svc
        out += check_secrets(pid)
        out += check_nginx()
        if plan.tls_host:
            out += check_cert(plan.tls_host, plan.tls_port, plan.timeout)
    return out


def render(checks: list[Check], base: str) -> str:
    width = max((len(c.name) for c in checks), default=10)
    lines = [f"GetMedia 部署后冒烟（只读）  {base}  {time.strftime('%Y-%m-%d %H:%M:%S %Z')}", ""]
    for c in checks:
        lines.append(f"{c.status:<4}  {c.name:<{width}}  {c.detail}")
    passed = sum(1 for c in checks if c.status == PASS)
    failed = sum(1 for c in checks if c.status == FAIL)
    skipped = sum(1 for c in checks if c.status == SKIP)
    lines += ["", f"通过 {passed} / 失败 {failed} / 跳过 {skipped}"]
    if failed:
        lines.append("有失败项：按上面每一行的说明查；先看 nginx -t 与 systemctl status getmedia-api。")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="部署后冒烟检查（只读，不写生产数据）")
    ap.add_argument("--base", required=True, help="站点根，例如 https://example.com（不含 /getmedia）")
    ap.add_argument("--free-cookie", help="free 账号的 Cookie 文件（Netscape 或 name=value）")
    ap.add_argument("--owner-cookie", help="owner 账号的 Cookie 文件")
    ap.add_argument("--unit", default="getmedia-api", help="systemd 单元名")
    ap.add_argument("--timeout", type=float, default=10.0, help="单请求超时秒数")
    ap.add_argument("--tls-host", help="证书检查的主机名（默认取 --base 的主机）")
    ap.add_argument("--tls-port", type=int, default=443)
    ap.add_argument("--allow-http", action="store_true", help="允许 http（只用于本机演练）")
    ap.add_argument("--no-host-check", action="store_true", help="跳过本机检查（systemd/nginx/证书）")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    args = ap.parse_args(argv)

    base = args.base.rstrip("/")
    if base.endswith(BASE_PREFIX):
        print(f"--base 不要带 {BASE_PREFIX}，脚本自己会加", file=sys.stderr)
        return 2
    host = urllib.parse.urlsplit(base).hostname or ""
    is_https = base.startswith("https://")
    if not is_https and not args.allow_http:
        print("--base 必须是 https://（本机演练请显式加 --allow-http）", file=sys.stderr)
        return 2

    plan = Plan(
        base=base,
        timeout=args.timeout,
        free_cookie=read_cookie_file(args.free_cookie) if args.free_cookie else None,
        owner_cookie=read_cookie_file(args.owner_cookie) if args.owner_cookie else None,
        unit=args.unit,
        host_check=not args.no_host_check,
        tls_host=args.tls_host or (host if is_https else None),
        tls_port=args.tls_port,
    )
    if args.free_cookie and not plan.free_cookie:
        print(f"读不出 Cookie：{args.free_cookie}", file=sys.stderr)
        return 2
    if args.owner_cookie and not plan.owner_cookie:
        print(f"读不出 Cookie：{args.owner_cookie}", file=sys.stderr)
        return 2

    checks = run(plan)
    if args.json:
        print(json.dumps([{"name": c.name, "status": c.status, "detail": c.detail} for c in checks],
                         ensure_ascii=False, indent=2))
    else:
        print(render(checks, plan.base))
    failed = sum(1 for c in checks if c.status == FAIL)
    return min(failed, 120)


if __name__ == "__main__":
    sys.exit(main())
