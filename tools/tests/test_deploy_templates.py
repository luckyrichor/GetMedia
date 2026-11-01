"""deploy/ 模板的静态检查与证书续期脚本测试（不需要 nginx；nginx 相关见 test_deploy_nginx.py）。"""
from __future__ import annotations

import configparser
import json
import os
import re
import shutil
import stat
import subprocess
import textwrap
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from getmedia.config import ConfigError, load_mail, load_settings
from getmedia.release import ReleaseStore
from getmedia.runtime import RuntimeStore
from getmedia.tiers import FREE_ROUTES

ROOT = Path(__file__).resolve().parents[2]
DEPLOY = ROOT / "deploy"


def read(rel: str) -> str:
    return (DEPLOY / rel).read_text(encoding="utf-8")


def parse_env(rel: str) -> dict:
    out = {}
    for ln in read(rel).splitlines():
        ln = ln.strip()
        if ln and not ln.startswith("#"):
            k, _, v = ln.partition("=")
            out[k] = v
    return out


# ---------------------------------------------------------------- nginx：http 级配置
def http_conf() -> str:
    return read("nginx/getmedia-http.conf")


def test_http_conf_whitelist_matches_free_routes():
    block = re.search(r'map "\$request_method \$uri" \$gm_api_allowed \{(.*?)\n\}', http_conf(), re.S).group(1)
    entries = re.findall(r'"~\^([A-Z]+) (/getmedia/api[^"]*?)\$"\s+1;', block)
    assert entries, "没有解析到白名单条目"
    assert "default 0;" in block
    want = set()
    for method, path in FREE_ROUTES:
        want.add((method, "/getmedia/api" + path.replace("{device_id}", "dev_[0-9a-f]{16}")))
    assert set(entries) == want, (set(entries) ^ want)


def test_http_conf_only_namespaced_non_global_directives():
    text = re.sub(r"#.*", "", http_conf())
    names = set(re.findall(r"^(\w+)\b", text, re.M))      # 顶格的就是 http 级指令；map 内部条目是缩进的
    # 只允许这四类指令；任何全局调优（map_hash_bucket_size、limit_req_status 等）都会影响同机其他项目
    assert names <= {"map", "limit_req_zone", "limit_conn_zone", "log_format"}, names
    for var in re.findall(r"\$(gm_\w+)", text):
        assert var.startswith("gm_")
    for zone in re.findall(r"zone=(\w+):", text):
        assert zone.startswith("gm_"), zone


def test_log_format_excludes_sensitive_variables():
    fmt = re.search(r"log_format getmedia (.*?);", http_conf(), re.S).group(1)
    for bad in ("$http_cookie", "$http_authorization", "$http_x_getmedia_session", "$http_referer",
                "$args", "$query_string", "$request_uri"):
        assert bad not in fmt, bad
    assert not re.search(r"\$request(?![_a-z])", fmt), "$request 含查询串"


# ---------------------------------------------------------------- nginx：前缀 snippet
def locations() -> dict:
    text = read("nginx/getmedia-prefix.conf")
    out, i = {}, 0
    for m in re.finditer(r"^location\s+(.+?)\s*\{", text, re.M):
        depth, j = 1, m.end()
        while depth:
            depth += {"{": 1, "}": -1}.get(text[j], 0)
            j += 1
        out[m.group(1)] = text[m.end():j - 1]
    return out


def test_snippet_locations_cover_expected_paths():
    assert set(locations()) == {
        "= /getmedia", "= /getmedia/api/v2/feedback", "^~ /getmedia/api/", "^~ /getmedia/download/",
        "^~ /getmedia/assets/", "^~ /getmedia/",
    }
    # 所有非精确的前缀 location 都用 ^~，避免被同一 server 块里其他项目的正则 location 抢走
    for k in locations():
        assert k.startswith("=") or k.startswith("^~"), k


