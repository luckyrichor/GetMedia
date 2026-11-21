"""抖音桥接的滚动：真滚动容器不是 body。

2026-11-21 实测：博主主页的可滚动元素是 `div.parent-route-container.route-scroll-container`，
`window.scrollTo(0, document.body.scrollHeight)`（body 本身不滚动）滚了 12 次一条新作品
都没加载，桥接永远只拿到首屏的 56 条。这里守住两件事：
1. 滚动脚本必须去动「可滚动余量最大」的那个元素，而不是 body；
2. 滚动预算要随 need 增长，且封顶。
"""
from __future__ import annotations

import pytest

from getmedia import creator_rt
from getmedia.config import testing_settings as make_settings
from getmedia.creators.douyin import (
    _MAX_CAPTURE_SCROLLS,
    _SCROLL_STEP_JS,
    _scroll_budget,
)


@pytest.fixture(autouse=True)
def _creator_settings(tmp_path):
    creator_rt.configure(make_settings(tmp_path).with_(douyin_browser_max_scrolls=6))
    yield


def test_scroll_script_targets_scrollable_element():
    assert "document.body.scrollHeight" not in _SCROLL_STEP_JS.split("if (!best)")[0]
    assert "scrollTop" in _SCROLL_STEP_JS and "overflowY" in _SCROLL_STEP_JS
    # 兜底仍然保留：真找不到容器时退回整页滚动
    assert "window.scrollTo" in _SCROLL_STEP_JS


def test_scroll_budget_scales_with_need():
    # 50 条（约 3 批）也要比配置的 6 次多；300 条要够滚
    assert _scroll_budget(50) >= 6
    assert _scroll_budget(300) > _scroll_budget(50)
    assert _scroll_budget(300) == min((300 + 17) // 18 * 2 + 4, _MAX_CAPTURE_SCROLLS)


def test_scroll_budget_is_capped():
    assert _scroll_budget(100000) == _MAX_CAPTURE_SCROLLS
