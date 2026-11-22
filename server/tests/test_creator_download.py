"""批量条目「下载到本机」：直链转发、B 站现解析回退、抖音过期提示。"""
from __future__ import annotations

import shutil
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from conftest import hdr, make_owner
from getmedia import creator_jobs_api as cja
from getmedia.alerts import RecordingAlertSink
from getmedia.api import create_app
from getmedia.config import testing_settings as make_settings
from getmedia.creator_links import CreatorRef, Platform
from getmedia.creators.base import CreatorProfile, CreatorVideo, CreatorWindow, VideoStats
from getmedia.extractor import FakeResolver

ROOT = Path(__file__).resolve().parents[2]
TZ = timezone(timedelta(hours=8))
BASE = datetime(2026, 11, 1, 12, 0, tzinfo=TZ)
PAYLOAD = FakeResolver.payload


def videos(n=3, *, platform=Platform.BILIBILI, play_urls=()):
    out = []
    for i in range(n):
        vid = f"BVd{i:08d}" if platform is Platform.BILIBILI else f"75980{i:08d}"
        url = (f"https://www.bilibili.com/video/{vid}" if platform is Platform.BILIBILI
               else f"https://www.douyin.com/video/{vid}")
        out.append(CreatorVideo(
            platform=platform, video_id=vid, title=f"作品 {i}", source_url=url,
            position=i + 1, creator_id="2", duration_sec=30.0,
            published_at=(BASE - timedelta(days=i)).isoformat(),
            stats=VideoStats(), play_urls=tuple(play_urls),
        ))
    return out


class Lister:
    def __init__(self, items):
        self.items = items

    def __call__(self, ref, start, count, cookie_file=None):
        page = self.items[start - 1: start - 1 + count]
        return CreatorWindow(
            profile=CreatorProfile(platform=self.items[0].platform, creator_id="2",
                                   name="测试博主", url="https://example.invalid/u",
                                   total_videos=len(self.items)),
            items=page, total=len(self.items),
            has_more=start - 1 + count < len(self.items),
        )


def build(tmp_path, *, items, link_resolver=None, media_resolver=None):
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

    lister = Lister(items)
    app = create_app(settings, clock=C(), alerts=RecordingAlertSink(),
                     creator_lister=lister,
                     creator_resolver=link_resolver or (
                         lambda url, platform=None, user_id=None: CreatorRef(Platform.BILIBILI, "2", url)),
                     creator_cookies=lambda p, user_id=None: (None, "none"),
                     creator_pinned=lambda ref, cookies=None: set(),
                     media_resolver=media_resolver or FakeResolver())
    env = SimpleNamespace(settings=settings, app=app,
                          client=TestClient(app, base_url="https://testserver"), tmp=tmp_path)
    app.state.runtime.write(registration_open=True)
    env.token = make_owner(env, name="boss")
    return env


def make_job(env, *, n=1):
    r = env.client.post("/v2/creators/jobs", headers=hdr(env.token),
                        json={"url": "https://example.invalid/u", "mode": "latest_n", "n": n})
    assert r.status_code == 202, r.text
    return r.json()["id"]


class FakeUpstream:
    """假 CDN：记录被请求的地址，返回固定字节。"""

    status_code = 200
    headers = {"content-type": "video/mp4", "content-length": "4"}

    def __init__(self):
        self.urls: list[str] = []

    def iter_bytes(self, chunk_size=None):
        yield b"ABCD"

    def close(self):
        pass


class FakeClient:
    def __init__(self, upstream, **_kw):
        self.upstream = upstream

    def build_request(self, method, url, headers=None):
        return SimpleNamespace(url=url, headers=headers or {})

    def send(self, req, stream=False):
        self.upstream.urls.append(req.url)
        return self.upstream

    def close(self):
        pass


@pytest.fixture
def upstream(monkeypatch):
    up = FakeUpstream()
    monkeypatch.setattr(cja.httpx, "Client", lambda **kw: FakeClient(up, **kw))
    return up


def test_download_uses_stored_play_url_and_sets_filename(jenv_upstream):
    env, up = jenv_upstream
    jid = make_job(env)
    vid = "BVd00000000"
    got = env.client.get(f"/v2/creators/jobs/{jid}/items/{vid}/file", headers=hdr(env.token))
    assert got.status_code == 200, got.text
    assert got.content == b"ABCD"
    # 用的是扫描时存下的直链，不是重新解析
    assert up.urls == ["https://cdn.example.invalid/a.mp4"]
    cd = got.headers["content-disposition"]
    assert cd.startswith("attachment;")
    assert "filename*=UTF-8''" in cd


def test_download_falls_back_to_resolver_for_bilibili(tmp_path, monkeypatch):
    """直链为空（B 站列表不带播放地址）时现解析；解析结果按附件下发。"""
    env = build(tmp_path, items=videos(2, play_urls=()))   # 无直链
    make_job(env)
    got = env.client.get("/v2/creators/jobs/1/items/BVd00000000/file", headers=hdr(env.token))
    assert got.status_code == 200, got.text
    assert got.content == PAYLOAD
    assert got.headers["content-disposition"].startswith("attachment;")


def test_download_expired_link_for_douyin_says_rescan(tmp_path, monkeypatch):
    """抖音没有直链就只能重扫：明确 409，而不是 502 让人反复重试。"""
    items = videos(2, platform=Platform.DOUYIN, play_urls=())
    env = build(tmp_path, items=items,
                link_resolver=lambda url, platform=None, user_id=None: CreatorRef(Platform.DOUYIN, "sec", url))
    make_job(env)
    got = env.client.get(f"/v2/creators/jobs/1/items/{items[0].video_id}/file",
                         headers=hdr(env.token))
    assert got.status_code == 409
    assert got.json()["code"] == "media_link_expired"


def test_download_rejects_unknown_item_and_other_users(tmp_path, monkeypatch):
    env = build(tmp_path, items=videos(1, play_urls=("https://cdn.example.invalid/a.mp4",)))
    make_job(env)
    assert env.client.get("/v2/creators/jobs/1/items/BVnope/file",
                          headers=hdr(env.token)).status_code == 404
    # 另一个 owner 看不到别人的任务
    other = make_owner(env, name="other")
    assert env.client.get("/v2/creators/jobs/1/items/BVd00000000/file",
                          headers=hdr(other)).status_code == 404


@pytest.fixture
def jenv_upstream(tmp_path, upstream):
    env = build(tmp_path, items=videos(2, play_urls=("https://cdn.example.invalid/a.mp4",)))
    return env, upstream
