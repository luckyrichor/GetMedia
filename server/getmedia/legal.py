"""协议文本：服务器文件为准，按修改时间热加载；版本号 + sha256 绑定。

规则：
- 文件首部必须含「版本：vN　生效日期：YYYY 年 M 月 D 日」，解析失败：启动时拒绝启动；热加载时保留上一份并告警。
- 同版本号但 sha256 不同 → 拒绝加载新文本，继续提供已登记文本，写日志并告警。
- 版本号低于已登记的最大版本 → 同样拒绝（防回退）。
- 版本号更大 → 登记并生效，用户下次登录必须重新同意。
"""
from __future__ import annotations

import hashlib
import logging
import os
import re
import threading
import time
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from sqlalchemy import text
from sqlalchemy.engine import Engine

from .alerts import AlertSink

log = logging.getLogger("getmedia.legal")

DOCS = {"user_agreement": "user-agreement.md", "privacy_policy": "privacy-policy.md"}
_HEADER = re.compile(r"版本：v(\d+)\s*生效日期：(\d{4})\s*年\s*(\d{1,2})\s*月\s*(\d{1,2})\s*日")


class LegalError(RuntimeError):
    pass


@dataclass(frozen=True)
class LegalDoc:
    doc: str
    version: int
    effective_date: str
    sha256: str
    text: str

    def public(self) -> dict:
        return {"version": self.version, "effective_date": self.effective_date, "sha256": self.sha256, "text": self.text}


def parse(doc: str, raw: bytes) -> LegalDoc:
    try:
        body = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise LegalError(f"{doc}: 不是 UTF-8 文本") from None
    m = _HEADER.search("\n".join(body.splitlines()[:5]))
    if not m:
        raise LegalError(f"{doc}: 首部缺少「版本：vN　生效日期：…」")
    y, mo, d = int(m[2]), int(m[3]), int(m[4])
    try:
        eff = date(y, mo, d).isoformat()
    except ValueError:
        raise LegalError(f"{doc}: 生效日期无效") from None
    return LegalDoc(doc, int(m[1]), eff, hashlib.sha256(raw).hexdigest(), body)


class LegalStore:
    def __init__(self, engine: Engine, legal_dir: Path, alerts: AlertSink, clock=time.time):
        self.engine, self.dir, self.alerts, self.clock = engine, legal_dir, alerts, clock
        self._lock = threading.Lock()
        self._stamps: dict[str, tuple | None] = {}
        self._current: dict[str, LegalDoc] = {}

    # -- 启动：任何问题都拒绝启动 --
    def load_initial(self) -> None:
        with self._lock:
            for doc in DOCS:
                raw = self._read(doc)
                if raw is None:
                    raise LegalError(f"{doc}: 找不到协议文件 {self.dir / DOCS[doc]}")
                new = parse(doc, raw)
                self._current[doc] = self._register_at_start(new)
                self._stamps[doc] = self._stamp(doc)

    def _register_at_start(self, new: LegalDoc) -> LegalDoc:
        with self.engine.begin() as c:
            top = c.execute(text("SELECT version, sha256, effective_date, text FROM legal_versions WHERE doc=:d ORDER BY version DESC LIMIT 1"),
                            {"d": new.doc}).first()
            if top is None or new.version > top[0]:
                self._insert(c, new)
                return new
            reg = LegalDoc(new.doc, top[0], top[2], top[1], top[3])
        if new.version == reg.version and new.sha256 == reg.sha256:
            return new
        # 版本相同文本不同 / 版本回退：继续使用已登记的文本，不阻止启动，但告警
        self._reject(new, reg)
        return reg

    def _insert(self, c, d: LegalDoc) -> None:
        c.execute(text("INSERT INTO legal_versions(doc,version,sha256,effective_date,text,first_loaded_at) VALUES(:doc,:v,:s,:e,:t,:n)"),
                  {"doc": d.doc, "v": d.version, "s": d.sha256, "e": d.effective_date, "t": d.text, "n": int(self.clock())})

    def _reject(self, new: LegalDoc, kept: LegalDoc) -> None:
        reason = "同版本号文本被改动" if new.version == kept.version else "版本号低于已登记版本"
        msg = f"{new.doc} v{new.version}：{reason}，已拒绝加载，继续使用 v{kept.version}"
        log.error(msg)
        self.alerts.notify("legal_hash_mismatch", msg)

    # -- 热加载 --
    def _read(self, doc: str) -> bytes | None:
        try:
            return (self.dir / DOCS[doc]).read_bytes()
        except OSError:
            return None

    def _stamp(self, doc: str):
        try:
            st = os.stat(self.dir / DOCS[doc])
            return (st.st_mtime_ns, st.st_size)
        except OSError:
            return None

    def refresh(self) -> None:
        for doc in DOCS:
            stamp = self._stamp(doc)
            if stamp == self._stamps.get(doc):
                continue
            with self._lock:
                if stamp == self._stamps.get(doc):
                    continue
                self._stamps[doc] = stamp
                raw = self._read(doc)
                if raw is None:
                    log.error("%s: 协议文件读取失败，继续使用已登记文本", doc)
                    continue
                try:
                    new = parse(doc, raw)
                except LegalError as e:
                    log.error("%s", e)
                    self.alerts.notify("legal_parse_failed", str(e))
                    continue
                cur = self._current[doc]
                if new.version == cur.version and new.sha256 == cur.sha256:
                    continue
                if new.version > cur.version:
                    with self.engine.begin() as c:
                        self._insert(c, new)
                    self._current[doc] = new
                    log.info("%s 升级到 v%s", doc, new.version)
                else:
                    self._reject(new, cur)

    def current(self) -> dict[str, LegalDoc]:
        self.refresh()
        return dict(self._current)

    def public(self) -> dict:
        return {k: v.public() for k, v in self.current().items()}
