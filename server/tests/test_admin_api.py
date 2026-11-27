"""管理员后台：账号列表、改等级、重置密码、停用/恢复、撤销注销、邀请码。"""
from __future__ import annotations

from sqlalchemy import text

from conftest import hdr, login, make_admin, make_owner, register

PW = "correct horse 1"


def _uid(env, token):
    return env.client.get("/account/me", headers=hdr(token)).json()["id"]


def _add_tracks(env, uid, audio=2, video=1, playlists=1):
    now = int(env.clock())
    with env.app.state.engine.begin() as c:
        for i in range(audio):
            c.execute(text("INSERT INTO tracks(user_id,media_type,platform,video_id,page,title,bytes,updated_seq,created_at,updated_at) "
                           "VALUES(:u,'audio','local',:v,1,'a',10,:s,:s,:s)"),
                      {"u": uid, "v": f"a{i}", "s": now})
        for i in range(video):
            c.execute(text("INSERT INTO tracks(user_id,media_type,platform,video_id,page,title,bytes,updated_seq,created_at,updated_at) "
                           "VALUES(:u,'video','local',:v,1,'v',20,:s,:s,:s)"),
                      {"u": uid, "v": f"v{i}", "s": now})
        for i in range(playlists):
            c.execute(text("INSERT INTO playlists(user_id,name,updated_seq,created_at,updated_at) VALUES(:u,:n,1,:s,:s)"),
                      {"u": uid, "n": f"L{i}", "s": now})


def test_admin_list_fields_and_counts(open_reg):
    admin = make_admin(open_reg)
    t = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, t)
    _add_tracks(open_reg, uid, audio=2, video=1)
    r = open_reg.client.get("/v2/admin/accounts", headers=hdr(admin))
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == 2
    bob = next(x for x in body["items"] if x["login_name"] == "bob")
    assert (bob["audio"], bob["video"], bob["media_total"], bob["playlists"]) == (2, 1, 3, 1)
    assert bob["bytes"] == 40 and bob["admin"] is False
    root = next(x for x in body["items"] if x["login_name"] == "root")
    assert root["admin"] is True and root["tier"] == "admin"
    assert body["summary"]["admin"] == 1 and body["summary"]["free"] == 1


def test_admin_list_filters_and_sort(open_reg):
    admin = make_admin(open_reg)
    for name in ("bob", "carol"):
        register(open_reg, name=name)
    r = open_reg.client.get("/v2/admin/accounts?q=CAROL", headers=hdr(admin)).json()
    assert [x["login_name"] for x in r["items"]] == ["carol"]
    r = open_reg.client.get("/v2/admin/accounts?tier=admin", headers=hdr(admin)).json()
    assert [x["login_name"] for x in r["items"]] == ["root"]
    r = open_reg.client.get("/v2/admin/accounts?sort=login_name&order=asc", headers=hdr(admin)).json()
    assert [x["login_name"] for x in r["items"]] == ["bob", "carol", "root"]
    assert open_reg.client.get("/v2/admin/accounts?tier=bogus", headers=hdr(admin)).status_code == 422
    assert open_reg.client.get("/v2/admin/accounts?expiry=bogus", headers=hdr(admin)).status_code == 422


def test_admin_list_forbidden_for_full_and_free(open_reg):
    full = make_owner(open_reg, name="boss")
    free = register(open_reg, name="bob").json()["session"]["token"]
    assert open_reg.client.get("/v2/admin/accounts", headers=hdr(free)).status_code == 403
    assert open_reg.client.get("/v2/admin/accounts", headers=hdr(full)).status_code == 403


def test_tier_change_sets_expiry_and_revokes_sessions(open_reg):
    admin = make_admin(open_reg)
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/tier", json={"tier": "full"}, headers=hdr(admin))
    assert r.status_code == 200
    assert r.json()["revoked_sessions"] >= 1 and r.json()["tier_expires_at"] is not None
    assert open_reg.client.get("/account/me", headers=hdr(tok)).status_code == 401    # 会话被吊销
    me = login(open_reg, name="bob").json()["account"]
    assert me["tier"] == "full" and me["tier_expires_at"] == r.json()["tier_expires_at"]
    # 再改回免费：到期时间清空
    r2 = open_reg.client.post(f"/v2/admin/accounts/{uid}/tier", json={"tier": "free"}, headers=hdr(admin))
    assert r2.json()["tier"] == "free" and r2.json()["tier_expires_at"] is None


def test_tier_change_permanent(open_reg):
    admin = make_admin(open_reg)
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/tier",
                             json={"tier": "full", "duration": "permanent"}, headers=hdr(admin))
    assert r.json()["tier_expires_at"] is None
    assert login(open_reg, name="bob").json()["account"]["tier"] == "full"


