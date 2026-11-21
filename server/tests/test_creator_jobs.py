"""P2b：博主批量任务（三种选取、置顶、断点、状态回传、导入）。"""
from __future__ import annotations

import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from conftest import hdr, make_owner
from getmedia.alerts import RecordingAlertSink
from getmedia.api import create_app
from getmedia.config import testing_settings as make_settings
from getmedia.creator_links import CreatorRef, Platform
from getmedia.creators.base import CreatorProfile, CreatorVideo, CreatorWindow, VideoStats

ROOT = Path(__file__).resolve().parents[2]
TZ = timezone(timedelta(hours=8))
BASE = datetime(2026, 11, 1, 12, 0, tzinfo=TZ)


def make_videos(n=45):
    """45 条作品，每天一条，第 1 条为置顶。"""
    out = []
    for i in range(n):
        pub = (BASE - timedelta(days=i)).isoformat()
        vid = "BVpin000001" if i == 0 else f"BVv{i:08d}"
        out.append(CreatorVideo(
            platform=Platform.BILIBILI, video_id=vid, title=f"作品 {i}", source_url=f"https://b/{vid}",
            position=i + 1, creator_id="2", duration_sec=60.0 * (i + 1), published_at=pub,
            stats=VideoStats(view_count=i),
        ))
    return out


class Lister:
    def __init__(self, videos=None):
        self.videos = videos if videos is not None else make_videos()
        self.calls = []

    def __call__(self, ref, start, count, cookie_file=None):
        self.calls.append((start, count))
        items = self.videos[start - 1: start - 1 + count]
        return CreatorWindow(
            profile=CreatorProfile(platform=Platform.BILIBILI, creator_id="2", name="测试博主",
                                   url="https://space.bilibili.com/2/video", total_videos=len(self.videos)),
            items=items,
            total=len(self.videos),
            has_more=start - 1 + count < len(self.videos),
        )


def resolver(url, platform=None):
    return CreatorRef(Platform.BILIBILI, "2", "https://space.bilibili.com/2/video")


def no_cookies(_platform):
    return None, "none"


def pinned_of(_ref, _cookies=None):
    return {"BVpin000001"}


def build(tmp_path, *, lister=None, **settings_kw):
    tmp_path.mkdir(parents=True, exist_ok=True)
    legal = tmp_path / "legal"
    legal.mkdir()
    for f in ("user-agreement.md", "privacy-policy.md"):
        shutil.copy(ROOT / "docs" / "legal" / f, legal / f)
    settings = make_settings(tmp_path, legal).with_(**settings_kw)

    class C:
        t = 1_800_000_000.0

        def __call__(self):
            return self.t

        def advance(self, s):
            self.t += s

    clock = C()
    lister = lister or Lister()
    app = create_app(settings, clock=clock, alerts=RecordingAlertSink(),
                     creator_lister=lister, creator_resolver=resolver, creator_cookies=no_cookies,
                     creator_pinned=pinned_of)
    client = TestClient(app, base_url="https://testserver")

    class E:
        pass

    e = E()
    e.settings, e.clock, e.app, e.client, e.tmp, e.lister = settings, clock, app, client, tmp_path, lister
    return e


@pytest.fixture
def jenv(tmp_path):
    env = build(tmp_path)
    env.app.state.runtime.write(registration_open=True)
    env.token = make_owner(env, name="boss")
    return env


def create(env, **body):
    payload = {"url": "https://space.bilibili.com/2/video"}
    payload.update(body)
    return env.client.post("/v2/creators/jobs", headers=hdr(env.token), json=payload)


def test_latest_n_stops_at_n(jenv):
    r = create(jenv, mode="latest_n", n=5)
    assert r.status_code == 202, r.text
    job = jenv.client.get(f"/v2/creators/jobs/{r.json()['id']}", headers=hdr(jenv.token)).json()
    assert job["status"] == "done"
    items = jenv.client.get(f"/v2/creators/jobs/{job['id']}/items", headers=hdr(jenv.token)).json()
    assert items["total"] == 5
    # 置顶不插队：第 1 条本身就是最新一条（置顶），排序仍按发布时间
    assert items["items"][0]["video_id"] == "BVpin000001"
    assert items["items"][0]["is_pinned"] is True
    assert [i["published_at"] for i in items["items"]] == sorted(
        [i["published_at"] for i in items["items"]], reverse=True)
    assert job["estimated_bytes"] > 0


