import json

import pytest
from fastapi import Depends

from getmedia.tiers import FREE_ROUTES, unprotected_routes, whitelisted_but_missing
from conftest import hdr, login, register


def _owner_route(env):
    app = env.app
    req = app.state.require_tier

    @app.get("/_t/owner-only", dependencies=[Depends(req("owner"))])
    def _o():
        return {"ok": True}
    return app


def _make_owner(env, name="alice"):
    with env.app.state.engine.begin() as c:
        c.exec_driver_sql("UPDATE users SET tier='owner' WHERE login_name_norm=?", (name,))


def test_route_walk_clean_and_whitelist_complete(env):
    assert unprotected_routes(env.app) == []
    # 白名单里还没实现的只允许是后续工作包的接口
    assert set(whitelisted_but_missing(env.app)) <= {"POST /v2/feedback", "DELETE /account/me"}


def test_route_walk_detects_unregistered_route(env):
    @env.app.get("/v2/sneaky")
    def _s():
        return {}
    assert unprotected_routes(env.app) == ["GET /v2/sneaky"]


def test_route_walk_rejects_free_tier_dependency(env):
    @env.app.get("/v2/free-dep", dependencies=[Depends(env.app.state.require_tier("free", "owner"))])
    def _s():
        return {}
    assert "GET /v2/free-dep" in unprotected_routes(env.app)


def test_route_walk_accepts_owner_dependency(env):
    _owner_route(env)
    assert unprotected_routes(env.app) == []


def test_free_account_gets_403_and_audit(open_reg):
    _owner_route(open_reg)
    t = register(open_reg).json()["session"]["token"]
    r = open_reg.client.get("/_t/owner-only", headers=hdr(t))
    assert r.status_code == 403 and r.json()["code"] == "forbidden_tier"
    with open_reg.app.state.engine.begin() as c:
        rows = [tuple(x) for x in c.exec_driver_sql("SELECT event,path,result FROM audit WHERE event='forbidden_tier'").all()]
    assert rows == [("forbidden_tier", "/_t/owner-only", "need=owner")]


def test_anonymous_gets_401_not_403(open_reg):
    _owner_route(open_reg)
    assert open_reg.client.get("/_t/owner-only").status_code == 401


def test_owner_account_allowed(open_reg):
    _owner_route(open_reg)
    t = register(open_reg).json()["session"]["token"]
    _make_owner(open_reg)
    assert open_reg.client.get("/_t/owner-only", headers=hdr(t)).status_code == 200
    assert open_reg.client.get("/account/me", headers=hdr(t)).json()["tier"] == "owner"


def test_expired_owner_falls_back_to_free(open_reg):
    _owner_route(open_reg)
    t = register(open_reg).json()["session"]["token"]
    _make_owner(open_reg)
    with open_reg.app.state.engine.begin() as c:
        c.exec_driver_sql("UPDATE users SET tier_expires_at=?", (int(open_reg.clock()) + 100,))
    assert open_reg.client.get("/_t/owner-only", headers=hdr(t)).status_code == 200
    open_reg.clock.advance(200)
    assert open_reg.client.get("/_t/owner-only", headers=hdr(t)).status_code == 403
    assert open_reg.client.get("/account/me", headers=hdr(t)).json()["tier"] == "free"


def test_startup_refuses_unregistered_route(env, monkeypatch):
    import getmedia.api as api
    import getmedia.tiers as tiers
    monkeypatch.setattr(api, "unprotected_routes", lambda app: ["GET /x"])
    with pytest.raises(RuntimeError):
        api.create_app(env.settings, clock=env.clock, alerts=env.alerts)


# ---------- /v2/config ----------
def test_config_defaults_closed_without_files(env):
    j = env.client.get("/v2/config").json()
    assert j["registration_open"] is False and j["latest"] is None and j["min_version"] is None and j["maintenance"] is None
    assert j["server_time"] == int(env.clock())


def test_config_follows_runtime_and_release_files(env):
    env.settings.runtime_file.write_text(json.dumps({"registration_open": True, "invite_required": True, "maintenance": "今晚维护"}), encoding="utf-8")
    env.settings.release_file.write_text(json.dumps({
        "min_version": "0.1.0",
        "latest": {"version": "0.2.0", "notes": "更新说明", "apk": "https://example.invalid/getmedia-0.2.0.apk", "sha256": "a" * 64}}), encoding="utf-8")
    j = env.client.get("/v2/config").json()
    assert j["registration_open"] and j["invite_required"] and j["maintenance"] == "今晚维护"
    assert j["latest"] == {"version": "0.2.0", "notes": "更新说明", "apk": "https://example.invalid/getmedia-0.2.0.apk", "sha256": "a" * 64}
    assert j["min_version"] == "0.1.0"
    # 改文件即生效（无需重启）
    env.settings.runtime_file.write_text(json.dumps({"registration_open": False}), encoding="utf-8")
    assert env.client.get("/v2/config").json()["registration_open"] is False