def test_tier_change_rejects_admin_target_and_admin_value(open_reg):
    admin = make_admin(open_reg)
    root_id = _uid(open_reg, admin)
    r = open_reg.client.post(f"/v2/admin/accounts/{root_id}/tier", json={"tier": "free"}, headers=hdr(admin))
    assert r.status_code == 409 and r.json()["code"] == "cannot_change_admin"
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    assert open_reg.client.post(f"/v2/admin/accounts/{uid}/tier",
                               json={"tier": "admin"}, headers=hdr(admin)).status_code == 422


def test_admin_sets_exact_expiry_on_permanent_account(open_reg):
    """永久完整版 → 指定到秒的到期时间；不吊销会话，下一个请求就按新时间算。"""
    admin = make_admin(open_reg)
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    open_reg.client.post(f"/v2/admin/accounts/{uid}/tier",
                         json={"tier": "full", "duration": "permanent"}, headers=hdr(admin))
    tok = login(open_reg, name="bob").json()["session"]["token"]     # 改等级把旧会话踢了
    exp = int(open_reg.clock()) + 100 * 86400 + 12 * 3600 + 34 * 60 + 56
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/expiry", json={"expires_at": exp}, headers=hdr(admin))
    assert r.status_code == 200
    assert r.json()["tier_expires_at"] == exp
    # 不踢人：原会话仍有效，且立刻看到秒级的新到期时间
    me = open_reg.client.get("/account/me", headers=hdr(tok))
    assert me.status_code == 200
    assert me.json()["tier"] == "full" and me.json()["tier_expires_at"] == exp


def test_admin_extends_and_shortens_timed_expiry(open_reg):
    admin = make_admin(open_reg)
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    open_reg.client.post(f"/v2/admin/accounts/{uid}/tier", json={"tier": "full"}, headers=hdr(admin))
    first = int(open_reg.clock()) + 60 * 86400 + 7
    assert open_reg.client.post(f"/v2/admin/accounts/{uid}/expiry", json={"expires_at": first},
                                headers=hdr(admin)).json()["tier_expires_at"] == first
    # 延长到一年后
    later = first + 365 * 86400
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/expiry", json={"expires_at": later}, headers=hdr(admin))
    assert r.json()["tier_expires_at"] == later
    # 缩短（仍晚于现在）
    shorter = int(open_reg.clock()) + 10 * 86400
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/expiry", json={"expires_at": shorter}, headers=hdr(admin))
    assert r.json()["tier_expires_at"] == shorter
    assert login(open_reg, name="bob").json()["account"]["tier_expires_at"] == shorter


def test_admin_expiry_back_to_permanent(open_reg):
    admin = make_admin(open_reg)
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    open_reg.client.post(f"/v2/admin/accounts/{uid}/tier", json={"tier": "full"}, headers=hdr(admin))
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/expiry", json={"expires_at": None}, headers=hdr(admin))
    assert r.status_code == 200 and r.json()["tier_expires_at"] is None
    me = login(open_reg, name="bob").json()["account"]
    assert me["tier"] == "full" and me["tier_expires_at"] is None


def test_admin_expiry_validation(open_reg):
    admin = make_admin(open_reg)
    now = int(open_reg.clock())
    root_id = _uid(open_reg, admin)
    # 管理员本来就是永久，不给改
    r = open_reg.client.post(f"/v2/admin/accounts/{root_id}/expiry", json={"expires_at": now + 86400},
                             headers=hdr(admin))
    assert r.status_code == 409 and r.json()["code"] == "cannot_change_admin"
    # 免费版没有"有效期"这回事，先设为完整版
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/expiry", json={"expires_at": now + 86400},
                             headers=hdr(admin))
    assert r.status_code == 409 and r.json()["code"] == "not_full"
    open_reg.client.post(f"/v2/admin/accounts/{uid}/tier", json={"tier": "full"}, headers=hdr(admin))
    # 过去的时间 / 没给字段 / 远得离谱
    for body in ({"expires_at": now}, {"expires_at": now - 1}, {}, {"expires_at": now + 60 * 366 * 86400}):
        r = open_reg.client.post(f"/v2/admin/accounts/{uid}/expiry", json=body, headers=hdr(admin))
        assert r.status_code == 422, body


def test_password_reset_revokes_all_sessions(open_reg):
    admin = make_admin(open_reg)
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/password", json={"password": "brand new pass 1"},
                             headers=hdr(admin))
    assert r.status_code == 200 and r.json()["revoked_sessions"] >= 1
    assert open_reg.client.get("/account/me", headers=hdr(tok)).status_code == 401
    assert login(open_reg, name="bob").status_code == 401
    assert login(open_reg, name="bob", password="brand new pass 1").status_code == 200
    # 与登录名相同的弱密码被拒
    assert open_reg.client.post(f"/v2/admin/accounts/{uid}/password", json={"password": "bob"},
                                headers=hdr(admin)).status_code == 422


