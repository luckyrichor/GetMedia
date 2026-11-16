"""迁移工具的演练：用构造出来的旧库跑 plan → apply → verify → rollback。

覆盖的是演练库里能造假的部分：
- 两个库、同一个人的不同 user_id（按手机号哈希归并，不迁移手机号）；
- 跨库同 (platform, video_id) 的重复条；已删除标记；缺文件的孤儿；
- 凭据重加密（旧 key + 旧 AAD → 新 vault）、解不开的降级 needs_reauth；
- 测试残留 user_id='test_u' 必须丢弃；
- apply 幂等、rollback 还原。
"""
from __future__ import annotations

import base64
import importlib.util
import json
import os
import sqlite3
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).parents[2]
spec = importlib.util.spec_from_file_location(
    "migrate_legacy", ROOT / "tools" / "migrate_legacy.py")
mig = importlib.util.module_from_spec(spec)
sys.modules["migrate_legacy"] = mig
spec.loader.exec_module(mig)

sys.path.insert(0, str(ROOT / "server"))
from getmedia.credentials import CredentialVault  # noqa: E402
from getmedia.db import make_engine, migrate as migrate_schema  # noqa: E402

OLD_KEY = b"k" * 32
NEW_KEY = b"n" * 32


def _seal_old(user_id: str, platform: str, header: str) -> bytes:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM

    nonce = b"1" * 12
    body = AESGCM(OLD_KEY).encrypt(nonce, header.encode(), f"{user_id}:{platform}".encode())
    return nonce + body


