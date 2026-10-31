import sqlite3

import pytest

from getmedia import db
from getmedia.config import ConfigError, load_settings
from getmedia.names import login_name_problem, normalize_login_name
from getmedia.security import PasswordHasher, new_recovery_code, normalize_recovery_code, recovery_hmac


def test_migrate_idempotent_and_wal(tmp_path):
    p = tmp_path / "a.db"
    e = db.make_engine(p)
    latest = db.MIGRATIONS[-1][0]
    assert db.migrate(e, p, tmp_path / "snap", 1000) == latest
    assert db.migrate(e, p, tmp_path / "snap", 1001) == latest
    assert not (tmp_path / "snap").exists()  # 全新库、无待执行迁移：不做快照
    with e.begin() as c:
        assert c.exec_driver_sql("PRAGMA journal_mode").scalar() == "wal"
        assert c.exec_driver_sql("PRAGMA foreign_keys").scalar() == 1


def test_pending_migration_snapshots_first(tmp_path, monkeypatch):
    p = tmp_path / "a.db"
    e = db.make_engine(p)
    db.migrate(e, p, tmp_path / "snap", 1000)
    base = db.MIGRATIONS[-1][0]
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS + [(base + 1, ["CREATE TABLE IF NOT EXISTS t2(x INTEGER)"])])
    assert db.migrate(e, p, tmp_path / "snap", 2000) == base + 1
    snaps = list((tmp_path / "snap").glob(f"pre-migrate-v{base}-*.db"))
    assert len(snaps) == 1
    assert oct(snaps[0].stat().st_mode & 0o777) == "0o600" and oct((tmp_path / "snap").stat().st_mode & 0o777) == "0o700"
    conn = sqlite3.connect(snaps[0])
    assert conn.execute("SELECT name FROM sqlite_master WHERE name='t2'").fetchone() is None  # 快照是迁移之前的状态
    conn.close()


def test_failed_migration_aborts_and_rolls_back(tmp_path, monkeypatch):
    p = tmp_path / "a.db"
    e = db.make_engine(p)
    db.migrate(e, p, tmp_path / "snap", 1000)
    base = db.MIGRATIONS[-1][0]
    monkeypatch.setattr(db, "MIGRATIONS", db.MIGRATIONS + [(base + 1, ["CREATE TABLE t3(x INTEGER)", "THIS IS NOT SQL"])])
    with pytest.raises(db.MigrationError) as ei:
        db.migrate(e, p, tmp_path / "snap", 2000)
    assert "THIS IS NOT SQL" not in str(ei.value)
    with e.begin() as c:
        assert c.exec_driver_sql("SELECT MAX(version) FROM schema_version").scalar() == base
        assert c.exec_driver_sql("SELECT name FROM sqlite_master WHERE name='t3'").first() is None


def test_password_hash_format_and_params_in_string():
    h = PasswordHasher(64, 1)
    s = h.hash("pw-12345678")
    assert s.startswith("$argon2id$v=19$m=64,t=1,p=1$")
    assert h.verify(s, "pw-12345678") and not h.verify(s, "other")
    assert not h.verify("garbage", "x")
    h2 = PasswordHasher(128, 2)  # 参数变更后旧哈希仍可校验
    assert h2.verify(s, "pw-12345678")
    assert s != h.hash("pw-12345678")  # 盐随机


def test_production_argon_params_work():
    h = PasswordHasher(65536, 3)
    assert h.verify(h.hash("a-long-enough-pw"), "a-long-enough-pw")


def test_recovery_code_properties():
    c = new_recovery_code()
    assert len(normalize_recovery_code(c)) == 16 and c.count("-") == 3
    assert recovery_hmac(b"k" * 32, c) == recovery_hmac(b"k" * 32, c.lower().replace("-", " "))
    assert recovery_hmac(b"k" * 32, c) != recovery_hmac(b"j" * 32, c)


def test_names():
    assert normalize_login_name(" ＡＢＣ ") == "abc"
    assert login_name_problem("abc") is None and login_name_problem("a_b-c9") is None
    assert login_name_problem("1234567") and login_name_problem("12345") is None


def test_config_requires_secret(tmp_path):
    with pytest.raises(ConfigError):
        load_settings({})
    s = load_settings({"GETMEDIA_SECRET_KEY": "x" * 40, "GETMEDIA_DATA_DIR": str(tmp_path)})
    assert s.database_path == tmp_path / "getmedia.db" and s.bind == "127.0.0.1:8700"
    with pytest.raises(ConfigError):
        load_settings({"GETMEDIA_SECRET_KEY": "x" * 40, "GETMEDIA_DATABASE_URL": "postgres://x"})
