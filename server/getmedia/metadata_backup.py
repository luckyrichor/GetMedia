"""曲库清单备份（元数据快照）的存储与校验。

设计见 docs/metadata-backup-design.md（v2）。三件事必须守住：

1. **服务器不解释内容**：表里只存 gzip 原样 BLOB + 用于列表页的少量元信息
   （字节数、sha256、counts、版本、机型）。恢复时把原字节原样发回去，客户端自己解析。
2. **校验全部通过后才计限频**：格式错的请求不该白白占掉一次 10 分钟配额（§3.2）。
3. **注销即删**：提交注销时立即删（§3.5），不等冷静期。
"""
from __future__ import annotations

import hashlib
import json
import secrets
import zlib
from typing import Any

from sqlalchemy import text

from . import ratelimit
from .errors import ApiError

# ---- 上限（§3.4）----
GZIP_MAX = 512 * 1024          # 单份 gzip 后；nginx 的 client_max_body_size 是 1m，留了余量
RAW_MAX = 16 * 1024 * 1024     # 解压后，防解压炸弹
MAX_TRACKS = 8000
MAX_CATEGORIES = 500
MAX_PLAYLISTS = 200
MAX_PLAYLIST_ITEMS = 40000

# 单字段上限：客户端按 §2.1 组装时远低于这些值；超了说明组装有 bug 或有人伪造，
# 直接拒收比悄悄截断安全——截断会让恢复端拿到的数据与上传端不一致。
TITLE_MAX = 300
UPLOADER_MAX = 120
URL_MAX = 2048

KEEP = 3                        # 每账号保留最近 3 份
UPLOAD_LIMIT = (1, 600)         # 每账号每 10 分钟 1 次

_COUNT_KEYS = ("tracks", "categories", "playlists", "playlist_items")


def new_id() -> str:
    return "bkp_" + secrets.token_hex(8)


def _invalid(detail: str = "备份内容不符合格式要求") -> ApiError:
    return ApiError(422, "backup_invalid", detail)


def _gunzip(body: bytes) -> bytes:
    """按流速解压并卡住上限：超了立刻停，不会先把 16 MiB 之外的内容读进内存。"""
    obj = zlib.decompressobj(16 + zlib.MAX_WBITS)  # 16+ 表示 gzip 容器
    try:
        out = obj.decompress(body, RAW_MAX + 1)
    except zlib.error:
        raise _invalid("备份内容不是有效的 gzip") from None
    if len(out) > RAW_MAX or obj.unconsumed_tail:
        raise ApiError(413, "backup_too_large", "备份内容过大")
    if not obj.eof:
        # 没读到 gzip 的 trailer：上传在传输中被截断了。放过去的话库里会存一份
        # "看起来合法、实际少了后半段"的快照，恢复时才发现不对。
        raise _invalid("备份内容不完整（gzip 流被截断）")
    if obj.unused_data:
        # gzip 流结束后还有字节：不是我们生成的格式，拒收而不是猜。
        raise _invalid("备份内容在 gzip 流之后还有多余数据")
    return out


def _text_field(item: dict, key: str, limit: int) -> str:
    value = item.get(key)
    if value is None:
        return ""
    if not isinstance(value, str):
        raise _invalid(f"{key} 必须是字符串")
    if len(value) > limit:
        raise _invalid(f"{key} 超过 {limit} 个字符")
    return value


def _require_list(data: dict, key: str) -> list:
    value = data.get(key)
    if value is None:
        return []
    if not isinstance(value, list):
        raise _invalid(f"{key} 必须是数组")
    return value


def parse(body: bytes) -> tuple[dict[str, Any], dict[str, int], bytes]:
    """解析并逐项校验；返回 (数据, counts, 解压后字节数)。任何不合法都抛 ApiError。"""
    if not body:
        raise _invalid("备份内容为空")
    if len(body) > GZIP_MAX:
        raise ApiError(413, "backup_too_large", "备份内容过大")
    raw = _gunzip(body)
    try:
        data = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise _invalid("备份内容不是有效的 JSON") from None
    if not isinstance(data, dict):
        raise _invalid()
    if data.get("v") != 1:
        # 未知版本直接拒收，不做兼容猜测（§3.2 第 2 条）
        raise _invalid("不支持的备份版本")

    tracks = _require_list(data, "tracks")
    categories = _require_list(data, "categories")
    playlists = _require_list(data, "playlists")

    items = 0
    for track in tracks:
        if not isinstance(track, dict):
            raise _invalid("tracks 里存在非对象条目")
        # 去重键：缺了恢复时会撞 id（§3.2 第 5 条）
        if not _text_field(track, "platform", 32).strip():
            raise _invalid("track 缺少 platform")
        if not _text_field(track, "video_id", 128).strip():
            raise _invalid("track 缺少 video_id")
        _text_field(track, "title", TITLE_MAX)
        _text_field(track, "uploader", UPLOADER_MAX)
        _text_field(track, "source_url", URL_MAX)
        nested = track.get("playlists")
        if nested is not None and not isinstance(nested, list):
            raise _invalid("track.playlists 必须是数组")
        items += len(nested or [])

    if len(tracks) > MAX_TRACKS or len(categories) > MAX_CATEGORIES or len(playlists) > MAX_PLAYLISTS:
        raise ApiError(413, "backup_too_large", "备份条目过多")
    if items > MAX_PLAYLIST_ITEMS:
        raise ApiError(413, "backup_too_large", "备份条目过多")

    counts = {"tracks": len(tracks), "categories": len(categories),
              "playlists": len(playlists), "playlist_items": items}
    claimed = data.get("counts")
    if not isinstance(claimed, dict):
        raise _invalid("缺少 counts")
    for key in _COUNT_KEYS:
        value = claimed.get(key)
        if not isinstance(value, int) or isinstance(value, bool):
            raise _invalid("counts 格式不正确")
        if value != counts[key]:
            # 不一致 = 客户端组装有 bug，拒收比存一份自相矛盾的快照好
            raise _invalid("counts 与实际条目数不一致")
    return data, counts, raw


