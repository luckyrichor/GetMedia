"""runtime.json：注册开关、邀请码开关、维护提示。按修改时间热加载；文件缺失或损坏时取最安全的默认（注册关闭）。"""
from __future__ import annotations

import json
import logging
import os
import threading
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger("getmedia.runtime")


@dataclass(frozen=True)
class Runtime:
    registration_open: bool = False
    invite_required: bool = False
    maintenance: str | None = None


class RuntimeStore:
    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.Lock()
        self._stamp: tuple | None = None
        self._value = Runtime()

    def get(self) -> Runtime:
        try:
            st = os.stat(self.path)
            stamp = (st.st_mtime_ns, st.st_size)
        except OSError:
            stamp = None
        with self._lock:
            if stamp != self._stamp:
                self._stamp = stamp
                self._value = self._read() if stamp else Runtime()
            return self._value

    def _read(self) -> Runtime:
        try:
            d = json.loads(self.path.read_text(encoding="utf-8"))
            m = d.get("maintenance")
            return Runtime(bool(d.get("registration_open", False)), bool(d.get("invite_required", False)),
                           m if isinstance(m, str) and m else None)
        except (OSError, ValueError, AttributeError):
            log.error("runtime.json 无法解析，按默认（注册关闭）处理")
            return Runtime()

    def write(self, **changes) -> Runtime:
        """原子写入（管理 CLI 用）。"""
        cur = self.get()
        d = {"registration_open": cur.registration_open, "invite_required": cur.invite_required, "maintenance": cur.maintenance}
        d.update(changes)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(self.path.name + ".tmp")
        tmp.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")
        os.replace(tmp, self.path)
        return self.get()
