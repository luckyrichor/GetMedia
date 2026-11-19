"""曲库清单备份（元数据快照）的服务端行为。

重点覆盖三件容易出错的事：
- 校验顺序：格式错的请求不能占掉 10 分钟配额；
- 限频与保留：每账号每 10 分钟 1 次、只留最近 3 份；
- 隐私边界：备份按账号隔离，注销即刻删除。
"""
from __future__ import annotations

import gzip
import hashlib
import json

import pytest
from sqlalchemy import text

from conftest import hdr, register

from getmedia import metadata_backup as mb

URL = "/account/metadata-backups"


def track(**kw) -> dict:
    item = {
        "platform": "douyin", "video_id": "7412", "page": 1,
        "title": "标题", "uploader": "作者", "media_type": "AUDIO", "format": "m4a",
        "duration_sec": 12.5, "bytes": 1024, "source_url": "https://v.douyin.com/x/",
        "added_at": 1731500000000, "favorite": False, "category_id": 1,
        "sort_index": 0, "title_override": None, "playlists": [],
    }
    item.update(kw)
    return item


def snapshot(tracks=None, categories=None, playlists=None, **kw) -> dict:
    tracks = [track()] if tracks is None else tracks
    categories = [{"id": 1, "name": "未分类", "parent_id": None, "kind": "LEAF", "created_at": 0}] \
        if categories is None else categories
    playlists = [{"id": 1, "name": "默认", "is_default": True, "created_at": 1731000000000}] \
        if playlists is None else playlists
    data = {
        "v": 1, "generated_at": 1731912345, "app_version": "0.1.0",
        "counts": {
            "tracks": len(tracks), "categories": len(categories), "playlists": len(playlists),
            "playlist_items": sum(len(t.get("playlists") or []) for t in tracks),
        },
        "categories": categories, "playlists": playlists, "tracks": tracks,
    }
    data.update(kw)
    return data


def gz(data: dict | bytes) -> bytes:
    raw = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False).encode()
    return gzip.compress(raw)


def upload(env, token, body: bytes, **headers):
    head = {"Content-Type": "application/gzip", **hdr(token), **headers}
    return env.client.post(URL, content=body, headers=head)


@pytest.fixture
def user(open_reg):
    r = register(open_reg, name="alice")
    return open_reg, r.json()["session"]["token"]


def test_upload_list_download_roundtrip(user):
    env, token = user
    body = gz(snapshot())

    r = upload(env, token, body, **{"X-GetMedia-App": "0.1.0", "X-GetMedia-Device": "Pixel"})
    assert r.status_code == 201, r.text
    out = r.json()
    assert out["id"].startswith("bkp_") and len(out["id"]) == 20
    assert out["pruned"] == []

    listed = env.client.get(URL, headers=hdr(token)).json()
    assert listed["keep"] == 3 and listed["total_bytes"] == len(body)
    assert len(listed["backups"]) == 1
    row = listed["backups"][0]
    assert row["id"] == out["id"]
    assert row["counts"] == {"tracks": 1, "categories": 1, "playlists": 1, "playlist_items": 0}
    assert row["app_version"] == "0.1.0" and row["device"] == "Pixel"
    assert "payload" not in row

    got = env.client.get(f"{URL}/{out['id']}", headers=hdr(token))
    assert got.status_code == 200
    assert got.headers["content-type"] == "application/gzip"
    assert got.headers["x-backup-sha256"] == hashlib.sha256(body).hexdigest()
    assert got.content == body      # 原样返回，不重新序列化


def test_requires_login(env):
    assert env.client.get(URL).status_code == 401
    assert env.client.post(URL, content=gz(snapshot()),
                           headers={"Content-Type": "application/gzip"}).status_code == 401


def test_free_tier_can_use_it(user):
    env, token = user
    assert env.client.get("/account/me", headers=hdr(token)).json()["tier"] == "free"
    assert upload(env, token, gz(snapshot())).status_code == 201


def test_content_type_must_be_gzip(user):
    env, token = user
    r = env.client.post(URL, content=gz(snapshot()), headers=hdr(token))
    assert r.status_code == 415
    assert r.json()["code"] == "unsupported_media_type"


