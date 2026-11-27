"""博主输入 → 规范化主页标识。

从旧音频项目的 ``sharelink`` 里挑出「解析博主主页」需要的部分移植过来：
只要 Platform / CreatorRef / LinkError / find_urls / expand / resolve_creator /
parse_creator_input。视频单条解析（ResolvedLink）不在这里——GetMedia 的
下载仍在手机上做，服务端只负责列表。
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

import httpx

_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

# 抖音分享文案会把中文直接怼在 URL 后面，字符集必须收紧。
URL_RE = re.compile(r"https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+")
BV_RE = re.compile(r"\bBV[0-9A-Za-z]{10}\b")
AV_RE = re.compile(r"\bav(\d{1,15})\b", re.IGNORECASE)

_BILI_HOSTS = ("bilibili.com", "b23.tv", "bili2233.cn", "acg.tv")
_DOUYIN_HOSTS = ("douyin.com", "iesdouyin.com")
_DOUYIN_VIDEO_ID_RE = re.compile(r"/(?:video|note|share/video)/(\d+)")
_BILI_SPACE_RE = re.compile(r"^/(\d{1,20})(?:/(?:upload/)?video)?/?$")
_DOUYIN_USER_RE = re.compile(r"^/(?:share/)?user/([A-Za-z0-9_-]{8,})/?$")

_DOUYIN_LABEL_RE = re.compile(
    r"(?:抖音号|抖音id|抖音ID|抖音|douyin(?:\s*id)?)\s*(?:[:：=\-]\s*)?([^\s:：=\-]{1,64})",
    re.IGNORECASE,
)
_BILI_LABEL_RE = re.compile(
    r"(?:b站|哔哩哔哩|bilibili)\s*(?:uid|id|号|用户)?\s*(?:[:：=\-]\s*)?([^\s:：=\-]{1,64})",
    re.IGNORECASE,
)
_UID_LABEL_RE = re.compile(r"\buid\s*(?:[:：=\-]\s*)?(\d{1,20})", re.IGNORECASE)
_SEC_UID_RE = re.compile(r"^MS4wLjAB[A-Za-z0-9_-]{8,}$")


class Platform(str, Enum):
    BILIBILI = "bilibili"
    DOUYIN = "douyin"


class LinkError(ValueError):
    """输入里没有可用的博主主页。"""


@dataclass(frozen=True)
class CreatorRef:
    """博主主页：批量任务只认主页，不认单条视频。"""

    platform: Platform
    creator_id: str
    url: str
    raw_url: str = ""

    def __str__(self) -> str:  # pragma: no cover - 仅日志
        return f"{self.platform.value}:{self.creator_id}"


@dataclass(frozen=True)
class CreatorInput:
    """手机用户常给的内容：抖音号、B 站 UID、昵称或完整主页链接。"""

    platform: Platform | None
    value: str
    kind: str  # numeric | sec_uid | nickname | url


def find_urls(text: str) -> list[str]:
    """从任意文本里抠出所有 http(s) 链接，按出现顺序。"""
    urls: list[str] = []
    for match in URL_RE.finditer(text or ""):
        url = match.group(0).rstrip(".,;:!?)）】」》\"'")
        if url not in urls:
            urls.append(url)
    return urls


def _host_of(url: str) -> str:
    return (httpx.URL(url).host or "").lower()


def _platform_of(url: str) -> Platform | None:
    host = _host_of(url)
    if any(host == h or host.endswith("." + h) for h in _BILI_HOSTS):
        return Platform.BILIBILI
    if any(host == h or host.endswith("." + h) for h in _DOUYIN_HOSTS):
        return Platform.DOUYIN
    return None


def _is_short(url: str) -> bool:
    return _host_of(url) in ("b23.tv", "bili2233.cn", "v.douyin.com", "v.iesdouyin.com")


def platform_of(url: str) -> Platform | None:
    """公开版：判断链接属于哪个平台，不认识返回 ``None``（不抛异常）。"""
    try:
        return _platform_of(url)
    except Exception:  # noqa: BLE001 - 畸形 URL 不该让调用方 500
        return None


def expand(url: str, *, client: httpx.Client | None = None, timeout: float = 15.0, max_redirects: int = 5) -> str:
    """跟随 302 把短链展开。用 GET 而不是 HEAD：b23.tv 对 HEAD 有时不给 Location。"""
    owns = client is None
    client = client or httpx.Client(
        timeout=timeout, follow_redirects=True, max_redirects=max_redirects, headers={"User-Agent": _UA}
    )
    try:
        return str(client.get(url).url)
    except httpx.HTTPError:
        # 展开失败不致命：原地址有时也能直接取列表
        return url
    finally:
        if owns:
            client.close()


def _platform_of_value(value) -> Platform | None:
    if value is None:
        return None
    if isinstance(value, Platform):
        return value
    try:
        return Platform(str(value).strip().lower())
    except ValueError:
        raise LinkError(f"不支持的平台：{value}") from None


_NUMERIC_ID_RE = re.compile(r"\d{1,20}")
# 抖音号是字母/数字/下划线/点/短横线的组合（`luoyonghao`、`chenling_hana`），
# 也有一批老号全是数字。**不含中文和空格**——那是昵称，不是号。
_DOUYIN_ID_RE = re.compile(r"[A-Za-z0-9_.-]{1,30}")

# 号码格式与对应的提示语：两个平台的「号」不是一回事，B 站 UID 只能是数字。
_ID_PATTERN = {"bilibili": _NUMERIC_ID_RE, "douyin": _DOUYIN_ID_RE}
_ID_HINT = {
    "bilibili": "只能填纯数字的 B 站号，或粘贴 space.bilibili.com/号 的主页链接",
    "douyin": "只能填抖音号（数字或字母），或粘贴 douyin.com/user/… 的主页链接",
}


def require_id_or_link(platform, value: str) -> None:
    """显式选了平台时，只接受「该平台的号」或「该平台的主页链接」。

    「号」按平台各自判：B 站是纯数字 UID，抖音号允许字母（`luoyonghao`）——
    抖音号是不是数字跟能不能按号查到没有关系，两种都由平台按 ``unique_id`` 查。

    前端已经拦一道，这里再拦一道：**昵称仍然不收**，免得后端拿一串中文去平台
    搜人（搜出来的是谁全凭平台排序，很容易抓错博主）。

    链接只判断「域名属不属于这个平台」，不在这里解析：
    短链展开、作品链接的「请贴主页」提示都由 :func:`resolve_creator` 给，
    那边的话术更准，这里只负责早点挡掉跨平台的输入。
    """
    if platform is None:
        return
    name = getattr(platform, "value", platform)
    pattern = _ID_PATTERN.get(name)
    if pattern is None:
        return
    v = (value or "").strip()
    if pattern.fullmatch(v):
        return
    urls = find_urls(v)
    if not urls:
        raise LinkError(_ID_HINT[name])
    for raw in urls:
        found = platform_of(raw)
        if found is None:
            raise LinkError(f"不支持的站点：{_host_of(raw)}")
        if found.value != name:
            raise LinkError(f"这是 {found.value} 的链接，但类型选的是 {name}")



def parse_creator_input(text: str, platform=None) -> CreatorInput:
    """「抖音号：xxx」「B站UID：xxx」「昵称」→ 统一查询。"""
    raw = (text or "").strip()
    if not raw:
        raise LinkError("输入为空")

    hint = _platform_of_value(platform)
    label_platform: Platform | None = None
    value: str | None = None

    if m := _DOUYIN_LABEL_RE.search(raw):
        label_platform, value = Platform.DOUYIN, m.group(1)
    elif m := _BILI_LABEL_RE.search(raw):
        label_platform, value = Platform.BILIBILI, m.group(1)
    elif m := _UID_LABEL_RE.search(raw):
        label_platform, value = Platform.BILIBILI, m.group(1)

    if label_platform is not None and hint is not None and label_platform is not hint:
        raise LinkError(f"输入写着 {label_platform.value}，但 platform 指定了 {hint.value}")
    effective = label_platform or hint
    value = (value if value is not None else raw).strip().strip('"\'“”').strip(" \t\r\n,，。;；")

    if not value:
        raise LinkError("没有识别出抖音号、B 站 UID 或昵称")
    if len(value) > 64:
        raise LinkError("博主标识太长，请传抖音号、B 站 UID 或主页链接")

    if _SEC_UID_RE.match(value):
        return CreatorInput(Platform.DOUYIN, value, "sec_uid")
    if value.isdigit():
        return CreatorInput(effective, value, "numeric")
    return CreatorInput(effective, value, "nickname")


def _creator_from_url(url: str) -> CreatorRef | None:
    u = httpx.URL(url)
    host = (u.host or "").lower()
    path = u.path or "/"

    if any(host == h or host.endswith("." + h) for h in _BILI_HOSTS):
        mid = None
        if host == "space.bilibili.com":
            if m := _BILI_SPACE_RE.match(path):
                mid = m.group(1)
        elif m := re.match(r"^/space/(\d{1,20})/?$", path):
            mid = m.group(1)
        if mid:
            return CreatorRef(Platform.BILIBILI, mid, f"https://space.bilibili.com/{mid}/video", url)

    if any(host == h or host.endswith("." + h) for h in _DOUYIN_HOSTS):
        if m := _DOUYIN_USER_RE.match(path):
            sec_uid = m.group(1)
            return CreatorRef(Platform.DOUYIN, sec_uid, f"https://www.douyin.com/user/{sec_uid}", url)

    return None


def resolve_creator(text: str, *, client: httpx.Client | None = None, timeout: float = 15.0, max_redirects: int = 5) -> CreatorRef:
    """分享文本 / 主页链接 → CreatorRef；单条视频链接给出明确提示。"""
    text = (text or "").strip()
    if not text:
        raise LinkError("输入为空")

    candidates = find_urls(text)
    if not candidates:
        raise LinkError("没有找到博主主页链接")

    errors: list[str] = []
    for raw in candidates:
        platform = _platform_of(raw)
        if platform is None:
            errors.append(f"不支持的站点: {_host_of(raw)}")
            continue

        url = expand(raw, client=client, timeout=timeout, max_redirects=max_redirects) if _is_short(raw) else raw
        platform = _platform_of(url) or platform
        if creator := _creator_from_url(url):
            return creator

        if platform is Platform.BILIBILI and (BV_RE.search(url) or AV_RE.search(url)):
            errors.append("这是单个视频链接；批量提取请传 UP 主空间主页（space.bilibili.com/<mid>）")
        elif platform is Platform.DOUYIN and _DOUYIN_VIDEO_ID_RE.search(httpx.URL(url).path):
            errors.append("这是单个视频链接；批量提取请传抖音博主主页（douyin.com/user/<sec_uid>）")
        else:
            errors.append(f"{platform.value} 链接里解析不出博主 id: {url}")

    raise LinkError("; ".join(errors) or "没有找到支持的博主主页链接")
