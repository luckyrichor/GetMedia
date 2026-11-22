"""release.json：版本与 APK 下载信息。按修改时间热加载；字段严格校验，无效字段丢弃（防止把非 https 链接下发给客户端/网页）。"""
from __future__ import annotations

import json
import logging
import os
import re
import threading
from pathlib import Path

log = logging.getLogger("getmedia.release")
_VER = re.compile(r"^\d+(\.\d+){1,3}$")
_SHA = re.compile(r"^[0-9a-f]{64}$")


def _link(d: dict | None) -> dict | None:
    """校验一组 {apk, sha256}；任一不合格整组丢弃，不给出半残链接。"""
    if not isinstance(d, dict):
        return None
    apk, sha = d.get("apk"), d.get("sha256")
    if not (isinstance(apk, str) and apk.startswith("https://") and len(apk) <= 500):
        return None
    if not (isinstance(sha, str) and _SHA.match(sha)):
        return None
    return {"apk": apk, "sha256": sha}


def _clean(d: dict) -> dict:
    out: dict = {"min_version": None, "latest": None}
    mv = d.get("min_version")
    out["min_version"] = mv if isinstance(mv, str) and _VER.match(mv) else None
    lt = d.get("latest")
    if isinstance(lt, dict) and isinstance(lt.get("version"), str) and _VER.match(lt["version"]):
        latest = {
            "version": lt["version"],
            "notes": lt["notes"][:2000] if isinstance(lt.get("notes"), str) else "",
            **(_link(lt) or {"apk": None, "sha256": None}),
        }
        # 免费版是另一个包名的独立安装包，必须单独给链接。免费版客户端只认这一块：
        # 没有就"本版不提示"，绝不退回完整版的包（那会装成另一个 App）。
        free = _link(lt.get("free"))
        if free:
            latest["free"] = free
        out["latest"] = latest
    return out


class ReleaseStore:
    def __init__(self, path: Path):
        self.path, self._lock, self._stamp, self._value = path, threading.Lock(), "unset", {"min_version": None, "latest": None}

    def get(self) -> dict:
        try:
            st = os.stat(self.path)
            stamp = (st.st_mtime_ns, st.st_size)
        except OSError:
            stamp = None
        with self._lock:
            if stamp != self._stamp:
                self._stamp = stamp
                self._value = self._read() if stamp else {"min_version": None, "latest": None}
            return self._value

    def _read(self) -> dict:
        try:
            return _clean(json.loads(self.path.read_text(encoding="utf-8")))
        except (OSError, ValueError, AttributeError):
            log.error("release.json 无法解析，按空处理")
            return {"min_version": None, "latest": None}
