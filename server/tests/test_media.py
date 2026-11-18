"""P2a：媒体库 / 提取任务 / 媒体流 / 配额。"""
from __future__ import annotations

import shutil
from pathlib import Path
from urllib.parse import quote

import pytest
from fastapi.testclient import TestClient

from conftest import Clock, hdr, make_owner  # noqa: F401  (Clock 仅用于类型参考)
from getmedia.alerts import RecordingAlertSink
from getmedia.api import create_app
from getmedia.config import testing_settings as make_settings
from getmedia.extractor import FakeResolver

ROOT = Path(__file__).resolve().parents[2]
PAYLOAD = FakeResolver.payload


def build(tmp_path, *, resolver=None, **settings_kw):
    tmp_path.mkdir(parents=True, exist_ok=True)
    legal = tmp_path / "legal"
    legal.mkdir()
    for f in ("user-agreement.md", "privacy-policy.md"):
        shutil.copy(ROOT / "docs" / "legal" / f, legal / f)
    settings = make_settings(tmp_path, legal).with_(**settings_kw)

    class C:
        t = 1_800_000_000.0

        def __call__(self):
            return self.t

        def advance(self, s):
            self.t += s

    clock = C()
    app = create_app(settings, clock=clock, alerts=RecordingAlertSink(),
                     media_resolver=resolver or FakeResolver())
    client = TestClient(app, base_url="https://testserver")

    class E:
        pass

    e = E()
    e.settings, e.clock, e.alerts, e.app, e.client, e.tmp = settings, clock, RecordingAlertSink(), app, client, tmp_path
    e.resolver = resolver or app.state.extractor
    e.svc = app.state.svc
    return e


@pytest.fixture
def menv(tmp_path):
    env = build(tmp_path)
    env.app.state.runtime.write(registration_open=True)
    env.token = make_owner(env, name="boss")
    return env


def extract(env, *, storage="metadata", media_type="video", text="https://www.bilibili.com/video/BVtest0001"):
    return env.client.post("/v2/extract", headers=hdr(env.token),
                           json={"input": text, "media_type": media_type, "storage": storage})


def test_extract_metadata_only(menv):
    r = extract(menv)
    assert r.status_code == 202
    job = menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(menv.token)).json()
    assert job["status"] == "done", job
    track = menv.client.get(f"/v2/tracks/{job['track_id']}", headers=hdr(menv.token)).json()
    assert track["storage"] == "metadata"
    assert track["has_file"] is False
    assert track["platform"] == "bilibili" and track["video_id"] == "BVtest0001"
    assert track["duration_sec"] == 12.5


def test_extract_archive_writes_file_and_streams_range(menv):
    r = extract(menv, storage="archive")
    job = menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(menv.token)).json()
    assert job["status"] == "done", job
    tid = job["track_id"]
    track = menv.client.get(f"/v2/tracks/{tid}", headers=hdr(menv.token)).json()
    assert track["storage"] == "archive" and track["has_file"] is True
    assert track["bytes"] == len(PAYLOAD)

    files = list((menv.tmp / "media").iterdir())
    assert len(files) == 1
    assert files[0].read_bytes() == PAYLOAD

    full = menv.client.get(f"/v2/media/{tid}", headers=hdr(menv.token))
    assert full.status_code == 200 and full.content == PAYLOAD
    assert full.headers["accept-ranges"] == "bytes"

    part = menv.client.get(f"/v2/media/{tid}", headers={**hdr(menv.token), "Range": "bytes=4-11"})
    assert part.status_code == 206
    assert part.content == PAYLOAD[4:12]
    assert part.headers["content-range"] == f"bytes 4-11/{len(PAYLOAD)}"

    bad = menv.client.get(f"/v2/media/{tid}", headers={**hdr(menv.token), "Range": "bytes=999-1000"})
    assert bad.status_code == 416
    assert bad.headers["content-range"] == f"bytes */{len(PAYLOAD)}"