def test_every_content_location_has_security_headers_and_log():
    for name, body in locations().items():
        if name == "= /getmedia":
            continue
        for h in ("Content-Security-Policy", "X-Content-Type-Options", "Referrer-Policy", "X-Robots-Tag"):
            assert re.search(rf'add_header {h} ".+" always;', body), (name, h)
        assert "access_log /var/log/nginx/getmedia.access.log getmedia;" in body, name
        assert "Access-Control" not in body, name
    csp = re.search(r'Content-Security-Policy "([^"]+)"', locations()["^~ /getmedia/"]).group(1)
    assert "default-src 'self'" in csp and "frame-ancestors 'none'" in csp


def test_api_locations_whitelist_and_proxy_headers():
    for name in ("^~ /getmedia/api/", "= /getmedia/api/v2/feedback"):
        body = locations()[name]
        assert "if ($gm_api_allowed = 0) { return 404; }" in body, name
        assert "rewrite" not in re.sub(r"#.*", "", body), name       # rewrite 早于 limit_req，会让 $gm_auth_key 失效
        assert "proxy_set_header X-Forwarded-For $remote_addr;" in body, name   # 覆盖而非追加
        assert "$proxy_add_x_forwarded_for" not in body, name
        assert "limit_req_status 429;" in body, name                            # 只在本 location 内设置
        assert 'add_header Cache-Control "no-store" always;' in body, name
    assert "proxy_pass http://127.0.0.1:8700/v2/feedback;" in locations()["= /getmedia/api/v2/feedback"]
    assert "proxy_pass http://127.0.0.1:8700/;" in locations()["^~ /getmedia/api/"]
    assert "client_max_body_size 14m;" in locations()["= /getmedia/api/v2/feedback"]
    assert "client_max_body_size 1m;" in locations()["^~ /getmedia/api/"]
    # 不全局设置任何状态码/体积类指令：这些只出现在 location 内（见上），此处确认 snippet 没有 server 级指令
    top = re.sub(r"^location .*?^\}", "", read("nginx/getmedia-prefix.conf"), flags=re.S | re.M)
    assert not re.search(r"^\s*[a-z_]+\s+[^#\n]*;", re.sub(r"#.*", "", top), re.M), "snippet 在 location 之外有指令"


def test_download_location_limits_bandwidth():
    body = locations()["^~ /getmedia/download/"]
    for want in ("limit_conn gm_dl_ip 1;", "limit_conn gm_dl_all 3;", "limit_rate 300k;", "autoindex off;"):
        assert want in body


def test_proxy_target_matches_service_and_env_defaults():
    port = re.search(r"proxy_pass http://127\.0\.0\.1:(\d+)[/;]", read("nginx/getmedia-prefix.conf")).group(1)
    svc = read("systemd/getmedia-api.service")
    assert f"--host 127.0.0.1 --port {port}" in svc
    assert parse_env("env/getmedia.env.example")["GETMEDIA_BIND"] == f"127.0.0.1:{port}"
    for m in re.findall(r"proxy_pass (http://[^;/]+)", read("nginx/getmedia-prefix.conf")):
        assert m == f"http://127.0.0.1:{port}"


def test_443_example_adds_exactly_one_getmedia_line():
    text = read("nginx/443-server-block.example.conf")
    lines = [ln for ln in text.splitlines() if "getmedia" in ln and not ln.strip().startswith("#")]
    assert lines == ["    include /etc/nginx/snippets/getmedia-prefix.conf;          # <—— GetMedia：只加这一行"]


# ---------------------------------------------------------------- logrotate / systemd
def test_logrotate_retention():
    t = read("logrotate/getmedia")
    assert re.search(r"^\s*daily\b", t, re.M) and re.search(r"^\s*rotate 14\b", t, re.M)
    assert re.search(r"^\s*compress\b", t, re.M)
    assert "/var/log/nginx/getmedia.access.log" in t


