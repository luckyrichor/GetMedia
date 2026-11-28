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


def extract(env, *, storage="metadata", media_type="video", text="https://www.bilibili.com/video/BVtest0001",
            formats=None):
    body = {"input": text, "media_type": media_type, "storage": storage}
    if formats is not None:
        body["formats"] = formats
    return env.client.post("/v2/extract", headers=hdr(env.token), json=body)


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


def test_extract_records_title_uploader_and_formats(menv):
    """任务要带标题/上传者/格式：列表里要能看出是谁的作品，也能按昵称筛。"""
    r = extract(menv, formats=["source"])
    job = menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(menv.token)).json()
    assert job["status"] == "done", job
    assert job["title"] == "示例作品" and job["uploader"] == "测试作者"
    assert job["formats"] == ["source"] and job["bytes"] == 0

    rows = menv.client.get("/v2/extraction-jobs", headers=hdr(menv.token)).json()["jobs"]
    assert rows[0]["title"] == "示例作品" and rows[0]["uploader"] == "测试作者"
    assert rows[0]["input"].startswith("https://www.bilibili.com/video/")


def test_extract_archive_records_bytes(menv):
    r = extract(menv, storage="archive", formats=["source"])
    job = menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(menv.token)).json()
    assert job["status"] == "done", job
    assert job["bytes"] == len(PAYLOAD)


def test_extract_rejects_format_of_other_media_type(menv):
    r = extract(menv, media_type="audio", formats=["mp4"])
    assert r.status_code == 422, r.text
    assert r.json()["code"] == "bad_formats"


def test_extract_converted_format_failure_is_reported(menv):
    """选了要转码但源文件转不出来：任务标失败、给 format_failed，且不留下半截文件。

    假字节不是真媒体，ffmpeg 必然失败；没装 ffmpeg 的机器上抛的是「没有安装 ffmpeg」，
    同样归到 format_failed —— 两种情况都不该报成功。
    """
    r = extract(menv, media_type="audio", storage="archive", formats=["mp3"])
    assert r.status_code == 202, r.text
    job = menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(menv.token)).json()
    assert job["status"] == "failed", job
    assert job["error_code"] == "format_failed"
    media = menv.tmp / "media"
    leftovers = list(media.iterdir()) if media.exists() else []
    assert leftovers == [], f"转码失败不该留下文件：{leftovers}"


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
    for method, path in [("GET", "/v2/tracks"), ("POST", "/v2/tracks"), ("GET", "/v2/sync"),
                         ("GET", "/v2/media/1"), ("POST", "/v2/resolve"), ("POST", "/v2/extract"),
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


# ---------- 手机端新建曲库条目（P0-2 阶段 1：POST /v2/tracks） ----------

def push(env, token=None, **over):
    """手机端上传"本机新增的一条"；只带元数据，没有文件、没有封面。"""
    body = {"platform": "bilibili", "video_id": "BVpush0001", "page": 1,
            "media_type": "audio", "title": "手机传上来的一条", "uploader": "某UP",
            "duration_sec": 12.5,
            "source_url": "https://www.bilibili.com/video/BVpush0001?p=1"}
    body.update(over)
    return env.client.post("/v2/tracks", headers=hdr(token or env.token), json=body)


def test_create_metadata_row_is_idempotent(menv):
    r = push(menv)
    assert r.status_code == 200, r.text
    first = r.json()
    assert first["created"] is True
    track = first["track"]
    assert track["storage"] == "metadata" and track["has_file"] is False
    assert track["title"] == "手机传上来的一条"
    assert track["page"] == 1 and track["platform"] == "bilibili"
    seq = track["updated_seq"]

    again = push(menv, title="手机上的另一个标题")
    assert again.status_code == 200
    assert again.json()["created"] is False
    assert again.json()["track"]["id"] == track["id"]
    # 已存在就一个字都不改：标题保持服务器原值，seq 也不动（否则所有设备白拉一遍）
    assert again.json()["track"]["title"] == "手机传上来的一条"
    assert again.json()["track"]["updated_seq"] == seq


def test_create_never_overwrites_web_edit(menv):
    tid = push(menv).json()["track"]["id"]
    menv.client.patch(f"/v2/tracks/{tid}", headers=hdr(menv.token), json={"title": "网页改的名字"})
    again = push(menv, title="手机上的旧标题").json()
    assert again["track"]["title"] == "网页改的名字"
    assert again["track"]["updated_seq"] > 0


def test_create_keeps_pages_apart(menv):
    p1 = push(menv, page=1).json()["track"]
    p2 = push(menv, page=2, source_url="https://www.bilibili.com/video/BVpush0001?p=2").json()["track"]
    assert p1["id"] != p2["id"] and p1["page"] == 1 and p2["page"] == 2
    listed = menv.client.get("/v2/tracks", headers=hdr(menv.token)).json()["items"]
    assert len([t for t in listed if t["video_id"] == "BVpush0001"]) == 2


def test_create_on_tombstone_is_409_with_row(menv):
    tid = push(menv).json()["track"]["id"]
    menv.client.delete(f"/v2/tracks/{tid}", headers=hdr(menv.token))
    r = push(menv)
    assert r.status_code == 409
    body = r.json()
    assert body["code"] == "track_deleted"
    assert body["track"]["id"] == tid and body["track"]["deleted"] is True
    # 不复活：服务器上仍然只有这一行，仍然是墓碑
    listed = menv.client.get("/v2/tracks", headers=hdr(menv.token)).json()["items"]
    assert [t["id"] for t in listed if t["video_id"] == "BVpush0001"] == []


def test_create_rejects_bad_input(menv):
    assert push(menv, platform="  ").status_code == 400
    assert push(menv, video_id="").status_code == 400
    assert push(menv, media_type="photo").status_code == 400
    assert push(menv, page=0).status_code == 400
    assert push(menv, title="   ").status_code == 400
    # 错误码要稳定，客户端要按 code 分支
    assert push(menv, media_type="photo").json()["code"] == "bad_media_type"


def test_create_respects_row_cap(tmp_path):
    env = build(tmp_path, library_max_rows=3)
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env)
    for i in range(3):
        assert push(env, token, video_id=f"BVcap{i:04d}").status_code == 200
    over = push(env, token, video_id="BVcap9999")
    assert over.status_code == 409
    assert over.json()["code"] == "library_full"