def test_cache_expiry_and_prune(menv):
    r = extract(menv, storage="cache")
    tid = menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(menv.token)).json()["track_id"]
    track = menv.client.get(f"/v2/tracks/{tid}", headers=hdr(menv.token)).json()
    assert track["storage"] == "cache" and track["cache_expires_at"] > menv.clock()
    assert (menv.tmp / "cache").is_dir()

    menv.clock.advance(menv.settings.cache_ttl_seconds + 1)
    out = menv.client.post("/v2/maintenance/prune-cache", headers=hdr(menv.token)).json()
    assert out["rows"] == 1
    assert list((menv.tmp / "cache").iterdir()) == []
    after = menv.client.get(f"/v2/tracks/{tid}", headers=hdr(menv.token)).json()
    assert after["storage"] == "metadata" and after["has_file"] is False


def test_archive_quota_rejects(menv, tmp_path):
    env = build(tmp_path / "small", archive_quota_bytes=32)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss2")
    r = env.client.post("/v2/extract", headers=hdr(token),
                        json={"input": "https://www.bilibili.com/video/BVtest0001", "storage": "archive"})
    job = env.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(token)).json()
    assert job["status"] == "failed"
    assert job["error_code"] == "quota_exceeded"
    assert not (env.tmp / "media").exists() or list((env.tmp / "media").iterdir()) == []


def test_resolve_failure_marks_job_failed(tmp_path):
    env = build(tmp_path, resolver=FakeResolver(fail=True))
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env, name="boss3")
    r = env.client.post("/v2/extract", headers=hdr(token), json={"input": "BVtest0001"})
    job = env.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(token)).json()
    assert job["status"] == "failed" and job["error_code"] == "resolve_failed"


def test_sync_incremental_and_tombstone(menv):
    a = extract(menv).json()
    b = extract(menv).json()
    j1 = menv.client.get(f"/v2/extraction-jobs/{a['id']}", headers=hdr(menv.token)).json()
    j2 = menv.client.get(f"/v2/extraction-jobs/{b['id']}", headers=hdr(menv.token)).json()
    # 同一作品二次提取是 upsert，不会多一条
    assert j1["track_id"] == j2["track_id"]

    extract(menv, text="BVtest0002")
    first = menv.client.get("/v2/sync?since_seq=0", headers=hdr(menv.token)).json()
    assert len(first["items"]) == 2
    assert first["next_seq"] >= 2

    tid = first["items"][0]["id"]
    menv.client.delete(f"/v2/tracks/{tid}", headers=hdr(menv.token))
    delta = menv.client.get(f"/v2/sync?since_seq={first['next_seq']}", headers=hdr(menv.token)).json()
    assert [i["id"] for i in delta["items"]] == [tid]
    assert delta["items"][0]["deleted"] is True

    restored = menv.client.post(f"/v2/tracks/{tid}/restore", headers=hdr(menv.token)).json()
    assert restored["deleted"] is False


def test_patch_filters_and_delete_file(menv):
    r = extract(menv, storage="archive", media_type="audio")
    tid = menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(menv.token)).json()["track_id"]

    p = menv.client.patch(f"/v2/tracks/{tid}", headers=hdr(menv.token),
                          json={"title": "新标题", "pinned": True})
    assert p.status_code == 200 and p.json()["title"] == "新标题" and p.json()["pinned"] is True

    audio = menv.client.get("/v2/tracks?media_type=audio", headers=hdr(menv.token)).json()
    video = menv.client.get("/v2/tracks?media_type=video", headers=hdr(menv.token)).json()
    assert [i["id"] for i in audio["items"]] == [tid]
    assert video["items"] == []

    dropped = menv.client.delete(f"/v2/tracks/{tid}/file", headers=hdr(menv.token)).json()
    assert dropped["has_file"] is False and dropped["storage"] == "metadata"
    assert list((menv.tmp / "media").iterdir()) == []


def test_stream_without_file_falls_back_to_resolve(menv):
    r = extract(menv, storage="metadata")
    tid = menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(menv.token)).json()["track_id"]
    got = menv.client.get(f"/v2/media/{tid}", headers=hdr(menv.token))
    assert got.status_code == 200
    assert got.content == PAYLOAD