@pytest.mark.skipif(shutil.which("logrotate") is None, reason="沙箱无 logrotate")
def test_logrotate_config_parses(tmp_path):
    log = tmp_path / "getmedia.access.log"
    log.write_text("x\n")
    conf = tmp_path / "lr.conf"
    conf.write_text(read("logrotate/getmedia").replace("/var/log/nginx/getmedia.access.log", str(log))
                    .replace("create 0640 www-data adm", "create 0640"))
    r = subprocess.run(["logrotate", "-d", "-s", str(tmp_path / "state"), str(conf)], capture_output=True, text=True)
    assert r.returncode == 0, r.stderr


def unit(rel: str) -> configparser.ConfigParser:
    cp = configparser.ConfigParser(strict=False, interpolation=None)
    cp.optionxform = str
    cp.read_string(read(rel))
    return cp


def test_api_service_single_worker_hardened():
    s = unit("systemd/getmedia-api.service")["Service"]
    assert "--workers 1" in s["ExecStart"] and "--factory" in s["ExecStart"] and "--no-access-log" in s["ExecStart"]
    assert s["User"] == "getmedia" and s["NoNewPrivileges"] == "true" and s["ProtectSystem"] == "strict"
    assert s["StateDirectory"] == "getmedia getmedia-ledger" and s["StateDirectoryMode"] == "0700"
    assert "EnvironmentFile=-/etc/getmedia/feedback.env" in read("systemd/getmedia-api.service")


def test_snapshot_timer_persistent_and_service_runs_cli():
    s = unit("systemd/getmedia-snapshot.service")["Service"]
    assert s["Type"] == "oneshot" and s["ExecStart"].endswith("-m getmedia.cli snapshot")
    t = unit("systemd/getmedia-snapshot.timer")
    assert t["Timer"]["Persistent"] == "true" and t["Install"]["WantedBy"] == "timers.target"


def test_cert_units_not_auto_started():
    # service 没有 [Install]；timer 的 [Install] 只是"可被 enable"，交接前是否 enable 由手册控制
    assert "[Install]" not in read("cert/getmedia-cert-renew.service")
    assert "交接前不得" in read("cert/getmedia-cert-renew.timer")


def test_scripts_are_executable_and_posix_sh():
    for rel in ("bin/getmedia-admin", "bin/probe-server.sh", "bin/verify-deploy.sh", "cert/getmedia-cert-renew.sh"):
        p = DEPLOY / rel
        assert p.stat().st_mode & stat.S_IXUSR, rel
        assert p.read_text().startswith("#!/bin/sh"), rel
        assert subprocess.run(["sh", "-n", str(p)]).returncode == 0, rel


def test_probe_script_is_read_only():
    t = read("bin/probe-server.sh")
    code = "\n".join(ln for ln in t.splitlines() if not ln.lstrip().startswith("#"))
    for bad in (" rm ", "mv ", "chmod", "chown", "systemctl restart", "systemctl reload", "systemctl start", "systemctl enable",
                "tee ", " > /", ">> /", "apt", "pip "):
        assert bad not in code, bad


# ---------------------------------------------------------------- env / JSON 示例
KEY_OK = "k" * 40


def test_getmedia_env_example_loads_and_placeholder_key_is_rejected():
    env = parse_env("env/getmedia.env.example")
    with pytest.raises(ConfigError):                       # 原样拷贝（占位密钥过短）必须拒绝启动
        load_settings(env)
    s = load_settings({**env, "GETMEDIA_SECRET_KEY": KEY_OK})
    assert str(s.database_path) == "/var/lib/getmedia/getmedia.db"
    assert str(s.runtime_file) == "/var/lib/getmedia/runtime.json"       # 不在只读的 /etc 下（CLI 要改写它）
    assert not str(s.runtime_file).startswith("/etc/")
    assert str(s.ledger_path).startswith("/var/lib/getmedia-ledger/")    # 台账在快照目录之外
    assert not str(s.ledger_path).startswith(str(s.backup_dir))
    assert s.bind == "127.0.0.1:8700"
    known = {"GETMEDIA_SECRET_KEY", "GETMEDIA_DATA_DIR", "GETMEDIA_DATABASE_URL", "GETMEDIA_BACKUP_DIR", "GETMEDIA_FEEDBACK_IMAGE_DIR",
             "GETMEDIA_LEDGER_PATH", "GETMEDIA_LEGAL_DIR", "GETMEDIA_RELEASE_FILE", "GETMEDIA_RUNTIME_FILE", "GETMEDIA_BIND",
             "GETMEDIA_CERT_PATH", "GETMEDIA_DISK_PATH"}
    assert set(env) == known


