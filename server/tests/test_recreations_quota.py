"""P2i：AI 素材与产物的存储归属（计入服务器库 3 GiB）、保留期与到期清理。

这一期改的是"钱袋子"：AI 文件不在 tracks 表里，配额与清理都得单独算，
所以每条都要有反向用例（超限不落盘、演练不删、有活任务不删、不碰媒体库真身）。
"""
from __future__ import annotations

import io
from pathlib import Path

import pytest
from sqlalchemy import text

from conftest import hdr, register
from getmedia.media import MediaError, MediaStore
from test_recreations import build, upload

DAY = 86400


def ai_dir(env, pid: int) -> Path:
    return Path(env.settings.media_dir) / "ai" / str(pid)


def projects(env) -> list[dict]:
    return env.client.get("/v2/recreations", headers=hdr(env.token)).json()["recreations"]


def test_upload_counts_into_archive_quota(tmp_path):
    env = build(tmp_path, archive_quota_bytes=1000)
    assert upload(env, data=b"x" * 600).status_code == 201

    body = env.client.get("/v2/maintenance/storage", headers=hdr(env.token)).json()
    assert body["ai"] == {"files": 1, "bytes": 600}
    # 没有任何 tracks，used.archive 全部来自 AI —— 说明确实计入了同一个 3 GiB
    assert body["used"]["archive"] == 600
    assert body["quota"]["archive"] == 1000
    assert body["by_storage"] == {}  # tracks 表里没东西，可见 AI 不是靠 tracks 记账的


def test_over_quota_upload_rejected_without_side_effects(tmp_path):
    env = build(tmp_path, archive_quota_bytes=1000)
    assert upload(env, data=b"x" * 600).status_code == 201

    r = upload(env, data=b"y" * 600)
    assert r.status_code == 409 and r.json()["code"] == "quota_exceeded"
    detail = r.json()["detail"]
    for part in ("服务器库空间不足", "已用", "本次需要", "先清理缓存或旧项目"):
        assert part in detail
    # 拒绝时不留项目行、不留空目录、不写盘
    assert len(projects(env)) == 1
    assert sorted(p.name for p in (Path(env.settings.media_dir) / "ai").iterdir()) == ["1"]


def test_project_exposes_remaining_days(tmp_path):
    env = build(tmp_path, ai_retention_days=14)
    p = upload(env, data=b"x" * 100).json()
    assert p["retention_days"] == 14
    assert p["expires_at"] == p["updated_at"] + 14 * DAY
    assert p["days_left"] == 14

    env.clock.advance(10 * DAY + 1)
    again = env.client.get(f"/v2/recreations/{p['id']}", headers=hdr(env.token)).json()
    assert again["days_left"] == 4

    # 活动会顺延：改描述把 updated_at 推到现在，剩余天数回到 14
    env.client.put(f"/v2/recreations/{p['id']}/description", json={"description": "再改一版"},
                   headers=hdr(env.token))
    assert env.client.get(f"/v2/recreations/{p['id']}", headers=hdr(env.token)).json()["days_left"] == 14


def test_gc_dry_run_then_apply(tmp_path):
    env = build(tmp_path, ai_retention_days=14)
    pid = upload(env, data=b"x" * 600).json()["id"]
    env.clock.advance(15 * DAY)

    dry = env.client.post("/v2/recreations/gc?dry_run=true", headers=hdr(env.token)).json()
    assert dry == {"projects": 1, "freed_bytes": 600, "days": 14, "dry_run": True}
    assert ai_dir(env, pid).is_dir()  # 演练不删
    assert projects(env)[0]["status"] == "draft"

    real = env.client.post("/v2/recreations/gc", headers=hdr(env.token)).json()
    assert real["projects"] == 1 and real["freed_bytes"] == 600 and real["dry_run"] is False
    assert not ai_dir(env, pid).exists()

    p = env.client.get(f"/v2/recreations/{pid}", headers=hdr(env.token)).json()
    assert p["status"] == "expired" and p["source_path"] is None and p["days_left"] == 0
    # 记录还在（历史可查），源视频接口如实说"没有源文件"，配额归零
    assert env.client.get(f"/v2/recreations/{pid}/source", headers=hdr(env.token)).status_code == 409
    body = env.client.get("/v2/maintenance/storage", headers=hdr(env.token)).json()
    assert body["ai"] == {"files": 0, "bytes": 0} and body["used"]["archive"] == 0


def test_gc_skips_project_with_active_run(tmp_path):
    env = build(tmp_path, ai_enabled=True, ai_retention_days=14)
    pid = upload(env, data=b"x" * 100).json()["id"]
    assert env.client.post(f"/v2/recreations/{pid}/analyze", headers=hdr(env.token)).status_code == 202

    env.clock.advance(30 * DAY)
    assert env.client.post("/v2/recreations/gc", headers=hdr(env.token)).json()["projects"] == 0
    assert ai_dir(env, pid).is_dir()


def test_gc_respects_explicit_days(tmp_path):
    env = build(tmp_path, ai_retention_days=30)
    upload(env, data=b"x" * 100)
    env.clock.advance(10 * DAY)
    assert env.client.post("/v2/recreations/gc", headers=hdr(env.token)).json()["projects"] == 0
    assert env.client.post("/v2/recreations/gc?days=7", headers=hdr(env.token)).json()["projects"] == 1


def test_gc_does_not_touch_media_library_file(tmp_path):
    """源来自媒体库的项目：文件在 tracks 那儿，gc 只删 ai/<项目号>/，不能连坐。"""
    env = build(tmp_path)
    keep = Path(env.settings.media_dir) / "lib.mp4"
    keep.parent.mkdir(parents=True, exist_ok=True)
    keep.write_bytes(b"v" * 100)
    with env.app.state.engine.begin() as c:
        c.execute(text("INSERT INTO recreations(user_id,title,source_path,status,created_at,updated_at)"
                       " SELECT id,'库里来的','lib.mp4','draft',:n,:n FROM users LIMIT 1"), {"n": 1})
    env.clock.advance(20 * DAY)
    assert env.client.post("/v2/recreations/gc", headers=hdr(env.token)).json()["projects"] == 1
    assert keep.is_file()


def test_gc_is_owner_only(tmp_path):
    env = build(tmp_path)
    assert env.client.post("/v2/recreations/gc").status_code == 401
    token = register(env, name="normal1").json()["session"]["token"]
    assert env.client.post("/v2/recreations/gc", headers=hdr(token)).status_code == 403


def test_media_archive_room_accounts_for_ai_bytes(tmp_path):
    """AI 占用的空间要让媒体入库也让路：同一个 3 GiB，不是两套账。"""
    env = build(tmp_path, archive_quota_bytes=1000)
    assert upload(env, data=b"x" * 900).status_code == 201
    store = MediaStore(env.app.state.engine, env.settings, env.clock)
    assert store.storage_used("archive") == 900
    with pytest.raises(MediaError) as exc:
        store.ensure_room("archive", 200)
    assert exc.value.code == "quota_exceeded"


def test_cli_ai_gc_prints_days_and_dry_run(tmp_path):
    env = build(tmp_path, ai_retention_days=7)
    upload(env, data=b"x" * 100)
    from getmedia.cli import main

    out = io.StringIO()
    assert main(["ai-gc", "--dry-run"], env.settings, out) == 0
    assert "保留期 7 天" in out.getvalue() and "演练" in out.getvalue()
