"""告警出口。WP1 只定义接口与日志实现；WP4 接邮件并做"同类每天一封"去重。"""
from __future__ import annotations

import logging
from typing import Protocol

log = logging.getLogger("getmedia.alerts")


class AlertSink(Protocol):
    def notify(self, kind: str, detail: str) -> None: ...


class LogAlertSink:
    def notify(self, kind: str, detail: str) -> None:
        log.error("ALERT %s: %s", kind, detail)


class RecordingAlertSink:
    """测试用。"""
    def __init__(self):
        self.items: list[tuple[str, str]] = []

    def notify(self, kind: str, detail: str) -> None:
        self.items.append((kind, detail))
