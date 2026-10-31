"""SQLite（WAL）+ 版本化迁移。事务一律 BEGIN IMMEDIATE；迁移前对已有库做一致快照；失败则中止启动。"""
from __future__ import annotations

import logging
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path

from sqlalchemy import create_engine, event, text
from sqlalchemy.engine import Connection, Engine

log = logging.getLogger("getmedia.db")


class MigrationError(RuntimeError):
    pass


def make_engine(path: Path) -> Engine:
    path.parent.mkdir(parents=True, exist_ok=True)
    engine = create_engine(f"sqlite:///{path}", connect_args={"check_same_thread": False})

    @event.listens_for(engine, "connect")
    def _connect(dbapi, _rec):
        dbapi.isolation_level = None  # 事务由下面的 begin 事件手动控制
        cur = dbapi.cursor()
        cur.execute("PRAGMA journal_mode=WAL")
        cur.execute("PRAGMA busy_timeout=5000")
        cur.execute("PRAGMA foreign_keys=ON")
        cur.execute("PRAGMA synchronous=NORMAL")
        cur.close()

    @event.listens_for(engine, "begin")
    def _begin(conn):
        conn.exec_driver_sql("BEGIN IMMEDIATE")

    return engine


@contextmanager
def begin(engine: Engine):
    with engine.begin() as conn:
        yield conn


def snapshot_file(src: Path, dest: Path) -> None:
    """用 SQLite 在线备份 API 做一致快照，写入 .partial 校验后改名；目录 700、文件 600。"""
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.parent.chmod(0o700)
    part = dest.with_name(dest.name + ".partial")
    s = sqlite3.connect(f"file:{src}?mode=ro", uri=True)
    d = sqlite3.connect(part)
    try:
        s.backup(d)
        ok = d.execute("PRAGMA integrity_check").fetchone()[0]
    finally:
        d.close(); s.close()
    if ok != "ok":
        part.unlink(missing_ok=True)
        raise MigrationError("快照完整性检查未通过")
    part.chmod(0o600)
    part.rename(dest)


