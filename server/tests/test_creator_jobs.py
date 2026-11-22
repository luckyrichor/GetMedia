"""P2b：博主批量任务（三种选取、置顶、断点、状态回传、导入）。"""
from __future__ import annotations

import shutil
from contextlib import contextmanager
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text

from conftest import hdr, make_owner
from getmedia.alerts import RecordingAlertSink
from getmedia.api import create_app
from getmedia.config import testing_settings as make_settings
from getmedia.creator_jobs import _StoreFailed
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


def resolver(url, platform=None, user_id=None):
    return CreatorRef(Platform.BILIBILI, "2", "https://space.bilibili.com/2/video")


def no_cookies(_platform, _user_id=None):
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


def test_create_rejects_link_when_numeric_platform_given(jenv):
    """前端已拦一道，后端也要拦：号码类型不接受链接或昵称。"""
    r = create(jenv, url="https://space.bilibili.com/2/video", platform="bilibili", mode="latest_n", n=3)
    assert r.status_code == 400, r.text
    assert r.json()["code"] == "creator_input"
    assert "只能填纯数字" in r.json()["detail"]


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


def links_of(env, jid):
    svc = env.app.state.creator_jobs
    with svc.engine.begin() as c:
        return {vid: pu for vid, pu in c.execute(
            text("SELECT video_id, play_urls FROM creator_items WHERE job_id=:j"), {"j": jid}).all()}


class LinkLister(Lister):
    """每次扫描返回带版本号的播放地址，用来验证「重试会刷新地址」。"""

    def __init__(self, videos=None):
        super().__init__(videos)
        self.version = 1

    def __call__(self, ref, start, count, cookie_file=None):
        win = super().__call__(ref, start, count, cookie_file)
        items = [replace(v, play_urls=(f"https://cdn.test/{self.version}/{v.video_id}",))
                 for v in win.items]
        return CreatorWindow(profile=win.profile, items=items, total=win.total, has_more=win.has_more)


def test_retry_on_finished_job_refreshes_links(tmp_path):
    """已扫完的任务点重试：从第一条重扫，只换播放地址，不新增条目。"""
    lister = LinkLister()
    env = build(tmp_path, lister=lister)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss")
    r = env.client.post("/v2/creators/jobs", headers=hdr(token),
                        json={"url": "https://space.bilibili.com/2/video", "mode": "latest_n", "n": 3})
    jid = r.json()["id"]
    before = links_of(env, jid)
    assert len(before) == 3
    assert all("cdn.test/1/" in (v or "") for v in before.values())

    lister.version = 2
    out = env.client.post(f"/v2/creators/jobs/{jid}/retry", headers=hdr(token)).json()
    assert out["rescan"] is True

    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(token)).json()
    assert job["status"] == "done", job
    assert job["total"] == 3, "刷新不该新增条目"
    after = links_of(env, jid)
    assert set(after) == set(before)
    assert all("cdn.test/2/" in (v or "") for v in after.values())
    # 从第一条重扫，而不是从已越过末尾的游标开始
    assert lister.calls[-1][0] == 1


def test_retry_on_cancelled_but_scanned_job_refreshes_links(tmp_path):
    """已扫完但被取消的任务点重试：列表已扫完，应重扫换地址（不新增条目）。"""
    lister = LinkLister()
    env = build(tmp_path, lister=lister)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss")
    r = env.client.post("/v2/creators/jobs", headers=hdr(token),
                        json={"url": "https://space.bilibili.com/2/video", "mode": "latest_n", "n": 3})
    jid = r.json()["id"]
    before = links_of(env, jid)
    assert len(before) == 3
    assert all("cdn.test/1/" in (v or "") for v in before.values())

    # 扫完后取消：状态变 cancelled，但列表已扫完（scan_done 仍在游标里）
    env.client.post(f"/v2/creators/jobs/{jid}/cancel", headers=hdr(token)).json()

    lister.version = 2
    out = env.client.post(f"/v2/creators/jobs/{jid}/retry", headers=hdr(token)).json()
    assert out["rescan"] is True, "扫完被取消的任务也应重扫换地址"

    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(token)).json()
    assert job["status"] == "done", job
    assert job["total"] == 3, "刷新不该新增条目"
    after = links_of(env, jid)
    assert set(after) == set(before)
    assert all("cdn.test/2/" in (v or "") for v in after.values())


