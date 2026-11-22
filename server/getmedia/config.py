"""配置：只从环境读取；不写入任何真实密钥。"""
from __future__ import annotations

import base64
import os
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Mapping
from urllib.parse import unquote, urlsplit


class ConfigError(RuntimeError):
    pass


@dataclass(frozen=True)
class ProxyConfig:
    """SMTP 代理（HTTP CONNECT）。repr 不含认证头，避免日志里出现凭据。"""
    host: str
    port: int
    auth_header: str = field(default="", repr=False)


@dataclass(frozen=True)
class MailConfig:
    """FEEDBACK_* 变量；只从环境读取。repr 不含任何值。"""
    to: str = field(repr=False)
    sender: str = field(repr=False)
    host: str = field(repr=False)
    port: int = field(repr=False)
    security: str = field(repr=False)
    user: str = field(repr=False)
    password: str = field(repr=False)
    # 可选：FEEDBACK_SMTP_PROXY。None = 直连；否则先 CONNECT 隧道再 TLS
    proxy: "ProxyConfig | None" = field(default=None, repr=False)


def parse_proxy(raw: str | None) -> ProxyConfig | None:
    """`http://[user:pass@]host:port`，也接受简写 `host:port`；空值返回 None（直连）。

    格式错误抛 ConfigError，让它在启动期暴露，不拖到第一次发信才炸。
    """
    text = (raw or "").strip()
    if not text:
        return None
    if "://" not in text:
        text = "http://" + text
    parts = urlsplit(text)
    if parts.scheme.lower() != "http":
        raise ConfigError(f"FEEDBACK_SMTP_PROXY 只支持 http://，收到 {parts.scheme}://")
    if not parts.hostname:
        raise ConfigError("FEEDBACK_SMTP_PROXY 缺少主机名")
    try:
        port = parts.port
    except ValueError:
        raise ConfigError("FEEDBACK_SMTP_PROXY 的端口不是数字") from None
    if not port:
        raise ConfigError("FEEDBACK_SMTP_PROXY 必须写明端口，例如 http://127.0.0.1:7890")

    auth = ""
    if parts.username is not None:
        pair = f"{unquote(parts.username)}:{unquote(parts.password or '')}"
        auth = "Basic " + base64.b64encode(pair.encode("utf-8")).decode("ascii")
    return ProxyConfig(parts.hostname, port, auth)


def load_mail(env: Mapping[str, str]) -> "MailConfig | None":
    # 这七项缺一个就整块视为未配置（只落库不发信）；FEEDBACK_SMTP_PROXY 是可选的第八项
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
    return MailConfig(env[keys[0]], env[keys[1]], env[keys[2]], port, sec, env[keys[5]], env[keys[6]],
                      proxy=parse_proxy(env.get("FEEDBACK_SMTP_PROXY")))


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
    creator_cookie_dir: Path
    media_dir: Path
    cache_dir: Path
    bind: str = "127.0.0.1:8700"
    cookie_path: str = "/getmedia/api/"
    # 平台凭据（Cookie）加密：AES-256-GCM，密钥只从 GETMEDIA_CRED_KEY 读。
    # 为空表示未配置，需要凭据的接口一律 503，不静默降级成明文。
    credential_key: bytes = b""
    # 服务端可选落盘的配额（0 = 禁用该模式）
    cache_ttl_seconds: int = 86400          # 单条提取的临时缓存保留 1 天
    cache_quota_bytes: int = 1 * 1024**3    # 服务器缓存 1 GiB
    archive_quota_bytes: int = 5 * 1024**3  # 服务器存储 5 GiB，满了按时间删最旧
    # 批量提取“服务器缓存”的保留期：一次任务可能跑几小时，1 天太短，给 7 天
    creator_cache_ttl_seconds: int = 7 * 86400
    # 每账号曲库行数上限（只算未删除的元数据行）。手机端同步会往里写，防跑飞；0 = 不限制
    library_max_rows: int = 20000
    # 手机端写入配额：每账号 60 条/分钟（与备份上传同一套固定窗口，落库、重启不丢）
    library_push_limit: tuple = (60, 60)
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
    # 博主批量：服务端只取列表，媒体仍在手机上提取
    http_timeout: float = 15.0
    max_redirects: int = 5
    creator_max_items: int = 50        # 单次最多返回多少条
    creator_max_start: int = 500       # 起点上限
    creator_page_size: int = 20        # 批量任务扫描的每页条数
    creator_scan_batch: int = 300      # 「全部作品」单次窗口条数（抖音桥接翻页很贵，一次抓够）
    creator_max_pages_per_run: int = 200   # 单次运行最多翻多少页（防一次跑太久）
    creator_audio_mb_per_min: float = 1.0  # 空间预估：音频
    creator_video_mb_per_min: float = 30.0  # 空间预估：视频
    creator_preview_limit: tuple = (30, 3600)   # 每账号每小时预览次数
    creator_request_interval: float = 0.0   # 逐条补互动数据之间的间隔
    douyin_browser: bool = False       # 抖音浏览器桥接（需 playwright）
    douyin_browser_headless: bool = True
    douyin_browser_timeout: float = 30.0
    douyin_browser_max_scrolls: int = 6
    douyin_browser_channel: str = ""
    # 提取 Agent 的模型（P2h，方案 B）：没有 api_key 时一律走确定性规则
    llm_api_key: str = ""
    llm_base_url: str = "https://ark.cn-beijing.volces.com/api/v3"
    llm_models: tuple = ()
    llm_timeout: float = 35.0
    llm_max_tokens: int = 700
    llm_daily_cap: int = 200
    # AI 视频重建（P2g）：默认关闭，避免没有模型密钥时误以为可用
    ai_enabled: bool = False
    ai_max_upload_bytes: int = 200 * 1024**2
    ai_timeout_sec: int = 1800
    ai_min_free_mb: int = 2048
    ai_concurrency: int = 1
    # AI 素材与产物的保留天数：到期只删文件、保留记录（status=expired），见 recreations.gc。
    # 14 天：一次 200 MiB 素材 + 产物在 3 GiB 里大约能放 7 个项目，按两周缓存的节奏够用。
    ai_retention_days: int = 14
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