def test_create_is_per_account(tmp_path):
    env = build(tmp_path)
    env.app.state.runtime.write(registration_open=True)
    a = make_owner(env, name="boss")
    b = make_owner(env, name="other")
    assert push(env, a).json()["created"] is True
    # 另一个账号同键：各建各的，互不可见
    assert push(env, b).json()["created"] is True
    la = env.client.get("/v2/tracks", headers=hdr(a)).json()["items"]
    lb = env.client.get("/v2/tracks", headers=hdr(b)).json()["items"]
    assert len(la) == 1 and len(lb) == 1 and la[0]["id"] != lb[0]["id"]


def test_create_is_rate_limited_per_account(tmp_path):
    """写入配额按账号计。429 必须带 Retry-After，否则客户端没法退避。"""
    env = build(tmp_path, library_push_limit=(2, 60))
    env.app.state.runtime.write(registration_open=True)
    token = make_owner(env)
    for i in range(2):
        assert push(env, token, video_id=f"BVrate{i:04d}").status_code == 200
    r = push(env, token, video_id="BVrate9999")
    assert r.status_code == 429
    assert r.json()["code"] == "rate_limited"
    assert int(r.headers["Retry-After"]) >= 1
    # 被拒之后没有偷偷建行
    listed = env.client.get("/v2/tracks", headers=hdr(token)).json()["items"]
    assert [t["video_id"] for t in listed] == ["BVrate0000", "BVrate0001"]

P3_BV = "BV1KG411w7r2"
P3_URL = f"https://www.bilibili.com/video/{P3_BV}?p=3"


def p3_track(env, storage):
    created = extract(env, storage=storage, media_type="audio", text=f"https://example.invalid/{P3_BV}-p3")
    job = env.client.get(f"/v2/extraction-jobs/{created.json()['id']}", headers=hdr(env.token)).json()
    return job["track_id"]