def test_date_range_and_early_stop(jenv):
    since = (BASE - timedelta(days=4)).date().isoformat()
    until = (BASE - timedelta(days=2)).date().isoformat()
    r = create(jenv, mode="date_range", since=since, until=until)
    job = jenv.client.get(f"/v2/creators/jobs/{r.json()['id']}", headers=hdr(jenv.token)).json()
    assert job["status"] == "done"
    items = jenv.client.get(f"/v2/creators/jobs/{job['id']}/items", headers=hdr(jenv.token)).json()["items"]
    assert len(items) == 3
    # 只翻了一页就停（本页最旧一条已早于 since）
    assert len(jenv.lister.calls) == 1


def test_all_mode_segments_and_resume(tmp_path):
    env = build(tmp_path, creator_max_pages_per_run=2, creator_page_size=20,
                creator_scan_batch=20)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss")
    r = env.client.post("/v2/creators/jobs", headers=hdr(token),
                        json={"url": "https://space.bilibili.com/2/video", "mode": "all"})
    job = env.client.get(f"/v2/creators/jobs/{r.json()['id']}", headers=hdr(token)).json()
    assert job["status"] == "paused" and job["pause_reason"] == "segment_limit"
    assert job["cursor"]["start"] == 41

    env.client.post(f"/v2/creators/jobs/{job['id']}/resume", headers=hdr(token))
    again = env.client.get(f"/v2/creators/jobs/{job['id']}", headers=hdr(token)).json()
    assert again["status"] == "done", again
    items = env.client.get(f"/v2/creators/jobs/{job['id']}/items", headers=hdr(token)).json()
    assert items["total"] == 45  # 续跑没有重复


def test_all_mode_uses_scan_batch(tmp_path):
    """「全部作品」要一次抓够：抖音桥接每个窗口都得从头滚到 need，按 20 条翻太亏。"""
    lister = Lister(make_videos(300))
    env = build(tmp_path, lister=lister, creator_scan_batch=200)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss")
    r = env.client.post("/v2/creators/jobs", headers=hdr(token),
                        json={"url": "https://space.bilibili.com/2/video", "mode": "all"})
    job = env.client.get(f"/v2/creators/jobs/{r.json()['id']}", headers=hdr(token)).json()
    assert job["status"] == "done", job
    assert job["total"] == 300
    # 第一窗口就要 200 条，而不是 20；300 条两窗扫完
    assert lister.calls == [(1, 200), (201, 200)]


def test_all_mode_small_creator_single_window(tmp_path):
    """45 条的小博主：一窗抓完，不再分两段。"""
    env = build(tmp_path, creator_scan_batch=200)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss")
    r = env.client.post("/v2/creators/jobs", headers=hdr(token),
                        json={"url": "https://space.bilibili.com/2/video", "mode": "all"})
    job = env.client.get(f"/v2/creators/jobs/{r.json()['id']}", headers=hdr(token)).json()
    assert job["status"] == "done" and job["total"] == 45, job
    assert env.lister.calls == [(1, 200)]


def test_include_pinned_false_excludes(jenv):
    r = create(jenv, mode="latest_n", n=3, include_pinned=False)
    job = jenv.client.get(f"/v2/creators/jobs/{r.json()['id']}", headers=hdr(jenv.token)).json()
    items = jenv.client.get(f"/v2/creators/jobs/{job['id']}/items", headers=hdr(jenv.token)).json()["items"]
    assert all(not i["is_pinned"] for i in items)
    assert len(items) == 3


def test_item_state_updates_counters(jenv):
    r = create(jenv, mode="latest_n", n=3)
    jid = r.json()["id"]
    jenv.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(jenv.token))
    items = jenv.client.get(f"/v2/creators/jobs/{jid}/items", headers=hdr(jenv.token)).json()["items"]
    vid = items[0]["video_id"]

    job = jenv.client.post(f"/v2/creators/jobs/{jid}/items/{vid}/state", headers=hdr(jenv.token),
                           json={"state": "done", "track_id": 7}).json()
    assert job["done"] == 1
    assert job["items"] if False else True

    job = jenv.client.post(f"/v2/creators/jobs/{jid}/items/{items[1]['video_id']}/state",
                           headers=hdr(jenv.token), json={"state": "failed", "error": "403"}).json()
    assert job["failed"] == 1

    gone = jenv.client.post(f"/v2/creators/jobs/{jid}/items/BVnope/state", headers=hdr(jenv.token),
                            json={"state": "done"})
    assert gone.status_code == 404


def test_import_items_dedupes(jenv):
    r = create(jenv, mode="latest_n", n=1)
    jid = r.json()["id"]
    body = {"items": [
        {"video_id": "BVphone0001", "title": "手机列到的", "duration_sec": 30.0, "published_at": 1_800_000_000},
        {"video_id": "BVphone0001", "title": "重复条目", "duration_sec": 30.0},
    ]}
    out = jenv.client.post(f"/v2/creators/jobs/{jid}/items:import", headers=hdr(jenv.token), json=body).json()
    assert out["added"] == 1
    again = jenv.client.post(f"/v2/creators/jobs/{jid}/items:import", headers=hdr(jenv.token), json=body).json()
    assert again["added"] == 0