def test_feedback_env_example_has_exactly_the_seven_variables():
    env = parse_env("env/feedback.env.example")
    assert set(env) == {"FEEDBACK_MAIL_TO", "FEEDBACK_MAIL_FROM", "FEEDBACK_SMTP_HOST", "FEEDBACK_SMTP_PORT",
                        "FEEDBACK_SMTP_SECURITY", "FEEDBACK_SMTP_USER", "FEEDBACK_SMTP_PASSWORD"}
    assert load_mail(env) is not None
    for k in ("FEEDBACK_MAIL_TO", "FEEDBACK_MAIL_FROM", "FEEDBACK_SMTP_USER", "FEEDBACK_SMTP_PASSWORD"):
        assert env[k] == "CHANGE_ME", k                      # 不放任何像真实邮箱/账号的值
    assert env["FEEDBACK_SMTP_HOST"].endswith(".example.invalid")


def test_json_examples_are_accepted_by_the_server_loaders(tmp_path):
    rt = tmp_path / "runtime.json"
    rt.write_text(read("env/runtime.json.example"))
    r = RuntimeStore(rt).get()
    assert r.registration_open is False and r.maintenance is None       # 默认关闭注册（上线前准备期）
    rel = tmp_path / "release.json"
    rel.write_text(read("env/release.json.example"))
    d = ReleaseStore(rel).get()
    assert d["min_version"] == "0.1.0" and d["latest"]["apk"].startswith("https://") and d["latest"]["sha256"]


# ---------------------------------------------------------------- 证书续期脚本（用桩替身跑真实脚本）
pytestmark_cert = pytest.mark.skipif(shutil.which("flock") is None or shutil.which("openssl") is None, reason="需要 flock 与 openssl")


def make_cert(path: Path, hours_left: float, serial: int = 1) -> None:
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "t")])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(serial).not_valid_before(now - timedelta(hours=1))
            .not_valid_after(now + timedelta(hours=hours_left)).sign(key, hashes.SHA256()))
    path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))


class Renew:
    """在临时目录里搭好桩：certbot / nginx / systemctl / getmedia-admin 都只记录调用。"""

    def __init__(self, tmp: Path, hours_left: float):
        self.tmp = tmp
        self.bin = tmp / "bin"
        self.bin.mkdir()
        self.calls = tmp / "calls.log"
        self.cert = tmp / "fullchain.pem"
        self.fresh = tmp / "fresh.pem"
        make_cert(self.cert, hours_left, 1)
        make_cert(self.fresh, 160, 2)
        self.stub("certbot", 'echo "certbot $*" >> "$CALLS"\n[ "${CERTBOT_FAIL:-0}" = 1 ] && exit 1\n'
                             '[ "${CERTBOT_NOOP:-0}" = 1 ] && exit 0\ncase "$*" in *--dry-run*) exit 0;; esac\ncp "$FRESH" "$CERT"\n')
        self.stub("nginx", 'echo "nginx $*" >> "$CALLS"\n[ "${NGINX_FAIL:-0}" = 1 ] && exit 1\nexit 0\n')
        self.stub("systemctl", 'echo "systemctl $*" >> "$CALLS"\nexit 0\n')
        self.stub("admin", 'echo "admin $*" >> "$CALLS"\nexit 0\n')
        (tmp / "cert-renew.env").write_text(textwrap.dedent(f"""\
            CERT_FILE={self.cert}
            CERT_NAME=PLACEHOLDER
            RENEW_BEFORE_HOURS=56
            CERTBOT_BIN={self.bin}/certbot
            ADMIN_BIN={self.bin}/admin
            LOCK_FILE={tmp}/renew.lock
        """))

    def stub(self, name: str, body: str) -> None:
        p = self.bin / name
        p.write_text("#!/bin/sh\n" + body)
        p.chmod(0o755)

    def run(self, *args, **env):
        e = {**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}", "CALLS": str(self.calls), "FRESH": str(self.fresh),
             "CERT": str(self.cert), "GETMEDIA_CERT_ENV": str(self.tmp / "cert-renew.env"), **{k: str(v) for k, v in env.items()}}
        r = subprocess.run(["sh", str(DEPLOY / "cert/getmedia-cert-renew.sh"), *args], env=e, capture_output=True, text=True)
        calls = self.calls.read_text().splitlines() if self.calls.exists() else []
        return r, calls


