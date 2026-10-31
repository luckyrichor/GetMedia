import json

import pytest

from conftest import consent_body, hdr, login, register


def test_register_success_and_recovery_code_shape(open_reg):
    r = register(open_reg)
    assert r.status_code == 201
    j = r.json()
    assert j["account"]["tier"] == "free" and j["account"]["login_name"] == "alice"
    assert len(j["recovery_code"].replace("-", "")) == 16
    assert j["session"]["token"] and j["session"]["device_id"].startswith("dev_")
    me = open_reg.client.get("/account/me", headers=hdr(j["session"]["token"])).json()
    assert me["consent_required"] is False and me["recovery_confirmed"] is False


def test_registration_closed_by_default(env):
    r = register(env)
    assert r.status_code == 403 and r.json()["code"] == "registration_closed"


def test_closed_registration_hides_name_taken(env):
    code = env.svc.create_invite()
    assert register(env, invite_code=code).status_code == 201
    r = register(env, name="alice")  # 已存在，且注册关闭、无邀请码
    assert r.json()["code"] == "registration_closed"


def test_invite_single_use_and_open_with_invite_required(env):
    code = env.svc.create_invite("t")
    assert register(env, name="bob1", invite_code=code).status_code == 201
    r = register(env, name="bob2", invite_code=code)
    assert r.status_code == 403
    env.app.state.runtime.write(registration_open=True, invite_required=True)
    r = register(env, name="bob3", invite_code="WRONG-WRONG-WRONG")
    assert r.json()["code"] == "invite_invalid"
    assert register(env, name="bob4", invite_code=env.svc.create_invite()).status_code == 201


def test_invite_not_consumed_when_name_taken(open_reg):
    env = open_reg
    register(env, name="carol")
    env.app.state.runtime.write(registration_open=False)
    code = env.svc.create_invite()
    assert register(env, name="carol", invite_code=code).status_code == 409
    assert register(env, name="carol2", invite_code=code).status_code == 201


@pytest.mark.parametrize("name", ["ab", "a" * 33, "Has Space", "中文名字", "a/b", "13800138000", "138-0013-8000"])
def test_invalid_login_names(open_reg, name):
    r = register(open_reg, name=name)
    assert r.status_code == 422 and r.json()["code"] == "invalid_login_name"
    assert name not in r.text


def test_name_normalization_makes_duplicates(open_reg):
    assert register(open_reg, name="alice").status_code == 201
    assert register(open_reg, name="ALICE").status_code == 409
    assert register(open_reg, name="ａｌｉｃｅ").status_code == 409  # 全角 NFKC → alice


def test_age_and_weak_password(open_reg):
    r = register(open_reg, age_confirmed=False)
    assert r.json()["code"] == "age_not_confirmed"
    r = register(open_reg, password="short")
    assert r.json()["code"] == "weak_password" and "short" not in r.text
    assert register(open_reg, name="samepass1", password="samepass1").json()["code"] == "weak_password"


def test_consent_must_match_current_legal(open_reg):
    bad = consent_body(open_reg)
    bad["privacy_policy"]["sha256"] = "0" * 64
    r = register(open_reg, consent=bad)
    assert r.status_code == 409 and r.json()["code"] == "legal_outdated"
    bad = consent_body(open_reg)
    bad["user_agreement"]["version"] = 99
    assert register(open_reg, consent=bad).json()["code"] == "legal_outdated"


def test_validation_error_does_not_echo(open_reg):
    r = open_reg.client.post("/account/register", json={"login_name": "secret-value-xyz", "password": 5})
    assert r.status_code == 422 and "secret-value-xyz" not in r.text and r.json()["code"] == "validation_error"


# ---------- 登录 ----------
def test_login_ok_and_generic_failure(open_reg):
    register(open_reg)
    ok = login(open_reg)
    assert ok.status_code == 200 and ok.json()["consent_required"] is False
    bad_pw = login(open_reg, password="wrong password 1")
    unknown = login(open_reg, name="nobody")
    assert bad_pw.status_code == unknown.status_code == 401
    assert bad_pw.json() == unknown.json()


