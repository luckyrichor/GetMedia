"""旧账号合并后的凭据冲突规则（R1–R3）。

规则来自用户 2026-11-16 的决定：4 个旧账号都是本人的，都迁成 owner；
同一新账号同一平台只能留一份凭据 → 默认保留"更新时间最近"的那份，
其余标为需重新授权。表上有 UNIQUE(user_id, platform)，所以"其余"不能落第二行，
只能进冲突清单 + 打 needs_reauth 标记。
"""
from __future__ import annotations

import base64
import importlib.util
import sqlite3
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[2]
spec = importlib.util.spec_from_file_location(
    "migrate_legacy_merge", ROOT / "tools" / "migrate_legacy.py")
mig = importlib.util.module_from_spec(spec)
sys.modules["migrate_legacy_merge"] = mig
spec.loader.exec_module(mig)

sys.path.insert(0, str(ROOT / "server"))
from getmedia.credentials import CredentialVault  # noqa: E402
from getmedia.db import make_engine, migrate as migrate_schema  # noqa: E402

OLD_KEY = b"o" * 32
NEW_KEY = b"n" * 32


def seal_old(user_id: str, platform: str, text: str | None) -> bytes:
    """text=None 表示"密文在，但解不开"（模拟旧密钥不对）。"""
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    body_text = text if text is not None else "corrupted"
    nonce = b"2" * 12
    body = AESGCM(OLD_KEY).encrypt(nonce, body_text.encode(), f"{user_id}:{platform}".encode())
    if text is None:
        body = bytes([body[0] ^ 0xFF]) + body[1:]
    return nonce + body


def src_of(kind: str, db: Path) -> "mig.Source":
    return mig.Source(kind=kind, db=db, data_dir=db.parent)


def build_source(root: Path, kind: str, rows: list[tuple]) -> Path:
    """rows: (user_id, platform, cookie_text|None, status, updated_at_text)"""
    root.mkdir(parents=True, exist_ok=True)
    db = root / f"{kind}.db"
    c = sqlite3.connect(db)
    c.executescript(
        """CREATE TABLE platform_credentials(id INTEGER PRIMARY KEY, user_id TEXT, platform TEXT,
           cookie_blob BLOB, status TEXT, last_checked_at TEXT, created_at TEXT, updated_at TEXT);"""
    )
    for user_id, platform, text, status, updated in rows:
        blob = seal_old(user_id, platform, text) if text != "" else b""
        c.execute("INSERT INTO platform_credentials(user_id,platform,cookie_blob,status,"
                  "created_at,updated_at) VALUES(?,?,?,?,?,?)",
                  (user_id, platform, blob, status, "2026-10-01 00:00:00.000000", updated))
    c.commit()
    c.close()
    (root / ".secret_key").write_text(base64.urlsafe_b64encode(OLD_KEY).decode())
    return db


@pytest.fixture()
def target(tmp_path):
    db = tmp_path / "target.db"
    engine = make_engine(db)
    migrate_schema(engine, db, tmp_path / "snap", 0)
    conn = sqlite3.connect(db)
    conn.row_factory = sqlite3.Row
    for uid, name in (("owner1", "owner1"), ("owner2", "owner2")):
        conn.execute("INSERT INTO users(id,login_name,login_name_norm,password_hash,created_at)"
                     " VALUES(?,?,?,?,?)", (uid, name, name, "x", 0))
    conn.commit()
    yield conn
    conn.close()


def run(conn, sources, mapping):
    stats = {"credentials": {"inserted": 0, "needs_reauth": 0, "skipped": 0,
                             "conflict": 0, "conflicts": []}}
    lines: list[str] = []
    cands = mig.collect_credentials(sources, mapping, CredentialVault(NEW_KEY), lines.append)
    mig.apply_credentials(conn, cands, stats, vault=CredentialVault(NEW_KEY), log=lines.append)
    return stats, lines


def rows_of(conn):
    return [dict(r) for r in conn.execute(
        "SELECT user_id,platform,status,cookie_blob,label,updated_at FROM platform_credentials"
        " ORDER BY user_id,platform")]


def test_newest_wins_and_loser_marked(tmp_path, target):
    """同人同平台两份：保留 updated_at 较新的，旧的进冲突清单并标 needs_reauth。"""
    src = src_of("audio", build_source(
        tmp_path / "a", "audio",
        [("u_old_a", "bilibili", "SESSDATA=old", "active", "2026-10-20 10:00:00.000000"),
         ("u_old_b", "bilibili", "SESSDATA=new", "active", "2026-11-01 10:00:00.000000")]))
    stats, _ = run(target, [src], {"audio": {"u_old_a": "owner1", "u_old_b": "owner1"}})

    rows = rows_of(target)
    assert len(rows) == 1, "UNIQUE(user_id, platform) 只允许一行"
    assert rows[0]["status"] == "active"
    assert rows[0]["label"].endswith("old_b") and rows[0]["label"].startswith("legacy:…")
    assert CredentialVault(NEW_KEY).open(rows[0]["cookie_blob"]) == "SESSDATA=new"
    assert stats["credentials"]["inserted"] == 1
    assert stats["credentials"]["conflict"] == 1
    loser = stats["credentials"]["conflicts"][0]
    assert loser["old_uid"].endswith("old_a") and loser["marked"] == "needs_reauth"
    assert loser["reason"] == "older_than_kept"


