import io
import json
import shutil
import sqlite3
from pathlib import Path

import pytest

from conftest import hdr, login, register
from getmedia.cli import main as cli
from getmedia.config import MailConfig

PW = "correct horse 1"


def run(env, *argv):
    out = io.StringIO()
    rc = cli(list(argv), env.settings, out)
    return rc, out.getvalue()


def q(env, sql, *a):
    with env.app.state.engine.begin() as c:
        return c.exec_driver_sql(sql, a).fetchall()


@pytest.fixture
def u(open_reg):
    r = register(open_reg)
    return open_reg, r.json()["session"]["token"]


def test_delete_requires_password_and_revokes(u):
    env, tok = u
    assert env.client.request("DELETE", "/account/me", json={"password": "wrong password 9"}, headers=hdr(tok)).status_code == 401
    r = env.client.request("DELETE", "/account/me", json={"password": PW}, headers=hdr(tok))
    assert r.status_code == 200 and r.json()["delete_at"] == int(env.clock()) + 7 * 86400
    assert env.client.get("/account/me", headers=hdr(tok)).status_code == 401
    assert login(env).status_code == 401


def test_cooldown_then_physical_delete_with_ledger(u):
    env, tok = u
    uid = env.client.get("/account/me", headers=hdr(tok)).json()["id"]
    env.client.post("/v2/feedback", data={"text": "hi", "idempotency_key": "key-00000001"}, headers=hdr(tok))
    env.client.request("DELETE", "/account/me", json={"password": PW}, headers=hdr(tok))
    ops = env.app.state.ops
    env.clock.advance(6 * 86400)
    assert ops.run_deletions() == 0 and q(env, "select count(*) from users")[0][0] == 1
    env.clock.advance(2 * 86400)
    assert ops.run_deletions() == 1
    assert q(env, "select count(*) from users")[0][0] == 0
    assert q(env, "select count(*) from feedback")[0][0] == 0
    assert q(env, "select count(*) from sessions")[0][0] == 0
    assert uid in ops.ledger_ids() and q(env, "select user_id from deletion_ledger")[0][0] == uid
    assert ops.run_deletions() == 0
    assert register(env).status_code == 201  # 登录名可再次使用


def test_cli_restore_account_in_cooldown(u):
    env, tok = u
    env.client.request("DELETE", "/account/me", json={"password": PW}, headers=hdr(tok))
    assert run(env, "restore-account", "alice")[0] == 0
    assert login(env).status_code == 200
    env.clock.advance(10 * 86400)
    assert env.app.state.ops.run_deletions() == 0


def test_cleanup_retention(u):
    env, tok = u
    env.client.post("/v2/heartbeat", json={"client": {"channel": "android"}}, headers=hdr(tok))
    env.clock.advance(31 * 86400)
    out = env.app.state.ops.cleanup()
    assert out["audit"] > 0 and out["rate_limits"] > 0 and out["heartbeats"] == 1
    assert q(env, "select count(*) from audit")[0][0] == 0
    assert q(env, "select count(*) from daily_stats")[0][0] >= 1  # 只剩数字，长期保留


def test_snapshot_rotation_and_permissions(u):
    env, _ = u
    ops = env.app.state.ops
    made = []
    for _i in range(9):
        env.clock.advance(60)
        p = ops.snapshot()
        made.append(p)
    left = ops._snaps()
    assert len(left) == 7 and all(ops.verify(p) for p in left)
    assert oct(env.settings.backup_dir.stat().st_mode & 0o777) == "0o700"
    assert oct(left[0].stat().st_mode & 0o777) == "0o600"
    assert run(env, "verify-backup")[0] == 0


def test_snapshot_failure_alerts(u, monkeypatch):
    env, _ = u
    import getmedia.ops as o
    monkeypatch.setattr(o, "snapshot_file", lambda *a: (_ for _ in ()).throw(OSError("disk")))
    assert env.app.state.ops.snapshot() is None
    assert any(k == "snapshot_failed" for k, _ in env.alerts.items)


def test_restore_replays_ledger(u, tmp_path):
    env, tok = u
    ops = env.app.state.ops
    uid = env.client.get("/account/me", headers=hdr(tok)).json()["id"]
    snap = ops.snapshot()  # 快照里还有该账号
    env.client.request("DELETE", "/account/me", json={"password": PW}, headers=hdr(tok))
    env.clock.advance(8 * 86400)
    ops.run_deletions()
    target = tmp_path / "restored" / "r.db"
    assert ops.restore(snap, target) == 1
    c = sqlite3.connect(target)
    assert c.execute("select count(*) from users where id=?", (uid,)).fetchone()[0] == 0
    with pytest.raises(FileExistsError):
        ops.restore(snap, target)