def test_login_always_hashes_once(open_reg, monkeypatch):
    register(open_reg)
    h = open_reg.app.state.svc.hasher
    calls = []
    real = h.verify
    monkeypatch.setattr(h, "verify", lambda s, p: (calls.append(1), real(s, p))[1])
    login(open_reg, name="nobody")
    login(open_reg, password="wrong password 1")
    login(open_reg)
    assert len(calls) == 3


def test_deactivated_user_cannot_login_and_session_dies(open_reg):
    j = register(open_reg).json()
    with open_reg.app.state.engine.begin() as c:
        c.exec_driver_sql("UPDATE users SET status='deactivated'")
    assert login(open_reg).status_code == 401
    assert open_reg.client.get("/account/me", headers=hdr(j["session"]["token"])).status_code == 401


def test_logout_revokes_immediately(open_reg):
    t = register(open_reg).json()["session"]["token"]
    assert open_reg.client.post("/account/logout", headers=hdr(t)).status_code == 204
    assert open_reg.client.get("/account/me", headers=hdr(t)).status_code == 401


def test_unauthenticated_and_bad_token(open_reg):
    assert open_reg.client.get("/account/me").status_code == 401
    assert open_reg.client.get("/account/me", headers=hdr("nope")).status_code == 401


# ---------- 限流 ----------
def test_login_ip_limit_and_retry_after(open_reg):
    register(open_reg)
    codes = [login(open_reg, name=f"u{i}").status_code for i in range(10)]
    assert set(codes) == {401}
    r = login(open_reg, name="u11")
    assert r.status_code == 429 and int(r.headers["Retry-After"]) >= 1


def test_login_name_failure_limit_blocks_even_correct_password(open_reg):
    register(open_reg)
    # 避开 IP 限额：每 5 次换一个 5 分钟窗口
    for i in range(5):
        assert login(open_reg, password="wrong password 1").status_code == 401
    open_reg.clock.advance(301)
    r = login(open_reg)
    assert r.status_code == 429
    open_reg.clock.advance(900)
    assert login(open_reg).status_code == 200


def test_rate_limits_survive_restart(open_reg):
    from getmedia.api import create_app
    from fastapi.testclient import TestClient
    register(open_reg)
    for _ in range(5):
        login(open_reg, password="wrong password 1")
    app2 = create_app(open_reg.settings, clock=open_reg.clock, alerts=open_reg.alerts)
    open_reg.client = TestClient(app2, base_url="https://testserver")
    assert login(open_reg).status_code == 429


def test_register_ip_limit(open_reg):
    for i in range(5):
        register(open_reg, name=f"user{i}x")
    assert register(open_reg, name="user9x").status_code == 429


# ---------- 设备 ----------
def test_app_device_limit_and_evict(open_reg):
    register(open_reg)
    tokens = []
    for i in range(4):
        r = login(open_reg, device=f"d{i}")
        assert r.status_code == 200, r.text
        tokens.append(r.json()["session"]["token"])
        open_reg.clock.advance(301)  # 避开 IP 限流窗口
    r = login(open_reg, device="d-extra")
    assert r.status_code == 409 and r.json()["code"] == "device_limit"
    devices = r.json()["devices"]
    assert len(devices) == 5
    open_reg.clock.advance(301)
    victim = devices[-1]["id"]
    r = login(open_reg, device="d-extra", evict_device_id=victim)
    assert r.status_code == 200
    assert len(open_reg.client.get("/account/devices", headers=hdr(r.json()["session"]["token"])).json()["devices"]) == 5


