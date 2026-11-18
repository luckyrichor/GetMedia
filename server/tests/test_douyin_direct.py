"""抖音直连：Cookie 键名必须大小写不敏感。

浏览器导出的 Netscape 文件里是 ``UIFID``，而请求参数要的是小写 ``uifid``。
照字面查会静默传空串，服务端随后报 ``Uifid Not Found``，看上去像机房 IP 被
风控，实际只是键名没对上。这组用例守住这条，避免以后再退化。
"""
from __future__ import annotations

from pathlib import Path

import pytest

from getmedia import creator_rt
from getmedia.config import testing_settings as make_settings
from getmedia.creator_links import CreatorRef, Platform
from getmedia.creators.base import CreatorNotFound
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
