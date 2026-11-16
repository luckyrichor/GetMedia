"""P2c：定时抓取（DB 化、run-once、到期触发）。"""
from __future__ import annotations

import pytest
from sqlalchemy import text

from conftest import hdr, make_owner
from test_creator_jobs import Lister, build


@pytest.fixture
def senv(tmp_path):
    env = build(tmp_path)
    env.app.state.runtime.write(registration_open=True)
    env.token = make_owner(env, name="boss")
    return env


def add(env, **body):
    payload = {"name": "每日一更", "platform": "bilibili", "input": "https://space.bilibili.com/2/video",
               "mode": "latest_n", "n": 3, "interval_minutes": 60}
    payload.update(body)
    return env.client.post("/v2/schedules", headers=hdr(env.token), json=payload)


def test_crud(senv):
    r = add(senv)
    assert r.status_code == 201, r.text
    sid = r.json()["id"]
    assert r.json()["next_run"] > 0 and r.json()["enabled"] is True

    assert [s["id"] for s in senv.client.get("/v2/schedules", headers=hdr(senv.token)).json()["schedules"]] == [sid]
    assert senv.client.get(f"/v2/schedules/{sid}", headers=hdr(senv.token)).json()["name"] == "每日一更"

    p = senv.client.patch(f"/v2/schedules/{sid}", headers=hdr(senv.token),
                          json={"interval_minutes": 120, "enabled": False})
    assert p.status_code == 200 and p.json()["interval_minutes"] == 120 and p.json()["enabled"] is False

    assert senv.client.delete(f"/v2/schedules/{sid}", headers=hdr(senv.token)).status_code == 204
    assert senv.client.get(f"/v2/schedules/{sid}", headers=hdr(senv.token)).status_code == 404


def test_validation(senv):
    assert add(senv, name="a", mode="date_range").status_code == 400
    assert add(senv, name="b", mode="latest_n", n=None).status_code == 400
    assert add(senv, name="c", interval_minutes=5).status_code == 422
    assert add(senv, name="d", since="2026-13-01", mode="date_range").status_code == 400
    assert add(senv, name="e").status_code == 201
    assert add(senv, name="e").status_code == 409


def test_run_once_creates_job_and_advances(senv):
    sid = add(senv).json()["id"]
    before = senv.client.get(f"/v2/schedules/{sid}", headers=hdr(senv.token)).json()
    assert senv.client.post(f"/v2/schedules/{sid}/run-once", headers=hdr(senv.token)).status_code == 202
    after = senv.client.get(f"/v2/schedules/{sid}", headers=hdr(senv.token)).json()
    assert after["last_status"] == "done"
    assert after["last_job_id"]
    assert after["next_run"] > before["next_run"]
    runs = senv.client.get(f"/v2/schedules/{sid}/runs", headers=hdr(senv.token)).json()["runs"]
    assert runs and runs[0]["status"] == "done"

    items = senv.client.get(f"/v2/creators/jobs/{after['last_job_id']}/items",
                            headers=hdr(senv.token)).json()
    assert items["total"] == 3


def test_tick_runs_due_only(senv):
    sid = add(senv, name="到期").json()["id"]
    off = add(senv, name="停用", enabled=False).json() if False else None  # enabled 由 patch 关
    sid2 = add(senv, name="关闭").json()["id"]
    senv.client.patch(f"/v2/schedules/{sid2}", headers=hdr(senv.token), json={"enabled": False})

    with senv.app.state.engine.begin() as c:
        c.execute(text("UPDATE schedules SET next_run=0"))
    out = senv.app.state.schedules.tick()
    assert [o["schedule_id"] for o in out] == [sid]

    with senv.app.state.engine.begin() as c:
        row = c.execute(text("SELECT last_status, next_run FROM schedules WHERE id=:i"), {"i": sid}).one()
    assert row[0] == "done" and row[1] > 0

    # 已推进到未来，再 tick 不会重复触发
    assert senv.app.state.schedules.tick() == []


def test_isolated_between_users(tmp_path):
    env = build(tmp_path)
    env.app.state.runtime.write(registration_open=True)
    ta = make_owner(env, name="owner_a")
    tb = make_owner(env, name="owner_b")
    sid = env.client.post("/v2/schedules", headers=hdr(ta),
                          json={"name": "x", "platform": "bilibili",
                                "input": "https://space.bilibili.com/2/video",
                                "mode": "latest_n", "n": 2, "interval_minutes": 60}).json()["id"]
    assert env.client.get(f"/v2/schedules/{sid}", headers=hdr(tb)).status_code == 404
    assert env.client.get(f"/v2/schedules/{sid}/runs", headers=hdr(tb)).status_code == 404
    assert env.client.delete(f"/v2/schedules/{sid}", headers=hdr(tb)).status_code == 404
    assert env.client.post(f"/v2/schedules/{sid}/run-once", headers=hdr(tb)).status_code == 404