def test_broken_gzip_rejected(user):
    env, token = user
    r = upload(env, token, b"definitely not gzip")
    assert r.status_code == 422 and r.json()["code"] == "backup_invalid"


def test_truncated_gzip_rejected(user):
    """只缺 trailer 的 gzip 也能解出全部内容，但没有 eof —— 必须当损坏拒收。

    这正是"上传到一半断了"的形态：若只看能不能解出字节，库里会存下一份看着正常、
    实际少了尾巴的快照，等恢复时才发现不对。
    """
    env, token = user
    r = upload(env, token, gz(snapshot())[:-8])
    assert r.status_code == 422 and r.json()["code"] == "backup_invalid"
    assert "不完整" in r.json()["detail"]


def test_broken_json_rejected(user):
    env, token = user
    r = upload(env, token, gz(b"{not json"))
    assert r.status_code == 422 and r.json()["code"] == "backup_invalid"


def test_unknown_version_rejected(user):
    env, token = user
    r = upload(env, token, gz(snapshot(v=2)))
    assert r.status_code == 422


def test_counts_mismatch_rejected(user):
    env, token = user
    data = snapshot()
    data["counts"]["tracks"] = 5
    r = upload(env, token, gz(data))
    assert r.status_code == 422


def test_track_without_platform_rejected(user):
    env, token = user
    r = upload(env, token, gz(snapshot(tracks=[track(platform="")])))
    assert r.status_code == 422
    r = upload(env, token, gz(snapshot(tracks=[track(video_id="")])))
    assert r.status_code == 422


def test_overlong_title_rejected(user):
    env, token = user
    r = upload(env, token, gz(snapshot(tracks=[track(title="长" * (mb.TITLE_MAX + 1))])))
    assert r.status_code == 422


def test_gzip_over_limit_rejected(user):
    env, token = user
    r = upload(env, token, b"x" * (mb.GZIP_MAX + 1))
    assert r.status_code == 413 and r.json()["code"] == "backup_too_large"


def test_too_many_tracks_rejected(user):
    env, token = user
    many = [track(video_id=f"v{i}") for i in range(mb.MAX_TRACKS + 1)]
    r = upload(env, token, gz(snapshot(tracks=many)))
    assert r.status_code == 413


def test_rejected_payload_does_not_consume_quota(user):
    """校验顺序：格式错的请求不计数，紧接着的合法上传必须成功。"""
    env, token = user
    assert upload(env, token, b"not gzip").status_code == 422
    assert upload(env, token, gz(snapshot(v=9))).status_code == 422
    assert upload(env, token, gz(snapshot())).status_code == 201


def test_upload_rate_limited_per_account(user):
    env, token = user
    assert upload(env, token, gz(snapshot())).status_code == 201
    second = upload(env, token, gz(snapshot()))
    assert second.status_code == 429
    assert second.json()["code"] == "rate_limited"
    assert int(second.headers["retry-after"]) > 0

    # 另一个账号不受影响（限频键带账号维度）
    other = register(env, name="bob").json()["session"]["token"]
    assert upload(env, other, gz(snapshot())).status_code == 201