def test_config_drops_unsafe_release_fields(env):
    env.settings.release_file.write_text(json.dumps({
        "min_version": "latest!", "latest": {"version": "1.0", "apk": "javascript:alert(1)", "sha256": "xyz"}}), encoding="utf-8")
    j = env.client.get("/v2/config").json()
    assert j["min_version"] is None and j["latest"]["apk"] is None and j["latest"]["sha256"] is None
    env.settings.release_file.write_text("{not json", encoding="utf-8")
    assert env.client.get("/v2/config").json()["latest"] is None


def test_config_corrupt_runtime_defaults_to_closed(env):
    env.settings.runtime_file.write_text("garbage", encoding="utf-8")
    assert env.client.get("/v2/config").json()["registration_open"] is False


def test_registration_follows_runtime_file_live(env):
    assert register(env).status_code == 403
    env.settings.runtime_file.write_text(json.dumps({"registration_open": True}), encoding="utf-8")
    assert register(env).status_code == 201


# ---------- 心跳 / 统计 ----------
def _stats(env):
    with env.app.state.engine.begin() as c:
        return [tuple(r) for r in c.exec_driver_sql("SELECT day,registrations,active FROM daily_stats ORDER BY day").all()]


def test_heartbeat_requires_login(open_reg):
    assert open_reg.client.post("/v2/heartbeat", json={"client": {"channel": "android"}}).status_code == 401


def test_heartbeat_once_per_device_per_day_and_stats(open_reg):
    t1 = register(open_reg).json()["session"]["token"]
    hb = {"client": {"channel": "android", "app_version": "0.1.0", "os": "Android 14", "device": "Pixel"}}
    for _ in range(3):
        assert open_reg.client.post("/v2/heartbeat", json=hb, headers=hdr(t1)).status_code == 204
    open_reg.clock.advance(301)
    t2 = login(open_reg, device="second").json()["session"]["token"]
    assert open_reg.client.post("/v2/heartbeat", json=hb, headers=hdr(t2)).status_code == 204
    with open_reg.app.state.engine.begin() as c:
        assert c.exec_driver_sql("SELECT COUNT(*) FROM heartbeats").scalar() == 2  # 两台设备各一条
    day = _stats(open_reg)
    assert day == [(day[0][0], 1, 1)]  # 一次注册，同一账号当天只算一个活跃


def test_new_day_counts_again(open_reg):
    t = register(open_reg).json()["session"]["token"]
    hb = {"client": {"channel": "android"}}
    open_reg.client.post("/v2/heartbeat", json=hb, headers=hdr(t))
    open_reg.clock.advance(86400)
    open_reg.client.post("/v2/heartbeat", json=hb, headers=hdr(t))
    s = _stats(open_reg)
    assert [x[2] for x in s] == [1, 1] and len(s) == 2


def test_heartbeat_sanitizes_client_strings(open_reg):
    t = register(open_reg).json()["session"]["token"]
    open_reg.client.post("/v2/heartbeat", json={"client": {"channel": "android", "device": "Pi\nxel" + "x" * 200}}, headers=hdr(t))
    with open_reg.app.state.engine.begin() as c:
        dev = c.exec_driver_sql("SELECT device FROM heartbeats").scalar()
    assert "\n" not in dev and len(dev) <= 64


def test_argon_parameters_configurable_with_bounds():
    import pytest
    from getmedia.config import ConfigError, load_settings
    base = {"GETMEDIA_SECRET_KEY": "k" * 40}
    s = load_settings(base)
    assert (s.argon_memory_kib, s.argon_iterations) == (65536, 3)
    s = load_settings({**base, "GETMEDIA_ARGON_MEMORY_KIB": "32768", "GETMEDIA_ARGON_ITERATIONS": "4"})
    assert (s.argon_memory_kib, s.argon_iterations) == (32768, 4)
    for bad in ({"GETMEDIA_ARGON_MEMORY_KIB": "abc"}, {"GETMEDIA_ARGON_MEMORY_KIB": "16"}, {"GETMEDIA_ARGON_ITERATIONS": "0"},
                {"GETMEDIA_ARGON_ITERATIONS": "99"}):
        with pytest.raises(ConfigError):
            load_settings({**base, **bad})
