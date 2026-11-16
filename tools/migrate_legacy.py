#!/usr/bin/env python3
"""把 GetAudio / GetVideo 的旧库迁到 GetMedia。

四个子命令，对应 plan.md §9.3：

- ``plan``     只读，输出「迁什么、多少条、多少字节、哪些会丢」的人读报告 + JSON；
- ``apply``    按表分批写入目标库，幂等，可 ``--since-id`` 续跑，迁移前自动快照；
- ``verify``   比对条数/字节/抽样 sha256，检查凭据能否解密、列出孤儿文件；
- ``rollback`` 用 apply 时的快照把目标库整体还原。

边界（与任务书一致）：

- 旧库一律 ``mode=ro`` 打开，**不写、不删、不改**；迁移阶段不删除任何源文件；
- ``apply`` 必须显式给 ``--map``（旧 user_id → 新 user_id），不给就退出，
  避免猜错人把两个账号的数据搅在一起；
- 凭据用旧密钥解出明文后用 ``GETMEDIA_CRED_KEY`` 重加密；解不开的记
  ``needs_reauth``，绝不把解不开的密文当明文写进去；
- 日志与报告只写计数、id、哈希前缀，**不写 Cookie、不写密钥**。
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sqlite3
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from hashlib import sha256
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "server"))

from getmedia.credentials import CredentialError, CredentialVault  # noqa: E402

BATCH = 500
# 本工具写入的目标 schema 版本，必须等于 server/getmedia/db.py 里 MIGRATIONS 的最大版本。
# P2 的表（v6 creator_jobs/creator_items、v7 schedules/schedule_runs、v8 agent_*、
# v9 platform_credentials/account_platform_bindings、v10 recreations）都落在这套版本里，
# 所以 P2 完成后目标库就是这个版本；表结构再变时必须同步改这里，否则 apply 会拒绝写库。
TARGET_SCHEMA = 11
STATUS_MAP = {"pending": "queued", "queued": "queued", "running": "running",
              "done": "done", "failed": "failed"}
# 旧库里明确要丢弃的测试残留（plan.md §9.4-4）
DROP_USER_IDS = {"test_u"}


# --------------------------------------------------------------------------- 基础设施

class MigrationError(RuntimeError):
    pass


def open_ro(path: Path) -> sqlite3.Connection:
    if not path.is_file():
        raise MigrationError(f"旧库不存在：{path}")
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    return conn


def open_rw(path: Path) -> sqlite3.Connection:
    if not path.is_file():
        raise MigrationError(f"目标库不存在：{path}（先用服务端初始化一次）")
    conn = sqlite3.connect(str(path))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def has_table(conn: sqlite3.Connection, name: str) -> bool:
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)
    ).fetchone()
    return row is not None


def count(conn: sqlite3.Connection, table: str) -> int:
    if not has_table(conn, table):
        return 0
    return int(conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])


def check_target_schema(conn: sqlite3.Connection) -> int:
    """确认目标库已由 GetMedia 服务端建好且版本匹配。

    ``plan`` 不检查（只读旧库）；``apply`` / ``verify`` 必须检查，否则会在
    缺表或旧版表结构上写一半再报错，排查成本远高于提前拒绝。
    """
    if not has_table(conn, "schema_version"):
        raise MigrationError(
            "目标库没有 schema_version 表：先用 getmedia 服务端启动一次完成初始化，再迁移")
    version = int(conn.execute("SELECT COALESCE(MAX(version),0) FROM schema_version").fetchone()[0])
    if version != TARGET_SCHEMA:
        raise MigrationError(
            f"目标库 schema v{version}，本工具要求 v{TARGET_SCHEMA}"
            f"（server/getmedia/db.py 里 MIGRATIONS 的最新版本）；"
            f"先把目标库升级到 v{TARGET_SCHEMA} 再迁移")
    return version


def columns(conn: sqlite3.Connection, table: str) -> set[str]:
    return {r[1] for r in conn.execute(f"PRAGMA table_info({table})")}


def rows(conn: sqlite3.Connection, table: str, *, since_id: int = 0) -> list[sqlite3.Row]:
    """按主键顺序读；旧库的 extraction_failures 是复合主键、没有 id 列，退回 rowid。"""
    if not has_table(conn, table):
        return []
    key = "id" if "id" in columns(conn, table) else "rowid"
    return list(conn.execute(f"SELECT * FROM {table} WHERE {key} > ? ORDER BY {key}", (since_id,)))


def rget(row: sqlite3.Row, key: str, default: Any = None) -> Any:
    return row[key] if key in row.keys() else default


def to_epoch(value: Any, default: int | None = None) -> int | None:
    """旧库时间列可能是 epoch、ISO 文本或空。SQLite 不保留时区，按 UTC 解释。"""
    if value is None or value == "":
        return default
    if isinstance(value, (int, float)):
        return int(value)
    text = str(value).strip()
    try:
        return int(float(text))
    except ValueError:
        pass
    try:
        dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return default
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return int(dt.timestamp())


def phone_hash(phone: str | None) -> str:
    """只用于归并两个库的同一个人，不迁移手机号本身。"""
    if not phone:
        return ""
    return sha256(str(phone).strip().encode()).hexdigest()[:12]


def parse_key(raw: str | None, source: str) -> bytes | None:
    if not raw:
        return None
    import base64

    try:
        key = base64.urlsafe_b64decode(raw)
    except Exception as exc:  # noqa: BLE001
        raise MigrationError(f"{source} 的密钥不是合法 base64：{exc}") from exc
    if len(key) != 32:
        raise MigrationError(f"{source} 的密钥应为 32 字节，实际 {len(key)}")
    return key


def load_legacy_key(explicit_file: str | None, env_name: str, data_dir: Path | None) -> bytes | None:
    """旧密钥来源：显式文件 > 环境变量 > 旧项目 data/.secret_key。"""
    if explicit_file:
        return parse_key(Path(explicit_file).read_text().strip(), explicit_file)
    if raw := os.environ.get(env_name):
        return parse_key(raw, env_name)
    if data_dir is not None:
        candidate = data_dir / ".secret_key"
        if candidate.is_file():
            return parse_key(candidate.read_text().strip(), str(candidate))
    return None


def legacy_decrypt(blob: bytes, key: bytes, aad: str) -> str:
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    if len(blob) <= 12:
        raise MigrationError("旧凭据密文过短")
    try:
        return AESGCM(key).decrypt(blob[:12], blob[12:], aad.encode()).decode()
    except InvalidTag as exc:
        raise MigrationError("旧凭据解密失败：密钥不匹配或密文被改动") from exc


@dataclass
class Source:
    kind: str                      # audio | video
    db: Path
    media_root: Path | None = None
    data_dir: Path | None = None
    key: bytes | None = None
    key_file: str | None = None
    env_name: str = ""
    label: str = ""

    def connect(self) -> sqlite3.Connection:
        return open_ro(self.db)

    def resolve_key(self) -> bytes | None:
        if self.key is None:
            self.key = load_legacy_key(self.key_file, self.env_name, self.data_dir)
        return self.key


@dataclass
class Report:
    """plan / apply / verify 共用的结果容器，最后统一 json.dump。"""

    payload: dict[str, Any] = field(default_factory=dict)
    lines: list[str] = field(default_factory=list)

    def say(self, text: str = "") -> None:
        self.lines.append(text)

    def as_text(self) -> str:
        return "\n".join(self.lines)


# --------------------------------------------------------------------------- 读取旧库

def source_users(src: Source) -> list[dict[str, Any]]:
    conn = src.connect()
    try:
        out = []
        for row in conn.execute("SELECT id, phone FROM app_users ORDER BY id"):
            uid = row["id"]
            out.append(
                {
                    "db": src.kind,
                    "user_id": uid,
                    "phone_hash": phone_hash(row["phone"]),
                    "dropped": uid in DROP_USER_IDS,
                }
            )
        return out
    finally:
        conn.close()


def suggest_groups(users: Iterable[dict[str, Any]]) -> list[dict[str, Any]]:
    """按手机号哈希归并：同一个人的两个旧 id 会落进同一组。"""
    groups: dict[str, dict[str, Any]] = {}
    for user in users:
        if user["dropped"] or not user["phone_hash"]:
            continue
        group = groups.setdefault(user["phone_hash"], {"phone_hash": user["phone_hash"], "members": []})
        group["members"].append({"db": user["db"], "user_id": user["user_id"]})
    return sorted(groups.values(), key=lambda g: g["phone_hash"])


def track_stats(src: Source) -> dict[str, Any]:
    conn = src.connect()
    try:
        stats: dict[str, Any] = {
            "db": src.kind,
            "tracks": 0,
            "bytes": 0,
            "deleted": 0,
            "orphans": [],
            "no_file_ref": 0,
        }
        if not has_table(conn, "tracks"):
            return stats
        for row in conn.execute("SELECT * FROM tracks ORDER BY id"):
            stats["tracks"] += 1
            stats["bytes"] += int(rget(row, "audio_bytes") or 0)
            if int(rget(row, "deleted") or 0):
                stats["deleted"] += 1
            rel = (rget(row, "audio_path") or "").strip()
            if not rel:
                stats["no_file_ref"] += 1
            elif src.media_root is not None:
                if not (src.media_root / rel).is_file():
                    stats["orphans"].append(rel)
        return stats
    finally:
        conn.close()


def credential_stats(src: Source, target_key: bytes | None) -> dict[str, Any]:
    conn = src.connect()
    try:
        out = {"db": src.kind, "total": 0, "active": 0, "decryptable": 0,
               "undecryptable": 0, "dropped": 0, "no_key": src.resolve_key() is None}
        if not has_table(conn, "platform_credentials"):
            return out
        key = src.resolve_key()
        for row in conn.execute("SELECT * FROM platform_credentials ORDER BY id"):
            out["total"] += 1
            if row["user_id"] in DROP_USER_IDS:
                out["dropped"] += 1
                continue
            if row["status"] == "active":
                out["active"] += 1
            blob = row["cookie_blob"]
            if key is None or not blob:
                out["undecryptable"] += 1
                continue
            try:
                legacy_decrypt(bytes(blob), key, f"{row['user_id']}:{row['platform']}")
                out["decryptable"] += 1
            except (MigrationError, Exception):  # noqa: BLE001
                out["undecryptable"] += 1
        out["target_key_configured"] = target_key is not None
        return out
    finally:
        conn.close()


def unmapped_refs(conn: sqlite3.Connection, src: Source) -> dict[str, int]:
    """旧库里出现过、但 app_users 里没有的 user_id：只能显式用 "*" 兜底或跳过。"""
    known = {r[0] for r in conn.execute("SELECT id FROM app_users")} if has_table(conn, "app_users") else set()
    out: dict[str, int] = {}
    for table, col in (("extract_jobs", "user_id"), ("extraction_failures", "owner_id"),
                       ("platform_credentials", "user_id"), ("agent_conversations", "user_id")):
        if not has_table(conn, table) or col not in columns(conn, table):
            continue
        for row in conn.execute(f"SELECT {col}, COUNT(*) FROM {table} GROUP BY {col}"):
            uid, n = row[0], int(row[1])
            if uid and uid not in known:
                out[f"{table}:{uid}"] = n
    return out


def scan(src: Source, target_key: bytes | None) -> dict[str, Any]:
    conn = src.connect()
    try:
        tables = {}
        for name in ("tracks", "extract_jobs", "extraction_failures", "platform_credentials",
                     "account_platform_bindings", "agent_conversations", "agent_events",
                     "agent_memories", "crawl_state", "creator_jobs", "creator_items",
                     "recreation_projects", "recreation_runs"):
            tables[name] = count(conn, name)
        refs = unmapped_refs(conn, src)
    finally:
        conn.close()
    return {
        "kind": src.kind,
        "db": str(src.db),
        "media_root": str(src.media_root) if src.media_root else None,
        "tables": tables,
        "users": source_users(src),
        "track_stats": track_stats(src),
        "credentials": credential_stats(src, target_key),
        "unmapped_refs": refs,
    }


# --------------------------------------------------------------------------- plan

def cmd_plan(sources: list[Source], target: Path, out_json: Path | None) -> int:
    report = Report()
    target_conn = open_ro(target) if target.is_file() else None
    if target_conn is None:
        raise MigrationError(f"目标库不存在：{target}")
    try:
        target_users = [r["id"] for r in target_conn.execute("SELECT id FROM users")]
        target_tracks = count(target_conn, "tracks")
    finally:
        target_conn.close()

    scans = [scan(src, None) for src in sources]
    users = [u for s in scans for u in s["users"]]
    groups = suggest_groups(users)

    total_tracks = sum(s["track_stats"]["tracks"] for s in scans)
    total_bytes = sum(s["track_stats"]["bytes"] for s in scans)
    total_orphans = sum(len(s["track_stats"]["orphans"]) for s in scans)
    creds = [s["credentials"] for s in scans]

    report.say("=" * 68)
    report.say("GetMedia 迁移计划（只读，未写任何库）")
    report.say("=" * 68)
    for s in scans:
        report.say(f"\n[{s['kind']}] {s['db']}")
        report.say(f"  媒体根：{s['media_root'] or '(未提供)'}")
        report.say("  表：" + "，".join(f"{k}={v}" for k, v in s["tables"].items() if v) or "  表：全空")
        ts = s["track_stats"]
        report.say(f"  tracks：{ts['tracks']} 条 / {ts['bytes']} 字节"
                   f"（deleted={ts['deleted']}，无文件引用={ts['no_file_ref']}，孤儿={len(ts['orphans'])}）")
        c = s["credentials"]
        report.say(f"  凭据：{c['total']} 条（active={c['active']}，可解密={c['decryptable']}，"
                   f"不可解密={c['undecryptable']}，丢弃测试={c['dropped']}）")

    report.say("\n用户归并（按手机号哈希，不迁移手机号）：")
    for group in groups:
        members = "、".join(f"{m['db']}:{m['user_id']}" for m in group["members"])
        report.say(f"  - {group['phone_hash']} → {members}")
    dropped = [u for u in users if u["dropped"]]
    if dropped:
        report.say("  丢弃：" + "、".join(f"{u['db']}:{u['user_id']}" for u in dropped))

    for s in scans:
        refs = s.get("unmapped_refs") or {}
        if refs:
            report.say(f"\n[{s['kind']}] 对不上 app_users 的 user_id（apply 默认跳过，可用 map 里的 \"*\" 兜底）：")
            for key, n in sorted(refs.items()):
                report.say(f"  - {key}：{n} 条")

    report.say(f"\n合计：{total_tracks} 条 track / {total_bytes} 字节 / 孤儿 {total_orphans} 个")
    report.say(f"目标库现有：{len(target_users)} 个用户、{target_tracks} 条 track")

    available = sorted(set(target_users))
    report.say(f"\napply 前需要：① 目标库里有 {len(groups)} 个 owner 账号；② 一份 --map 文件，"
               f"形如 {{\"{sources[0].kind}\": {{\"<old_user_id>\": \"<new_user_id>\"}}}}")
    if target_tracks:
        report.say("  注意：目标库已有 track，apply 会按唯一键 (user_id,platform,video_id,page) 跳过重复行。")

    payload = {
        "generated_at": int(time.time()),
        "target": str(target),
        "sources": [{k: v for k, v in s.items() if k != "users"} for s in scans],
        "users": users,
        "groups": groups,
        "totals": {"tracks": total_tracks, "bytes": total_bytes, "orphans": total_orphans},
        "target_existing": {"users": target_users, "tracks": target_tracks},
        "group_count": len(groups),
        "available_target_users": available,
    }
    print(report.as_text())
    if out_json:
        out_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2, default=str))
        print(f"\nJSON 已写入 {out_json}")
    return 0


# --------------------------------------------------------------------------- apply

def load_map(path: Path, sources: list[Source]) -> dict[str, dict[str, str]]:
    data = json.loads(path.read_text())
    mapping: dict[str, dict[str, str]] = {}
    for src in sources:
        part = data.get(src.kind) or {}
        if not isinstance(part, dict):
            raise MigrationError(f"--map 里 {src.kind} 段必须是 {{old_user_id: new_user_id}}")
        mapping[src.kind] = {str(k): str(v) for k, v in part.items()}
    return mapping


def ensure_target_users(conn: sqlite3.Connection, mapping: dict[str, dict[str, str]]) -> None:
    known = {r["id"] for r in conn.execute("SELECT id FROM users")}
    missing = sorted({v for part in mapping.values() for v in part.values()} - known)
    if missing:
        raise MigrationError(
            "目标库里没有这些用户：" + "、".join(missing) +
            "（先用 `getmedia create-account <登录名> --owner` 建号，再写进 --map）"
        )


def resolve_target_id(conn: sqlite3.Connection, *, user_id: str, platform: str,
                      video_id: str, page: int = 1) -> int | None:
    row = conn.execute(
        "SELECT id FROM tracks WHERE user_id=? AND platform=? AND video_id=? AND page=?",
        (user_id, platform, video_id, page),
    ).fetchone()
    return int(row["id"]) if row else None


def copy_media(src_file: Path, dst_file: Path) -> None:
    dst_file.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src_file, dst_file)


def cmd_apply(args: argparse.Namespace, sources: list[Source]) -> int:
    target: Path = args.target
    if not args.map:
        raise MigrationError("apply 必须给 --map（旧 user_id → 新 user_id），不给就不动库")
    mapping = load_map(args.map, sources)
    vault_key = parse_key(os.environ.get("GETMEDIA_CRED_KEY"), "GETMEDIA_CRED_KEY")
    vault = CredentialVault(vault_key) if vault_key else None

    log_path = Path(args.log_dir) / f"migration-{datetime.now().strftime('%Y%m%d-%H%M%S')}.log"
    log_lines: list[str] = []

    def log(text: str) -> None:
        log_lines.append(text)

    conn = open_rw(target)
    check_target_schema(conn)
    ensure_target_users(conn, mapping)

    if args.snapshot:
        snap = Path(args.snapshot)
        snap.parent.mkdir(parents=True, exist_ok=True)
        dest = sqlite3.connect(str(snap))
        with dest:
            conn.backup(dest)
        dest.close()
        log(f"snapshot={snap}")

    stats: dict[str, Any] = {"tracks": {"inserted": 0, "duplicate": 0, "orphan": 0},
                             "extract_jobs": {"inserted": 0, "skipped": 0},
                             "extraction_failures": {"inserted": 0},
                             "credentials": {"inserted": 0, "needs_reauth": 0, "skipped": 0,
                                             "conflict": 0, "conflicts": []},
                             "bindings": 0,
                             "agent": {"conversations": 0, "messages": 0, "memories": 0},
                             "recreations": {"projects": 0, "runs": 0}}
    seq = int(conn.execute("SELECT COALESCE(MAX(updated_seq),0) FROM tracks").fetchone()[0])
    media_root = Path(args.media_root) if args.media_root else (target.parent / "media")

    for src in sources:
        if not mapping.get(src.kind):
            log(f"[{src.kind}] --map 里没有该库的映射，跳过")
            continue
        conn.execute("BEGIN")
        try:
            seq = _apply_tracks(conn, src, mapping[src.kind], stats, seq, args, media_root, log)
            _apply_jobs(conn, src, mapping[src.kind], stats, log)
            _apply_failures(conn, src, mapping[src.kind], stats, log)
            _apply_bindings(conn, src, mapping[src.kind], stats, log)
            _apply_agent(conn, src, mapping[src.kind], stats, log)
            _apply_recreations(conn, src, mapping[src.kind], stats, log)
            conn.commit()
        except Exception:
            conn.rollback()
            raise

    # 凭据必须在所有库都读完之后再统一落库：同一新账号同一平台只保留一份，
    # 逐库写入会让"先处理的那个库"默认胜出，与"保留最近更新"的规则冲突。
    conn.execute("BEGIN")
    try:
        candidates = collect_credentials(sources, mapping, vault, log)
        apply_credentials(conn, candidates, stats, vault=vault, log=log)
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    conn.close()

    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text("\n".join(log_lines) + "\n")
    out = {"applied_at": int(time.time()), "target": str(target),
           "stats": stats, "log": str(log_path),
           "snapshot": str(args.snapshot) if args.snapshot else None}
    print(json.dumps(out, ensure_ascii=False, indent=2))
    return 0


def _apply_tracks(conn, src: Source, mapping: dict[str, str], stats, seq: int,
                  args, media_root: Path, log) -> int:
    media_type = "audio" if src.kind == "audio" else "video"
    for row in rows(src.connect(), "tracks"):
        old_user = str(rget(row, "owner_scope") or "")
        old_uid = str(rget(row, "user_id") or "")
        # 旧库用 owner_scope 记归属（实测全是 legacy）；只有显式映射过才认，
        # 不允许"没映射就都算 legacy"的兜底，否则多账号数据会被并给同一个人。
        owner = old_uid or old_user
        new_user = mapping.get(owner) or mapping.get("*")
        if not new_user:
            stats["tracks"]["skipped"] = stats["tracks"].get("skipped", 0) + 1
            log(f"track {row['id']} 找不到用户映射（{owner}），跳过")
            continue
        platform = str(rget(row, "platform") or "")
        video_id = str(rget(row, "video_id") or "")
        rel = str(rget(row, "audio_path") or "").strip()
        exists = bool(rel) and src.media_root is not None and (src.media_root / rel).is_file()
        media_path = rel or None
        if args.files == "copy" and exists:
            dst = media_root / media_type / rel
            copy_media(src.media_root / rel, dst)
            media_path = f"{media_type}/{rel}"
        elif exists:
            media_path = f"{media_type}/{rel}" if args.files == "skip" else rel
        storage = "archive" if exists else "metadata"
        if not exists:
            stats["tracks"]["orphan"] += 1
        seq += 1
        cur = conn.execute(
            """INSERT OR IGNORE INTO tracks
               (user_id,media_type,platform,video_id,page,title,uploader,duration_sec,
                cover_url,source_url,storage,media_path,bytes,sha256,pinned,deleted,
                updated_seq,created_at,updated_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (new_user, media_type, platform, video_id, 1,
             str(rget(row, "title") or ""), rget(row, "uploader"),
             rget(row, "duration_sec"), rget(row, "thumbnail_url"),
             rget(row, "source_url"), storage, media_path,
             int(rget(row, "audio_bytes") or 0), rget(row, "audio_sha256"),
             0, int(rget(row, "deleted") or 0), seq,
             to_epoch(rget(row, "created_at"), 0), to_epoch(rget(row, "updated_at"), 0)),
        )
        if cur.rowcount:
            stats["tracks"]["inserted"] += 1
        else:
            stats["tracks"]["duplicate"] += 1
    return seq