def test_rejected_request_does_not_extend_window(user):
    """被拒的那次只加计数，不推迟窗口：+300 仍是 429，到 +600 必须放行。

    窗口锚在绝对时间网格上（`ratelimit._window`），被拒绝的请求计进当窗但不改变边界；
    若哪天改成"首次请求 + 600"或"被拒就顺延"，这条会先红。
    """
    env, token = user
    # 时钟先对齐到 600 秒网格：前提写明确，换初始时间也不会让用例随相位漂移
    env.clock.t = (int(env.clock()) // 600) * 600

    assert upload(env, token, gz(snapshot())).status_code == 201          # t0
    env.clock.advance(300)
    assert upload(env, token, gz(snapshot())).status_code == 429          # t0+300
    env.clock.advance(300)                                                # t0+600
    assert upload(env, token, gz(snapshot())).status_code == 201


def test_keeps_latest_three(user):
    env, token = user
    ids = []
    for _ in range(4):
        r = upload(env, token, gz(snapshot()))
        assert r.status_code == 201, r.text
        ids.append(r.json()["id"])
        env.clock.advance(600)

    listed = env.client.get(URL, headers=hdr(token)).json()
    assert [b["id"] for b in listed["backups"]] == list(reversed(ids[1:]))
    # 第 4 次上传时应把最旧的那份挤掉
    last = upload(env, token, gz(snapshot()))
    assert last.status_code == 201
    assert last.json()["pruned"] == [ids[1]]


def test_delete_one(user):
    env, token = user
    backup_id = upload(env, token, gz(snapshot())).json()["id"]
    r = env.client.delete(f"{URL}/{backup_id}", headers=hdr(token))
    assert r.status_code == 200 and r.json() == {"deleted": 1}
    assert env.client.get(URL, headers=hdr(token)).json()["backups"] == []
    # 再删一次：不存在的备份
    assert env.client.delete(f"{URL}/{backup_id}", headers=hdr(token)).status_code == 404


def test_delete_all(user):
    env, token = user
    upload(env, token, gz(snapshot()))
    env.clock.advance(600)
    upload(env, token, gz(snapshot()))
    r = env.client.delete(URL, headers=hdr(token))
    assert r.status_code == 200 and r.json() == {"deleted": 2}
    assert env.client.get(URL, headers=hdr(token)).json()["total_bytes"] == 0


def test_isolation_between_accounts(user):
    env, token = user
    backup_id = upload(env, token, gz(snapshot())).json()["id"]
    other = register(env, name="bob").json()["session"]["token"]

    assert env.client.get(URL, headers=hdr(other)).json()["backups"] == []
    assert env.client.get(f"{URL}/{backup_id}", headers=hdr(other)).status_code == 404
    assert env.client.delete(f"{URL}/{backup_id}", headers=hdr(other)).status_code == 404
    # 别人的删除请求不能真的删掉
    assert env.client.get(f"{URL}/{backup_id}", headers=hdr(token)).status_code == 200


def test_delete_account_removes_backups_immediately(user):
    env, token = user
    upload(env, token, gz(snapshot()))
    r = env.client.request("DELETE", "/account/me", json={"password": "correct horse 1"},
                           headers=hdr(token))
    assert r.status_code == 200
    with env.app.state.engine.begin() as c:
        assert c.execute(text("SELECT COUNT(*) FROM metadata_backups")).scalar_one() == 0


def test_run_deletions_removes_backups_too(user):
    """双保险：冷静期到期真正删号时再删一遍，防冷静期被绕过。"""
    env, token = user
    upload(env, token, gz(snapshot()))
    uid = env.client.get("/account/me", headers=hdr(token)).json()["id"]
    env.client.request("DELETE", "/account/me", json={"password": "correct horse 1"}, headers=hdr(token))

    # 重新造一条（模拟"停用之后又被写进来"的极端情况），再把冷静期拨到已到期
    with env.app.state.engine.begin() as c:
        c.execute(text("INSERT INTO metadata_backups(id,user_id,bytes,raw_bytes,sha256,counts_json,"
                       "app_version,device,payload,created_at) "
                       "VALUES('bkp_0000000000000001',:u,1,1,'x','{}','','',x'00',:n)"),
                  {"u": uid, "n": int(env.clock())})
        c.execute(text("UPDATE deletion_requests SET execute_at=:n WHERE user_id=:u"),
                  {"n": int(env.clock()) - 1, "u": uid})
    assert env.app.state.ops.run_deletions() == 1
    with env.app.state.engine.begin() as c:
        assert c.execute(text("SELECT COUNT(*) FROM metadata_backups")).scalar_one() == 0


def test_login_after_delete_still_works_for_new_account(user):
    """删号后同名账号重新注册，云端是干净的（不该继承旧备份）。"""
    env, token = user
    upload(env, token, gz(snapshot()))
    env.client.request("DELETE", "/account/me", json={"password": "correct horse 1"}, headers=hdr(token))
    fresh = register(env, name="alice2").json()["session"]["token"]
    assert env.client.get(URL, headers=hdr(fresh)).json()["backups"] == []