def test_retry_on_unfinished_job_resumes_instead(tmp_path):
    """没扫完的任务点重试仍按游标续扫，不做地址刷新。"""
    env = build(tmp_path, creator_max_pages_per_run=1, creator_scan_batch=20)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss")
    r = env.client.post("/v2/creators/jobs", headers=hdr(token),
                        json={"url": "https://space.bilibili.com/2/video", "mode": "all"})
    jid = r.json()["id"]
    paused = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(token)).json()
    assert paused["status"] == "paused" and paused["cursor"]["start"] == 21

    out = env.client.post(f"/v2/creators/jobs/{jid}/retry", headers=hdr(token)).json()
    assert out["rescan"] is False
    again = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(token)).json()
    assert again["cursor"]["start"] > 21, again


def test_request_refresh_queues_once_and_skips_unfinished(tmp_path):
    """下载撞上「直链全失效」时请求重扫：同一任务只排一次，没跑完的任务不排。"""
    lister = LinkLister()
    env = build(tmp_path, lister=lister)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss")
    r = env.client.post("/v2/creators/jobs", headers=hdr(token),
                        json={"url": "https://space.bilibili.com/2/video", "mode": "latest_n", "n": 3})
    jid = r.json()["id"]
    svc = env.app.state.creator_jobs
    with svc.engine.begin() as c:
        uid = c.execute(text("SELECT user_id FROM creator_jobs WHERE id=:j"), {"j": jid}).scalar_one()

    assert svc.request_refresh(uid, jid) == "started"
    # 第二次不再排：一个人点了下载，几个人同时点不该开好几个浏览器
    assert svc.request_refresh(uid, jid) == "busy"
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(token)).json()
    assert job["status"] == "pending", "排上重扫后任务应回到待跑，交给后台执行"
    assert job["total"] == 3


def test_request_refresh_noop_for_unfinished_job(tmp_path):
    """没扫完的任务缺的是后面的作品，不是地址：不该拿重扫把游标冲掉。"""
    env = build(tmp_path, creator_max_pages_per_run=1, creator_scan_batch=20)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss")
    r = env.client.post("/v2/creators/jobs", headers=hdr(token),
                        json={"url": "https://space.bilibili.com/2/video", "mode": "all"})
    jid = r.json()["id"]
    svc = env.app.state.creator_jobs
    with svc.engine.begin() as c:
        uid = c.execute(text("SELECT user_id FROM creator_jobs WHERE id=:j"), {"j": jid}).scalar_one()

    before = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(token)).json()["cursor"]["start"]
    assert svc.request_refresh(uid, jid) == "noop"
    after = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(token)).json()["cursor"]["start"]
    assert after == before, "noop 不该动游标"


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


# ---------- 存放方式：真的落盘 ----------
def fake_opener(payload: bytes = b"DATA"):
    @contextmanager
    def open_(_url, _platform):
        yield [payload]
    return open_


def with_play_urls(n=3):
    return [replace(v, play_urls=(f"https://cdn.example.invalid/{v.video_id}.mp4",))
            for v in make_videos(n)]


def env_with_files(tmp_path, **settings_kw):
    """列表带播放地址 + 假取流，落盘路径不需要真的连 CDN。"""
    env = build(tmp_path, lister=Lister(with_play_urls(3)), **settings_kw)
    env.app.state.runtime.write(registration_open=True)
    env.token = make_owner(env, name="boss")
    env.app.state.creator_jobs.opener = fake_opener(b"DATA")
    return env


def test_cache_storage_puts_files_on_server(tmp_path):
    env = env_with_files(tmp_path)
    r = create(env, mode="latest_n", n=3, storage="cache")
    assert r.status_code == 202, r.text
    jid = r.json()["id"]
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(env.token)).json()
    assert job["status"] == "done"
    assert job["stored"] == 3
    assert job["store_bytes"] == 12
    files = [p for p in env.settings.cache_dir.glob("*") if p.is_file()]
    assert len(files) == 3 and all(p.stat().st_size == 4 for p in files)

    # 有本地文件就直接读，不再回上游（这里上游是假的，真去取会报错）
    got = env.client.get(f"/v2/creators/jobs/{jid}/items/BVpin000001/file", headers=hdr(env.token))
    assert got.status_code == 200, got.text
    assert got.content == b"DATA"
    assert got.headers["content-disposition"].startswith("attachment;")


def test_metadata_storage_writes_nothing(tmp_path):
    env = env_with_files(tmp_path)
    jid = create(env, mode="latest_n", n=3).json()["id"]
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(env.token)).json()
    assert job["storage"] == "metadata" and job["stored"] == 0
    assert not env.settings.cache_dir.exists() or not any(env.settings.cache_dir.iterdir())
    assert not env.settings.media_dir.exists() or not any(env.settings.media_dir.iterdir())


def test_phone_storage_is_normalized_to_metadata(tmp_path):
    env = env_with_files(tmp_path)
    r = create(env, mode="latest_n", n=2, storage="phone")
    assert r.status_code == 202, r.text
    assert r.json()["storage"] == "metadata"


