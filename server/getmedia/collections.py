"""播放列表（跨设备同步用）：按名称对齐，条目按平台三元组对齐。

为什么不用 track id：
- 条目可以在"还没被这台设备拉到曲库"的情况下存在（网页端提取的新条目，手机还没同步下来）；
- 存档类条目的 id 在重建曲库后会变，id 对齐会把整张列表指到错的行上。
``(platform, video_id, page)`` 与曲库判重用的是同一套键，所以两边永远能对上。

删除只写墓碑（``deleted=1``）并清空成员：别的设备靠墓碑知道"这个列表被删了"，
条目本身与文件一个都不动（隐私说明 v3 的承诺）。
"""
from __future__ import annotations

from sqlalchemy import text
from sqlalchemy.engine import Engine

from .media import MediaError

NAME_MAX = 100
MAX_PLAYLISTS = 200
MAX_ITEMS = 2000
PLATFORMS = ("bilibili", "douyin", "local")


def clean_name(value: str) -> str:
    name = " ".join(str(value or "").split())
    if not name:
        raise MediaError("bad_name", "播放列表名称不能为空", 400)
    return name[:NAME_MAX]


def _clean_items(items: list[dict]) -> list[dict]:
    """去重保序，逐条校验平台与页码。重复的最后一次出现为准（位置也跟最后一次）。"""
    seen: dict[tuple[str, str, int], int] = {}
    out: list[dict] = []
    for raw in items:
        platform = str(raw.get("platform") or "").strip().lower()
        video_id = str(raw.get("video_id") or "").strip()
        try:
            page = int(raw.get("page") or 1)
        except (TypeError, ValueError):
            raise MediaError("bad_item", "条目页码不合法", 400) from None
        if platform not in PLATFORMS:
            raise MediaError("bad_item", "条目平台不支持", 400)
        if not video_id or len(video_id) > 128 or page < 1 or page > 10000:
            raise MediaError("bad_item", "条目标识不合法", 400)
        key = (platform, video_id, page)
        seen[key] = len(out)
        out.append({"platform": platform, "video_id": video_id, "page": page})
    if len(out) > MAX_ITEMS:
        raise MediaError("playlist_full", f"单个播放列表最多 {MAX_ITEMS} 条", 400)
    # 同一键出现多次时，保留最后一次的位置，去掉前面的重复项
    last = {}
    for i, item in enumerate(out):
        last[(item["platform"], item["video_id"], item["page"])] = i
    if len(last) != len(out):
        out = [item for i, item in enumerate(out)
               if last[(item["platform"], item["video_id"], item["page"])] == i]
    return out


class CollectionStore:
    def __init__(self, engine: Engine, clock):
        self.engine, self.clock = engine, clock

    def now(self) -> int:
        return int(self.clock())

    @staticmethod
    def _next_seq(c, user_id: str) -> int:
        return int(c.execute(
            text("SELECT COALESCE(MAX(updated_seq),0)+1 FROM playlists WHERE user_id=:u"),
            {"u": user_id},
        ).scalar_one())

    def list_all(self, user_id: str) -> list[dict]:
        """全量列表（含墓碑）。列表数量很小，客户端每次全量拉，不需要增量水位。"""
        with self.engine.begin() as c:
            lists = c.execute(text(
                "SELECT id, name, updated_seq, deleted, created_at, updated_at "
                "FROM playlists WHERE user_id=:u ORDER BY name"), {"u": user_id}).all()
            if not lists:
                return []
            members = c.execute(text(
                "SELECT playlist_id, platform, video_id, page, order_index, added_at "
                "FROM playlist_items WHERE user_id=:u ORDER BY playlist_id, order_index, id"),
                {"u": user_id}).all()
        by_list: dict[int, list[dict]] = {}
        for m in members:
            row = m._mapping
            by_list.setdefault(row["playlist_id"], []).append({
                "platform": row["platform"], "video_id": row["video_id"],
                "page": row["page"], "order_index": row["order_index"],
                "added_at": row["added_at"],
            })
        return [{
            "name": r._mapping["name"],
            "deleted": bool(r._mapping["deleted"]),
            "updated_seq": r._mapping["updated_seq"],
            "created_at": r._mapping["created_at"],
            "updated_at": r._mapping["updated_at"],
            "items": [] if r._mapping["deleted"] else by_list.get(r._mapping["id"], []),
        } for r in lists]

    def replace(self, user_id: str, name: str, items: list[dict]) -> dict:
        """整体替换一个列表的成员与顺序（名称不存在则创建，墓碑则复活）。"""
        name = clean_name(name)
        items = _clean_items(items)
        now = self.now()
        with self.engine.begin() as c:
            row = c.execute(text(
                "SELECT id, deleted FROM playlists WHERE user_id=:u AND name=:n"),
                {"u": user_id, "n": name}).first()
            if row is None:
                total = int(c.execute(text(
                    "SELECT COUNT(*) FROM playlists WHERE user_id=:u AND deleted=0"),
                    {"u": user_id}).scalar_one())
                if total >= MAX_PLAYLISTS:
                    raise MediaError("playlists_full", f"最多 {MAX_PLAYLISTS} 个播放列表", 400)
                seq = self._next_seq(c, user_id)
                playlist_id = c.execute(text(
                    "INSERT INTO playlists(user_id, name, updated_seq, deleted, created_at, updated_at) "
                    "VALUES(:u,:n,:s,0,:t,:t)"),
                    {"u": user_id, "n": name, "s": seq, "t": now}).lastrowid
            else:
                playlist_id = row._mapping["id"]
                seq = self._next_seq(c, user_id)
                c.execute(text(
                    "UPDATE playlists SET deleted=0, updated_seq=:s, updated_at=:t "
                    "WHERE id=:i AND user_id=:u"),
                    {"i": playlist_id, "u": user_id, "s": seq, "t": now})
                c.execute(text("DELETE FROM playlist_items WHERE playlist_id=:i"),
                          {"i": playlist_id})
            if items:
                c.execute(text(
                    "INSERT INTO playlist_items(user_id, playlist_id, platform, video_id, page, "
                    "order_index, added_at) VALUES(:u,:p,:pl,:v,:pg,:o,:t)"),
                    [{"u": user_id, "p": playlist_id, "pl": it["platform"], "v": it["video_id"],
                      "pg": it["page"], "o": i, "t": now} for i, it in enumerate(items)])
        return {"name": name, "deleted": False, "updated_seq": seq,
                "created_at": now, "updated_at": now, "items": items}

    def remove(self, user_id: str, name: str) -> dict:
        """删列表：写墓碑并清空成员。重复删除幂等返回，不报 404。"""
        name = clean_name(name)
        now = self.now()
        with self.engine.begin() as c:
            row = c.execute(text(
                "SELECT id FROM playlists WHERE user_id=:u AND name=:n"),
                {"u": user_id, "n": name}).first()
            if row is None:
                return {"name": name, "deleted": True, "updated_seq": 0}
            playlist_id = row._mapping["id"]
            seq = self._next_seq(c, user_id)
            c.execute(text(
                "UPDATE playlists SET deleted=1, updated_seq=:s, updated_at=:t "
                "WHERE id=:i AND user_id=:u"),
                {"i": playlist_id, "u": user_id, "s": seq, "t": now})
            c.execute(text("DELETE FROM playlist_items WHERE playlist_id=:i"),
                      {"i": playlist_id})
        return {"name": name, "deleted": True, "updated_seq": seq}