def test_other_user_cannot_read_track(tmp_path):
    env = build(tmp_path)
    env.app.state.runtime.write(registration_open=True)
    token_a = make_owner(env, name="owner_a")
    token_b = make_owner(env, name="owner_b")
    r = env.client.post("/v2/extract", headers=hdr(token_a), json={"input": "BVtest0001"})
    tid = env.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(token_a)).json()["track_id"]
    assert env.client.get(f"/v2/tracks/{tid}", headers=hdr(token_b)).status_code == 404
    assert env.client.get(f"/v2/media/{tid}", headers=hdr(token_b)).status_code == 404


def test_storage_report(menv):
    extract(menv, storage="archive")
    body = menv.client.get("/v2/maintenance/storage", headers=hdr(menv.token)).json()
    assert body["by_storage"]["archive"]["tracks"] == 1
    assert body["used"]["archive"] == len(PAYLOAD)
    assert body["quota"]["archive"] == menv.settings.archive_quota_bytes


def test_all_media_routes_are_owner_only(menv):
    for method, path in [("GET", "/v2/tracks"), ("GET", "/v2/sync"), ("GET", "/v2/media/1"),
                         ("POST", "/v2/resolve"), ("POST", "/v2/extract"),
                         ("GET", "/v2/maintenance/storage")]:
        assert menv.client.request(method, path, json={} if method == "POST" else None).status_code == 401


# ---------- 下载到本机（?download=1） ----------

def _track_id(menv, storage: str) -> int:
    r = extract(menv, storage=storage)
    return menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(menv.token)).json()["track_id"]


def test_download_archived_file_is_attachment(menv):
    tid = _track_id(menv, "archive")
    got = menv.client.get(f"/v2/media/{tid}?download=1", headers=hdr(menv.token))
    assert got.status_code == 200 and got.content == PAYLOAD
    cd = got.headers["content-disposition"]
    assert cd.startswith("attachment;")
    assert f'filename="media-{tid}.mp4"' in cd
    # 中文标题走 RFC 5987 编码，不会在头里出现裸中文
    assert "filename*=UTF-8''" in cd
    assert quote("示例作品", safe="") in cd

    # 拖动进度条也要是附件，否则浏览器会当成内联播放
    part = menv.client.get(f"/v2/media/{tid}?download=1",
                           headers={**hdr(menv.token), "Range": "bytes=4-11"})
    assert part.status_code == 206 and part.content == PAYLOAD[4:12]
    assert part.headers["content-disposition"].startswith("attachment;")


def test_download_metadata_only_still_streams_with_attachment(menv):
    """没有落盘文件也能下：当场解析 + 中转，同样按附件下发。"""
    tid = _track_id(menv, "metadata")
    got = menv.client.get(f"/v2/media/{tid}?download=1", headers=hdr(menv.token))
    assert got.status_code == 200 and got.content == PAYLOAD
    assert got.headers["content-disposition"].startswith("attachment;")


def test_plain_playback_stays_inline(menv):
    """不加参数时不能带 attachment，否则播放器会变成"下载"而不是播放。"""
    tid = _track_id(menv, "archive")
    got = menv.client.get(f"/v2/media/{tid}", headers=hdr(menv.token))
    assert got.status_code == 200
    assert "content-disposition" not in got.headers


def test_download_filename_sanitizes_hostile_title(menv):
    """标题里的换行/引号/斜杠不能漏进响应头（否则是头注入）。"""
    tid = _track_id(menv, "archive")
    evil = 'bad\r\nX-Evil: 1/../有"引号"?'
    r = menv.client.patch(f"/v2/tracks/{tid}", headers=hdr(menv.token), json={"title": evil})
    assert r.status_code == 200
    got = menv.client.get(f"/v2/media/{tid}?download=1", headers=hdr(menv.token))
    cd = got.headers["content-disposition"]
    assert "\n" not in cd and "\r" not in cd
    assert got.headers.get("x-evil") is None
    assert '"' not in cd.split("filename*=UTF-8''")[0].split("filename=")[1].strip('"; ')
    # 真正要守的是"不能带路径分隔符、不能以点开头"；文件名中间出现 .. 无害
    from urllib.parse import unquote
    name = unquote(cd.split("filename*=UTF-8''", 1)[1])
    assert "/" not in name and "\\" not in name
    assert not name.startswith(".")