def test_stored_p3_uses_original_archive_and_never_resolves(menv):
    import hashlib

    tid = p3_track(menv, "archive")
    headers = hdr(menv.token)
    original = menv.client.get(f"/v2/tracks/{tid}", headers=headers).json()
    assert (original["video_id"], original["page"], original["storage"], original["sha256"]) == (
        P3_BV + "-p3", 1, "archive", hashlib.sha256(PAYLOAD).hexdigest(),
    )
    calls = list(menv.resolver.calls)
    jobs_before = menv.client.get("/v2/extraction-jobs", headers=headers).json()
    full = menv.client.get(f"/v2/media/{tid}?stored_only=1", headers=headers)
    part = menv.client.get(f"/v2/media/{tid}?stored_only=1", headers={**headers, "Range": "bytes=3-8"})
    assert full.status_code == 200 and full.content == PAYLOAD
    assert part.status_code == 206 and part.content == PAYLOAD[3:9]
    assert full.headers["X-GetMedia-Media-Source"] == part.headers["X-GetMedia-Media-Source"] == "stored"
    assert menv.resolver.calls == calls
    assert menv.client.get("/v2/extraction-jobs", headers=headers).json() == jobs_before
    assert menv.client.get("/v2/tracks", headers=headers).json()["count"] == 1
    assert menv.client.get(f"/v2/tracks/{tid}", headers=headers).json() == original


def test_stored_only_after_cache_prune_and_targeted_refill(menv):
    from dataclasses import replace

    tid = p3_track(menv, "cache")
    headers = hdr(menv.token)
    menv.clock.advance(menv.settings.cache_ttl_seconds + 1)
    menv.app.state.media.prune_cache()
    original = menv.client.get(f"/v2/tracks/{tid}", headers=headers).json()
    assert original["storage"] == "metadata"
    calls = list(menv.resolver.calls)
    reply = menv.client.get(f"/v2/media/{tid}?stored_only=1", headers=headers)
    assert reply.status_code == 409 and reply.json()["code"] == "stored_file_missing"
    assert menv.resolver.calls == calls

    def resolve_target(text, *, media_type, page, user_id=None):
        assert text == P3_URL and page == 3 and media_type == "audio"
        return replace(menv.resolver.resolve(text, media_type=media_type),
                       video_id=P3_BV, page=3, media_type=media_type)

    menv.resolver.resolve_target = resolve_target
    r = menv.client.post("/v2/extract", headers=headers, json={
        "input": P3_URL, "media_type": "audio", "storage": "cache",
        "target": {"track_id": tid, "platform": "bilibili", "video_id": P3_BV, "page": 3},
    })
    assert r.status_code == 202, r.text
    job = menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=headers).json()
    assert job["status"] == "done" and job["track_id"] == tid
    current = menv.client.get(f"/v2/tracks/{tid}", headers=headers).json()
    assert current["storage"] == "cache" and current["bytes"] == len(PAYLOAD)
    assert (current["title"], current["video_id"], current["page"]) == (
        original["title"], original["video_id"], original["page"],
    )
    assert menv.client.get("/v2/tracks", headers=headers).json()["count"] == 1


@pytest.mark.parametrize(("video", "page", "kind"), [
    (P3_BV, 1, "audio"), ("BV1GJ411x7h7", 3, "audio"), (P3_BV, 3, "video"),
])
def test_targeted_mismatch_leaves_original_row_and_disk_untouched(menv, video, page, kind):
    from dataclasses import replace

    tid = p3_track(menv, "metadata")
    headers = hdr(menv.token)
    before = menv.client.get("/v2/sync", headers=headers).json()
    menv.resolver.resolve_target = lambda text, **kw: replace(
        menv.resolver.resolve(text, media_type=kw["media_type"]),
        video_id=video, page=page, media_type=kind,
    )
    r = menv.client.post("/v2/extract", headers=headers, json={
        "input": P3_URL, "media_type": "audio", "storage": "cache",
        "target": {"track_id": tid, "platform": "bilibili", "video_id": P3_BV, "page": 3},
    })
    assert r.status_code == 202, r.text
    job = menv.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=headers).json()
    assert job["status"] == "failed" and job["error_code"] == "target_mismatch"
    assert menv.client.get("/v2/sync", headers=headers).json() == before
    assert not list((menv.tmp / "cache").glob("*"))