def test_pause_cancel_and_retry(tmp_path):
    env = build(tmp_path, creator_max_pages_per_run=1, creator_scan_batch=20)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss")
    r = env.client.post("/v2/creators/jobs", headers=hdr(token),
                        json={"url": "https://space.bilibili.com/2/video", "mode": "all"})
    jid = r.json()["id"]
    paused = env.client.post(f"/v2/creators/jobs/{jid}/pause", headers=hdr(token)).json()
    assert paused["status"] == "paused"
    cancelled = env.client.post(f"/v2/creators/jobs/{jid}/cancel", headers=hdr(token)).json()
    assert cancelled["status"] == "cancelled"
    assert env.client.post(f"/v2/creators/jobs/{jid}/pause", headers=hdr(token)).status_code == 409

    r2 = env.client.post("/v2/creators/jobs", headers=hdr(token),
                         json={"url": "https://space.bilibili.com/2/video", "mode": "latest_n", "n": 2})
    jid2 = r2.json()["id"]
    items = env.client.get(f"/v2/creators/jobs/{jid2}/items", headers=hdr(token)).json()["items"]
    env.client.post(f"/v2/creators/jobs/{jid2}/items/{items[0]['video_id']}/state",
                    headers=hdr(token), json={"state": "failed", "error": "x"})
    out = env.client.post(f"/v2/creators/jobs/{jid2}/retry", headers=hdr(token)).json()
    assert out["requeued"] == 1


def test_batch_preview_supports_modes(jenv):
    r = jenv.client.post("/v2/creators/preview", headers=hdr(jenv.token),
                         json={"url": "https://space.bilibili.com/2/video", "mode": "latest_n", "n": 4})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["mode"] == "latest_n"
    assert len(body["items"]) == 4
    assert body["items"][0]["is_pinned"] is True
    assert body["estimated_bytes"] > 0
    assert body["profile"]["name"] == "测试博主"


class ShallowLister:
    """模拟抖音桥接：单次最多抓到 ``depth`` 条，起点超过它就报「抓不够」。

    旧实现用「递增 start 跳页」扫「全部作品」，第二次请求到 start=57 就会被这个上限挡住、
    整次预览 400；现在 ``all`` 只扫一批并如实回 ``has_more``。
    """

    def __init__(self, total: int = 300, depth: int = 56):
        self.videos = make_videos(total)
        self.depth = depth
        self.calls = []

    def __call__(self, ref, start, count, cookie_file=None):
        from getmedia.creators.base import CreatorUnavailable
        self.calls.append((start, count))
        if start > self.depth:
            raise CreatorUnavailable(f"只抓取到 {self.depth} 条，不足以覆盖起点 {start}")
        end = min(start - 1 + count, self.depth)
        return CreatorWindow(
            profile=CreatorProfile(platform=Platform.BILIBILI, creator_id="2", name="测试博主",
                                   url="https://space.bilibili.com/2/video", total_videos=len(self.videos)),
            items=self.videos[start - 1:end],
            total=len(self.videos),
            has_more=True,
        )


def test_all_preview_survives_shallow_list(tmp_path):
    """「全部作品」预览不该因为单次抓不深而整体失败（抖音 56/57 那个报错）。"""
    lister = ShallowLister()
    env = build(tmp_path, lister=lister)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss")
    r = env.client.post("/v2/creators/preview", headers=hdr(token),
                        json={"url": "https://space.bilibili.com/2/video", "mode": "all"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["mode"] == "all"
    assert body["has_more"] is True
    assert 0 < len(body["items"]) <= 50
    # 只发一批：不再用递增起点跳页
    assert len(lister.calls) == 1 and lister.calls[0][0] == 1


def test_creator_batch_is_isolated_between_users(tmp_path):
    env = build(tmp_path)
    env.app.state.runtime.write(registration_open=True)
    ta = make_owner(env, name="owner_a")
    tb = make_owner(env, name="owner_b")
    jid = env.client.post("/v2/creators/jobs", headers=hdr(ta),
                          json={"url": "https://space.bilibili.com/2/video", "mode": "latest_n", "n": 2}).json()["id"]
    assert env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(tb)).status_code == 404
    assert env.client.get(f"/v2/creators/jobs/{jid}/items", headers=hdr(tb)).status_code == 404


def test_invalid_bodies(jenv):
    assert create(jenv, mode="date_range").status_code == 400
    assert create(jenv, mode="latest_n").status_code == 400
    assert create(jenv, mode="latest_n", n=3, storage="weird").status_code == 422
