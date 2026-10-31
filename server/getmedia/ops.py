"""运维：注销执行、保留期清理、每日快照与恢复、健康检查（证书/磁盘）。"""
from __future__ import annotations

import logging
import os
import re
import shutil
import sqlite3
import time
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import text
from sqlalchemy.engine import Engine

from . import ratelimit
from .config import Settings
from .db import snapshot_file

log = logging.getLogger("getmedia.ops")
_SNAP = re.compile(r"^(daily|pre-migrate)-.*\.db$")


class Ops:
    def __init__(self, engine: Engine, settings: Settings, fb, alerts, clock=time.time):
        self.engine, self.s, self.fb, self.alerts, self.clock = engine, settings, fb, alerts, clock

    def now(self) -> int:
        return int(self.clock())

    # ---------- 台账 ----------
    def _ledger_append(self, user_id: str, ts: int) -> None:
        p = self.s.ledger_path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.parent.chmod(0o700)
        fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as f:
            f.write(f"{user_id} {ts}\n")
            f.flush(); os.fsync(f.fileno())

    def ledger_ids(self) -> list[str]:
        try:
            return [ln.split()[0] for ln in self.s.ledger_path.read_text(encoding="utf-8").splitlines() if ln.strip()]
        except OSError:
            return []

    # ---------- 注销 ----------
    def run_deletions(self) -> int:
        now = self.now()
        with self.engine.begin() as c:
            due = [r[0] for r in c.execute(text("SELECT user_id FROM deletion_requests WHERE executed_at IS NULL AND execute_at<=:n"), {"n": now})]
        for uid in due:
            self._ledger_append(uid, now)  # 先写台账（含 fsync），再删库：崩溃后可重放
            with self.engine.begin() as c:
                self.fb.delete_for_user(c, uid)
                c.execute(text("DELETE FROM heartbeats WHERE user_id=:u"), {"u": uid})
                c.execute(text("DELETE FROM audit WHERE user_id=:u"), {"u": uid})
                c.execute(text("DELETE FROM users WHERE id=:u"), {"u": uid})  # sessions/recovery/consents 级联
                c.execute(text("INSERT OR IGNORE INTO deletion_ledger(user_id,deleted_at) VALUES(:u,:n)"), {"u": uid, "n": now})
                c.execute(text("UPDATE deletion_requests SET executed_at=:n WHERE user_id=:u"), {"u": uid, "n": now})
        return len(due)

    def restore_account(self, user_id: str) -> bool:
        """冷静期内恢复（仅管理 CLI）。"""
        with self.engine.begin() as c:
            n = c.execute(text("DELETE FROM deletion_requests WHERE user_id=:u AND executed_at IS NULL"), {"u": user_id}).rowcount
            if n:
                c.execute(text("UPDATE users SET status='active' WHERE id=:u AND status='pending_deletion'"), {"u": user_id})
        return bool(n)

    # ---------- 保留期 ----------
    def cleanup(self) -> dict:
        now = self.now()
        cut = now - self.s.retention_days * 86400
        day = datetime.fromtimestamp(cut, timezone.utc).strftime("%Y-%m-%d")
        with self.engine.begin() as c:
            a = c.execute(text("DELETE FROM audit WHERE ts<:t"), {"t": cut}).rowcount
            r = ratelimit.purge(c, now, self.s.retention_days * 86400)
            h = c.execute(text("DELETE FROM heartbeats WHERE day<:d"), {"d": day}).rowcount
            sess = c.execute(text("DELETE FROM sessions WHERE (revoked_at IS NOT NULL AND revoked_at<:t) OR expires_at<:t"), {"t": cut}).rowcount
        out = {"audit": a, "rate_limits": r, "heartbeats": h, "sessions": sess}
        out.update({f"feedback_{k}": v for k, v in self.fb.cleanup().items()})
        out["snapshots"] = self.prune_snapshots()
        return out

    # ---------- 快照 ----------
    def snapshot(self) -> Path | None:
        d = datetime.fromtimestamp(self.now(), timezone.utc).strftime("%Y%m%d-%H%M%S")
        dest = self.s.backup_dir / f"daily-{d}.db"
        try:
            snapshot_file(self.s.database_path, dest)
        except Exception as e:  # noqa: BLE001
            self.alerts.notify("snapshot_failed", f"快照失败：{type(e).__name__}")
            return None
        self.prune_snapshots()
        return dest

    @staticmethod
    def _stamp(p: Path) -> float:
        """快照时间取自文件名（daily-YYYYmmdd-HHMMSS / pre-migrate-vN-<epoch>），文件名不可解析才用 mtime。"""
        m = re.match(r"^daily-(\d{8}-\d{6})\.db$", p.name)
        if m:
            return datetime.strptime(m.group(1), "%Y%m%d-%H%M%S").replace(tzinfo=timezone.utc).timestamp()
        m = re.match(r"^pre-migrate-v\d+-(\d+)\.db$", p.name)
        return float(m.group(1)) if m else p.stat().st_mtime

    def _snaps(self) -> list[Path]:
        try:
            return sorted((p for p in self.s.backup_dir.iterdir() if _SNAP.match(p.name)), key=self._stamp, reverse=True)
        except OSError:
            return []

    def prune_snapshots(self) -> int:
        now, n = self.now(), 0
        for i, p in enumerate(self._snaps()):
            if i >= self.s.snapshot_keep or now - self._stamp(p) > self.s.snapshot_max_age_days * 86400:
                p.unlink(missing_ok=True); n += 1
        return n

    @staticmethod
    def verify(path: Path) -> bool:
        try:
            c = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
            try:
                return c.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            finally:
                c.close()
        except sqlite3.Error:
            return False

    def restore(self, snapshot: Path, target: Path, replay_ledger: bool = True) -> int:
        """把快照复制到 target（必须不存在），并重放删除台账；返回重放删除的账号数。"""
        if target.exists():
            raise FileExistsError("目标库已存在，拒绝覆盖")
        if not self.verify(snapshot):
            raise ValueError("快照校验未通过")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(snapshot, target)
        target.chmod(0o600)
        n = 0
        if replay_ledger:
            c = sqlite3.connect(target)
            try:
                c.execute("PRAGMA foreign_keys=ON")
                for uid in self.ledger_ids():
                    n += c.execute("DELETE FROM users WHERE id=?", (uid,)).rowcount
                    c.execute("DELETE FROM feedback WHERE user_id=?", (uid,))
                    c.execute("DELETE FROM heartbeats WHERE user_id=?", (uid,))
                    c.execute("DELETE FROM audit WHERE user_id=?", (uid,))
                    c.execute("DELETE FROM deletion_requests WHERE user_id=?", (uid,))
                    c.execute("INSERT OR IGNORE INTO deletion_ledger(user_id,deleted_at) VALUES(?,?)", (uid, self.now()))
                c.commit()
            finally:
                c.close()
        return n

    # ---------- 健康检查 / 告警 ----------
    def cert_remaining_hours(self) -> float | None:
        if not self.s.cert_path or not self.s.cert_path.exists():
            return None
        from cryptography import x509
        cert = x509.load_pem_x509_certificate(self.s.cert_path.read_bytes())
        return (cert.not_valid_after_utc.timestamp() - self.now()) / 3600

    def health_checks(self) -> list[str]:
        problems = []
        try:
            h = self.cert_remaining_hours()
            if h is not None and h < 24:
                self.alerts.notify("cert_expiring", f"证书剩余 {h:.1f} 小时"); problems.append("cert")
        except Exception as e:  # noqa: BLE001
            self.alerts.notify("cert_unreadable", type(e).__name__); problems.append("cert")
        try:
            u = shutil.disk_usage(self.s.disk_path)
            if u.used / u.total * 100 >= self.s.disk_alert_pct:
                self.alerts.notify("disk_high", f"磁盘使用 {u.used / u.total * 100:.0f}%"); problems.append("disk")
        except OSError:
            pass
        return problems

    def tick(self) -> None:
        """后台线程每小时调用一次。快照由 systemd 定时器调用 CLI，不在此处。"""
        self.run_deletions(); self.cleanup(); self.health_checks()
