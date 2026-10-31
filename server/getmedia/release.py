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


def _clean(d: dict) -> dict:
    out: dict = {"min_version": None, "latest": None}
    mv = d.get("min_version")
    out["min_version"] = mv if isinstance(mv, str) and _VER.match(mv) else None
    lt = d.get("latest")
    if isinstance(lt, dict) and isinstance(lt.get("version"), str) and _VER.match(lt["version"]):
        apk = lt.get("apk")
        sha = lt.get("sha256")
        out["latest"] = {
            "version": lt["version"],
            "notes": lt["notes"][:2000] if isinstance(lt.get("notes"), str) else "",
            "apk": apk if isinstance(apk, str) and apk.startswith("https://") and len(apk) <= 500 else None,
            "sha256": sha if isinstance(sha, str) and _SHA.match(sha) else None,
        }
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
