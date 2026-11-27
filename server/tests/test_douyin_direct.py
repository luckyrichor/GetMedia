"""抖音直连：Cookie 键名必须大小写不敏感。

浏览器导出的 Netscape 文件里是 ``UIFID``，而请求参数要的是小写 ``uifid``。
照字面查会静默传空串，服务端随后报 ``Uifid Not Found``，看上去像机房 IP 被
风控，实际只是键名没对上。这组用例守住这条，避免以后再退化。
"""
from __future__ import annotations

import dataclasses
from pathlib import Path

import pytest

from getmedia import creator_rt
from getmedia.config import testing_settings as make_settings
from getmedia.creator_links import CreatorRef, Platform
from getmedia.creators import douyin as douyin_mod
from getmedia.creators.base import CreatorNotFound, CreatorUnavailable
from getmedia.creators.douyin import DouyinCreatorProvider, _ck


@pytest.fixture(autouse=True)
def _creator_settings(tmp_path):
    creator_rt.configure(make_settings(tmp_path))
    yield


REF = CreatorRef(
    Platform.DOUYIN,
    "MS4wLjABAAAAtest",
    "https://www.douyin.com/user/MS4wLjABAAAAtest",
)


def _jar(tmp_path: Path, rows: list[tuple[str, str]]) -> Path:
    """写一份 Netscape cookie 文件：rows 是 (name, value)。"""
    body = "# Netscape HTTP Cookie File\n"
    for name, value in rows:
        body += f".douyin.com\tTRUE\t/\tTRUE\t1893456000\t{name}\t{value}\n"
    path = tmp_path / "douyin.txt"
    path.write_text(body, encoding="utf-8")
    return path


class _Resp:
    def __init__(self, payload: dict):
        self.status_code = 200
        self.text = "{}"
        self._payload = payload

    def json(self) -> dict:
        return self._payload


class _Client:
    """记录发出去的 query，返回一个空列表（只要能拿到参数就够了）。"""

    def __init__(self, log: list[dict]):
        self.log = log

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def get(self, url, params=None):
        self.log.append(dict(params or {}))
        return _Resp({"status_code": 0, "aweme_list": [], "has_more": False, "max_cursor": 0})


def _params_for(tmp_path: Path, rows: list[tuple[str, str]]) -> dict:
    calls: list[dict] = []
    provider = DouyinCreatorProvider(client_factory=lambda **kw: _Client(calls))
    with pytest.raises(CreatorNotFound):
        provider._fetch_direct(REF, need=1, cookie_file=_jar(tmp_path, rows))
    assert len(calls) == 1, "应当只发一次请求（本轮用的是空列表 + has_more=False）"
    return calls[0]


def test_ck_is_case_insensitive():
    assert _ck({"UIFID": "v1"}, "uifid") == "v1"
    assert _ck({"uifid": "v2"}, "uifid") == "v2"
    assert _ck({"UiFiD": "v3"}, "uifid") == "v3"
    assert _ck({"msToken": "m"}, "MSTOKEN") == "m"


def test_ck_prefers_exact_name_then_falls_back():
    # 两个键同时存在时，精确命中优先，避免被同义键盖掉
    assert _ck({"uifid": "exact", "UIFID": "upper"}, "uifid") == "exact"


def test_ck_returns_empty_when_missing_or_blank():
    assert _ck({}, "uifid") == ""
    assert _ck({"uifid": ""}, "uifid") == ""
    assert _ck({"UIFID": ""}, "uifid") == ""


def test_direct_request_sends_uppercase_uifid(tmp_path):
    """浏览器导出的 UIFID 必须真的进到请求参数里。"""
    params = _params_for(
        tmp_path,
        [("sessionid", "sess"), ("UIFID", "the-uifid-value"), ("ttwid", "tw")],
    )
    assert params["uifid"] == "the-uifid-value", "大写键取不到就会变空串，服务端随即报 Uifid Not Found"


def test_direct_request_sends_ms_token_and_webid(tmp_path):
    params = _params_for(
        tmp_path,
        [("sessionid", "sess"), ("msToken", "tok"), ("WEBID", "wid")],
    )
    assert params["msToken"] == "tok"
    assert params["webid"] == "wid", "webid 在文件里可能是大写"


def test_direct_request_without_those_keys_sends_empty(tmp_path):
    """只有登录态、没有签名相关 Cookie 时仍要发请求，由服务端给出可读错误。"""
    params = _params_for(tmp_path, [("sessionid", "sess")])
    assert params["uifid"] == ""
    assert params["msToken"] == ""
    assert params["webid"] == ""


# ---------- 按抖音号查人：字母号也走「按号查」，不退回搜昵称 ----------


