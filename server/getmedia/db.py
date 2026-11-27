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
    (4, [
        """CREATE TABLE IF NOT EXISTS deletion_requests(
            user_id TEXT PRIMARY KEY,
            requested_at INTEGER NOT NULL,
            execute_at INTEGER NOT NULL,
            executed_at INTEGER)""",
        # 台账：只存账号编号与删除时间；同时追加写入快照目录之外的文件
        """CREATE TABLE IF NOT EXISTS deletion_ledger(
            user_id TEXT PRIMARY KEY,
            deleted_at INTEGER NOT NULL)""",
    ]),
    # v5 媒体库与提取任务（P2a）
    (5, [
        """CREATE TABLE IF NOT EXISTS tracks(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            media_type TEXT NOT NULL,
            platform TEXT NOT NULL,
            video_id TEXT NOT NULL,
            page INTEGER NOT NULL DEFAULT 1,
            title TEXT NOT NULL DEFAULT '',
            uploader TEXT,
            duration_sec REAL,
            cover_url TEXT,
            source_url TEXT,
            storage TEXT NOT NULL DEFAULT 'metadata',
            media_path TEXT,
            bytes INTEGER NOT NULL DEFAULT 0,
            sha256 TEXT,
            pinned INTEGER NOT NULL DEFAULT 0,
            deleted INTEGER NOT NULL DEFAULT 0,
            updated_seq INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            cache_expires_at INTEGER,
            last_access_at INTEGER,
            UNIQUE(user_id, platform, video_id, page))""",
        "CREATE INDEX IF NOT EXISTS idx_tracks_user_seq ON tracks(user_id, updated_seq)",
        "CREATE INDEX IF NOT EXISTS idx_tracks_cache ON tracks(storage, cache_expires_at)",
        """CREATE TABLE IF NOT EXISTS extract_jobs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            media_type TEXT NOT NULL,
            storage TEXT NOT NULL DEFAULT 'metadata',
            input TEXT NOT NULL,
            platform TEXT,
            video_id TEXT,
            status TEXT NOT NULL DEFAULT 'queued',
            error_code TEXT,
            error_detail TEXT,
            track_id INTEGER,
            created_at INTEGER NOT NULL,
            started_at INTEGER,
            finished_at INTEGER)""",
        "CREATE INDEX IF NOT EXISTS idx_extract_jobs_user ON extract_jobs(user_id, id)",
        """CREATE TABLE IF NOT EXISTS extraction_failures(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT,
            platform TEXT,
            video_id TEXT,
            track_id INTEGER,
            stage TEXT NOT NULL DEFAULT '',
            error_code TEXT NOT NULL DEFAULT '',
            detail TEXT NOT NULL DEFAULT '',
            client_version TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS idx_failures_created ON extraction_failures(created_at)",
    ]),
    # v6 博主批量任务（P2b）
    (6, [
        """CREATE TABLE IF NOT EXISTS creator_jobs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            platform TEXT NOT NULL,
            creator_id TEXT NOT NULL,
            creator_name TEXT NOT NULL DEFAULT '',
            source_url TEXT NOT NULL DEFAULT '',
            mode TEXT NOT NULL,
            since_ts INTEGER,
            until_ts INTEGER,
            limit_n INTEGER,
            include_pinned INTEGER NOT NULL DEFAULT 1,
            storage TEXT NOT NULL DEFAULT 'phone',
            media_type TEXT NOT NULL DEFAULT 'video',
            status TEXT NOT NULL DEFAULT 'pending',
            pause_reason TEXT,
            cursor_json TEXT NOT NULL DEFAULT '{}',
            total INTEGER,
            done INTEGER NOT NULL DEFAULT 0,
            failed INTEGER NOT NULL DEFAULT 0,
            estimated_bytes INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            started_at INTEGER,
            finished_at INTEGER)""",
        "CREATE INDEX IF NOT EXISTS idx_creator_jobs_user ON creator_jobs(user_id, id)",
        """CREATE TABLE IF NOT EXISTS creator_items(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            job_id INTEGER NOT NULL REFERENCES creator_jobs(id) ON DELETE CASCADE,
            platform TEXT NOT NULL,
            video_id TEXT NOT NULL,
            page INTEGER NOT NULL DEFAULT 1,
            title TEXT NOT NULL DEFAULT '',
            duration_sec REAL,
            published_at INTEGER,
            is_pinned INTEGER NOT NULL DEFAULT 0,
            cover_url TEXT,
            source_url TEXT NOT NULL DEFAULT '',
            download_state TEXT NOT NULL DEFAULT 'pending',
            track_id INTEGER,
            error TEXT,
            updated_at INTEGER NOT NULL,
            UNIQUE(job_id, platform, video_id, page))""",
        "CREATE INDEX IF NOT EXISTS idx_creator_items_state ON creator_items(job_id, download_state)",
    ]),
    # v7 定时抓取（P2c）：旧 keywords.yaml 的 groups 变成表
    (7, [
        """CREATE TABLE IF NOT EXISTS schedules(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            platform TEXT NOT NULL,
            input TEXT NOT NULL,
            mode TEXT NOT NULL DEFAULT 'latest_n',
            since_ts INTEGER,
            until_ts INTEGER,
            limit_n INTEGER,
            interval_minutes INTEGER NOT NULL,
            include_pinned INTEGER NOT NULL DEFAULT 1,
            storage TEXT NOT NULL DEFAULT 'phone',
            media_type TEXT NOT NULL DEFAULT 'video',
            enabled INTEGER NOT NULL DEFAULT 1,
            next_run INTEGER,
            last_run INTEGER,
            last_status TEXT,
            last_job_id INTEGER,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            UNIQUE(user_id, name))""",
        "CREATE INDEX IF NOT EXISTS idx_schedules_due ON schedules(enabled, next_run)",
        """CREATE TABLE IF NOT EXISTS schedule_runs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            schedule_id INTEGER NOT NULL REFERENCES schedules(id) ON DELETE CASCADE,
            job_id INTEGER,
            status TEXT NOT NULL DEFAULT 'queued',
            message TEXT,
            started_at INTEGER NOT NULL,
            finished_at INTEGER)""",
        "CREATE INDEX IF NOT EXISTS idx_schedule_runs ON schedule_runs(schedule_id, id)",
    ]),
    # v8 提取 Agent（P2d）
    (8, [
        """CREATE TABLE IF NOT EXISTS agent_conversations(
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            title TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS idx_agent_conv_user ON agent_conversations(user_id, updated_at)",
        """CREATE TABLE IF NOT EXISTS agent_messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            conversation_id TEXT NOT NULL REFERENCES agent_conversations(id) ON DELETE CASCADE,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            meta_json TEXT NOT NULL DEFAULT '{}',
            created_at INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS idx_agent_msg_conv ON agent_messages(conversation_id, id)",
        """CREATE TABLE IF NOT EXISTS agent_memories(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            key TEXT NOT NULL,
            value TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            UNIQUE(user_id, key))""",
    ]),
    # v9 平台授权（P2e）：Cookie 只存密文，明文仅以 600 权限的 Netscape 文件存在服务端
    (9, [
        """CREATE TABLE IF NOT EXISTS platform_credentials(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            platform TEXT NOT NULL,
            cookie_blob BLOB NOT NULL,
            status TEXT NOT NULL DEFAULT 'active',
            label TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            last_verified_at INTEGER,
            UNIQUE(user_id, platform))""",
        """CREATE TABLE IF NOT EXISTS platform_auth_sessions(
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            platform TEXT NOT NULL,
            method TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            message TEXT,
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL)""",
        """CREATE TABLE IF NOT EXISTS account_platform_bindings(
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            platform TEXT NOT NULL,
            bound_at INTEGER NOT NULL,
            source TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'active',
            PRIMARY KEY(user_id, platform))""",
    ]),
    # v10 AI 视频重建（P2g）：任务表即队列，worker 独立进程领取
    (10, [
        """CREATE TABLE IF NOT EXISTS recreations(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            title TEXT NOT NULL DEFAULT '',
            source_track_id INTEGER,
            source_path TEXT,
            description TEXT,
            status TEXT NOT NULL DEFAULT 'draft',
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS idx_recreations_user ON recreations(user_id, id)",
        """CREATE TABLE IF NOT EXISTS recreation_runs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            recreation_id INTEGER NOT NULL REFERENCES recreations(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'queued',
            progress INTEGER NOT NULL DEFAULT 0,
            output_path TEXT,
            error_code TEXT,
            attempts INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            started_at INTEGER,
            finished_at INTEGER)""",
        "CREATE INDEX IF NOT EXISTS idx_recreation_runs_queue ON recreation_runs(status, id)",
    ]),
    # v11 提取 Agent 的动作闭环（P2h）：模型负责"说"，动作由服务端生成、手机领取并回报。
    # 单独建表而不是塞进 messages：动作有状态机（pending→running→done/failed）和一次性语义，
    # 与消息的"追加不改"不同；放在 agent_actions 里可以用唯一约束挡住重复领取。
    (11, [
        """CREATE TABLE IF NOT EXISTS agent_actions(
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            conversation_id TEXT NOT NULL REFERENCES agent_conversations(id) ON DELETE CASCADE,
            kind TEXT NOT NULL DEFAULT 'extract',
            platform TEXT NOT NULL DEFAULT '',
            input_url TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            mode TEXT,
            result_json TEXT,
            created_at INTEGER NOT NULL,
            claimed_at INTEGER,
            finished_at INTEGER)""",
        "CREATE INDEX IF NOT EXISTS idx_agent_actions_conv ON agent_actions(conversation_id, id)",
        "CREATE INDEX IF NOT EXISTS idx_agent_actions_user ON agent_actions(user_id, status)",
        # 每日调用计数：按 (用户, 日期) 自增，用来实现"每账号每日 N 次"的上限，不需要扫消息表
        """CREATE TABLE IF NOT EXISTS agent_usage(
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            day TEXT NOT NULL,
            calls INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(user_id, day))""",
    ]),
    # v12 批量条目存播放地址：抖音的作品列表响应里本来就带 video.play_addr，
    # 存下来后下载可以直接转发 CDN，不必为每条再开一次浏览器（一次约 10-20 秒）。
    # 地址带签名、有过期时间，失效时下载接口会回落到实时解析。
    (12, [
        "ALTER TABLE creator_items ADD COLUMN play_urls TEXT",
        "ALTER TABLE creator_items ADD COLUMN play_urls_at INTEGER",
    ]),
    # 曲库清单备份：独立表存 gzip 快照，服务器不解释内容（design §3.1）
    (13, [
        """CREATE TABLE IF NOT EXISTS metadata_backups(
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            bytes INTEGER NOT NULL,
            raw_bytes INTEGER NOT NULL,
            sha256 TEXT NOT NULL,
            counts_json TEXT NOT NULL,
            app_version TEXT NOT NULL DEFAULT '',
            device TEXT NOT NULL DEFAULT '',
            payload BLOB NOT NULL,
            created_at INTEGER NOT NULL)""",
        "CREATE INDEX IF NOT EXISTS idx_meta_backups_user ON metadata_backups(user_id, created_at DESC)",
    ]),
    # v14 批量提取真的落盘：条目可以带一份服务器文件（暂存 / 长期保存）。
    # 文件路径只是相对名，真实位置由 job.storage 决定（cache_dir / media_dir），
    # 与曲库共用同一套配额账本，这样「服务器一共多少空间」只有一个答案。
    (14, [
        "ALTER TABLE creator_items ADD COLUMN media_path TEXT",
        "ALTER TABLE creator_items ADD COLUMN bytes INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE creator_items ADD COLUMN stored_at INTEGER",
        "ALTER TABLE creator_items ADD COLUMN store_expires_at INTEGER",
        "ALTER TABLE creator_items ADD COLUMN store_error TEXT",
        "CREATE INDEX IF NOT EXISTS idx_creator_items_stored ON creator_items(stored_at)",
    ]),
    # v15 批量提取多格式 + 任务卡信息：
    # ``formats`` 是这一批要产出哪些格式（source/mp4/mkv/webm 或 m4a/mp3/flac/wav），
    # ``files_json`` 记条目在服务器上的每个格式对应哪个文件（{格式: {path, bytes}}），
    # ``added_at`` 是条目入库时间（表头「提取时间」用它，落盘后另有 stored_at）。
    (15, [
        "ALTER TABLE creator_jobs ADD COLUMN formats TEXT",
        "ALTER TABLE creator_jobs ADD COLUMN source_input TEXT",
        "ALTER TABLE creator_items ADD COLUMN files_json TEXT",
        "ALTER TABLE creator_items ADD COLUMN added_at INTEGER",
        "UPDATE creator_items SET added_at = updated_at WHERE added_at IS NULL",
    ]),
    # v16 回填老任务的 source_input：早期创建任务时后端把 source_input 写死成了链接，
    # 用户填的原始抖音号没留下来（库里只剩 sec_uid）。这里把已知的这批任务补上抖音号，
    # 让任务卡能亮出「抖音号 xxx」而不是一串看不懂的 sec_uid。
    (16, [
        "UPDATE creator_jobs SET source_input = '67194609689'"
        " WHERE source_input IS NULL AND platform = 'douyin'"
        " AND creator_id = 'MS4wLjABAAAAAR1hYk5la2AbZ_a-00wgh6UnypbV35hOG_8bQvsBqDrNGZpxcHBu7ueuCbAarO5l'",
    ]),
    # v17 置顶作品独立成组：置顶作品要在列表最前、按平台给的顺序、不占普通作品序号。
    # ``pinned_order`` 记置顶作品在平台原始列表里的顺序（0 起，NULL 表示非置顶）；
    # 普通作品仍按 published_at 倒序、序号从 1 开始。is_pinned 已有，这里只补顺序。
    (17, [
        "ALTER TABLE creator_items ADD COLUMN pinned_order INTEGER",
    ]),
    # v18 「指定起点」选取方式：从第 N 个普通作品（剔除置顶，按发布时间倒序）开始，
    # 往最新（newer）或往最久（older）方向取。start_n 是起点序号（1=最新），direction 是方向。
    (18, [
        "ALTER TABLE creator_jobs ADD COLUMN start_n INTEGER",
        "ALTER TABLE creator_jobs ADD COLUMN direction TEXT",
    ]),
    # v19 整理信息跨设备同步：收藏与所属类别挂在条目上，播放列表单独两张表。
    #
    # - ``tracks.favorite`` / ``tracks.category``：category 存**类别路径**（如 ``音乐/古风``），
    #   空/NULL 表示未分类。不存类别编号——两台设备的类别编号互不相干，路径才是它们能对齐的东西。
    # - ``playlists``：按 ``(user_id, name)`` 唯一，名称就是跨设备的身份。``deleted`` 是墓碑，
    #   别的设备靠它知道"这个列表被删了"；``updated_seq`` 只在播放列表自己的序号空间里递增
    #   （客户端每次全量拉，列表数量很小，不需要和曲库共用水位）。
    # - ``playlist_items``：存条目的平台三元组而不是 track id——条目可能还没被这台设备拉下来，
    #   存 id 会让它永远对不上。
    (19, [
        "ALTER TABLE tracks ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0",
        "ALTER TABLE tracks ADD COLUMN category TEXT",
        """CREATE TABLE IF NOT EXISTS playlists(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            updated_seq INTEGER NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            UNIQUE(user_id, name))""",
        "CREATE INDEX IF NOT EXISTS idx_playlists_user_seq ON playlists(user_id, updated_seq)",
        """CREATE TABLE IF NOT EXISTS playlist_items(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            playlist_id INTEGER NOT NULL,
            platform TEXT NOT NULL,
            video_id TEXT NOT NULL,
            page INTEGER NOT NULL DEFAULT 1,
            order_index INTEGER NOT NULL DEFAULT 0,
            added_at INTEGER NOT NULL,
            UNIQUE(user_id, playlist_id, platform, video_id, page))""",
        "CREATE INDEX IF NOT EXISTS idx_playlist_items_list ON playlist_items(playlist_id, order_index)",
    ]),
    # v20：等级取值 owner → full，并指定唯一的 admin；admin 用部分唯一索引兜底。
    # `luckyrichor` 是空号（0 曲目/0 列表），提升为管理员不动任何数据；账号不存在时 UPDATE 影响 0 行，不报错。
    (20, [
        "UPDATE users SET tier='full' WHERE tier='owner'",
        "UPDATE users SET tier='admin', tier_expires_at=NULL WHERE login_name_norm='luckyrichor'",
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_users_one_admin ON users(tier) WHERE tier='admin'",
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