def test_web_device_limit_is_three(open_reg):
    register(open_reg, channel="web")
    for _ in range(2):
        assert login(open_reg, channel="web").status_code == 200
        open_reg.clock.advance(301)
    assert login(open_reg, channel="web").status_code == 409
    open_reg.clock.advance(301)
    assert login(open_reg, channel="android").status_code == 200  # 不同渠道各自计数


def test_cannot_touch_other_users_device(open_reg):
    a = register(open_reg, name="alice1").json()
    b = register(open_reg, name="bobby1").json()
    r = open_reg.client.delete(f"/account/devices/{a['session']['device_id']}", headers=hdr(b["session"]["token"]))
    assert r.status_code == 404
    assert open_reg.client.get("/account/me", headers=hdr(a["session"]["token"])).status_code == 200
    r = open_reg.client.delete("/account/devices/dev_doesnotexist", headers=hdr(b["session"]["token"]))
    assert r.status_code == 404


def test_revoke_other_devices(open_reg):
    t1 = register(open_reg).json()["session"]["token"]
    open_reg.clock.advance(301)
    t2 = login(open_reg).json()["session"]["token"]
    r = open_reg.client.post("/account/devices/revoke-others", headers=hdr(t2))
    assert r.json()["revoked"] == 1
    assert open_reg.client.get("/account/me", headers=hdr(t1)).status_code == 401
    assert open_reg.client.get("/account/me", headers=hdr(t2)).status_code == 200


# ---------- 会话有效期 ----------
def test_sliding_expiry_and_hard_expiry(open_reg):
    t = register(open_reg).json()["session"]["token"]
    day = 86400
    for _ in range(3):  # 每 60 天使用一次，累计 180 天仍有效（滑动）
        open_reg.clock.advance(60 * day)
        assert open_reg.client.get("/account/me", headers=hdr(t)).status_code == 200
    open_reg.clock.advance(91 * day)
    assert open_reg.client.get("/account/me", headers=hdr(t)).status_code == 401


def test_web_session_14_days(open_reg):
    r = register(open_reg, channel="web")
    exp = r.json()["session"]["expires_at"]
    assert exp - int(open_reg.clock()) == 14 * 86400


# ---------- 恢复 / 改密 / 同意 ----------
def test_recovery_flow(open_reg):
    j = register(open_reg).json()
    t = j["session"]["token"]
    assert open_reg.client.post("/account/recovery/ack", headers=hdr(t)).status_code == 204
    assert open_reg.client.get("/account/me", headers=hdr(t)).json()["recovery_confirmed"] is True
    old_code = j["recovery_code"]
    r = open_reg.client.post("/account/recover", json={"login_name": "alice", "recovery_code": old_code.lower(), "new_password": "brand new pass 2"})
    assert r.status_code == 200
    new_code = r.json()["recovery_code"]
    assert new_code != old_code
    assert open_reg.client.get("/account/me", headers=hdr(t)).status_code == 401  # 全部会话失效
    assert login(open_reg, password="correct horse 1").status_code == 401
    ok = login(open_reg, password="brand new pass 2")
    assert ok.status_code == 200 and ok.json()["recovery_confirmed"] is False
    r = open_reg.client.post("/account/recover", json={"login_name": "alice", "recovery_code": old_code, "new_password": "another pass 3"})
    assert r.status_code == 401 and r.json()["code"] == "invalid_recovery"


def test_recovery_failure_is_generic_and_limited(open_reg):
    register(open_reg)
    body = lambda n: {"login_name": n, "recovery_code": "AAAA-BBBB-CCCC-DDDD", "new_password": "brand new pass 2"}
    a = open_reg.client.post("/account/recover", json=body("alice"))
    b = open_reg.client.post("/account/recover", json=body("nobody"))
    assert a.status_code == b.status_code == 401 and a.json() == b.json()
    for _ in range(4):  # 已用 1 次 + 4 次 = 5 次，仍允许；第 6 次起限流
        assert open_reg.client.post("/account/recover", json=body("alice")).status_code in (401,)
    assert open_reg.client.post("/account/recover", json=body("alice")).status_code == 429  # 同账号每小时 5 次