def test_cli_accounts_owner_invite_registration(open_reg):
    env = open_reg
    rc, out = run(env, "create-account", "boss", "--owner", "--password", PW)
    assert rc == 0 and "恢复码" in out
    assert login(env, "boss").json()["account"]["tier"] == "owner"
    tok = login(env, "boss").json()["session"]["token"]
    assert run(env, "disable", "boss")[0] == 0
    assert env.client.get("/account/me", headers=hdr(tok)).status_code == 401
    assert login(env, "boss").status_code == 401
    run(env, "enable", "boss")
    assert login(env, "boss").status_code == 200
    run(env, "unset-owner", "boss")
    assert login(env, "boss").json()["account"]["tier"] == "free"
    assert run(env, "create-account", "boss", "--password", PW)[0] == 1
    assert run(env, "registration", "close")[0] == 0 and register(env, "newbie").status_code == 403
    code = run(env, "invite")[1].strip()
    assert register(env, "newbie", invite_code=code).status_code == 201
    rc, out = run(env, "stats")
    assert "注册总数：2" in out
    assert "login_ok" in run(env, "audit")[1]


def test_alert_mail_dedup_per_day(u):
    from getmedia.alerts import MailAlertSink
    env, _ = u
    sent = []
    cfg = MailConfig("to@example.invalid", "f@example.invalid", "h.example.invalid", 465, "ssl", "u", "p")
    sink = MailAlertSink(env.app.state.engine, env.settings.with_(mail=cfg), env.clock, sent.append)
    sink.notify("disk_high", "x"); sink.notify("disk_high", "y"); sink.notify("cert_expiring", "z")
    assert [m["Subject"] for m in sent] == ["[GetMedia 告警] disk_high", "[GetMedia 告警] cert_expiring"]
    env.clock.advance(86400)
    sink.notify("disk_high", "again")
    assert len(sent) == 3


def test_cert_and_disk_checks(u, tmp_path):
    from datetime import datetime, timedelta, timezone
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    env, _ = u
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "x")])
    nb = datetime.fromtimestamp(env.clock(), timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(1).not_valid_before(nb - timedelta(days=1)).not_valid_after(nb + timedelta(hours=10))
            .sign(key, hashes.SHA256()))
    pem = tmp_path / "c.pem"
    pem.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    ops = env.app.state.ops
    ops.s = env.settings.with_(cert_path=pem)
    assert ops.health_checks() == ["cert"]
    assert any(k == "cert_expiring" for k, _ in env.alerts.items)
    ops.s = env.settings.with_(cert_path=pem, disk_alert_pct=0)
    assert "disk" in ops.health_checks()


# ---------- 部署辅助命令：alert / mail-test ----------

def _mail_env(open_reg):
    cfg = MailConfig("to@example.invalid", "f@example.invalid", "h.example.invalid", 465, "ssl", "u", "p")
    open_reg.settings = open_reg.settings.with_(mail=cfg)
    return open_reg


def test_cli_alert_dedup_and_validation(open_reg, monkeypatch):
    from getmedia.feedback import FeedbackService
    sent = []
    monkeypatch.setattr(FeedbackService, "mailer", lambda self, msg: sent.append(msg))
    env = _mail_env(open_reg)
    assert run(env, "alert", "cert_renew_failed", "退出码 1")[0] == 0
    assert run(env, "alert", "cert_renew_failed", "退出码 1")[0] == 0   # 同类同日第二次不再发
    assert [m["Subject"] for m in sent] == ["[GetMedia 告警] cert_renew_failed"]
    assert run(env, "alert", "Bad Kind\r\nBcc: x@example.invalid")[0] == 1  # 类型含空白/换行 → 拒绝
    assert len(sent) == 1


def test_cli_mail_test_success_failure_and_unconfigured(open_reg, monkeypatch):
    import smtplib
    from getmedia.feedback import FeedbackService
    assert run(open_reg, "mail-test")[0] == 1            # 未配置邮件
    env = _mail_env(open_reg)
    sent = []
    monkeypatch.setattr(FeedbackService, "mailer", lambda self, msg: sent.append(msg))
    rc, out = run(env, "mail-test")
    assert rc == 0 and sent and sent[0]["Subject"].startswith("[GetMedia 反馈]")

    def boom(self, msg):
        raise smtplib.SMTPAuthenticationError(535, b"bad login for to@example.invalid with p")
    monkeypatch.setattr(FeedbackService, "mailer", boom)
    import contextlib
    err = io.StringIO()
    with contextlib.redirect_stderr(err):
        rc, out = run(env, "mail-test")
    assert rc == 1
    shown = err.getvalue() + out
    assert "SMTPAuthenticationError" in shown and "535" in shown
    assert "example.invalid" not in shown and "bad login" not in shown   # 不泄露收件地址/服务器回复原文


def test_cli_invite_required_toggle_while_registration_open(open_reg):
    env = open_reg
    assert register(env, "pub1").status_code == 201                    # 开放且不要求邀请码：直接注册
    rc, out = run(env, "invite-required", "on")
    assert rc == 0 and "开启" in out and "注册开放" in out
    assert register(env, "pub2").status_code == 403                    # 开放但要求邀请码：无码被拒
    code = run(env, "invite")[1].strip()
    assert register(env, "pub2", invite_code=code).status_code == 201
    assert register(env, "pub3", invite_code=code).status_code == 403  # 邀请码一次性
    assert run(env, "invite-required", "off")[0] == 0
    assert register(env, "pub3").status_code == 201                    # 关闭后恢复"开放即可注册"