def test_disable_and_enable(open_reg):
    admin = make_admin(open_reg)
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/status", json={"status": "disabled"}, headers=hdr(admin))
    assert r.status_code == 200 and r.json()["revoked_sessions"] >= 1
    assert open_reg.client.get("/account/me", headers=hdr(tok)).status_code == 401
    r = login(open_reg, name="bob")
    assert r.status_code == 403 and r.json()["code"] == "account_disabled"
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/status", json={"status": "active"}, headers=hdr(admin))
    assert r.status_code == 200 and r.json()["revoked_sessions"] == 0
    assert login(open_reg, name="bob").status_code == 200
    assert open_reg.client.get("/account/me", headers=hdr(tok)).status_code == 401   # 旧会话不会复活


def test_admin_cannot_be_disabled(open_reg):
    admin = make_admin(open_reg)
    root_id = _uid(open_reg, admin)
    r = open_reg.client.post(f"/v2/admin/accounts/{root_id}/status", json={"status": "disabled"}, headers=hdr(admin))
    assert r.status_code == 409 and r.json()["code"] == "cannot_disable_admin"


def test_restore_deletion_within_cooldown(open_reg):
    admin = make_admin(open_reg)
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    assert open_reg.client.request("DELETE", "/account/me", json={"password": PW}, headers=hdr(tok)).status_code == 200
    r = open_reg.client.get("/v2/admin/accounts?status=pending_deletion", headers=hdr(admin)).json()
    bob = next(x for x in r["items"] if x["login_name"] == "bob")
    assert bob["deletion"]["days_left"] == 7 and bob["deletion"]["execute_at"] > int(open_reg.clock())
    r = open_reg.client.post(f"/v2/admin/accounts/{uid}/restore-deletion", headers=hdr(admin))
    assert r.status_code == 200
    assert login(open_reg, name="bob").status_code == 200
    # 没有待执行请求时再撤销 → 409
    assert open_reg.client.post(f"/v2/admin/accounts/{uid}/restore-deletion", headers=hdr(admin)).status_code == 409


def test_invites_create_list_and_states(open_reg):
    admin = make_admin(open_reg)
    r = open_reg.client.post("/v2/admin/invites", json={}, headers=hdr(admin))
    assert r.status_code == 201 and len(r.json()["codes"]) == 1
    assert open_reg.client.post("/v2/admin/invites", json={"count": 51}, headers=hdr(admin)).status_code == 422
    codes = open_reg.client.post("/v2/admin/invites", json={"count": 3}, headers=hdr(admin)).json()["codes"]
    assert len(set(codes)) == 3
    lst = open_reg.client.get("/v2/admin/invites", headers=hdr(admin)).json()
    assert lst["total"] == 4 and all(x["state"] == "unused" for x in lst["items"])
    assert all("code" not in x and "code_hash" not in x for x in lst["items"])   # 列表里没有明文
    tok = register(open_reg, name="bob").json()["session"]["token"]
    assert open_reg.client.post("/account/redeem-invite", json={"code": codes[0]}, headers=hdr(tok)).status_code == 200
    lst = open_reg.client.get("/v2/admin/invites?status=used", headers=hdr(admin)).json()
    assert lst["total"] == 1 and lst["items"][0]["used_by"] == "bob" and lst["items"][0]["state"] == "used"


def test_admin_actions_are_audited(open_reg):
    admin = make_admin(open_reg)
    tok = register(open_reg, name="bob").json()["session"]["token"]
    uid = _uid(open_reg, tok)
    open_reg.client.post(f"/v2/admin/accounts/{uid}/tier", json={"tier": "full"}, headers=hdr(admin))
    open_reg.client.post(f"/v2/admin/accounts/{uid}/password", json={"password": "brand new pass 1"}, headers=hdr(admin))
    open_reg.client.post(f"/v2/admin/accounts/{uid}/status", json={"status": "disabled"}, headers=hdr(admin))
    open_reg.client.post("/v2/admin/invites", json={"count": 2}, headers=hdr(admin))
    with open_reg.app.state.engine.begin() as c:
        events = [r[0] for r in c.execute(text("SELECT event FROM audit WHERE event LIKE 'admin_%' ORDER BY id")).all()]
    assert events == ["admin_tier_change", "admin_password_reset", "admin_disable", "admin_invite_create"]
    with open_reg.app.state.engine.begin() as c:
        dump = " ".join(str(x) for x in c.execute(text("SELECT result FROM audit WHERE event LIKE 'admin_%'")).all())
    assert "brand new pass 1" not in dump