@pytest.fixture
def renew_factory(tmp_path):
    n = [0]

    def make(hours_left):
        n[0] += 1
        d = tmp_path / f"r{n[0]}"
        d.mkdir()
        return Renew(d, hours_left)
    return make


@pytestmark_cert
def test_renew_skipped_when_plenty_of_validity_left(renew_factory):
    r = renew_factory(100)
    res, calls = r.run()
    assert res.returncode == 0 and calls == [] and "无需续期" in res.stdout


@pytestmark_cert
def test_renew_success_reloads_nginx_after_config_check(renew_factory):
    r = renew_factory(10)
    res, calls = r.run()
    assert res.returncode == 0, res.stdout + res.stderr
    assert any(c.startswith("certbot renew --cert-name PLACEHOLDER --force-renewal") for c in calls)
    assert calls.index("nginx -t") < calls.index("systemctl reload nginx")
    assert not any(c.startswith("admin") for c in calls)


@pytestmark_cert
def test_renew_dry_run_changes_nothing(renew_factory):
    r = renew_factory(10)
    before = r.cert.read_bytes()
    res, calls = r.run("--dry-run")
    assert res.returncode == 0 and r.cert.read_bytes() == before
    assert any("--dry-run" in c for c in calls) and not any("reload" in c for c in calls)
    assert "续期演练" in res.stdout


@pytestmark_cert
def test_renew_force_ignores_remaining_validity(renew_factory):
    r = renew_factory(100)
    res, calls = r.run("--force")
    assert res.returncode == 0 and any("--force-renewal" in c for c in calls) and "systemctl reload nginx" in calls


@pytestmark_cert
@pytest.mark.parametrize("env,why", [
    ({"CERTBOT_FAIL": 1}, "ACME 续期命令失败"),
    ({"CERTBOT_NOOP": 1}, "有效期没有变化"),
    ({"NGINX_FAIL": 1}, "nginx -t 未通过"),
])
def test_renew_failures_alert_and_never_reload_broken_state(renew_factory, env, why):
    r = renew_factory(10)
    res, calls = r.run(**env)
    assert res.returncode == 1 and why in res.stdout
    alerts = [c for c in calls if c.startswith("admin alert cert_renew_failed")]
    assert len(alerts) == 1 and why in alerts[0]
    if "NGINX_FAIL" in env:
        assert "systemctl reload nginx" not in calls     # 配置检查失败不 reload，保留正在运行的旧配置


@pytestmark_cert
def test_renew_refuses_concurrent_instance(renew_factory, tmp_path):
    r = renew_factory(10)
    lock = r.tmp / "renew.lock"
    holder = subprocess.Popen(["flock", str(lock), "sleep", "5"])
    try:
        import time
        time.sleep(0.3)
        res, calls = r.run()
        assert res.returncode == 0 and calls == [] and "另一个续期实例" in res.stdout
    finally:
        holder.kill()
        holder.wait()