# 版本 → 语句列表。迁移脚本必须幂等（IF NOT EXISTS）。
MIGRATIONS: list[tuple[int, list[str]]] = [
    (1, [
        """CREATE TABLE IF NOT EXISTS users(
            id TEXT PRIMARY KEY,
            login_name TEXT NOT NULL,
            login_name_norm TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            tier TEXT NOT NULL DEFAULT 'free',
            tier_expires_at INTEGER,
            status TEXT NOT NULL DEFAULT 'active',
            recovery_confirmed_at INTEGER,
            registered_channel TEXT,
            registered_version TEXT,
            created_at INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS sessions(
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            token_hash TEXT NOT NULL UNIQUE,
            channel TEXT NOT NULL,
            device_name TEXT NOT NULL DEFAULT '',
            app_version TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            last_used_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            last_ip TEXT NOT NULL DEFAULT '',
            revoked_at INTEGER)""",
        "CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id)",
        """CREATE TABLE IF NOT EXISTS recovery_codes(
            user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
            code_hmac TEXT NOT NULL,
            rotated_at INTEGER NOT NULL)""",
        # text 列：保存首次登记时的全文，使热加载被拒绝或重启后仍能继续提供上一份已登记文本
        """CREATE TABLE IF NOT EXISTS legal_versions(
            doc TEXT NOT NULL,
            version INTEGER NOT NULL,
            sha256 TEXT NOT NULL,
            effective_date TEXT NOT NULL,
            text TEXT NOT NULL,
            first_loaded_at INTEGER NOT NULL,
            PRIMARY KEY(doc, version))""",
        """CREATE TABLE IF NOT EXISTS consents(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            ua_version INTEGER NOT NULL, ua_sha256 TEXT NOT NULL,
            pp_version INTEGER NOT NULL, pp_sha256 TEXT NOT NULL,
            consented_at INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS idx_consents_user ON consents(user_id)",
        """CREATE TABLE IF NOT EXISTS invites(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code_hash TEXT NOT NULL UNIQUE,
            purpose TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            expires_at INTEGER,
            used_by TEXT,
            used_at INTEGER)""",
        """CREATE TABLE IF NOT EXISTS audit(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts INTEGER NOT NULL,
            event TEXT NOT NULL,
            user_id TEXT,
            ip TEXT NOT NULL DEFAULT '',
            path TEXT NOT NULL DEFAULT '',
            result TEXT NOT NULL DEFAULT '')""",
        "CREATE INDEX IF NOT EXISTS idx_audit_ts ON audit(ts)",
        """CREATE TABLE IF NOT EXISTS rate_limits(
            key TEXT NOT NULL,
            window_start INTEGER NOT NULL,
            count INTEGER NOT NULL,
            PRIMARY KEY(key, window_start))""",
    ]),
    (2, [
        # 活跃信息（含设备型号/版本）最长保留 30 天；长期只留 daily_stats 里的数字
        """CREATE TABLE IF NOT EXISTS heartbeats(
            day TEXT NOT NULL,
            session_id TEXT NOT NULL,
            user_id TEXT NOT NULL,
            channel TEXT NOT NULL,
            app_version TEXT NOT NULL DEFAULT '',
            os TEXT NOT NULL DEFAULT '',
            device TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(day, session_id))""",
        "CREATE INDEX IF NOT EXISTS idx_heartbeats_user ON heartbeats(day, user_id)",
        """CREATE TABLE IF NOT EXISTS daily_stats(
            day TEXT PRIMARY KEY,
            registrations INTEGER NOT NULL DEFAULT 0,
            active INTEGER NOT NULL DEFAULT 0)""",
    ]),
    (3, [
        """CREATE TABLE IF NOT EXISTS feedback(
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            category TEXT NOT NULL DEFAULT '',
            text TEXT,
            contact TEXT NOT NULL DEFAULT '',
            diagnostics_json TEXT NOT NULL DEFAULT '{}',
            image_paths TEXT NOT NULL DEFAULT '[]',
            image_bytes INTEGER NOT NULL DEFAULT 0,
            status TEXT NOT NULL DEFAULT 'new',
            mail_state TEXT NOT NULL DEFAULT 'pending',
            email_attempts INTEGER NOT NULL DEFAULT 0,
            next_attempt_at INTEGER NOT NULL DEFAULT 0,
            idempotency_key TEXT NOT NULL,
            UNIQUE(user_id, idempotency_key))""",
        "CREATE INDEX IF NOT EXISTS idx_feedback_user ON feedback(user_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_feedback_mail ON feedback(mail_state, next_attempt_at)",
    ]),
]


def _applied(conn: Connection) -> int:
    conn.exec_driver_sql("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)")
    return conn.execute(text("SELECT COALESCE(MAX(version),0) FROM schema_version")).scalar_one()


def migrate(engine: Engine, db_path: Path, snapshot_dir: Path, now: float | None = None) -> int:
    """应用待执行迁移；返回当前版本。有待执行迁移且库非全新时先做快照；任何失败抛 MigrationError（调用方应中止启动）。"""
    now = int(now if now is not None else time.time())
    with engine.begin() as c:
        current = _applied(c)
    pending = [m for m in MIGRATIONS if m[0] > current]
    if not pending:
        return current
    if current > 0:
        snapshot_file(db_path, snapshot_dir / f"pre-migrate-v{current}-{now}.db")
    for version, stmts in pending:
        try:
            with engine.begin() as c:
                for s in stmts:
                    c.exec_driver_sql(s)
                c.execute(text("INSERT INTO schema_version(version, applied_at) VALUES(:v,:t)"), {"v": version, "t": now})
        except Exception as e:  # noqa: BLE001 - 只记录类别，不记原文
            raise MigrationError(f"迁移 v{version} 失败：{type(e).__name__}") from None
        log.info("migrated to v%s", version)
    return pending[-1][0]
