"""P2e：平台授权（Cookie 导入、加密保存、绑定/解绑）。"""
from __future__ import annotations

import shutil
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from conftest import hdr, make_owner
from getmedia.alerts import RecordingAlertSink
from getmedia.api import create_app
from getmedia.config import testing_settings as make_settings

ROOT = Path(__file__).resolve().parents[2]
GOOD_BILI = "SESSDATA=abc123%2Cdef; bili_jct=xyz; DedeUserID=42"
GOOD_DY = "sessionid=deadbeef; ttwid=tt123; passport_csrf_token=zz"


def start(env, token, platform="bilibili", method="webview"):
    return env.client.post("/account/platform-auth/start", headers=hdr(token),
                           json={"platform": platform, "method": method})


def test_platforms_and_empty_bindings(open_reg):
    token = make_owner(open_reg, name="boss")
    body = open_reg.client.get("/account/platforms", headers=hdr(token)).json()
    assert {p["platform"] for p in body["platforms"]} == {"bilibili", "douyin"}
    assert body["oauth_configured"] is False
    assert open_reg.client.get("/account/platform-bindings", headers=hdr(token)).json()["bindings"] == []


def test_import_cookie_binds_and_writes_file(open_reg):
    token = make_owner(open_reg, name="boss")
    uid = open_reg.client.get("/account/me", headers=hdr(token)).json()["id"]
    sid = start(open_reg, token).json()["session_id"]
    r = open_reg.client.post("/account/platform-auth/cookie", headers=hdr(token),
                             json={"session_id": sid, "cookie": GOOD_BILI})
    assert r.status_code == 200, r.text
    assert r.json()["bound"] is True and r.json()["cookie_count"] == 3
    assert "SESSDATA" not in r.text  # 响应不回显 Cookie

    b = open_reg.client.get("/account/platform-bindings", headers=hdr(token)).json()
    assert b["bindings"][0]["platform"] == "bilibili"
    assert b["bindings"][0]["source"] == "webview"
    assert b["credentials"][0]["decryptable"] is True

    # 按用户隔离：写的是 <platform>.<user_id>.txt，不再覆盖全局 <platform>.txt
    p = Path(open_reg.settings.creator_cookie_dir) / f"bilibili.{uid}.txt"
    assert p.is_file()
    assert oct(p.stat().st_mode & 0o777) == "0o600"
    text = p.read_text()
    assert "SESSDATA\tabc123%2Cdef" in text

    st = open_reg.client.post("/account/platform-auth/status", headers=hdr(token),
                              json={"session_id": sid}).json()
    assert st["status"] == "done"


def test_cookie_validation(open_reg):
    token = make_owner(open_reg, name="boss")
    sid = start(open_reg, token).json()["session_id"]
    r = open_reg.client.post("/account/platform-auth/cookie", headers=hdr(token),
                             json={"session_id": sid, "cookie": "foo=bar"})
    assert r.status_code == 400 and r.json()["code"] == "missing_cookie"

    r = open_reg.client.post("/account/platform-auth/cookie", headers=hdr(token),
                             json={"session_id": sid, "cookie": "not a cookie"})
    assert r.status_code == 400 and r.json()["code"] == "cookie_unparsable"

    dsid = start(open_reg, token, platform="douyin", method="cookie").json()["session_id"]
    ok = open_reg.client.post("/account/platform-auth/cookie", headers=hdr(token),
                              json={"session_id": dsid, "cookie": GOOD_DY})
    assert ok.status_code == 200 and ok.json()["platform"] == "douyin"


def test_unbind_and_cancel(open_reg):
    token = make_owner(open_reg, name="boss")
    uid = open_reg.client.get("/account/me", headers=hdr(token)).json()["id"]
    sid = start(open_reg, token).json()["session_id"]
    open_reg.client.post("/account/platform-auth/cookie", headers=hdr(token),
                         json={"session_id": sid, "cookie": GOOD_BILI})
    assert open_reg.client.post("/account/platform-bindings/bilibili/unbind",
                                headers=hdr(token)).status_code == 200
    assert open_reg.client.post("/account/platform-bindings/bilibili/unbind",
                                headers=hdr(token)).status_code == 404
    assert not (Path(open_reg.settings.creator_cookie_dir) / f"bilibili.{uid}.txt").exists()

    sid2 = start(open_reg, token).json()["session_id"]
    assert open_reg.client.post("/account/platform-auth/cancel", headers=hdr(token),
                                json={"session_id": sid2}).json()["status"] == "cancelled"
    r = open_reg.client.post("/account/platform-auth/cookie", headers=hdr(token),
                             json={"session_id": sid2, "cookie": GOOD_BILI})
    assert r.status_code == 409


def test_douyin_callback_is_anonymous_501(open_reg):
    r = open_reg.client.get("/account/platform-auth/douyin/callback?code=x&state=y")
    assert r.status_code == 501
    assert r.json()["code"] == "oauth_unconfigured"


def test_vault_unavailable_returns_503(tmp_path):
    legal = tmp_path / "legal"
    legal.mkdir()
    for f in ("user-agreement.md", "privacy-policy.md"):
        shutil.copy(ROOT / "docs" / "legal" / f, legal / f)
    settings = make_settings(tmp_path, legal).with_(credential_key=b"")
    app = create_app(settings, clock=lambda: 1_800_000_000.0, alerts=RecordingAlertSink())
    app.state.runtime.write(registration_open=True)
    client = TestClient(app, base_url="https://testserver")

    body = {"login_name": "boss", "password": "correct horse 1", "age_confirmed": True,
            "consent": {k: {"version": v["version"], "sha256": v["sha256"]}
                        for k, v in client.get("/v2/legal").json()["documents"].items()},
            "client": {"channel": "android"}}
    token = client.post("/account/register", json=body).json()["session"]["token"]
    from sqlalchemy import text
    with app.state.engine.begin() as c:
        c.execute(text("UPDATE users SET tier='owner'"))
    sid = client.post("/account/platform-auth/start", headers=hdr(token),
                      json={"platform": "bilibili", "method": "cookie"}).json()["session_id"]
    r = client.post("/account/platform-auth/cookie", headers=hdr(token),
                    json={"session_id": sid, "cookie": GOOD_BILI})
    assert r.status_code == 503 and r.json()["code"] == "vault_unavailable"