def build_legacy(root: Path, kind: str, *, users, tracks, creds=(), jobs=(), failures=(),
                 agent=None, bindings=()) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    db = root / f"{kind}.db"
    c = sqlite3.connect(db)
    c.executescript(
        """
        CREATE TABLE app_users(id TEXT PRIMARY KEY, phone TEXT, password_hash TEXT, created_at TEXT);
        CREATE TABLE tracks(id INTEGER PRIMARY KEY, owner_scope TEXT, platform TEXT, video_id TEXT,
            source_url TEXT, title TEXT, uploader TEXT, description TEXT, thumbnail_url TEXT,
            upload_date TEXT, duration_sec REAL, audio_path TEXT, audio_format TEXT,
            audio_bytes INTEGER, audio_sha256 TEXT, categories_json TEXT, has_vocal INTEGER,
            classify_stage TEXT, updated_seq INTEGER, deleted INTEGER, created_at TEXT, updated_at TEXT);
        CREATE TABLE extract_jobs(id INTEGER PRIMARY KEY, user_id TEXT, raw_input TEXT, platform TEXT,
            video_id TEXT, resolved_url TEXT, status TEXT, error TEXT, track_id INTEGER,
            created_at TEXT, finished_at TEXT);
        CREATE TABLE platform_credentials(id INTEGER PRIMARY KEY, user_id TEXT, platform TEXT,
            cookie_blob BLOB, status TEXT, last_checked_at TEXT, created_at TEXT, updated_at TEXT);
        CREATE TABLE extraction_failures(owner_id TEXT, report_id TEXT, version TEXT,
            occurred_at_ms INTEGER, received_at TEXT, share_text TEXT, logs_json TEXT);
        CREATE TABLE agent_conversations(id TEXT PRIMARY KEY, user_id TEXT, created_at TEXT);
        CREATE TABLE agent_events(id INTEGER PRIMARY KEY, conversation_id TEXT, request_id TEXT,
            role TEXT, payload_json TEXT, created_at TEXT);
        CREATE TABLE agent_memories(id INTEGER PRIMARY KEY, user_id TEXT, content TEXT,
            revision INTEGER, deleted INTEGER, created_at TEXT, updated_at TEXT);
        CREATE TABLE account_platform_bindings(id INTEGER PRIMARY KEY, user_id TEXT, platform TEXT,
            subject TEXT, nickname TEXT, created_at TEXT);
        """
    )
    c.executemany("INSERT INTO app_users VALUES(?,?,?,?)",
                  [(u[0], u[1], "x", "2026-10-06 22:36:00.000000") for u in users])
    for t in tracks:
        c.execute(
            """INSERT INTO tracks(owner_scope,platform,video_id,source_url,title,uploader,
               thumbnail_url,duration_sec,audio_path,audio_format,audio_bytes,audio_sha256,
               updated_seq,deleted,created_at,updated_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (t["owner"], t["platform"], t["video_id"], f"https://example.invalid/{t['video_id']}",
             t.get("title", "标题"), "某 UP", "https://example.invalid/c.jpg",
             t.get("duration", 12.0), t["path"], "m4a" if kind == "audio" else "mp4",
             t.get("bytes", 1024), t.get("sha256"), 1, t.get("deleted", 0),
             "2026-10-06 22:36:00.000000", "2026-10-07 01:00:00.000000"))
    for u, p, header, status in creds:
        c.execute("INSERT INTO platform_credentials(user_id,platform,cookie_blob,status,"
                  "created_at,updated_at) VALUES(?,?,?,?,?,?)",
                  (u, p, _seal_old(u, p, header) if header else b"", status,
                   "2026-10-06 22:36:00.000000", "2026-10-07 01:00:00.000000"))
    for b in bindings:
        c.execute("INSERT INTO account_platform_bindings(user_id,platform,subject,nickname,created_at)"
                  " VALUES(?,?,?,?,?)", (b[0], b[1], "sub", "昵称", "2026-10-06 22:36:00.000000"))
    for j in jobs:
        c.execute("INSERT INTO extract_jobs(user_id,raw_input,platform,video_id,status,error,"
                  "track_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
                  (j["user"], j["input"], "bilibili", j.get("video_id"), j["status"],
                   j.get("error"), j.get("track_id"), "2026-10-06 22:36:00.000000"))
    for f in failures:
        c.execute("INSERT INTO extraction_failures VALUES(?,?,?,?,?,?,?)",
                  (f["owner"], f["report_id"], "0.9.10", f["ms"], "2026-10-07 01:00:00.000000",
                   f["share"], json.dumps({"platform": "bilibili", "videoId": f.get("video_id")})))
    if agent:
        c.execute("INSERT INTO agent_conversations VALUES(?,?,?)",
                  (agent["id"], agent["user"], "2026-10-06 22:36:00.000000"))
        c.execute("INSERT INTO agent_events(conversation_id,request_id,role,payload_json,created_at)"
                  " VALUES(?,?,?,?,?)",
                  (agent["id"], "r1", "user", json.dumps({"text": "找点古风"}),
                   "2026-10-06 22:37:00.000000"))
        c.execute("INSERT INTO agent_memories(user_id,content,revision,deleted,created_at,updated_at)"
                  " VALUES(?,?,?,?,?,?)",
                  (agent["user"], "喜欢笛子", 1, 0, "2026-10-06 22:38:00.000000",
                   "2026-10-06 22:38:00.000000"))
    c.commit()
    c.close()
    (root / ".secret_key").write_text(base64.urlsafe_b64encode(OLD_KEY).decode())
    return db


def make_target(root: Path, user_ids: list[str]) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    db = root / "getmedia.db"
    engine = make_engine(db)
    migrate_schema(engine, db, root / "snapshots")
    c = sqlite3.connect(db)
    for i, uid in enumerate(user_ids):
        c.execute("INSERT INTO users(id,login_name,login_name_norm,password_hash,tier,"
                  "registered_channel,created_at) VALUES(?,?,?,?,?,?,?)",
                  (uid, f"boss{i}", f"boss{i}", "x", "owner", "cli", 1_800_000_000))
    c.commit()
    c.close()
    return db


@pytest.fixture
def env(tmp_path):
    audio_media = tmp_path / "audio-media"
    video_media = tmp_path / "video-media"
    (audio_media / "bilibili").mkdir(parents=True)
    (video_media / "bilibili").mkdir(parents=True)
    (audio_media / "bilibili" / "a.m4a").write_bytes(b"a" * 2048)
    (video_media / "bilibili" / "v.mp4").write_bytes(b"v" * 4096)

    audio_db = build_legacy(
        tmp_path / "getaudio", "audio",
        users=[("acct_audio_1", "13800000001"), ("test_u", "13800000009")],
        tracks=[
            {"owner": "legacy", "platform": "bilibili", "video_id": "BV1", "path": "bilibili/a.m4a",
             "bytes": 2048, "sha256": "a" * 64, "title": "古风一首"},
            {"owner": "legacy", "platform": "bilibili", "video_id": "BV2", "path": "bilibili/lost.m4a",
             "bytes": 999, "title": "文件丢了"},
            {"owner": "legacy", "platform": "bilibili", "video_id": "BV3", "path": "bilibili/a.m4a",
             "bytes": 2048, "deleted": 1, "title": "已删除的"},
        ],
        creds=[("acct_audio_1", "bilibili", "SESSDATA=abc", "active"),
               ("test_u", "bilibili", "SESSDATA=test", "active")],
        jobs=[{"user": "acct_audio_1", "input": "BV1", "video_id": "BV1", "status": "DONE"},
              {"user": "acct_audio_1", "input": "BV9", "video_id": "BV9", "status": "FAILED",
               "error": "风控"},
              {"user": "uuid-orphan", "input": "BV8", "video_id": "BV8", "status": "DONE"}],
        failures=[{"owner": "acct_audio_1", "report_id": "r1", "ms": 1_760_000_000_000,
                   "share": "分享文案", "video_id": "BV2"}],
        agent={"id": "conv-1", "user": "acct_audio_1"},
    )
    video_db = build_legacy(
        tmp_path / "getvideo", "video",
        users=[("acct_video_9", "13800000001")],
        tracks=[
            # 与 audio 库同 video_id：应被唯一键挡下，记 duplicate
            {"owner": "legacy", "platform": "bilibili", "video_id": "BV1", "path": "bilibili/v.mp4",
             "bytes": 4096, "title": "同一首的视频版"},
            {"owner": "legacy", "platform": "bilibili", "video_id": "BV7", "path": "bilibili/v.mp4",
             "bytes": 4096, "title": "视频独有"},
        ],
        creds=[("acct_video_9", "bilibili", "SESSDATA=xyz", "active"),
               ("acct_video_9", "douyin", "", "needs_reauth")],
        bindings=[("acct_video_9", "bilibili")],
    )
    target = make_target(tmp_path / "target", ["u_owner"])
    return {
        "root": tmp_path, "audio_db": audio_db, "video_db": video_db,
        "audio_media": audio_media, "video_media": video_media, "target": target,
    }


def run(capsys, *argv):
    code = mig.main([str(a) for a in argv])
    return code, capsys.readouterr().out


def base_args(env):
    return ["--audio-db", env["audio_db"], "--audio-media", env["audio_media"],
            "--video-db", env["video_db"], "--video-media", env["video_media"],
            "--target", env["target"]]


def test_plan_counts_and_grouping(env, capsys):
    out_json = env["root"] / "plan.json"
    code, out = run(capsys, "plan", *base_args(env), "--json", out_json)
    assert code == 0
    assert "5 条 track" in out or "5 条 track" in out
    report = json.loads(out_json.read_text())
    # 两个库同一个手机号 → 归并成一组；test_u 被丢弃
    assert len(report["groups"]) == 1
    members = {(m["db"], m["user_id"]) for m in report["groups"][0]["members"]}
    assert members == {("audio", "acct_audio_1"), ("video", "acct_video_9")}
    assert any(u["dropped"] for u in report["users"])
    # 孤儿文件被识别
    audio = next(s for s in report["sources"] if s["kind"] == "audio")
    assert any("uuid-orphan" in k for k in audio["unmapped_refs"])
    assert audio["track_stats"]["orphans"] == ["bilibili/lost.m4a"]
    # 凭据：两条可解密，一条测试残留
    assert audio["credentials"]["decryptable"] == 1
    assert audio["credentials"]["dropped"] == 1


def test_apply_then_verify(env, capsys, monkeypatch):
    monkeypatch.setenv("GETMEDIA_CRED_KEY", base64.urlsafe_b64encode(NEW_KEY).decode())
    mapping = env["root"] / "map.json"
    mapping.write_text(json.dumps({
        "audio": {"legacy": "u_owner", "acct_audio_1": "u_owner"},
        "video": {"legacy": "u_owner", "acct_video_9": "u_owner"},
    }))
    snapshot = env["root"] / "before.db"
    media = env["root"] / "target-media"
    code, out = run(capsys, "apply", *base_args(env), "--map", mapping,
                    "--media-root", media, "--snapshot", snapshot,
                    "--log-dir", env["root"])
    assert code == 0, out
    stats = json.loads(out)["stats"]
    assert stats["tracks"]["inserted"] == 4          # BV1 跨库重复 → 只进一条
    assert stats["tracks"]["duplicate"] == 1
    assert stats["tracks"]["orphan"] == 1
    assert stats["extract_jobs"]["inserted"] == 2     # 另一个是 app_users 里没有的 uuid，跳过
    assert stats["extract_jobs"]["skipped"] == 1
    assert stats["bindings"] == 1
    assert stats["extraction_failures"]["inserted"] == 1
    assert stats["credentials"]["inserted"] == 2     # 测试残留被丢弃
    assert stats["credentials"]["needs_reauth"] == 1
    # 两个库的 bilibili 凭据 updated_at 相同（夹具都是同一个时间戳），
    # 按"时间戳相同则旧 user_id 倒序"决胜 → acct_video_9 胜出，另一份进冲突清单。
    assert stats["credentials"]["conflict"] == 1
    assert snapshot.is_file()

    c = sqlite3.connect(env["target"])
    v = c.execute("SELECT status, cookie_blob FROM platform_credentials WHERE platform='bilibili'"
                  ).fetchall()
    assert len(v) == 1 and v[0][0] == "active"
    assert CredentialVault(NEW_KEY).open(bytes(v[0][1])) == "SESSDATA=xyz"
    dy = c.execute("SELECT status, length(cookie_blob) FROM platform_credentials WHERE platform='douyin'"
                   ).fetchone()
    assert dy[0] == "needs_reauth" and dy[1] == 0
    # deleted 标记保留，media_path 指向新根下的副本
    row = c.execute("SELECT deleted, media_path, storage FROM tracks WHERE video_id='BV3'").fetchone()
    assert row[0] == 1
    paths = [r[0] for r in c.execute("SELECT media_path FROM tracks WHERE storage='archive'")]
    assert all((media / p).is_file() for p in paths)
    c.close()

    code, out = run(capsys, "verify", *base_args(env), "--media-root", media)
    assert code == 0, out
    assert "未发现条数" in out


def test_apply_is_idempotent(env, capsys, monkeypatch):
    monkeypatch.setenv("GETMEDIA_CRED_KEY", base64.urlsafe_b64encode(NEW_KEY).decode())
    mapping = env["root"] / "map.json"
    mapping.write_text(json.dumps({
        "audio": {"legacy": "u_owner", "acct_audio_1": "u_owner"},
        "video": {"legacy": "u_owner", "acct_video_9": "u_owner"},
    }))
    args = ["apply", *base_args(env), "--map", mapping, "--media-root", env["root"] / "m",
            "--log-dir", env["root"]]
    first = json.loads(run(capsys, *args)[1])["stats"]
    second = json.loads(run(capsys, *args)[1])["stats"]
    assert first["tracks"]["inserted"] == 4
    assert second["tracks"]["inserted"] == 0
    assert second["tracks"]["duplicate"] >= 1
    c = sqlite3.connect(env["target"])
    assert c.execute("SELECT COUNT(*) FROM tracks").fetchone()[0] == 4
    assert c.execute("SELECT COUNT(*) FROM platform_credentials").fetchone()[0] == 2
    c.close()


def test_apply_without_map_fails(env, capsys):
    code = mig.main([str(a) for a in
                     ["apply", *base_args(env), "--media-root", env["root"] / "m"]])
    assert code == 2
    assert "--map" in capsys.readouterr().err


def test_apply_refuses_unknown_target_user(env, capsys):
    mapping = env["root"] / "bad.json"
    mapping.write_text(json.dumps({"audio": {"legacy": "u_missing"},
                                   "video": {"legacy": "u_missing"}}))
    code, _ = run(capsys, "apply", *base_args(env), "--map", mapping,
                  "--media-root", env["root"] / "m", "--log-dir", env["root"])
    assert code == 2


def test_rollback_restores(env, capsys, monkeypatch):
    monkeypatch.setenv("GETMEDIA_CRED_KEY", base64.urlsafe_b64encode(NEW_KEY).decode())
    mapping = env["root"] / "map.json"
    mapping.write_text(json.dumps({
        "audio": {"legacy": "u_owner", "acct_audio_1": "u_owner"},
        "video": {"legacy": "u_owner", "acct_video_9": "u_owner"},
    }))
    snapshot = env["root"] / "before.db"
    run(capsys, "apply", *base_args(env), "--map", mapping, "--media-root", env["root"] / "m",
        "--snapshot", snapshot, "--log-dir", env["root"])
    c = sqlite3.connect(env["target"])
    assert c.execute("SELECT COUNT(*) FROM tracks").fetchone()[0] == 4
    c.close()

    code, _ = run(capsys, "rollback", "--target", env["target"], "--snapshot", snapshot)
    assert code == 2                      # 没给 --yes 拒绝执行
    code, out = run(capsys, "rollback", "--target", env["target"], "--snapshot", snapshot, "--yes")
    assert code == 0, out
    c = sqlite3.connect(env["target"])
    assert c.execute("SELECT COUNT(*) FROM tracks").fetchone()[0] == 0
    c.close()


def test_snapshot_only_when_asked(env, capsys, monkeypatch):
    """没有 --snapshot 时不留备份文件；日志写到 --log-dir 且不含 Cookie 明文。"""
    monkeypatch.setenv("GETMEDIA_CRED_KEY", base64.urlsafe_b64encode(NEW_KEY).decode())
    mapping = env["root"] / "map.json"
    mapping.write_text(json.dumps({"audio": {"legacy": "u_owner", "acct_audio_1": "u_owner"},
                                   "video": {"legacy": "u_owner", "acct_video_9": "u_owner"}}))
    logs = env["root"] / "logs"
    run(capsys, "apply", *base_args(env), "--map", mapping, "--media-root", env["root"] / "m",
        "--log-dir", logs)
    files = list(logs.glob("migration-*.log"))
    assert len(files) == 1
    text = files[0].read_text()
    assert "SESSDATA" not in text


def test_apply_rejects_wrong_target_schema(env, capsys):
    """目标库必须是当前 schema（v11）；旧版本库要提前拒绝，不能写一半再报错。"""
    # 直接在夹具目标库上退版本（每个用例独立 tmp_path）；注意库是 WAL，
    # 不能用 shutil.copy2 复制主文件——未 checkpoint 的内容在 -wal 里。
    old = env["target"]
    c = sqlite3.connect(old)
    c.execute("DELETE FROM schema_version WHERE version=11")
    c.commit()
    c.close()

    mapping = env["root"] / "map.json"
    mapping.write_text(json.dumps({"audio": {"legacy": "u_owner", "acct_audio_1": "u_owner"}}))
    code = mig.main([str(a) for a in [
        "apply", "--audio-db", env["audio_db"], "--audio-media", env["audio_media"],
        "--video-db", env["video_db"], "--video-media", env["video_media"],
        "--target", str(old), "--map", str(mapping), "--log-dir", str(env["root"])]])
    assert code == 2
    err = capsys.readouterr().err
    assert "schema v10" in err and "v11" in err

    # verify 同样要拦
    assert mig.main([str(a) for a in [
        "verify", "--audio-db", env["audio_db"], "--audio-media", env["audio_media"],
        "--video-db", env["video_db"], "--video-media", env["video_media"],
        "--target", str(old)]]) == 2