def _apply_jobs(conn, src: Source, mapping: dict[str, str], stats, log) -> None:
    media_type = "audio" if src.kind == "audio" else "video"
    for row in rows(src.connect(), "extract_jobs"):
        new_user = mapping.get(str(rget(row, "user_id") or ""))
        if not new_user:
            stats["extract_jobs"]["skipped"] += 1
            continue
        old_track = rget(row, "track_id")
        new_track = None
        if old_track is not None:
            t = conn.execute(
                "SELECT id FROM tracks WHERE user_id=? AND platform=? AND video_id=? LIMIT 1",
                (new_user, str(rget(row, "platform") or ""), str(rget(row, "video_id") or "")),
            ).fetchone()
            new_track = int(t["id"]) if t else None
        # 实测旧库存的是大写枚举（DONE/FAILED），不能直接查小写表
        status = STATUS_MAP.get(str(rget(row, "status") or "").lower(), "failed")
        cur = conn.execute(
            """INSERT INTO extract_jobs
               (user_id,media_type,storage,input,platform,video_id,status,error_code,
                error_detail,track_id,created_at,started_at,finished_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (new_user, media_type, "metadata", str(rget(row, "raw_input") or ""),
             rget(row, "platform"), rget(row, "video_id"), status,
             "legacy_error" if status == "failed" else None,
             rget(row, "error"), new_track,
             to_epoch(rget(row, "created_at"), 0), None, to_epoch(rget(row, "finished_at"))),
        )
        if cur.rowcount:
            stats["extract_jobs"]["inserted"] += 1


def _dig(obj: Any, keys: tuple[str, ...]) -> Any:
    """在任意嵌套的 JSON 里找第一个命中的键。"""
    if isinstance(obj, dict):
        for key in keys:
            if obj.get(key) not in (None, ""):
                return obj[key]
        for value in obj.values():
            found = _dig(value, keys)
            if found is not None:
                return found
    elif isinstance(obj, list):
        for value in obj:
            found = _dig(value, keys)
            if found is not None:
                return found
    return None


def _apply_failures(conn, src: Source, mapping: dict[str, str], stats, log) -> None:
    for row in rows(src.connect(), "extraction_failures"):
        new_user = mapping.get(str(rget(row, "owner_id") or ""))
        if not new_user:
            continue
        try:
            logs = json.loads(rget(row, "logs_json") or "{}")
        except json.JSONDecodeError:
            logs = {}
        platform = _dig(logs, ("platform", "platformName"))
        video_id = _dig(logs, ("videoId", "video_id", "bvid", "aweme_id"))
        code = _dig(logs, ("errorCode", "error_code", "reason")) or "legacy_report"
        share = str(rget(row, "share_text") or "")
        detail = share if len(share) <= 1000 else share[:1000] + "…"
        conn.execute(
            """INSERT INTO extraction_failures
               (user_id,platform,video_id,track_id,stage,error_code,detail,client_version,created_at)
               VALUES(?,?,?,?,?,?,?,?,?)""",
            (new_user, platform, video_id, None, "legacy", str(code), detail,
             str(rget(row, "version") or ""),
             int(rget(row, "occurred_at_ms") or 0) // 1000 or to_epoch(rget(row, "received_at"), 0)),
        )
        stats["extraction_failures"]["inserted"] += 1


def mask_uid(value: str) -> str:
    """冲突清单只留后 6 位，报告可以外发。"""
    value = str(value or "")
    return f"…{value[-6:]}" if len(value) > 6 else value


def collect_credentials(sources: list[Source], mapping: dict[str, dict[str, str]],
                        vault: CredentialVault | None, log) -> list[dict[str, Any]]:
    """把各旧库的凭据读成候选（明文只在内存里，不落盘、不进日志）。

    这里只收集不写库，是因为"同一新账号同一平台只留一份"的判断必须跨库看，
    逐库写入会让"先到先得"变成事实规则。
    """
    out: list[dict[str, Any]] = []
    for src in sources:
        if not mapping.get(src.kind):
            continue
        key = src.resolve_key()
        for row in rows(src.connect(), "platform_credentials"):
            old_uid = str(rget(row, "user_id") or "")
            platform = str(rget(row, "platform") or "")
            if old_uid in DROP_USER_IDS:
                log(f"凭据 {mask_uid(old_uid)}/{platform} 是测试残留，丢弃")
                continue
            new_user = mapping[src.kind].get(old_uid)
            if not new_user:
                log(f"凭据 {mask_uid(old_uid)}/{platform} 没有映射，跳过")
                continue
            updated = to_epoch(rget(row, "updated_at"), 0) or 0
            plain = None
            blob_in = rget(row, "cookie_blob")
            if key and blob_in and len(bytes(blob_in)) > 12:
                try:
                    plain = legacy_decrypt(bytes(blob_in), key, f"{old_uid}:{platform}")
                except Exception as exc:  # noqa: BLE001
                    log(f"凭据 {mask_uid(old_uid)}/{platform} 解密失败：{type(exc).__name__}")
            out.append({
                "source": src.kind, "old_uid": old_uid, "new_user": new_user,
                "platform": platform, "updated_at": updated,
                "plain": plain, "status": str(rget(row, "status") or "active"),
                "created_at": to_epoch(rget(row, "created_at"), updated) or updated,
            })
    return out


def apply_credentials(conn, candidates: list[dict[str, Any]], stats, *,
                      vault: CredentialVault | None, log) -> None:
    """同一 (新账号, 平台) 只保留一份凭据。

    规则（与 `docs/credential-merge.md` 一致）：
    R1 候选先按 ``updated_at`` 倒序，**第一份能解密的**胜出；
    R2 全部解不开时保留最新一份，``status=needs_reauth``、密文置空；
    R3 落选者记进冲突清单并标"需重新授权"，不写第二行（表上有 UNIQUE）。
    同一时间戳按旧 user_id 倒序决胜，结果与库的处理顺序无关。
    """
    if vault is None:
        if candidates:
            log("未配置 GETMEDIA_CRED_KEY，凭据段整体跳过")
            stats["credentials"]["skipped"] += len(candidates)
        return
    groups: dict[tuple[str, str], list[dict[str, Any]]] = {}
    for cand in candidates:
        groups.setdefault((cand["new_user"], cand["platform"]), []).append(cand)

    for (new_user, platform), rows_ in groups.items():
        rows_.sort(key=lambda c: (c["updated_at"], c["old_uid"]), reverse=True)
        winner = next((c for c in rows_ if c["plain"] is not None), None)
        degraded = winner is None
        if degraded:
            winner = rows_[0]
        blob = vault.seal(winner["plain"]) if winner["plain"] is not None else b""
        status = winner["status"] if not degraded else "needs_reauth"
        if degraded:
            log(f"凭据 {new_user}/{platform}：{len(rows_)} 份候选全部解不开，保留最新一份并标 needs_reauth")
            stats["credentials"]["needs_reauth"] += 1

        existing = conn.execute(
            "SELECT id, updated_at FROM platform_credentials WHERE user_id=? AND platform=?",
            (new_user, platform)).fetchone()
        if existing is None:
            conn.execute(
                """INSERT INTO platform_credentials
                   (user_id,platform,cookie_blob,status,label,created_at,updated_at,last_verified_at)
                   VALUES(?,?,?,?,?,?,?,NULL)""",
                (new_user, platform, blob, status, f"legacy:{mask_uid(winner['old_uid'])}",
                 winner["created_at"], winner["updated_at"]))
            stats["credentials"]["inserted"] += 1
        elif winner["updated_at"] >= int(existing["updated_at"]) and not degraded:
            conn.execute(
                """UPDATE platform_credentials SET cookie_blob=?, status=?, label=?,
                   updated_at=?, last_verified_at=NULL WHERE id=?""",
                (blob, status, f"legacy:{mask_uid(winner['old_uid'])}",
                 winner["updated_at"], existing["id"]))
            stats["credentials"]["inserted"] += 1
            log(f"凭据 {new_user}/{platform}：旧库中更新的那份覆盖了先写入的一份")

        losers = [c for c in rows_ if c is not winner]
        for c in losers:
            stats["credentials"]["conflict"] += 1
            stats["credentials"]["conflicts"].append({
                "user": new_user, "platform": platform,
                "old_uid": mask_uid(c["old_uid"]), "source": c["source"],
                "updated_at": c["updated_at"],
                "reason": "older_than_kept" if c["plain"] is not None else "undecryptable",
                "marked": "needs_reauth",
            })
            log(f"凭据冲突 {new_user}/{platform}：{mask_uid(c['old_uid'])}（{c['source']}，"
                f"updated_at={c['updated_at']}）未保留，已标需重新授权")


def _apply_bindings(conn, src: Source, mapping: dict[str, str], stats, log) -> None:
    """旧表的列比模型少（没有 status），按实际列读，缺的用默认值。"""
    conn_src = src.connect()
    try:
        if not has_table(conn_src, "account_platform_bindings"):
            return
        for row in rows(conn_src, "account_platform_bindings"):
            new_user = mapping.get(str(rget(row, "user_id") or ""))
            if not new_user:
                continue
            cur = conn.execute(
                "INSERT OR IGNORE INTO account_platform_bindings"
                "(user_id,platform,bound_at,source,status) VALUES(?,?,?,?,?)",
                (new_user, str(rget(row, "platform") or ""),
                 to_epoch(rget(row, "created_at"), int(time.time())),
                 "legacy", str(rget(row, "status") or "active")))
            if cur.rowcount:
                stats["bindings"] = stats.get("bindings", 0) + 1
    finally:
        conn_src.close()


def _apply_agent(conn, src: Source, mapping: dict[str, str], stats, log) -> None:
    conn_src = src.connect()
    try:
        conv_map: dict[str, str] = {}
        for row in rows(conn_src, "agent_conversations"):
            new_user = mapping.get(str(rget(row, "user_id") or ""))
            if not new_user:
                continue
            cid = str(row["id"])
            created = to_epoch(rget(row, "created_at"), 0)
            cur = conn.execute(
                "INSERT OR IGNORE INTO agent_conversations(id,user_id,title,created_at,updated_at)"
                " VALUES(?,?,?,?,?)", (cid, new_user, "旧库会话", created, created))
            if cur.rowcount:
                stats["agent"]["conversations"] += 1
            conv_map[cid] = cid
        if has_table(conn_src, "agent_events"):
            for row in conn_src.execute("SELECT * FROM agent_events ORDER BY id"):
                cid = conv_map.get(str(row["conversation_id"]))
                if not cid:
                    continue
                payload = rget(row, "payload_json") or "{}"
                try:
                    data = json.loads(payload)
                    text = _dig(data, ("text", "content")) or payload
                except json.JSONDecodeError:
                    text = payload
                cur = conn.execute(
                    "INSERT OR IGNORE INTO agent_messages(conversation_id,role,content,meta_json,created_at)"
                    " VALUES(?,?,?,?,?)",
                    (cid, str(rget(row, "role") or "user"), str(text)[:4000],
                     json.dumps({"legacy_request_id": rget(row, "request_id")}, ensure_ascii=False),
                     to_epoch(rget(row, "created_at"), 0)))
                if cur.rowcount:
                    stats["agent"]["messages"] += 1
        if has_table(conn_src, "agent_memories"):
            for row in conn_src.execute("SELECT * FROM agent_memories ORDER BY id"):
                new_user = mapping.get(str(rget(row, "user_id") or ""))
                if not new_user or int(rget(row, "deleted") or 0):
                    continue
                cur = conn.execute(
                    "INSERT OR IGNORE INTO agent_memories(user_id,key,value,created_at,updated_at)"
                    " VALUES(?,?,?,?,?)",
                    (new_user, f"legacy:{row['id']}", str(rget(row, "content") or ""),
                     to_epoch(rget(row, "created_at"), 0), to_epoch(rget(row, "updated_at"), 0)))
                if cur.rowcount:
                    stats["agent"]["memories"] += 1
    finally:
        conn_src.close()


def _apply_recreations(conn, src: Source, mapping: dict[str, str], stats, log) -> None:
    if src.kind != "video":
        return
    conn_src = src.connect()
    try:
        if has_table(conn_src, "recreation_projects"):
            for row in conn_src.execute("SELECT * FROM recreation_projects ORDER BY rowid"):
                new_user = mapping.get(str(rget(row, "owner_id") or ""))
                if not new_user:
                    continue
                cur = conn.execute(
                    """INSERT INTO recreations(user_id,title,source_track_id,source_path,
                       description,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)""",
                    (new_user, str(rget(row, "title") or ""), None, rget(row, "source_path"),
                     rget(row, "guidance"), str(rget(row, "status") or "draft"),
                     to_epoch(rget(row, "created_at"), 0), to_epoch(rget(row, "created_at"), 0)))
                if cur.rowcount:
                    stats["recreations"]["projects"] += 1
        if has_table(conn_src, "recreation_runs"):
            for row in conn_src.execute("SELECT * FROM recreation_runs ORDER BY rowid"):
                new_user = mapping.get(str(rget(row, "owner_id") or ""))
                if not new_user:
                    continue
                created = to_epoch(rget(row, "created_at"), 0)
                conn.execute(
                    """INSERT INTO recreation_runs(recreation_id,kind,status,progress,output_path,
                       error_code,attempts,created_at,started_at,finished_at)
                       VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    (0, "legacy", str(rget(row, "status") or "queued"), 0,
                     rget(row, "result_path"), rget(row, "error"), 0, created, created, None))
                stats["recreations"]["runs"] += 1
    finally:
        conn_src.close()


