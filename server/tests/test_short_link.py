"""b23.tv 短链展开（带 SSRF 防护）与 ?p=N 分集。"""
from __future__ import annotations

import httpx
import pytest

from getmedia.extractor import API_PLAYURL, API_VIEW, MediaResolver, ResolveError, expand_short_link

BV = "BV1KG411w7r2"


@pytest.fixture(autouse=True)
def _settings(monkeypatch):
    """解析器读 creator_rt.settings.http_timeout；单测里不启动完整应用，给个最小替身。"""
    from types import SimpleNamespace
    monkeypatch.setattr("getmedia.extractor.settings", SimpleNamespace(http_timeout=10.0))


def _resolver(handler):
    return MediaResolver(client_factory=lambda **kw: httpx.Client(transport=httpx.MockTransport(handler), **kw))


def _bili_upstream(cids):
    def handler(request):
        url = str(request.url).split("?", 1)[0]
        if url == API_VIEW:
            return httpx.Response(200, json={"code": 0, "data": {
                "bvid": BV, "title": "t", "cid": 101, "duration": 90,
                "pages": [{"page": 1, "cid": 101}, {"page": 3, "cid": 303, "duration": 37}],
            }})
        assert url == API_PLAYURL
        cids.append(request.url.params["cid"])
        return httpx.Response(200, json={"code": 0, "data": {"durl": [{"url": "https://example.invalid/m"}]}})
    return handler


def test_expand_short_link_follows_redirect_and_resolves_page(monkeypatch):
    cids, seen = [], []

    def handler(request):
        if request.url.host == "b23.tv":
            seen.append(str(request.url))
            return httpx.Response(302, headers={"location": f"https://www.bilibili.com/video/{BV}?p=3"})
        return _bili_upstream(cids)(request)

    resolver = _resolver(handler)
    monkeypatch.setattr(resolver, "cookie_file", lambda *a, **k: None)
    monkeypatch.setattr("getmedia.extractor._get_mixin_key", lambda _: None)

    m = resolver.resolve(f"【分享】看看这个 https://b23.tv/AbCdEf?share_source=copy 提取码 ")
    assert seen == ["https://b23.tv/AbCdEf?share_source=copy"]
    assert (m.video_id, m.page, m.duration_sec) == (BV, 3, 37)
    assert cids == ["303"]
    assert m.source_url.endswith("?p=3")


def test_full_link_is_not_expanded(monkeypatch):
    def handler(request):
        assert request.url.host != "b23.tv"
        return _bili_upstream([])(request)

    resolver = _resolver(handler)
    monkeypatch.setattr(resolver, "cookie_file", lambda *a, **k: None)
    monkeypatch.setattr("getmedia.extractor._get_mixin_key", lambda _: None)
    m = resolver.resolve(f"https://www.bilibili.com/video/{BV}")
    assert (m.video_id, m.page) == (BV, 1)


@pytest.mark.parametrize("target", [
    "http://127.0.0.1:8700/v2/config",           # 环回
    "http://169.254.169.254/latest/meta-data",   # 链路本地
    "https://evil.example/x",                    # 白名单外
    "https://www.bilibili.com:8443/video/x",     # 非标准端口
    "file:///etc/passwd",                        # 非 http
])
def test_expand_rejects_untrusted_redirect(target):
    def handler(request):
        return httpx.Response(302, headers={"location": target})

    with pytest.raises(ResolveError) as e:
        expand_short_link("https://b23.tv/AbCdEf", lambda **kw: httpx.Client(
            transport=httpx.MockTransport(handler), **kw))
    assert e.value.code == "unsupported_link"


def test_expand_stops_after_five_hops():
    def handler(request):
        n = int(request.url.path.strip("/") or 0)
        return httpx.Response(302, headers={"location": f"https://b23.tv/{n + 1}"})

    with pytest.raises(ResolveError) as e:
        expand_short_link("https://b23.tv/0", lambda **kw: httpx.Client(
            transport=httpx.MockTransport(handler), **kw))
    assert e.value.code == "unsupported_link"


def test_short_link_that_cannot_expand_reports_unsupported(monkeypatch):
    def handler(request):
        return httpx.Response(502)

    resolver = _resolver(handler)
    monkeypatch.setattr(resolver, "cookie_file", lambda *a, **k: None)
    with pytest.raises(ResolveError) as e:
        resolver.resolve("分享 https://b23.tv/AbCdEf")
    assert e.value.code == "unsupported_link"