def test_submit_with_p3_query_uses_that_page(tmp_path, monkeypatch):
    """带 ?p=3 的单条提取：下的是第 3 集（以前一律取第 1 集）。"""
    import httpx
    from getmedia.extractor import API_PLAYURL, API_VIEW, MediaResolver

    cids = []

    def upstream(request):
        url = str(request.url).split("?", 1)[0]
        if url == API_VIEW:
            return httpx.Response(200, json={"code": 0, "data": {
                "bvid": P3_BV, "title": "test", "cid": 101, "duration": 90,
                "pages": [{"page": 1, "cid": 101}, {"page": 3, "cid": 303, "duration": 37}],
            }})
        assert url == API_PLAYURL
        cids.append(request.url.params["cid"])
        return httpx.Response(200, json={"code": 0, "data": {"durl": [{"url": "https://example.invalid/media"}]}})

    resolver = MediaResolver(client_factory=lambda **kw: httpx.Client(transport=httpx.MockTransport(upstream), **kw))
    monkeypatch.setattr(resolver, "cookie_file", lambda _platform, user_id=None: None)
    monkeypatch.setattr("getmedia.extractor._get_mixin_key", lambda _: None)
    env = build(tmp_path, resolver=resolver)
    env.app.state.runtime.write(registration_open=True)
    env.token = make_owner(env)
    for _ in range(2):
        reply = extract(env, text=P3_URL)
        assert reply.status_code == 202
    tracks = env.client.get("/v2/tracks", headers=hdr(env.token)).json()["items"]
    assert len(tracks) == 1 and tracks[0]["video_id"] == P3_BV and tracks[0]["page"] == 3
    assert cids == ["303", "303"]
    targeted = resolver.resolve_target(P3_URL, media_type="audio", page=3)
    assert (targeted.video_id, targeted.page, targeted.duration_sec, cids[-1]) == (P3_BV, 3, 37, "303")


# ---------- P0-2 阶段 5：手机同步专用 DELETE / PATCH ----------

def _one_track(env, *, storage="metadata", media_type="audio", text="BVtest0001"):
    r = extract(env, storage=storage, media_type=media_type, text=text)
    job = env.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(env.token)).json()
    assert job["status"] == "done", job
    track = env.client.get(f"/v2/tracks/{job['track_id']}", headers=hdr(env.token)).json()
    return track


def _im(seq) -> dict:
    return {"If-Match": f'"{seq}"'}


def test_sync_delete_requires_if_match(menv):
    t = _one_track(menv)
    path = f"/v2/sync/tracks/{t['id']}"
    missing = menv.client.delete(path, headers=hdr(menv.token))
    assert missing.status_code == 428 and missing.json()["code"] == "precondition_required"
    for bad in ("abc", "0", "-1", 'W/"3"'):
        r = menv.client.delete(path, headers={**hdr(menv.token), "If-Match": bad})
        assert r.status_code == 400 and r.json()["code"] == "bad_version", (bad, r.text)


def test_sync_delete_metadata_is_tombstone_and_idempotent(menv):
    t = _one_track(menv)
    path = f"/v2/sync/tracks/{t['id']}"
    first = menv.client.delete(path, headers={**hdr(menv.token), **_im(t["updated_seq"])})
    assert first.status_code == 200
    body = first.json()
    assert body["ok"] is True and body["deleted"] is True and body["updated_seq"] > t["updated_seq"]
    # 重复 DELETE（响应丢失后的重放）仍返回 200，不产生新墓碑
    again = menv.client.delete(path, headers={**hdr(menv.token), **_im(t["updated_seq"])})
    assert again.status_code == 200 and again.json()["updated_seq"] == body["updated_seq"]
    # 普通列表隐藏，同步带墓碑
    listed = menv.client.get("/v2/tracks", headers=hdr(menv.token)).json()["items"]
    assert all(i["id"] != t["id"] for i in listed)
    sync = menv.client.get(f"/v2/sync?since_seq={t['updated_seq']}", headers=hdr(menv.token)).json()
    assert [i["id"] for i in sync["items"]] == [t["id"]]
    assert sync["items"][0]["deleted"] is True


