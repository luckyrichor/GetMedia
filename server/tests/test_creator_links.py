"""博主输入解析：纯逻辑，不联网。"""
import pytest

from getmedia import creator_rt
from getmedia.config import testing_settings as make_settings
from getmedia.creator_links import LinkError, Platform, find_urls, parse_creator_input, resolve_creator
from getmedia.creator_service import CreatorRangeError, resolve_ref, validate_range


@pytest.fixture(autouse=True)
def _creator_settings(tmp_path):
    """窗口上限来自 Settings；这些用例不建 app，手动注入一份。"""
    creator_rt.configure(make_settings(tmp_path))
    yield


@pytest.mark.parametrize(
    "url,expected",
    [
        ("https://space.bilibili.com/2/video", ("2", "https://space.bilibili.com/2/video")),
        ("https://space.bilibili.com/2", ("2", "https://space.bilibili.com/2/video")),
        ("https://space.bilibili.com/2/upload/video", ("2", "https://space.bilibili.com/2/video")),
        ("https://www.bilibili.com/space/2", ("2", "https://space.bilibili.com/2/video")),
    ],
)
def test_bilibili_home_forms(url, expected):
    ref = resolve_creator(url)
    assert ref.platform is Platform.BILIBILI
    assert (ref.creator_id, ref.url) == expected


def test_douyin_home_form():
    ref = resolve_creator("https://www.douyin.com/user/MS4wLjABAAAAabcdefgh")
    assert ref.platform is Platform.DOUYIN
    assert ref.creator_id == "MS4wLjABAAAAabcdefgh"


def test_share_text_extracts_url():
    text = "7.53 复制打开抖音，看看【作者】的作品 https://www.douyin.com/user/MS4wLjABAAAAabcdefgh 复制此链接"
    assert find_urls(text) == ["https://www.douyin.com/user/MS4wLjABAAAAabcdefgh"]
    assert resolve_creator(text).platform is Platform.DOUYIN


@pytest.mark.parametrize(
    "url",
    [
        "https://www.bilibili.com/video/BV1xx411c7mD",
        "https://www.douyin.com/video/7300000000000000000",
    ],
)
def test_single_video_is_rejected_with_hint(url):
    with pytest.raises(LinkError) as err:
        resolve_creator(url)
    assert "单个视频" in str(err.value)


def test_no_url_is_rejected():
    with pytest.raises(LinkError):
        resolve_creator("碧诗")


def test_empty_input_is_rejected():
    with pytest.raises(LinkError):
        resolve_creator("   ")


@pytest.mark.parametrize(
    "text,platform,expected_platform,expected_value,expected_kind",
    [
        ("抖音号：67194609689", None, Platform.DOUYIN, "67194609689", "numeric"),
        ("67194609689", "douyin", Platform.DOUYIN, "67194609689", "numeric"),
        ("2", "bilibili", Platform.BILIBILI, "2", "numeric"),
        ("B站UID：2", None, Platform.BILIBILI, "2", "numeric"),
        ("MS4wLjABAAAAabcdefgh", None, Platform.DOUYIN, "MS4wLjABAAAAabcdefgh", "sec_uid"),
        ("碧诗", "bilibili", Platform.BILIBILI, "碧诗", "nickname"),
        ("  某博主  ", None, None, "某博主", "nickname"),
    ],
)
def test_parse_creator_input(text, platform, expected_platform, expected_value, expected_kind):
    query = parse_creator_input(text, platform=platform)
    assert (query.platform, query.value, query.kind) == (expected_platform, expected_value, expected_kind)


def test_parse_rejects_conflicting_platform_label():
    with pytest.raises(LinkError) as err:
        parse_creator_input("抖音号：67194609689", platform="bilibili")
    assert "platform 指定了" in str(err.value)


def test_parse_rejects_overlong_value():
    with pytest.raises(LinkError):
        parse_creator_input("x" * 80, platform="bilibili")


def test_resolve_ref_rejects_platform_conflict():
    with pytest.raises(LinkError) as err:
        resolve_ref("https://space.bilibili.com/2/video", platform="douyin")
    assert "platform 指定了" in str(err.value)


def test_resolve_ref_requires_platform_for_bare_number():
    with pytest.raises(LinkError) as err:
        resolve_ref("67194609689")
    assert "请注明平台" in str(err.value)


@pytest.mark.parametrize(
    "start,count,ok",
    [(1, 10, True), (500, 50, True), (0, 10, False), (501, 10, False), (1, 0, False), (1, 51, False)],
)
def test_validate_range(start, count, ok):
    if ok:
        assert validate_range(start, count) == (start, count)
    else:
        with pytest.raises(CreatorRangeError):
            validate_range(start, count)
