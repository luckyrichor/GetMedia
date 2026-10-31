"""配置：只从环境读取；不写入任何真实密钥。"""
from __future__ import annotations

import os
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Mapping


class ConfigError(RuntimeError):
    pass


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

    def with_(self, **kw) -> "Settings":
        return replace(self, **kw)


def _sqlite_path(url: str) -> Path:
    prefix = "sqlite:///"
    if not url.startswith(prefix):
        raise ConfigError("GETMEDIA_DATABASE_URL 只支持 sqlite:///<绝对路径>")
    return Path(url[len(prefix):])


def load_settings(env: Mapping[str, str] | None = None) -> Settings:
    env = os.environ if env is None else env
    data_dir = Path(env.get("GETMEDIA_DATA_DIR", "/var/lib/getmedia"))
    key = env.get("GETMEDIA_SECRET_KEY", "")
    if len(key) < 32:
        raise ConfigError("GETMEDIA_SECRET_KEY 未设置或过短（至少 32 个字符）")
    db_url = env.get("GETMEDIA_DATABASE_URL")
    etc = Path("/etc/getmedia")
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