def test_sync_delete_cache_keeps_file_bytes(menv):
    t = _one_track(menv, storage="cache")
    before = list((menv.tmp / "cache").iterdir())
    assert len(before) == 1
    payload = before[0].read_bytes()
    r = menv.client.delete(f"/v2/sync/tracks/{t['id']}",
                           headers={**hdr(menv.token), **_im(t["updated_seq"])})
    assert r.status_code == 200
    # 墓碑只隐藏记录，不清缓存文件；清理任务仍按原规则回收
    assert before[0].is_file() and before[0].read_bytes() == payload
    assert menv.client.get(f"/v2/media/{t['id']}?stored_only=1",
                           headers=hdr(menv.token)).status_code == 404


def test_sync_delete_archive_is_protected_but_web_delete_still_works(menv):
    t = _one_track(menv, storage="archive")
    path = menv.tmp / "media"
    file = next(path.iterdir())
    before = (file.stat().st_size, file.read_bytes())
    r = menv.client.delete(f"/v2/sync/tracks/{t['id']}",
                           headers={**hdr(menv.token), **_im(t["updated_seq"])})
    assert r.status_code == 409 and r.json()["code"] == "archive_protected"
    # 页面正常列表仍在，文件字节与 SHA 不变
    listed = menv.client.get("/v2/tracks", headers=hdr(menv.token)).json()["items"]
    assert [i["id"] for i in listed] == [t["id"]]
    assert (file.stat().st_size, file.read_bytes()) == before
    # 旧网页接口行为不变：仍可给归档行写墓碑（兼容测试）
    old = menv.client.delete(f"/v2/tracks/{t['id']}", headers=hdr(menv.token))
    assert old.status_code == 200 and old.json()["deleted"] is True
    assert file.is_file() and (file.stat().st_size, file.read_bytes()) == before


def test_sync_delete_version_conflict_and_late_request_after_restore(menv):
    t = _one_track(menv)
    changed = menv.client.patch(f"/v2/tracks/{t['id']}", headers=hdr(menv.token),
                                json={"title": "网页改过"}).json()
    stale = menv.client.delete(f"/v2/sync/tracks/{t['id']}",
                               headers={**hdr(menv.token), **_im(t["updated_seq"])})
    assert stale.status_code == 412 and stale.json()["code"] == "track_version_conflict"
    ok = menv.client.delete(f"/v2/sync/tracks/{t['id']}",
                            headers={**hdr(menv.token), **_im(changed["updated_seq"])})
    assert ok.status_code == 200
    restored = menv.client.post(f"/v2/tracks/{t['id']}/restore", headers=hdr(menv.token)).json()
    # 复活后，迟到的旧 DELETE（版本是第一次删除前的）必须被版本条件挡住
    late = menv.client.delete(f"/v2/sync/tracks/{t['id']}",
                              headers={**hdr(menv.token), **_im(t["updated_seq"])})
    assert late.status_code == 412
    again = menv.client.delete(f"/v2/sync/tracks/{t['id']}",
                               headers={**hdr(menv.token), **_im(restored["updated_seq"])})
    assert again.status_code == 200


def test_sync_patch_title_is_conditional_and_title_only(menv):
    t = _one_track(menv)
    path = f"/v2/sync/tracks/{t['id']}"
    missing = menv.client.patch(path, headers=hdr(menv.token), json={"title": "新名"})
    assert missing.status_code == 428
    extra = menv.client.patch(path, headers={**hdr(menv.token), **_im(t["updated_seq"])},
                              json={"title": "新名", "pinned": True})
    assert extra.status_code == 422
    r = menv.client.patch(path, headers={**hdr(menv.token), **_im(t["updated_seq"])},
                          json={"title": "  新名  "})
    assert r.status_code == 200
    body = r.json()
    assert body["title"] == "新名" and body["updated_seq"] > t["updated_seq"]
    # 旧网页 PATCH 行为不变：无 If-Match、可改 pinned
    old = menv.client.patch(f"/v2/tracks/{t['id']}", headers=hdr(menv.token),
                            json={"title": "网页名", "pinned": True})
    assert old.status_code == 200 and old.json()["title"] == "网页名" and old.json()["pinned"] is True


