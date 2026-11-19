"""落库的固定窗口限流：重启不丢。调用方必须在独立短事务里调用，避免被拒绝时回滚计数。

窗口锚在**绝对时间网格**上（`now - now % size`），不是"第一次请求 + size"。
所以：

- 被拒绝的请求**只加计数、不会推迟窗口**。窗口在 `window_start + size` 那一刻照常换新，
  不会因为用户多试了几次而往后顺延（否则连续重试等于把自己永久关在门外）。
- 代价是窗口边界与首次请求的时刻无关：t0 落在网格中间时，配额会在网格边界提前重置。
  客户端本地按 `now - last_at >= window` 拦一道，正常路径不会踩到这个边界。
"""
from __future__ import annotations

from sqlalchemy import text
from sqlalchemy.engine import Connection


def _window(now: int, size: int) -> int:
    return now - now % size


def count(conn: Connection, key: str, window_s: int, now: int) -> int:
    row = conn.execute(text("SELECT count FROM rate_limits WHERE key=:k AND window_start=:w"),
                       {"k": key, "w": _window(now, window_s)}).first()
    return row[0] if row else 0


def hit(conn: Connection, key: str, limit: int, window_s: int, now: int) -> tuple[bool, int]:
    """计一次；返回 (是否允许, 建议等待秒数)。超限的这次也计入。"""
    w = _window(now, window_s)
    n = conn.execute(text(
        "INSERT INTO rate_limits(key, window_start, count) VALUES(:k,:w,1) "
        "ON CONFLICT(key, window_start) DO UPDATE SET count=count+1 RETURNING count"),
        {"k": key, "w": w}).scalar_one()
    return n <= limit, max(1, w + window_s - now)


def purge(conn: Connection, now: int, keep_s: int = 30 * 86400) -> int:
    return conn.execute(text("DELETE FROM rate_limits WHERE window_start < :t"), {"t": now - keep_s}).rowcount
