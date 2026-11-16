"""B 站 WBI 签名与浏览器指纹参数。

从旧音频项目的 B 站提取器移植。GetMedia 服务端不做下载，
只保留列表/详情接口要用的签名部分，不引入 yt-dlp 与转封装依赖。
"""
from __future__ import annotations

import base64
import hashlib
import json
import math
import random
import string
import time
import urllib.parse
from pathlib import Path

import httpx

API_NAV = "https://api.bilibili.com/x/web-interface/nav"

# B 站官方的 mixin_key 重排表，硬编码（服务端不下发，只能跟随）
_MIXIN_TABLE = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
]

_WBI_TTL = 12 * 3600
_wbi_cache: tuple[float, str] | None = None


class WbiError(RuntimeError):
    """拿不到签名密钥。"""


def _mixin_key(img_key: str, sub_key: str) -> str:
    raw = img_key + sub_key
    return "".join(raw[i] for i in _MIXIN_TABLE)[:32]


def _key_from_url(url: str) -> str:
    return url.rsplit("/", 1)[-1].split(".")[0]


def _get_mixin_key(client: httpx.Client) -> str:
    global _wbi_cache
    now = time.time()
    if _wbi_cache and now - _wbi_cache[0] < _WBI_TTL:
        return _wbi_cache[1]

    data = client.get(API_NAV).json()
    # nav 未登录时 code=-101，但 wbi_img 照样下发 —— 不能因为 code 非 0 就报错
    wbi = (data.get("data") or {}).get("wbi_img") or {}
    if not wbi.get("img_url") or not wbi.get("sub_url"):
        raise WbiError("拿不到 WBI 密钥（nav 接口返回异常）")

    key = _mixin_key(_key_from_url(wbi["img_url"]), _key_from_url(wbi["sub_url"]))
    _wbi_cache = (now, key)
    return key


def _sign(params: dict, mixin_key: str) -> dict:
    """给参数补上 wts 和 w_rid。"""
    signed = dict(params)
    signed["wts"] = int(time.time())
    query = "&".join(f"{k}={urllib.parse.quote(str(signed[k]), safe='')}" for k in sorted(signed))
    signed["w_rid"] = hashlib.md5((query + mixin_key).encode()).hexdigest()
    return signed


def reset_wbi_cache() -> None:
    """空间接口返回 412/-352 时丢弃缓存的密钥，下次重新向 nav 取。"""
    global _wbi_cache
    _wbi_cache = None


def dm_params() -> dict[str, str]:
    """空间接口还会看浏览器指纹参数，缺了容易被 412。"""
    width, height = 1920, 1080
    rnd = math.floor(114 * random.random())
    wh = [2 * width + 2 * height + 3 * rnd, 4 * width - height + rnd, rnd]
    scroll_top, scroll_left = 10, 10
    rnd2 = math.floor(514 * random.random())
    offsets = [3 * scroll_top + 2 * scroll_left + rnd2, 4 * scroll_top - 4 * scroll_left + 2 * rnd2, rnd2]
    return {
        "dm_img_list": "[]",
        "dm_img_str": base64.b64encode(
            "".join(random.choices(string.printable, k=random.randint(16, 64))).encode()
        )[:-2].decode(),
        "dm_cover_img_str": base64.b64encode(
            "".join(random.choices(string.printable, k=random.randint(32, 128))).encode()
        )[:-2].decode(),
        "dm_img_inter": json.dumps({"ds": [], "wh": wh, "of": offsets}, separators=(",", ":")),
    }


def _parse_cookie_file(path: Path | None) -> dict[str, str]:
    """读 Netscape cookie 文件。"""
    if not path or not path.exists():
        return {}
    jar: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 7:
            jar[parts[5]] = parts[6]
    return jar