# --------------------------------------------------------------------------- verify

def cmd_verify(args: argparse.Namespace, sources: list[Source]) -> int:
    conn = open_ro(args.target)
    check_target_schema(conn)
    report = Report()
    problems: list[str] = []
    report.say("=" * 68)
    report.say("迁移核对")
    report.say("=" * 68)

    target_tracks = count(conn, "tracks")
    report.say(f"\n目标库 tracks 总数：{target_tracks}")
    for src in sources:
        media_type = "audio" if src.kind == "audio" else "video"
        conn_src = src.connect()
        try:
            src_rows = list(conn_src.execute("SELECT * FROM tracks ORDER BY id"))
        finally:
            conn_src.close()
        matched = 0
        duplicates = 0
        bytes_src = bytes_dst = 0
        sample: list[tuple[str, str]] = []
        step = max(1, int(1 / args.sample)) if args.sample else 1
        for index, row in enumerate(src_rows):
            platform, video_id = str(rget(row, "platform") or ""), str(rget(row, "video_id") or "")
            hit = conn.execute(
                "SELECT id,bytes,sha256,media_path,media_type FROM tracks"
                " WHERE media_type=? AND platform=? AND video_id=?",
                (media_type, platform, video_id)).fetchone()
            if hit is None:
                # 两个旧库有同一条时只迁一条，另一条在这里会落到别的 media_type 上
                hit = conn.execute(
                    "SELECT id,bytes,sha256,media_path,media_type FROM tracks"
                    " WHERE platform=? AND video_id=? LIMIT 1",
                    (platform, video_id)).fetchone()
                if hit is None:
                    continue
                duplicates += 1
                matched += 1
                continue
            matched += 1
            bytes_src += int(rget(row, "audio_bytes") or 0)
            bytes_dst += int(hit["bytes"] or 0)
            if index % step == 0:
                sample.append((str(rget(row, "audio_path") or ""), str(hit["media_path"] or "")))
        report.say(f"\n[{src.kind}] 源 {len(src_rows)} 条，目标命中 {matched} 条"
                   f"（其中跨库重复 {duplicates} 条，字节 {bytes_src} → {bytes_dst}）")
        if matched != len(src_rows):
            problems.append(f"{src.kind}: {len(src_rows) - matched} 条未命中")

        checked = bad = missing = 0
        for src_rel, dst_rel in sample:
            src_file = (src.media_root / src_rel) if (src.media_root and src_rel) else None
            dst_file = (Path(args.media_root) / dst_rel) if (args.media_root and dst_rel) else None
            if src_file is None or not src_file.is_file():
                missing += 1
                continue
            if dst_file is None or not dst_file.is_file():
                missing += 1
                continue
            checked += 1
            if sha256(src_file.read_bytes()).hexdigest() != sha256(dst_file.read_bytes()).hexdigest():
                bad += 1
                problems.append(f"{src.kind}: sha256 不一致 {dst_rel}")
        if sample:
            report.say(f"  抽样：{checked} 个比对通过，{bad} 个不一致，{missing} 个文件缺失")

        c = conn.execute(
            "SELECT status, COUNT(*) FROM platform_credentials GROUP BY status").fetchall()
        if c:
            report.say("  目标库凭据状态（全表）：" + "，".join(f"{r[0]}={r[1]}" for r in c))
    conn.close()

    if problems:
        report.say("\n发现问题：")
        for item in problems[:40]:
            report.say(f"  - {item}")
        if len(problems) > 40:
            report.say(f"  …共 {len(problems)} 条")
    else:
        report.say("\n未发现条数/字节/抽样哈希问题。")
    print(report.as_text())
    return 1 if problems else 0