class MetadataBackupService:
    def __init__(self, engine, clock):
        self.engine = engine
        self.now = clock

    # ---------- 查询 ----------
    def list(self, user_id: str) -> dict:
        with self.engine.begin() as c:
            rows = c.execute(text(
                "SELECT id,bytes,counts_json,app_version,device,created_at "
                "FROM metadata_backups WHERE user_id=:u ORDER BY created_at DESC, rowid DESC"),
                {"u": user_id}).all()
        backups = [self._row(r) for r in rows]
        return {"backups": backups, "total_bytes": sum(b["bytes"] for b in backups), "keep": KEEP}

    def payload(self, user_id: str, backup_id: str) -> tuple[bytes, str]:
        with self.engine.begin() as c:
            row = c.execute(text(
                "SELECT payload,sha256 FROM metadata_backups WHERE id=:i AND user_id=:u"),
                {"i": backup_id, "u": user_id}).first()
        if row is None:
            # 别人的备份与不存在的备份回同一个 404，不做存在性区分
            raise ApiError(404, "backup_not_found", "备份不存在")
        return bytes(row[0]), row[1]

    # ---------- 上传 ----------
    def create(self, user_id: str, body: bytes, app_version: str = "", device: str = "") -> dict:
        _, counts, raw = parse(body)   # 先校验
        self._hit(user_id)             # 全部通过后才计限频
        backup_id, created_at = new_id(), int(self.now())
        digest = hashlib.sha256(body).hexdigest()
        with self.engine.begin() as c:
            c.execute(text(
                "INSERT INTO metadata_backups(id,user_id,bytes,raw_bytes,sha256,counts_json,"
                "app_version,device,payload,created_at) "
                "VALUES(:i,:u,:b,:r,:h,:c,:v,:d,:p,:t)"),
                {"i": backup_id, "u": user_id, "b": len(body), "r": len(raw), "h": digest,
                 "c": json.dumps(counts, sort_keys=True), "v": app_version[:32], "d": device[:64],
                 "p": body, "t": created_at})
            pruned = self._prune(c, user_id)
        return {"id": backup_id, "created_at": created_at, "pruned": pruned}

    def _prune(self, conn, user_id: str) -> list[str]:
        """按 created_at 倒序保留最近 KEEP 份，返回被删掉的 id。"""
        ids = [r[0] for r in conn.execute(text(
            "SELECT id FROM metadata_backups WHERE user_id=:u ORDER BY created_at DESC, rowid DESC"),
            {"u": user_id})]
        stale = ids[KEEP:]
        for backup_id in stale:
            conn.execute(text("DELETE FROM metadata_backups WHERE id=:i"), {"i": backup_id})
        return stale

    def _hit(self, user_id: str) -> None:
        limit, window = UPLOAD_LIMIT
        now = int(self.now())
        with self.engine.begin() as c:
            ok, retry = ratelimit.hit(c, f"meta-backup:{user_id}", limit, window, now)
        if not ok:
            raise ApiError(429, "rate_limited", "备份太频繁，请稍后再试",
                           headers={"Retry-After": str(retry)})

    # ---------- 删除 ----------
    def delete(self, user_id: str, backup_id: str) -> int:
        with self.engine.begin() as c:
            return c.execute(text("DELETE FROM metadata_backups WHERE id=:i AND user_id=:u"),
                             {"i": backup_id, "u": user_id}).rowcount

    def delete_all(self, user_id: str) -> int:
        with self.engine.begin() as c:
            return c.execute(text("DELETE FROM metadata_backups WHERE user_id=:u"),
                             {"u": user_id}).rowcount

    # ---------- 注销 ----------
    def delete_for_user(self, conn, user_id: str) -> None:
        """给注销流程用：传进来的是事务连接，和停用账号在同一个事务里。"""
        conn.execute(text("DELETE FROM metadata_backups WHERE user_id=:u"), {"u": user_id})

    @staticmethod
    def _row(row) -> dict:
        try:
            counts = json.loads(row[2])
        except (TypeError, ValueError):
            counts = {}
        return {"id": row[0], "bytes": row[1], "counts": counts,
                "app_version": row[3], "device": row[4], "created_at": row[5]}


__all__ = ["MetadataBackupService", "parse", "GZIP_MAX", "RAW_MAX", "KEEP", "UPLOAD_LIMIT"]
