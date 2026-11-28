"""抖音浏览器解析：不联网的单测（页面抓取用替身，直链收集是真代码）。"""
from __future__ import annotations

from types import SimpleNamespace

import pytest

from getmedia import douyin_media
from getmedia.extractor import MediaResolver, ResolveError

AID = "7203853768797064503"
DETAIL = {
    "aweme_id": AID,
    "desc": "出山",
    "author": {"nickname": "某作者"},
    "video": {
        "duration": 61000,
        "play_addr": {"url_list": ["https://cdn.invalid/a.mp4", "https://cdn.invalid/b.mp4"]},
        "bit_rate": [
            {"play_addr": {"url_list": ["https://cdn.invalid/a.mp4", "https://cdn.invalid/hd.mp4"]}},
            {"play_addr": {"url_list": []}},
        ],
        "cover": {"url_list": ["https://cdn.invalid/c.jpg"]},
    },
}


@pytest.fixture(autouse=True)
def _clean_cache():
    douyin_media.clear_cache()
    yield
    douyin_media.clear_cache()


def _settings(**over):
    base = dict(http_timeout=10.0, douyin_browser=True,
                douyin_browser_timeout=5.0, douyin_browser_headless=True)
    base.update(over)
    return SimpleNamespace(**base)


def test_collect_urls_puts_primary_first_and_dedupes():
    urls = douyin_media._collect_urls(DETAIL["video"])
    assert urls[0] == "https://cdn.invalid/a.mp4"
    assert urls.count("https://cdn.invalid/a.mp4") == 1
    assert "https://cdn.invalid/hd.mp4" in urls


def test_resolve_maps_fields_and_caches(monkeypatch):
    calls = []

    def fake_detail(aweme_id, cookies, *, timeout, headless):
        calls.append((aweme_id, timeout, headless))
        return DETAIL

    monkeypatch.setattr(douyin_media, "_detail_from_page", fake_detail)
    info = douyin_media.resolve(AID, None, timeout=5.0, headless=True)
    assert info["title"] == "出山"
    assert info["uploader"] == "某作者"
    assert info["duration_sec"] == pytest.approx(61.0)
    assert info["cover_url"] == "https://cdn.invalid/c.jpg"

    # 第二次命中缓存，不再开浏览器
    douyin_media.resolve(AID, None, timeout=5.0, headless=True)
    assert len(calls) == 1


def test_resolve_without_play_url_raises(monkeypatch):
    monkeypatch.setattr(douyin_media, "_detail_from_page",
                        lambda *a, **k: {"aweme_id": AID, "video": {}})
    with pytest.raises(douyin_media.DouyinMediaError) as exc:
        douyin_media.resolve(AID, None, timeout=5.0)
    assert exc.value.code == "douyin_no_play_url"


def test_resolver_refuses_douyin_when_bridge_disabled(monkeypatch):
    monkeypatch.setattr("getmedia.extractor.settings", _settings(douyin_browser=False))
    resolver = MediaResolver()
    with pytest.raises(ResolveError) as exc:
        resolver.resolve(f"https://www.douyin.com/video/{AID}")
    assert exc.value.status == 503
    assert exc.value.code == "platform_needs_phone"


def test_resolver_uses_bridge_and_returns_sources(monkeypatch):
    monkeypatch.setattr("getmedia.extractor.settings", _settings())
    monkeypatch.setattr("getmedia.extractor.douyin_media.resolve",
                        lambda aid, cookie, *, timeout, headless: {
                            "urls": ("https://cdn.invalid/a.mp4", "https://cdn.invalid/hd.mp4"),
                            "title": "出山", "duration_sec": 61.0,
                            "cover_url": None, "uploader": "某作者",
                        })
    resolver = MediaResolver()
    monkeypatch.setattr(resolver, "cookie_file", lambda *a, **k: None)

    m = resolver.resolve(f"https://www.douyin.com/video/{AID}", media_type="audio")
    assert m.platform == "douyin"
    assert m.video_id == AID
    assert m.title == "出山"
    assert m.media_type == "audio"
    assert m.source_url == f"https://www.douyin.com/video/{AID}"
    assert len(m.sources) == 2
    # 抖音 CDN 认 Referer，不带就是 403
    assert m.sources[0].headers["Referer"] == "https://www.douyin.com/"


def test_resolver_translates_bridge_failure(monkeypatch):
    monkeypatch.setattr("getmedia.extractor.settings", _settings())

    def boom(*a, **k):
        raise douyin_media.DouyinMediaError("页面没加载出详情", code="douyin_detail_empty")

    monkeypatch.setattr("getmedia.extractor.douyin_media.resolve", boom)
    resolver = MediaResolver()
    monkeypatch.setattr(resolver, "cookie_file", lambda *a, **k: None)
    with pytest.raises(ResolveError) as exc:
        resolver.resolve(f"https://www.douyin.com/video/{AID}")
    assert exc.value.status == 502
    assert exc.value.code == "douyin_detail_empty"