def _settings_with(tmp_path, **over):
    """一份改了指定字段的设置（douyin.py 读的是模块级 ``settings`` 名字，要打在模块上）。"""
    return dataclasses.replace(make_settings(tmp_path), **over)


class _Recorder:
    """替身：既当「按号查」的函数用，也当浏览器桥接用；记下调用，返回预设结果或抛预设异常。"""

    def __init__(self, *, result=None, error=None):
        self.result = result
        self.error = error
        self.calls: list[str] = []

    def _run(self, value):
        self.calls.append(value)
        if self.error is not None:
            raise self.error
        return self.result

    def __call__(self, value, cookie_file=None):  # 替身 _resolve_unique_id
        return self._run(value)

    def resolve_user(self, value, *, cookie_file=None):  # 替身浏览器桥接
        return self._run(value)


def _provider(monkeypatch, *, lookup, bridge=None):
    provider = DouyinCreatorProvider(bridge_factory=lambda: bridge)
    monkeypatch.setattr(provider, "_resolve_unique_id", lookup)
    return provider


def test_letter_unique_id_is_looked_up_as_id(monkeypatch, tmp_path):
    """字母抖音号按 unique_id 查，不需要浏览器桥接（`isdigit()` 那道门已经拆了）。"""
    monkeypatch.setattr(douyin_mod, "settings", _settings_with(tmp_path, douyin_browser=False))
    want = CreatorRef(Platform.DOUYIN, "MS4wLjABAAAAabcdefgh", "https://www.douyin.com/user/MS4wLjABAAAAabcdefgh", "luoyonghao")
    lookup = _Recorder(result=want)
    ref = _provider(monkeypatch, lookup=lookup).resolve("luoyonghao", cookie_file=None)
    assert ref.creator_id == "MS4wLjABAAAAabcdefgh"
    assert lookup.calls == ["luoyonghao"]


def test_not_found_does_not_fall_back_to_search(monkeypatch, tmp_path):
    """按号查不到就是查不到：不拿这个名字去搜索里猜一个人回来。"""
    monkeypatch.setattr(douyin_mod, "settings", _settings_with(tmp_path, douyin_browser=True))
    lookup = _Recorder(error=CreatorNotFound("没有找到抖音号 nosuchuser 对应的博主"))
    bridge = _Recorder(result={"sec_uid": "MS4wLjABAAAAsomeonexx", "unique_id": "someone_else"})
    provider = _provider(monkeypatch, lookup=lookup, bridge=bridge)
    with pytest.raises(CreatorNotFound) as err:
        provider.resolve("nosuchuser", cookie_file=None)
    assert "nosuchuser" in str(err.value)
    assert bridge.calls == [], "查不到时不许退回搜索"


def test_search_fallback_only_when_lookup_unavailable(monkeypatch, tmp_path):
    """接口不可用才退回搜索，且必须号对得上；对不上宁可报错。"""
    monkeypatch.setattr(douyin_mod, "settings", _settings_with(tmp_path, douyin_browser=True))
    ok = _Recorder(result={"sec_uid": "MS4wLjABAAAAabcdefgh", "unique_id": "luoyonghao", "nickname": "罗永浩"})
    ref = _provider(monkeypatch, lookup=_Recorder(error=CreatorUnavailable("接口被挡")), bridge=ok).resolve(
        "luoyonghao", cookie_file=None
    )
    assert ref.creator_id == "MS4wLjABAAAAabcdefgh"

    wrong = _Recorder(result={"sec_uid": "MS4wLjABAAAAsomeonexx", "unique_id": "someone_else", "nickname": "另一个人"})
    provider = _provider(monkeypatch, lookup=_Recorder(error=CreatorUnavailable("接口被挡")), bridge=wrong)
    with pytest.raises(CreatorNotFound) as err:
        provider.resolve("luoyonghao", cookie_file=None)
    assert "没查到" in str(err.value)


def test_nickname_still_goes_through_search(monkeypatch, tmp_path):
    """带中文的名字不是号，只能走搜索——这条老路要留着。"""
    monkeypatch.setattr(douyin_mod, "settings", _settings_with(tmp_path, douyin_browser=True))
    lookup = _Recorder(result=CreatorRef(Platform.DOUYIN, "x", "x", "x"))
    bridge = _Recorder(result={"sec_uid": "MS4wLjABAAAAnickxxxx", "unique_id": "whoever"})
    ref = _provider(monkeypatch, lookup=lookup, bridge=bridge).resolve("罗永浩", cookie_file=None)
    assert ref.creator_id == "MS4wLjABAAAAnickxxxx"
    assert lookup.calls == [], "昵称不该拿去按号查"
