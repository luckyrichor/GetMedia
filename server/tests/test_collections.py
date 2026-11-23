"""整理信息跨设备同步：收藏、类别路径、播放列表（schema v19）。"""
from __future__ import annotations

import shutil
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from conftest import hdr, make_owner
from getmedia.alerts import RecordingAlertSink
from getmedia.api import create_app
from getmedia.config import testing_settings as make_settings
from getmedia.extractor import FakeResolver

ROOT = Path(__file__).resolve().parents[2]


def build(tmp_path):
    tmp_path.mkdir(parents=True, exist_ok=True)
    legal = tmp_path / "legal"
    legal.mkdir()
    for f in ("user-agreement.md", "privacy-policy.md"):
        shutil.copy(ROOT / "docs" / "legal" / f, legal / f)
    settings = make_settings(tmp_path, legal)

    class C:
        t = 1_800_000_000.0

        def __call__(self):
            return self.t

        def advance(self, s):
            self.t += s

    clock = C()
    app = create_app(settings, clock=clock, alerts=RecordingAlertSink(),
                     media_resolver=FakeResolver())
    client = TestClient(app, base_url="https://testserver")

    class E:
        pass

    e = E()
    e.settings, e.clock, e.app, e.client, e.tmp = settings, clock, app, client, tmp_path
    return e


@pytest.fixture
def cenv(tmp_path):
    env = build(tmp_path)
    env.app.state.runtime.write(registration_open=True)
    env.token = make_owner(env, name="boss")
    return env


def add_track(env, text="BVtest0001", media_type="video"):
    r = env.client.post("/v2/extract", headers=hdr(env.token),
                        json={"input": text, "media_type": media_type, "storage": "metadata"})
    job = env.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(env.token)).json()
    return job["track_id"]


def test_patch_favorite_and_category(cenv):
    tid = add_track(cenv)
    p = cenv.client.patch(f"/v2/tracks/{tid}", headers=hdr(cenv.token),
                          json={"favorite": True, "category": "音乐/古风"})
    assert p.status_code == 200, p.text
    assert p.json()["favorite"] is True and p.json()["category"] == "音乐/古风"

    # 同步通道要带上这两个字段：客户端只靠它拿到整理信息
    page = cenv.client.get("/v2/sync?since_seq=0", headers=hdr(cenv.token)).json()
    row = next(i for i in page["items"] if i["id"] == tid)
    assert row["favorite"] is True and row["category"] == "音乐/古风"

    # 只改收藏：类别不受影响
    p = cenv.client.patch(f"/v2/tracks/{tid}", headers=hdr(cenv.token), json={"favorite": False})
    assert p.json()["favorite"] is False and p.json()["category"] == "音乐/古风"

    # 类别传空串是"清空"；不传这个字段才是"不动"
    p = cenv.client.patch(f"/v2/tracks/{tid}", headers=hdr(cenv.token), json={"category": ""})
    assert p.json()["category"] == ""
    p = cenv.client.patch(f"/v2/tracks/{tid}", headers=hdr(cenv.token), json={"title": "改名"})
    assert p.json()["category"] == ""


def test_category_is_normalized(cenv):
    tid = add_track(cenv)
    p = cenv.client.patch(f"/v2/tracks/{tid}", headers=hdr(cenv.token),
                          json={"category": "  音乐 / / 古风  "})
    assert p.json()["category"] == "音乐/古风"


def test_playlist_put_and_list(cenv):
    a = cenv.client.put("/v2/sync/playlists", headers=hdr(cenv.token),
                        json={"name": "跑步", "items": [
                            {"platform": "douyin", "video_id": "111", "page": 1},
                            {"platform": "bilibili", "video_id": "BV1", "page": 2},
                        ]})
    assert a.status_code == 200, a.text
    assert [i["video_id"] for i in a.json()["items"]] == ["111", "BV1"]

    cenv.client.put("/v2/sync/playlists", headers=hdr(cenv.token),
                    json={"name": "睡前", "items": [{"platform": "local", "video_id": "abc"}]})

    out = cenv.client.get("/v2/sync/playlists", headers=hdr(cenv.token)).json()["playlists"]
    assert [p["name"] for p in out] == ["睡前", "跑步"]
    run = next(p for p in out if p["name"] == "跑步")
    assert [i["order_index"] for i in run["items"]] == [0, 1]
    assert run["deleted"] is False


def test_playlist_replace_reorders_and_dedupes(cenv):
    cenv.client.put("/v2/sync/playlists", headers=hdr(cenv.token),
                    json={"name": "跑步", "items": [
                        {"platform": "douyin", "video_id": "111"},
                        {"platform": "douyin", "video_id": "222"},
                    ]})
    # 整体替换：顺序反转，并带一条重复键（保留最后一次的位置）
    r = cenv.client.put("/v2/sync/playlists", headers=hdr(cenv.token),
                        json={"name": "跑步", "items": [
                            {"platform": "douyin", "video_id": "222"},
                            {"platform": "douyin", "video_id": "111"},
                            {"platform": "douyin", "video_id": "222"},
                        ]})
    assert [i["video_id"] for i in r.json()["items"]] == ["111", "222"], r.text
    out = cenv.client.get("/v2/sync/playlists", headers=hdr(cenv.token)).json()["playlists"]
    assert [i["video_id"] for i in out[0]["items"]] == ["111", "222"]


def test_playlist_delete_is_tombstone_and_can_revive(cenv):
    cenv.client.put("/v2/sync/playlists", headers=hdr(cenv.token),
                    json={"name": "跑步", "items": [{"platform": "douyin", "video_id": "111"}]})
    d = cenv.client.delete("/v2/sync/playlists", headers=hdr(cenv.token), params={"name": "跑步"})
    assert d.status_code == 200 and d.json()["deleted"] is True

    out = cenv.client.get("/v2/sync/playlists", headers=hdr(cenv.token)).json()["playlists"]
    assert out[0]["deleted"] is True and out[0]["items"] == []

    # 同名的再写一次就是复活
    cenv.client.put("/v2/sync/playlists", headers=hdr(cenv.token),
                    json={"name": "跑步", "items": [{"platform": "douyin", "video_id": "222"}]})
    out = cenv.client.get("/v2/sync/playlists", headers=hdr(cenv.token)).json()["playlists"]
    assert out[0]["deleted"] is False
    assert [i["video_id"] for i in out[0]["items"]] == ["222"]


def test_playlist_delete_unknown_name_is_idempotent(cenv):
    d = cenv.client.delete("/v2/sync/playlists", headers=hdr(cenv.token), params={"name": "没有这个"})
    assert d.status_code == 200 and d.json()["deleted"] is True


def test_playlist_rejects_bad_input(cenv):
    bad = cenv.client.put("/v2/sync/playlists", headers=hdr(cenv.token),
                          json={"name": "跑步", "items": [{"platform": "youtube", "video_id": "x"}]})
    assert bad.status_code == 422
    empty = cenv.client.put("/v2/sync/playlists", headers=hdr(cenv.token),
                            json={"name": "", "items": []})
    assert empty.status_code == 422
    extra = cenv.client.put("/v2/sync/playlists", headers=hdr(cenv.token),
                            json={"name": "跑步", "items": [], "owner": "someone"})
    assert extra.status_code == 422


def test_playlist_is_per_account(cenv):
    cenv.client.put("/v2/sync/playlists", headers=hdr(cenv.token),
                    json={"name": "跑步", "items": [{"platform": "douyin", "video_id": "111"}]})
    other = make_owner(cenv, name="other")
    assert cenv.client.get("/v2/sync/playlists", headers=hdr(other)).json()["playlists"] == []