# --------------------------------------------------------------------------- rollback

def cmd_rollback(args: argparse.Namespace) -> int:
    snap = Path(args.snapshot)
    target = Path(args.target)
    if not snap.is_file():
        raise MigrationError(f"快照不存在：{snap}")
    if not target.is_file():
        raise MigrationError(f"目标库不存在：{target}")
    if not args.yes:
        raise MigrationError("回滚会覆盖目标库，确认后加 --yes")
    safety = target.with_suffix(target.suffix + f".before-rollback-{int(time.time())}")
    shutil.copy2(target, safety)
    src = sqlite3.connect(str(snap))
    dst = sqlite3.connect(str(target))
    with dst:
        src.backup(dst)
    src.close()
    dst.close()
    print(json.dumps({"restored_from": str(snap), "previous_copy": str(safety)}, ensure_ascii=False))
    return 0


# --------------------------------------------------------------------------- CLI

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="GetAudio/GetVideo → GetMedia 迁移工具")
    sub = p.add_subparsers(dest="cmd", required=True)

    def add_sources(sp):
        sp.add_argument("--audio-db", type=Path, help="旧 GetAudio 的 getaudio.db")
        sp.add_argument("--audio-media", type=Path, help="旧 GetAudio 的 audio 目录")
        sp.add_argument("--audio-key-file", help="旧 GetAudio 密钥文件（默认读 GETAUDIO_SECRET_KEY）")
        sp.add_argument("--video-db", type=Path, help="旧 GetVideo 的 getvideo.db")
        sp.add_argument("--video-media", type=Path, help="旧 GetVideo 的 video 目录")
        sp.add_argument("--video-key-file", help="旧 GetVideo 密钥文件（默认读 GETVIDEO_SECRET_KEY）")

    plan = sub.add_parser("plan", help="只读盘点，输出报告")
    add_sources(plan)
    plan.add_argument("--target", type=Path, required=True, help="目标 getmedia.db")
    plan.add_argument("--json", type=Path, dest="out_json", help="报告 JSON 落盘路径")

    apply_p = sub.add_parser("apply", help="按映射写入目标库")
    add_sources(apply_p)
    apply_p.add_argument("--target", type=Path, required=True)
    apply_p.add_argument("--map", type=Path, help="旧 user_id → 新 user_id 的 JSON（apply 必填）")
    apply_p.add_argument("--media-root", type=Path, help="目标媒体根，默认 <目标库目录>/media")
    apply_p.add_argument("--files", choices=["copy", "skip"], default="copy",
                         help="copy=把文件复制到新根（源文件保留）；skip=只写库")
    apply_p.add_argument("--snapshot", type=Path, help="迁移前把目标库备份到这里")
    apply_p.add_argument("--log-dir", default=".", help="日志目录")

    verify = sub.add_parser("verify", help="核对条数、字节与抽样哈希")
    add_sources(verify)
    verify.add_argument("--target", type=Path, required=True)
    verify.add_argument("--media-root", type=Path, help="目标媒体根，用于抽样比对")
    verify.add_argument("--sample", type=float, default=0.05, help="抽样比例，0 表示不抽样")

    roll = sub.add_parser("rollback", help="用快照还原目标库")
    roll.add_argument("--target", type=Path, required=True)
    roll.add_argument("--snapshot", type=Path, required=True)
    roll.add_argument("--yes", action="store_true")
    return p


def collect_sources(args: argparse.Namespace) -> list[Source]:
    sources: list[Source] = []
    if args.audio_db:
        sources.append(Source(
            kind="audio", db=args.audio_db, media_root=args.audio_media,
            data_dir=args.audio_db.parent, key_file=args.audio_key_file,
            env_name="GETAUDIO_SECRET_KEY"))
    if args.video_db:
        sources.append(Source(
            kind="video", db=args.video_db, media_root=args.video_media,
            data_dir=args.video_db.parent, key_file=args.video_key_file,
            env_name="GETVIDEO_SECRET_KEY"))
    if not sources:
        raise MigrationError("至少给一个旧库（--audio-db / --video-db）")
    return sources


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.cmd == "rollback":
            return cmd_rollback(args)
        sources = collect_sources(args)
        if args.cmd == "plan":
            return cmd_plan(sources, args.target, args.out_json)
        if args.cmd == "apply":
            return cmd_apply(args, sources)
        if args.cmd == "verify":
            return cmd_verify(args, sources)
    except MigrationError as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
