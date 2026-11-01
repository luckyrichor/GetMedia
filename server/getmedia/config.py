"""配置：只从环境读取；不写入任何真实密钥。"""
from __future__ import annotations

import os
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Mapping


class ConfigError(RuntimeError):
    pass


@dataclass(frozen=True)
class MailConfig:
    """FEEDBACK_* 七个变量；只从环境读取。repr 不含任何值。"""
    to: str = field(repr=False)
    sender: str = field(repr=False)
    host: str = field(repr=False)
    port: int = field(repr=False)
    security: str = field(repr=False)
    user: str = field(repr=False)
    password: str = field(repr=False)


def load_mail(env: Mapping[str, str]) -> "MailConfig | None":
    keys = ["FEEDBACK_MAIL_TO", "FEEDBACK_MAIL_FROM", "FEEDBACK_SMTP_HOST", "FEEDBACK_SMTP_PORT",
            "FEEDBACK_SMTP_SECURITY", "FEEDBACK_SMTP_USER", "FEEDBACK_SMTP_PASSWORD"]
    if not all(env.get(k) for k in keys):
        return None
    sec = env["FEEDBACK_SMTP_SECURITY"].lower()
    if sec not in ("ssl", "starttls"):
        raise ConfigError("FEEDBACK_SMTP_SECURITY 只能是 ssl 或 starttls")
    try:
        port = int(env["FEEDBACK_SMTP_PORT"])
    except ValueError:
        raise ConfigError("FEEDBACK_SMTP_PORT 必须是数字") from None
    return MailConfig(env[keys[0]], env[keys[1]], env[keys[2]], port, sec, env[keys[5]], env[keys[6]])


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    secret_key: bytes
    database_path: Path
    legal_dir: Path
    release_file: Path
    runtime_file: Path
    backup_dir: Path
    ledger_path: Path
    feedback_image_dir: Path
    bind: str = "127.0.0.1:8700"
    cookie_path: str = "/getmedia/api/"
    # Argon2id（参数写入哈希串，可日后调整）
    argon_memory_kib: int = 65536
    argon_iterations: int = 3
    hash_concurrency: int = 2
    # 会话
    app_session_days: int = 90
    web_session_days: int = 14
    max_app_devices: int = 5
    max_web_devices: int = 3
    # 限流 (次数, 窗口秒)
    login_ip_limit: tuple = (10, 300)
    login_name_fail_limit: tuple = (5, 900)
    register_ip_limit: tuple = (5, 3600)
    recover_account_limit: tuple = (5, 3600)
    trusted_proxies: tuple = ("127.0.0.1", "::1")
    # 反馈
    mail: "MailConfig | None" = None
    feedback_account_limit: tuple = (5, 86400)
    feedback_ip_limit: tuple = (10, 86400)
    feedback_min_interval: int = 30
    feedback_daily_mail_cap: int = 50
    feedback_image_total_cap: int = 2 * 1024**3
    feedback_image_days: int = 90
    feedback_text_days: int = 180
    # 运维
    deletion_cooldown_days: int = 7
    retention_days: int = 30          # 审计 / 限流 / 活跃明细
    snapshot_keep: int = 7
    snapshot_max_age_days: int = 7
    cert_path: "Path | None" = None
    disk_path: Path = Path("/")
    disk_alert_pct: int = 85

    def with_(self, **kw) -> "Settings":
        return replace(self, **kw)


def _sqlite_path(url: str) -> Path:
    prefix = "sqlite:///"
    if not url.startswith(prefix):
        raise ConfigError("GETMEDIA_DATABASE_URL 只支持 sqlite:///<绝对路径>")
    return Path(url[len(prefix):])


def _int_env(env: Mapping[str, str], name: str, default: int, lo: int, hi: int) -> int:
    raw = env.get(name)
    if raw is None or raw == "":
        return default
    try:
        v = int(raw)
    except ValueError:
        raise ConfigError(f"{name} 必须是整数") from None
    if not lo <= v <= hi:
        raise ConfigError(f"{name} 超出允许范围 {lo}–{hi}")
    return v


def load_settings(env: Mapping[str, str] | None = None) -> Settings:
    env = os.environ if env is None else env
    data_dir = Path(env.get("GETMEDIA_DATA_DIR", "/var/lib/getmedia"))
    key = env.get("GETMEDIA_SECRET_KEY", "")
    if len(key) < 32:
        raise ConfigError("GETMEDIA_SECRET_KEY 未设置或过短（至少 32 个字符）")
    db_url = env.get("GETMEDIA_DATABASE_URL")
    etc = Path("/etc/getmedia")
    # Argon2id 参数：在服务器规格上复测后可调到单次约 100–200 ms；参数写入哈希串，已有哈希不受影响
    argon_mem = _int_env(env, "GETMEDIA_ARGON_MEMORY_KIB", 65536, 8192, 1048576)
    argon_iter = _int_env(env, "GETMEDIA_ARGON_ITERATIONS", 3, 1, 10)
    return Settings(
        data_dir=data_dir,
        secret_key=key.encode("utf-8"),
        database_path=_sqlite_path(db_url) if db_url else data_dir / "getmedia.db",
        legal_dir=Path(env.get("GETMEDIA_LEGAL_DIR", str(etc / "legal"))),
        release_file=Path(env.get("GETMEDIA_RELEASE_FILE", str(etc / "release.json"))),
        runtime_file=Path(env.get("GETMEDIA_RUNTIME_FILE", str(etc / "runtime.json"))),
        backup_dir=Path(env.get("GETMEDIA_BACKUP_DIR", str(data_dir / "snapshots"))),
        ledger_path=Path(env.get("GETMEDIA_LEDGER_PATH", str(data_dir.parent / "getmedia-ledger" / "deletion-ledger.log"))),
        feedback_image_dir=Path(env.get("GETMEDIA_FEEDBACK_IMAGE_DIR", str(data_dir / "feedback-images"))),
        bind=env.get("GETMEDIA_BIND", "127.0.0.1:8700"),
        argon_memory_kib=argon_mem,
        argon_iterations=argon_iter,
        mail=load_mail(env),
        cert_path=Path(env["GETMEDIA_CERT_PATH"]) if env.get("GETMEDIA_CERT_PATH") else None,
        disk_path=Path(env.get("GETMEDIA_DISK_PATH", str(data_dir))),
    )


def testing_settings(root: Path, legal_dir: Path | None = None) -> Settings:
    """测试用：低成本 Argon2 参数，所有路径在临时目录。"""
    return Settings(
        data_dir=root,
        secret_key=b"test-secret-key-0123456789abcdef0123456789",
        database_path=root / "t.db",
        legal_dir=legal_dir or root / "legal",
        release_file=root / "release.json",
        runtime_file=root / "runtime.json",
        backup_dir=root / "snapshots",
        ledger_path=root / "ledger" / "deletion-ledger.log",
        feedback_image_dir=root / "feedback-images",
        argon_memory_kib=64,
        argon_iterations=1,
    )