def test_sync_patch_conflict_tombstone_and_missing(menv):
    t = _one_track(menv)
    path = f"/v2/sync/tracks/{t['id']}"
    conflict = menv.client.patch(path, headers={**hdr(menv.token), **_im(t["updated_seq"] + 99)},
                                 json={"title": "x"})
    assert conflict.status_code == 412 and conflict.json()["code"] == "track_version_conflict"
    empty = menv.client.patch(path, headers={**hdr(menv.token), **_im(t["updated_seq"])},
                              json={"title": "   "})
    assert empty.status_code == 400 and empty.json()["code"] == "bad_title"
    deleted = menv.client.delete(path, headers={**hdr(menv.token), **_im(t["updated_seq"])}).json()
    tomb = menv.client.patch(path, headers={**hdr(menv.token), **_im(deleted["updated_seq"])},
                             json={"title": "x"})
    assert tomb.status_code == 409 and tomb.json()["code"] == "track_deleted"
    missing = menv.client.patch("/v2/sync/tracks/99999", headers={**hdr(menv.token), **_im(1)},
                                json={"title": "x"})
    assert missing.status_code == 404 and missing.json()["code"] == "track_not_found"


def test_sync_patch_concurrent_same_version_only_one_wins(menv):
    t = _one_track(menv)
    path = f"/v2/sync/tracks/{t['id']}"
    first = menv.client.patch(path, headers={**hdr(menv.token), **_im(t["updated_seq"])},
                              json={"title": "A"})
    second = menv.client.patch(path, headers={**hdr(menv.token), **_im(t["updated_seq"])},
                               json={"title": "B"})
    assert first.status_code == 200
    assert second.status_code == 412 and second.json()["code"] == "track_version_conflict"
    now = menv.client.get(f"/v2/tracks/{t['id']}", headers=hdr(menv.token)).json()
    assert now["title"] == "A"


def test_sync_writes_are_per_account(tmp_path):
    env = build(tmp_path)
    env.app.state.runtime.write(registration_open=True)
    a = make_owner(env, name="sync_a")
    b = make_owner(env, name="sync_b")
    r = env.client.post("/v2/extract", headers=hdr(a), json={"input": "BVtest0001"})
    tid = env.client.get(f"/v2/extraction-jobs/{r.json()['id']}", headers=hdr(a)).json()["track_id"]
    seq = env.client.get(f"/v2/tracks/{tid}", headers=hdr(a)).json()["updated_seq"]
    assert env.client.delete(f"/v2/sync/tracks/{tid}", headers={**hdr(b), **_im(seq)}).status_code == 404
    assert env.client.patch(f"/v2/sync/tracks/{tid}", headers={**hdr(b), **_im(seq)},
                            json={"title": "x"}).status_code == 404
    # 越权写不能改到 A 的行
    assert env.client.get(f"/v2/tracks/{tid}", headers=hdr(a)).json()["title"] != "x"


def test_sync_write_routes_require_owner(menv):
    t = _one_track(menv)
    assert menv.client.delete(f"/v2/sync/tracks/{t['id']}").status_code == 401
    assert menv.client.patch(f"/v2/sync/tracks/{t['id']}", json={"title": "x"}).status_code == 401


# ---------- 本机导入（platform=local）上传归档 ----------

def _local_track(env, *, title="本机导入", media_type="audio", video_id="loc_5f0c1d2e"):
    r = env.client.post("/v2/tracks", headers=hdr(env.token), json={
        "platform": "local", "video_id": video_id, "page": 1, "media_type": media_type,
        "title": title, "uploader": None, "duration_sec": 12.5, "source_url": "",
    })
    assert r.status_code == 200, r.text
    return r.json()["track"]


def _upload(env, tid, payload, name="晴天.mp3"):
    return env.client.post(f"/v2/tracks/{tid}/file", headers=hdr(env.token),
                           files={"file": (name, payload, "application/octet-stream")})