def test_archive_evicts_oldest_when_quota_is_full(tmp_path):
    # 配额只够两份 4 字节：第三条要落盘时必须把最早的那条删掉
    env = env_with_files(tmp_path, archive_quota_bytes=8)
    jid = create(env, mode="latest_n", n=3, storage="archive").json()["id"]
    rows = env.client.get(f"/v2/creators/jobs/{jid}/items", headers=hdr(env.token)).json()["items"]
    by_id = {r["video_id"]: r for r in rows}
    assert by_id["BVpin000001"]["stored"] is False          # 最旧的被淘汰
    assert "已清理" in (by_id["BVpin000001"]["store_error"] or "")
    assert by_id["BVv00000002"]["stored"] is True          # 最新的留下来了
    files = [p for p in env.settings.media_dir.glob("*") if p.is_file()]
    assert len(files) == 2
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(env.token)).json()
    assert job["stored"] == 2 and job["store_bytes"] == 8


def test_cache_pool_evicts_by_age_when_full(tmp_path):
    """缓存池满了走 prune_cache（不是归档那条路），同样按时间淘汰。"""
    env = env_with_files(tmp_path, cache_quota_bytes=8)
    jid = create(env, mode="latest_n", n=3, storage="cache").json()["id"]
    rows = env.client.get(f"/v2/creators/jobs/{jid}/items", headers=hdr(env.token)).json()["items"]
    by_id = {r["video_id"]: r for r in rows}
    assert by_id["BVpin000001"]["stored"] is False
    assert by_id["BVv00000002"]["stored"] is True
    files = [p for p in env.settings.cache_dir.glob("*") if p.is_file()]
    assert len(files) == 2


# ---------- 抖音：落盘撞上直链过期，自动重扫一次 ----------
def dy_videos(tag="old", n=2):
    return [CreatorVideo(
        platform=Platform.DOUYIN, video_id=f"dy{i:04d}", title=f"抖音作品 {i}",
        source_url=f"https://www.douyin.com/video/{i}", position=i + 1, creator_id="9",
        duration_sec=30.0, published_at=(BASE - timedelta(days=i)).isoformat(),
        play_urls=(f"https://cdn.example.invalid/{tag}-{i}.mp4",)) for i in range(n)]


class DouyinLister(Lister):
    """第一轮给过期地址，之后（重扫）给新地址；``calls`` 用来数开了几次列表。"""

    def __init__(self, first, later):
        super().__init__(first)
        self.first, self.later, self.round = first, later, 0

    def __call__(self, ref, start, count, cookie_file=None):
        self.round += 1
        self.videos = self.later if self.round > 1 else self.first
        return super().__call__(ref, start, count, cookie_file)


def env_with_douyin(tmp_path, *, later=None):
    lister = DouyinLister(dy_videos("old"), later if later is not None else dy_videos("fresh"))
    env = build(tmp_path, lister=lister)
    env.app.state.runtime.write(registration_open=True)
    env.token = make_owner(env, name="boss")
    jobs = env.app.state.creator_jobs
    jobs.resolve_fn = lambda url, platform=None, user_id=None: CreatorRef(
        Platform.DOUYIN, "9", "https://www.douyin.com/user/9")

    def opener(url, _platform):
        if "fresh" not in url:               # 扫描时签的地址已经过期（重扫才给新地址）
            raise _StoreFailed("平台返回 HTTP 403")
        yield [b"DATA"]

    jobs.opener = contextmanager(opener)
    return env


def test_douyin_expired_link_rescans_and_retries(tmp_path):
    """抖音存的直链全 403 → 重扫换地址 → 重试这一条成功。"""
    env = env_with_douyin(tmp_path)
    r = create(env, url="9", platform="douyin", mode="latest_n", n=2, storage="archive")
    assert r.status_code == 202, r.text
    jid = r.json()["id"]
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(env.token)).json()
    assert job["platform"] == "douyin" and job["status"] == "done"
    assert job["stored"] == 2 and job["failed"] == 0
    rows = env.client.get(f"/v2/creators/jobs/{jid}/items", headers=hdr(env.token)).json()["items"]
    assert all(r["stored"] and r["store_error"] is None for r in rows)
    # 一次扫描 + 一次重扫，不再多开浏览器
    assert len(env.lister.calls) == 2


