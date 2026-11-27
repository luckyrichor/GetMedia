"""等级三档 / 有效期 / 邀请码升级。"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

from sqlalchemy import text

from conftest import hdr, make_owner, register
from getmedia.service import add_months

CN = timezone(timedelta(hours=8))


def _ts(y, m, d, hh=12, mm=0):
    return int(datetime(y, m, d, hh, mm, tzinfo=CN).timestamp())


def _fmt(ts):
    return datetime.fromtimestamp(ts, CN).strftime("%Y-%m-%d %H:%M")


# ---------- 自然月 ----------
def test_add_months_natural_month_and_month_end():
    assert _fmt(add_months(_ts(2026, 11, 27), 6)) == "2027-05-27 12:00"
    assert _fmt(add_months(_ts(2026, 1, 31), 6)) == "2026-07-31 12:00"
    assert _fmt(add_months(_ts(2026, 8, 31), 6)) == "2027-02-28 12:00"
    assert _fmt(add_months(_ts(2028, 8, 31), 6)) == "2029-02-28 12:00"   # 2029 不是闰年
    assert _fmt(add_months(_ts(2026, 12, 31), 6)) == "2027-06-30 12:00"
    assert _fmt(add_months(_ts(2026, 11, 27), 12)) == "2027-11-27 12:00"


# ---------- 兑换 ----------
def test_free_redeem_upgrades_for_six_months(open_reg):
    token = register(open_reg).json()["session"]["token"]
    code = open_reg.svc.create_invites()[0]
    r = open_reg.client.post("/account/redeem-invite", json={"code": code}, headers=hdr(token))
    assert r.status_code == 200
    body = r.json()
    assert body["tier"] == "full"
    assert body["tier_expires_at"] == add_months(int(open_reg.clock()), 6)
    me = open_reg.client.get("/account/me", headers=hdr(token)).json()
    assert me["tier"] == "full" and me["tier_expires_at"] == body["tier_expires_at"]


def test_redeem_stacks_on_existing_expiry(open_reg):
    token = make_owner(open_reg, name="boss")
    with open_reg.app.state.engine.begin() as c:
        c.execute(text("UPDATE users SET tier_expires_at=:e WHERE login_name_norm='boss'"),
                  {"e": int(open_reg.clock()) + 30 * 86400})
    base = int(open_reg.clock()) + 30 * 86400
    code = open_reg.svc.create_invites()[0]
    r = open_reg.client.post("/account/redeem-invite", json={"code": code}, headers=hdr(token))
    assert r.json()["tier_expires_at"] == add_months(base, 6)


def test_redeem_after_expiry_starts_from_now(open_reg):
    token = make_owner(open_reg, name="boss")
    with open_reg.app.state.engine.begin() as c:
        c.execute(text("UPDATE users SET tier_expires_at=:e WHERE login_name_norm='boss'"),
                  {"e": int(open_reg.clock()) - 86400})
    # 读路径已经降级成 free，但库里仍是 full + 过期时间
    assert open_reg.client.get("/account/me", headers=hdr(token)).json()["tier"] == "free"
    code = open_reg.svc.create_invites()[0]
    r = open_reg.client.post("/account/redeem-invite", json={"code": code}, headers=hdr(token))
    assert r.json()["tier_expires_at"] == add_months(int(open_reg.clock()), 6)


def test_invite_is_single_use(open_reg):
    t1 = register(open_reg, name="user1").json()["session"]["token"]
    t2 = register(open_reg, name="user2").json()["session"]["token"]
    code = open_reg.svc.create_invites()[0]
    assert open_reg.client.post("/account/redeem-invite", json={"code": code}, headers=hdr(t1)).status_code == 200
    r = open_reg.client.post("/account/redeem-invite", json={"code": code}, headers=hdr(t2))
    assert r.status_code == 404 and r.json()["code"] == "invite_invalid"


def test_expired_invite_rejected(open_reg):
    token = register(open_reg).json()["session"]["token"]
    code = open_reg.svc.create_invites()[0]
    with open_reg.app.state.engine.begin() as c:
        c.execute(text("UPDATE invites SET expires_at=:e"), {"e": int(open_reg.clock()) - 1})
    assert open_reg.client.post("/account/redeem-invite", json={"code": code}, headers=hdr(token)).status_code == 404


def test_admin_cannot_redeem(open_reg):
    from conftest import make_admin
    token = make_admin(open_reg)
    code = open_reg.svc.create_invites()[0]
    r = open_reg.client.post("/account/redeem-invite", json={"code": code}, headers=hdr(token))
    assert r.status_code == 409 and r.json()["code"] == "already_admin"


def test_permanent_full_cannot_redeem_and_keeps_permanent(open_reg):
    """永久完整版兑换一次就会从"永久"缩水成"6 个月后到期"，必须直接拒绝，且码不能被烧掉。"""
    token = make_owner(open_reg, name="boss")
    me = open_reg.client.get("/account/me", headers=hdr(token)).json()
    assert me["tier"] == "full" and me["tier_expires_at"] is None
    code = open_reg.svc.create_invites()[0]

    r = open_reg.client.post("/account/redeem-invite", json={"code": code}, headers=hdr(token))
    assert r.status_code == 409 and r.json()["code"] == "already_permanent"

    after = open_reg.client.get("/account/me", headers=hdr(token)).json()
    assert after["tier"] == "full" and after["tier_expires_at"] is None   # 还是永久

    # 码没被消耗：换个人照样能兑
    other = register(open_reg, name="user9").json()["session"]["token"]
    assert open_reg.client.post("/account/redeem-invite", json={"code": code},
                                headers=hdr(other)).status_code == 200


def test_redeem_requires_login(open_reg):
    code = open_reg.svc.create_invites()[0]
    assert open_reg.client.post("/account/redeem-invite", json={"code": code}).status_code == 401


# ---------- 注册时填码 ----------
def test_register_with_invite_creates_full_account(env):
    code = env.svc.create_invites()[0]
    r = register(env, name="rich", invite_code=code)
    assert r.status_code == 201
    acct = r.json()["account"]
    assert acct["tier"] == "full"
    assert acct["tier_expires_at"] == add_months(int(env.clock()), 6)


def test_register_rejects_bad_code_even_when_open(open_reg):
    r = register(open_reg, name="rich", invite_code="NOPE-NOPE-NOPE")
    assert r.status_code == 403 and r.json()["code"] == "invite_invalid"


# ---------- 到期清扫 ----------
def test_sweep_downgrades_expired_full_only(open_reg):
    register(open_reg, name="root")          # 留给 admin
    make_owner(open_reg, name="boss")
    with open_reg.app.state.engine.begin() as c:
        c.execute(text("UPDATE users SET tier_expires_at=:e WHERE login_name_norm='boss'"),
                  {"e": int(open_reg.clock()) - 1})
        c.execute(text("UPDATE users SET tier='admin', tier_expires_at=NULL WHERE login_name_norm='root'"))
    assert open_reg.svc.sweep_expired_tiers() == 1
    with open_reg.app.state.engine.begin() as c:
        rows = dict(c.execute(text("SELECT login_name, tier FROM users")).all())
        assert rows == {"root": "admin", "boss": "free"}
        assert c.execute(text("SELECT COUNT(*) FROM users WHERE tier_expires_at IS NOT NULL")).scalar() == 0
        assert c.execute(text("SELECT COUNT(*) FROM audit WHERE event='tier_expired'")).scalar() == 1


def test_sweep_invites_deletes_unused_expired_and_used(open_reg):
    now = int(open_reg.clock())
    with open_reg.app.state.engine.begin() as c:
        c.execute(text("INSERT INTO invites(code_hash,purpose,created_at,expires_at) VALUES('h1','full',:n,:e)"),
                  {"n": now - 3 * 86400, "e": now - 2 * 86400})          # 过期未用 → 删
        c.execute(text("INSERT INTO invites(code_hash,purpose,created_at,expires_at) VALUES('h2','full',:n,:e)"),
                  {"n": now - 3600, "e": now + 86400})                    # 未用且有效 → 留
        c.execute(text("INSERT INTO invites(code_hash,purpose,created_at,expires_at,used_by,used_at) VALUES('h3','full',:n,:e,'u',:u)"),
                  {"n": now - 3 * 86400, "e": now - 2 * 86400, "u": now - 2 * 86400})  # 用过 → 删
        c.execute(text("INSERT INTO invites(code_hash,purpose,created_at,expires_at,used_by,used_at) VALUES('h4','full',:n,:e,'u',:u)"),
                  {"n": now - 60, "e": now + 86400, "u": now - 60})       # 刚用过 → 留 1 天
    assert open_reg.svc.sweep_invites() == 2
    with open_reg.app.state.engine.begin() as c:
        assert [r[0] for r in c.execute(text("SELECT code_hash FROM invites ORDER BY code_hash")).all()] == ["h2", "h4"]


# ---------- 迁移 v20 ----------
def test_migration_v20_rewrites_owner_and_sets_admin(tmp_path, monkeypatch):
    import pytest
    from sqlalchemy.exc import IntegrityError

    import getmedia.db as dbm

    path = tmp_path / "m.db"
    eng = dbm.make_engine(path)
    monkeypatch.setattr(dbm, "MIGRATIONS", [m for m in dbm.MIGRATIONS if m[0] < 20])
    dbm.migrate(eng, path, tmp_path / "snaps", 1_800_000_000)
    with eng.begin() as c:
        c.exec_driver_sql("INSERT INTO users(id,login_name,login_name_norm,password_hash,tier,created_at) "
                          "VALUES('a','luckyrichor','luckyrichor','x','owner',1)")
        c.exec_driver_sql("INSERT INTO users(id,login_name,login_name_norm,password_hash,tier,created_at) "
                          "VALUES('b','owner1','owner1','x','owner',1)")
    monkeypatch.undo()

    assert dbm.migrate(eng, path, tmp_path / "snaps", 1_800_000_100) == 20
    with eng.begin() as c:
        rows = dict(c.execute(text("SELECT login_name,tier FROM users")).all())
        assert rows == {"luckyrichor": "admin", "owner1": "full"}
    with pytest.raises(IntegrityError):
        with eng.begin() as c:
            c.exec_driver_sql("INSERT INTO users(id,login_name,login_name_norm,password_hash,tier,created_at) "
                              "VALUES('c','second','second','x','admin',1)")