def _bool_env(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = env.get(name)
    if raw is None or raw == "":
        return default
    value = raw.strip().lower()
    if value in ("1", "true", "yes", "on"):
        return True
    if value in ("0", "false", "no", "off"):
        return False
    raise ConfigError(f"{name} 只能是 true/false")


def decode_credential_key(raw: str) -> bytes:
    """GETMEDIA_CRED_KEY：base64url 编码的 32 字节密钥；空值返回 b""（未配置）。"""
    if not raw:
        return b""
    import base64
    import binascii

    try:
        key = base64.urlsafe_b64decode(raw)
    except (ValueError, binascii.Error):
        raise ConfigError("GETMEDIA_CRED_KEY 不是合法的 base64url") from None
    if len(key) != 32:
        raise ConfigError("GETMEDIA_CRED_KEY 解码后必须是 32 字节（AES-256）")
    return key


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
        creator_cookie_dir=Path(env.get("GETMEDIA_CREATOR_COOKIE_DIR", str(data_dir / "creator-cookies"))),
        media_dir=Path(env.get("GETMEDIA_MEDIA_DIR", str(data_dir / "media"))),
        cache_dir=Path(env.get("GETMEDIA_CACHE_DIR", str(data_dir / "cache"))),
        bind=env.get("GETMEDIA_BIND", "127.0.0.1:8700"),
        credential_key=decode_credential_key(env.get("GETMEDIA_CRED_KEY", "")),
        cache_ttl_seconds=_int_env(env, "GETMEDIA_CACHE_TTL_SECONDS", 86400, 60, 30 * 86400),
        cache_quota_bytes=_int_env(env, "GETMEDIA_CACHE_QUOTA_MB", 1024, 0, 51200) * 1024**2,
        archive_quota_bytes=_int_env(env, "GETMEDIA_ARCHIVE_QUOTA_MB", 5120, 0, 51200) * 1024**2,
        creator_cache_ttl_seconds=_int_env(env, "GETMEDIA_CREATOR_CACHE_TTL_DAYS", 7, 1, 30) * 86400,
        library_max_rows=_int_env(env, "GETMEDIA_LIBRARY_MAX_ROWS", 20000, 0, 100000),
        argon_memory_kib=argon_mem,
        argon_iterations=argon_iter,
        creator_max_items=_int_env(env, "GETMEDIA_CREATOR_MAX_ITEMS", 50, 1, 50),
        creator_scan_batch=_int_env(env, "GETMEDIA_CREATOR_SCAN_BATCH", 300, 20, 1200),
        llm_api_key=env.get("GETMEDIA_LLM_API_KEY", ""),
        llm_base_url=env.get("GETMEDIA_LLM_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"),
        llm_models=tuple(m.strip() for m in env.get("GETMEDIA_LLM_MODELS", "").split(",") if m.strip()),
        llm_timeout=float(_int_env(env, "GETMEDIA_LLM_TIMEOUT", 35, 5, 300)),
        llm_max_tokens=_int_env(env, "GETMEDIA_LLM_MAX_TOKENS", 700, 64, 8192),
        llm_daily_cap=_int_env(env, "GETMEDIA_LLM_DAILY_CAP", 200, 0, 100000),
        douyin_browser=_bool_env(env, "GETMEDIA_DOUYIN_BROWSER", False),
        ai_enabled=_bool_env(env, "GETMEDIA_AI_ENABLED", False),
        ai_retention_days=_int_env(env, "GETMEDIA_AI_RETENTION_DAYS", 14, 1, 365),
        douyin_browser_headless=_bool_env(env, "GETMEDIA_DOUYIN_BROWSER_HEADLESS", True),
        douyin_browser_channel=env.get("GETMEDIA_DOUYIN_BROWSER_CHANNEL", ""),
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
        creator_cookie_dir=root / "creator-cookies",
        media_dir=root / "media",
        cache_dir=root / "cache",
        credential_key=b"0123456789abcdef0123456789abcdef",
        argon_memory_kib=64,
        argon_iterations=1,
    )