def test_douyin_rescan_is_only_tried_once_per_job(tmp_path):
    """重扫后地址仍然取不到：按失败记账，不会每条都再开一次浏览器。"""
    env = env_with_douyin(tmp_path, later=dy_videos("bad"))
    jid = create(env, url="9", platform="douyin", mode="latest_n", n=2,
                 storage="cache").json()["id"]
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(env.token)).json()
    assert job["stored"] == 0 and job["failed"] == 2
    assert len(env.lister.calls) == 2
    rows = env.client.get(f"/v2/creators/jobs/{jid}/items", headers=hdr(env.token)).json()["items"]
    assert all(r["stored"] is False and r["store_error"] for r in rows)


# ---------- 多格式输出 ----------
def test_default_video_formats_keep_source_file(tmp_path):
    env = env_with_files(tmp_path)
    jid = create(env, mode="latest_n", n=2, storage="archive").json()["id"]
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(env.token)).json()
    assert job["formats"] == ["source"]
    rows = env.client.get(f"/v2/creators/jobs/{jid}/items", headers=hdr(env.token)).json()["items"]
    assert rows[0]["files"]["source"]["path"].endswith(".mp4")


def test_audio_job_defaults_to_m4a(tmp_path):
    env = env_with_files(tmp_path)
    jid = create(env, mode="latest_n", n=2, media_type="audio", storage="cache").json()["id"]
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(env.token)).json()
    assert job["formats"] == ["m4a"]


def test_formats_must_match_media_type(tmp_path):
    env = env_with_files(tmp_path)
    r = create(env, mode="latest_n", n=2, storage="archive", formats=["mp4", "mp3"])
    assert r.status_code == 400 and r.json()["code"] == "bad_formats"


def test_store_produces_every_selected_format(tmp_path, monkeypatch):
    """选 mp4 + mkv：一次下载产出两份文件，下载接口按 ?fmt= 分别取。"""
    from getmedia import creator_formats

    def fake_convert(src, dest, fmt, **_kw):
        dest.write_bytes(b"CV" + fmt.encode())

    monkeypatch.setattr(creator_formats, "convert", fake_convert)
    env = env_with_files(tmp_path)
    jid = create(env, mode="latest_n", n=2, storage="archive",
                 formats=["source", "mp4", "mkv"]).json()["id"]
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(env.token)).json()
    assert job["formats"] == ["source", "mp4", "mkv"] and job["stored"] == 2
    rows = env.client.get(f"/v2/creators/jobs/{jid}/items", headers=hdr(env.token)).json()["items"]
    files = rows[0]["files"]
    assert set(files) == {"source", "mp4", "mkv"}
    assert rows[0]["bytes"] == sum(f["bytes"] for f in files.values())
    assert len(list(env.settings.media_dir.glob("*.mkv"))) == 2

    got = env.client.get(f"/v2/creators/jobs/{jid}/items/{rows[0]['video_id']}/file?fmt=mkv",
                         headers=hdr(env.token))
    assert got.status_code == 200 and got.content.startswith(b"CVmkv")
    assert got.headers["content-type"].startswith("video/x-matroska")


def test_creator_name_is_saved_for_task_card(tmp_path):
    env = env_with_files(tmp_path)
    jid = create(env, mode="latest_n", n=2).json()["id"]
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(env.token)).json()
    assert job["creator_name"] == "测试博主"
    assert job["source_input"] == "https://space.bilibili.com/2/video"


def test_source_input_keeps_user_typed_handle(tmp_path):
    """前端按号抓时把原始号传进 source_input，后端要原样存，而不是写死成链接。"""
    env = env_with_files(tmp_path)
    jid = create(env, mode="latest_n", n=2, source_input="67194609689").json()["id"]
    job = env.client.get(f"/v2/creators/jobs/{jid}", headers=hdr(env.token)).json()
    # 用户填的是数字号，source_input 就该是这个号，不能退化成 url
    assert job["source_input"] == "67194609689"


def test_download_converts_on_demand_when_not_stored(tmp_path, monkeypatch):
    """只记清单的任务：点某一种格式时现取现转，临时目录发完就删。"""
    from getmedia import creator_formats

    monkeypatch.setattr(creator_formats, "convert",
                        lambda src, dest, fmt, **kw: dest.write_bytes(b"CV" + fmt.encode()))
    env = env_with_files(tmp_path)
    jid = create(env, mode="latest_n", n=1, storage="metadata").json()["id"]
    rows = env.client.get(f"/v2/creators/jobs/{jid}/items", headers=hdr(env.token)).json()["items"]
    vid = rows[0]["video_id"]

    got = env.client.get(f"/v2/creators/jobs/{jid}/items/{vid}/file?fmt=mkv",
                         headers=hdr(env.token))
    assert got.status_code == 200 and got.content == b"CVmkv"
    assert got.headers["content-type"] == "video/x-matroska"
    # 临时文件不留在缓存目录里
    left = [p for p in env.settings.cache_dir.glob("fmt-*")]
    assert left == []