def test_change_password(open_reg):
    t1 = register(open_reg).json()["session"]["token"]
    open_reg.clock.advance(301)
    t2 = login(open_reg).json()["session"]["token"]
    r = open_reg.client.post("/account/password", headers=hdr(t2), json={"old_password": "nope nope 12", "new_password": "fresh pass 123"})
    assert r.status_code == 401
    r = open_reg.client.post("/account/password", headers=hdr(t2), json={"old_password": "correct horse 1", "new_password": "fresh pass 123"})
    assert r.status_code == 204
    assert open_reg.client.get("/account/me", headers=hdr(t1)).status_code == 401
    assert open_reg.client.get("/account/me", headers=hdr(t2)).status_code == 200


# ---------- 网页 Cookie ----------
def test_web_cookie_attributes_and_no_token_in_body(open_reg):
    r = register(open_reg, channel="web")
    assert "token" not in r.json()["session"]
    sc = r.headers["set-cookie"]
    assert sc.startswith("__Secure-gm_session=")
    for part in ("Secure", "HttpOnly", "SameSite=strict", "Path=/getmedia/api/"):
        assert part.lower() in sc.lower(), part


def _cookie_of(r):
    return r.headers["set-cookie"].split(";")[0].split("=", 1)[1]


def test_web_cookie_auth_and_csrf(open_reg):
    r = register(open_reg, channel="web")
    c = {"Cookie": f"__Secure-gm_session={_cookie_of(r)}"}
    assert open_reg.client.get("/account/me", headers=c).status_code == 200
    # 写操作：缺 CSRF 头 / 缺 Origin / Origin 不符 → 403
    assert open_reg.client.post("/account/recovery/ack", headers=c).status_code == 403
    assert open_reg.client.post("/account/recovery/ack", headers={**c, "X-GetMedia-CSRF": "1"}).status_code == 403
    assert open_reg.client.post("/account/recovery/ack", headers={**c, "X-GetMedia-CSRF": "1", "Origin": "https://evil.example"}).status_code == 403
    ok = open_reg.client.post("/account/recovery/ack", headers={**c, "X-GetMedia-CSRF": "1", "Origin": "https://testserver"})
    assert ok.status_code == 204


def test_app_header_writes_need_no_csrf(open_reg):
    t = register(open_reg).json()["session"]["token"]
    assert open_reg.client.post("/account/recovery/ack", headers=hdr(t)).status_code == 204


# ---------- 杂项 ----------
def test_health_and_unknown_route(env):
    assert env.client.get("/health").json() == {"ok": True}
    r = env.client.get("/nope")
    assert r.status_code == 404 and r.json()["code"] == "http_404"
    assert env.client.get("/health").headers["cache-control"] == "no-store"


def test_audit_records_events_without_secrets(open_reg):
    register(open_reg)
    login(open_reg, password="wrong password 1")
    with open_reg.app.state.engine.begin() as c:
        rows = [tuple(r) for r in c.exec_driver_sql("SELECT event, result FROM audit ORDER BY id").all()]
    assert [r[0] for r in rows] == ["register", "login_fail"]
    assert "wrong password" not in json.dumps(rows)


def test_client_ip_trusts_xff_only_from_proxy():
    from starlette.requests import Request
    from getmedia.api import client_ip

    def req(peer, xff):
        return Request({"type": "http", "client": (peer, 1), "headers": [(b"x-forwarded-for", xff.encode())] if xff else []})
    assert client_ip(req("127.0.0.1", "203.0.113.7"), ("127.0.0.1",)) == "203.0.113.7"
    assert client_ip(req("198.51.100.9", "203.0.113.7"), ("127.0.0.1",)) == "198.51.100.9"
    assert client_ip(req("127.0.0.1", "garbage"), ("127.0.0.1",)) == "127.0.0.1"
