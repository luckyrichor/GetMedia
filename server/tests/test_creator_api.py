"""预览接口：用假的平台适配器验证契约、错误映射与鉴权，不联网。"""
from pathlib import Path

import pytest

from conftest import hdr, make_owner
from getmedia import creator_service
from getmedia.creator_links import CreatorRef, Platform
from getmedia.creators.base import (
    CreatorAuthError,
    CreatorNotFound,
    CreatorProfile,
    CreatorRangeError,
    CreatorUnavailable,
    CreatorVideo,
    CreatorWindow,
    VideoStats,
)

SPACE = "https://space.bilibili.com/2/video"


class FakeProvider:
    platform = Platform.BILIBILI

    def __init__(self, *, items=2, error=None):
        self.items = items
        self.error = error
        self.enriched = 0

    def resolve(self, value, *, cookie_file):
        return CreatorRef(Platform.BILIBILI, value, SPACE, value)

    def fetch_window(self, ref, *, start, count, cookie_file):
        if self.error:
            raise self.error
        videos = [
            CreatorVideo(
                platform=Platform.BILIBILI,
                video_id=f"BV{i:010d}",
                title=f"第 {i} 条",
                source_url=f"https://www.bilibili.com/video/BV{i:010d}",
                position=start + i - 1,
                creator_id=ref.creator_id,
                cover_url="https://i0.hdslb.com/cover.jpg",
                duration_sec=61.5,
                published_at="2026-11-01T12:00:00+00:00",
                stats=VideoStats(view_count=1000 + i, like_count=10 * i),
            )
            for i in range(1, min(self.items, count) + 1)
        ]
        return CreatorWindow(
            profile=CreatorProfile(
                platform=Platform.BILIBILI,
                creator_id=ref.creator_id,
                name="碧诗",
                url=ref.url,
                avatar_url="https://i0.hdslb.com/face.jpg",
                total_videos=99,
                follower_count=1234,
                signature="介绍",
            ),
            items=videos,
            total=99,
            has_more=True,
        )

    def enrich(self, video, *, include_stats, include_comments, comment_limit, cookie_file):
        self.enriched += 1
        return video


@pytest.fixture
def creator(open_reg, monkeypatch):
    """把平台适配器换成假的，并避免真的去预热访客 cookie。"""
    provider = FakeProvider()
    monkeypatch.setattr(creator_service, "get_provider", lambda platform: provider)
    monkeypatch.setattr(creator_service, "cookie_file_for", lambda platform, user_id=None: (None, "none"))
    open_reg.provider = provider
    return open_reg


def _token(env, name="boss"):
    """博主批量属于 owner 能力，测试统一用 owner 会话。"""
    return make_owner(env, name=name)


def test_preview_requires_login(creator):
    r = creator.client.post("/v2/creators/preview", json={"url": SPACE})
    assert r.status_code == 401 and r.json()["code"] == "unauthenticated"


def test_preview_returns_profile_and_items(creator):
    r = creator.client.post(
        "/v2/creators/preview",
        json={"url": SPACE, "start": 1, "count": 2},
        headers=hdr(_token(creator)),
    )
    assert r.status_code == 200
    body = r.json()
    assert body["profile"]["name"] == "碧诗"
    assert body["profile"]["platform"] == "bilibili"
    assert body["profile"]["follower_count"] == 1234
    assert body["range"] == {"start": 1, "count": 2}
    assert body["total"] == 99 and body["has_more"] is True
    assert [i["position"] for i in body["items"]] == [1, 2]
    assert body["items"][0]["stats"]["view_count"] == 1001
    assert body["items"][0]["cover_url"].startswith("https://")
    assert body["cookie_source"] == "none"
    assert creator.provider.enriched == 2


def test_preview_accepts_input_alias(creator):
    r = creator.client.post(
        "/v2/creators/preview", json={"input": SPACE, "count": 1}, headers=hdr(_token(creator))
    )
    assert r.status_code == 200


def test_preview_validates_range(creator):
    token = _token(creator)
    assert creator.client.post("/v2/creators/preview", json={"url": SPACE, "count": 51},
                               headers=hdr(token)).status_code == 422
    assert creator.client.post("/v2/creators/preview", json={"url": SPACE, "start": 501},
                               headers=hdr(token)).status_code == 422


def test_preview_rejects_unknown_field(creator):
    r = creator.client.post("/v2/creators/preview", json={"url": SPACE, "foo": 1}, headers=hdr(_token(creator)))
    assert r.status_code == 422


@pytest.mark.parametrize(
    "extra",
    [{"formats": ["m4a"]}, {"source_input": "67194609689"}, {"storage": "archive"}],
    ids=["formats", "source_input", "storage"],
)
def test_preview_rejects_job_only_fields(creator, extra):
    """预览只认选取参数：输出格式、存放方式、原始输入是建任务才有的字段。

    以前前端预览与建任务共用一个 body，预览把 ``formats`` 一起发过来，
    撞上 ``extra="forbid"`` 就是整单 422（页面显示「提交的内容格式不正确」）。
    这条测试守住「预览契约里没有这些字段」，前端要拆开请求体。
    """
    r = creator.client.post("/v2/creators/preview", json={"url": SPACE, **extra},
                            headers=hdr(_token(creator)))
    assert r.status_code == 422


@pytest.mark.parametrize(
    "error,status,code",
    [
        (CreatorNotFound("该 UP 主不存在或主页不可见"), 404, "creator_not_found"),
        (CreatorAuthError("需要登录"), 428, "creator_auth"),
        (CreatorUnavailable("触发风控"), 503, "creator_unavailable"),
        (CreatorRangeError("范围超出：该博主只有 3 条公开视频"), 400, "creator_input"),
    ],
)
def test_preview_maps_errors(creator, error, status, code):
    creator.provider.error = error
    r = creator.client.post("/v2/creators/preview", json={"url": SPACE}, headers=hdr(_token(creator)))
    assert r.status_code == status
    assert r.json()["code"] == code
    assert r.json()["detail"]


def test_preview_rejects_single_video(creator):
    r = creator.client.post(
        "/v2/creators/preview",
        json={"url": "https://www.bilibili.com/video/BV1xx411c7mD"},
        headers=hdr(_token(creator)),
    )
    assert r.status_code == 400
    assert "单个视频" in r.json()["detail"]


def test_limits_endpoint(creator):
    r = creator.client.get("/v2/creators/limits", headers=hdr(_token(creator)))
    assert r.json() == {"max_items": 50, "max_start": 500}


def test_preview_rejects_link_when_numeric_platform_given(creator):
    """选了「抖音号 / B 站号」就只能填数字，链接要走「主页链接」。"""
    r = creator.client.post(
        "/v2/creators/preview",
        json={"url": "https://space.bilibili.com/2", "platform": "bilibili", "mode": "latest_n", "n": 3},
        headers=hdr(_token(creator)),
    )
    assert r.status_code == 400
    assert r.json()["code"] == "creator_input"
    assert "只能填纯数字" in r.json()["detail"]


def test_preview_rejects_non_numeric_text_even_in_window_mode(creator):
    r = creator.client.post(
        "/v2/creators/preview",
        json={"url": "碧诗", "platform": "bilibili"},
        headers=hdr(_token(creator)),
    )
    assert r.status_code == 400
    assert r.json()["code"] == "creator_input"
