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


class MailAlertSink:
    """告警走反馈邮件通道；同一类型每天最多一封（去重记录落库，重启不丢）。无邮件配置时只记日志。"""

    def __init__(self, engine, settings, clock, sender=None):
        self.engine, self.s, self.clock, self.sender = engine, settings, clock, sender

    def notify(self, kind: str, detail: str) -> None:
        from sqlalchemy import text
        from email.message import EmailMessage
        from . import ratelimit
        log.error("ALERT %s", kind)  # 详情可能含路径，日志只记类型
        now = int(self.clock())
        with self.engine.begin() as c:
            ok, _ = ratelimit.hit(c, f"alert:{kind}", 1, 86400, now)
        if not ok or self.s.mail is None:
            return
        try:
            m = EmailMessage()
            m["From"], m["To"] = self.s.mail.sender, self.s.mail.to
            m["Subject"] = f"[GetMedia 告警] {kind}"[:120]
            m.set_content(f"{kind}\n\n{detail}\n\n（同类告警每天最多一封）")
            (self.sender or _smtp_send(self.s))(m)
        except Exception as e:  # noqa: BLE001 - 只记类别与 SMTP 数字码
            log.warning("alert mail failed: %s code=%s", type(e).__name__, getattr(e, "smtp_code", None))


def _smtp_send(settings):
    from .feedback import FeedbackService
    def send(msg):
        FeedbackService.mailer(type("S", (), {"s": settings})(), msg)
    return send