def test_upload_local_file_lands_in_archive_and_is_idempotent(menv):
    t = _local_track(menv)
    payload = b"local-audio-payload" * 512
    r = _upload(menv, t["id"], payload)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["storage"] == "archive" and body["has_file"] is True
    assert body["bytes"] == len(payload)
    files = [p for p in (menv.tmp / "media").iterdir() if p.is_file()]
    assert len(files) == 1 and files[0].read_bytes() == payload
    assert files[0].suffix == ".mp3"

    # 同一条重传：幂等，不重写文件、不动版本（响应丢失后的重试靠这条）
    again = _upload(menv, t["id"], payload)
    assert again.status_code == 200 and again.json()["updated_seq"] == body["updated_seq"]
    assert len([p for p in (menv.tmp / "media").iterdir() if p.is_file()]) == 1

    got = menv.client.get(f"/v2/media/{t['id']}", headers=hdr(menv.token))
    assert got.status_code == 200 and got.content == payload


def test_delete_local_removes_bytes_and_keeps_tombstone(menv):
    t = _local_track(menv)
    payload = b"to-be-deleted" * 300
    assert _upload(menv, t["id"], payload).status_code == 200
    file = next(p for p in (menv.tmp / "media").iterdir() if p.is_file())

    dropped = menv.client.delete(f"/v2/tracks/{t['id']}", headers=hdr(menv.token))
    assert dropped.status_code == 200 and dropped.json()["deleted"] is True
    assert not file.exists()
    row = menv.client.get(f"/v2/tracks/{t['id']}", headers=hdr(menv.token)).json()
    assert row["deleted"] is True and row["has_file"] is False
    assert row["storage"] == "metadata" and row["bytes"] == 0
    # 归档池的占用跟着归零
    used = menv.client.get("/v2/maintenance/storage", headers=hdr(menv.token)).json()["used"]
    assert used["archive"] == 0


def test_sync_delete_is_allowed_for_local_archive(menv):
    t = _local_track(menv)
    assert _upload(menv, t["id"], b"x" * 4096).status_code == 200
    current = menv.client.get(f"/v2/tracks/{t['id']}", headers=hdr(menv.token)).json()
    r = menv.client.delete(f"/v2/sync/tracks/{t['id']}",
                           headers={**hdr(menv.token), **_im(current["updated_seq"])})
    assert r.status_code == 200 and r.json()["deleted"] is True
    assert [p for p in (menv.tmp / "media").iterdir() if p.is_file()] == []


def test_upload_quota_rejects_oversized_file(tmp_path):
    env = build(tmp_path / "tiny", archive_quota_bytes=64)
    env.app.state.runtime.write(registration_open=True)
    env.token = make_owner(env, name="boss")
    t = _local_track(env)
    r = _upload(env, t["id"], b"y" * 256)
    assert r.status_code == 409 and r.json()["code"] == "quota_exceeded"
    # 失败不留半成品
    assert [p for p in (env.tmp / "media").iterdir() if p.is_file()] == []
    assert env.client.get(f"/v2/tracks/{t['id']}", headers=hdr(env.token)).json()["has_file"] is False


def test_sync_resets_when_client_watermark_is_ahead_of_server(menv):
    """清库/硬删后序号回落到 1 开始，设备还攥着旧的高水位。

    2026-11-28 实测踩到：新行 seq 1/3/5，另一台设备水位 156，增量拉取永远为空，
    改名也永远传不过去。水位高于全库最大序号时按全量重来。
    """
    extract(menv, text="BVtest0002")
    full = menv.client.get("/v2/sync?since_seq=0", headers=hdr(menv.token)).json()
    assert len(full["items"]) == 1

    # 模拟"设备水位比全库最大序号还高"
    ahead = menv.client.get("/v2/sync?since_seq=99999", headers=hdr(menv.token)).json()
    assert [i["id"] for i in ahead["items"]] == [full["items"][0]["id"]]
    assert ahead["next_seq"] <= 99999

    # 正常水位仍按增量走：不重复返回
    normal = menv.client.get(f"/v2/sync?since_seq={ahead['next_seq']}",
                             headers=hdr(menv.token)).json()
    assert normal["items"] == []