def test_undecryptable_newest_falls_back_to_working(tmp_path, target):
    """最新的解不开时，不能让坏密文占位：退到次新可解密的一份，坏的那份记 undecryptable。"""
    src = src_of("audio", build_source(
        tmp_path / "a", "audio",
        [("u_ok", "bilibili", "SESSDATA=ok", "active", "2026-10-10 10:00:00.000000"),
         ("u_bad", "bilibili", None, "active", "2026-11-10 10:00:00.000000")]))
    stats, _ = run(target, [src], {"audio": {"u_ok": "owner1", "u_bad": "owner1"}})

    rows = rows_of(target)
    assert len(rows) == 1
    assert rows[0]["status"] == "active"
    assert CredentialVault(NEW_KEY).open(rows[0]["cookie_blob"]) == "SESSDATA=ok"
    assert stats["credentials"]["conflicts"][0]["reason"] == "undecryptable"


def test_all_undecryptable_keeps_newest_as_needs_reauth(tmp_path, target):
    """全部解不开：保留最新一份，密文空、状态 needs_reauth。"""
    src = src_of("audio", build_source(
        tmp_path / "a", "audio",
        [("u_1", "douyin", None, "active", "2026-10-01 10:00:00.000000"),
         ("u_2", "douyin", None, "active", "2026-10-20 10:00:00.000000")]))
    stats, lines = run(target, [src], {"audio": {"u_1": "owner1", "u_2": "owner1"}})

    rows = rows_of(target)
    assert len(rows) == 1
    assert rows[0]["status"] == "needs_reauth"
    assert rows[0]["cookie_blob"] == b""
    assert stats["credentials"]["needs_reauth"] == 1
    assert any("needs_reauth" in line for line in lines)


def test_different_platform_or_user_no_conflict(tmp_path, target):
    """平台不同、或映射到不同 owner，都不算冲突。"""
    src = src_of("audio", build_source(
        tmp_path / "a", "audio",
        [("u_1", "bilibili", "B=1", "active", "2026-10-01 10:00:00.000000"),
         ("u_1", "douyin", "D=1", "active", "2026-10-02 10:00:00.000000"),
         ("u_2", "bilibili", "B=2", "active", "2026-10-03 10:00:00.000000")]))
    stats, _ = run(target, [src], {"audio": {"u_1": "owner1", "u_2": "owner2"}})

    rows = rows_of(target)
    assert len(rows) == 3
    assert stats["credentials"]["conflict"] == 0
    assert {(r["user_id"], r["platform"]) for r in rows} == {
        ("owner1", "bilibili"), ("owner1", "douyin"), ("owner2", "bilibili")}


def test_cross_library_conflict_is_order_independent(tmp_path, target):
    """audio 库较新、video 库较旧：先处理哪个库，结果都得是 audio 那份。"""
    a = src_of("audio", build_source(
        tmp_path / "a", "audio", [("u_a", "bilibili", "A", "active", "2026-11-05 00:00:00.000000")]))
    v = src_of("video", build_source(
        tmp_path / "v", "video", [("u_v", "bilibili", "V", "active", "2026-10-05 00:00:00.000000")]))
    mapping = {"audio": {"u_a": "owner1"}, "video": {"u_v": "owner1"}}

    run(target, [v, a], mapping)          # 故意让旧的先处理
    rows = rows_of(target)
    assert len(rows) == 1
    assert CredentialVault(NEW_KEY).open(rows[0]["cookie_blob"]) == "A"


def test_conflict_report_is_masked_and_has_no_secret(tmp_path, target):
    """冲突清单可外发：旧 user_id 只留后 6 位，任何日志都不得出现明文 Cookie。"""
    uid = "acct_0123456789abcdef0123456789abcdef"   # 合成 id，不是任何真实账号
    src = src_of("audio", build_source(
        tmp_path / "a", "audio",
        [(uid, "bilibili", "SESSDATA=SECRET-VALUE", "active", "2026-10-01 10:00:00.000000"),
         (uid + "b", "bilibili", "SESSDATA=SECRET-2", "active", "2026-10-09 10:00:00.000000")]))
    stats, lines = run(target, [src], {"audio": {uid: "owner1", uid + "b": "owner1"}})

    text = repr(stats["credentials"]["conflicts"]) + "\n".join(lines)
    assert uid not in text
    assert "SECRET-VALUE" not in text and "SECRET-2" not in text
    assert stats["credentials"]["conflicts"][0]["old_uid"].startswith("…")


def test_apply_is_idempotent_for_same_candidates(tmp_path, target):
    """同一批候选跑两遍：仍只有一行，不重复计数插入。"""
    src = src_of("audio", build_source(
        tmp_path / "a", "audio",
        [("u_1", "bilibili", "X", "active", "2026-10-01 10:00:00.000000")]))
    mapping = {"audio": {"u_1": "owner1"}}
    run(target, [src], mapping)
    stats2, _ = run(target, [src], mapping)
    assert len(rows_of(target)) == 1
    assert stats2["credentials"]["conflict"] == 0
