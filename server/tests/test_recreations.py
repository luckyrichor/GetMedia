"""P2g：AI 视频重建（能力开关、入队、worker 领取、产物）。"""
from __future__ import annotations

import shutil
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from conftest import hdr, make_owner
from getmedia.alerts import RecordingAlertSink
from getmedia.api import create_app
from getmedia.config import testing_settings as make_settings

ROOT = Path(__file__).resolve().parents[2]


def build(tmp_path, *, ai_enabled=False, **settings_kw):
    tmp_path.mkdir(parents=True, exist_ok=True)
    legal = tmp_path / "legal"
    legal.mkdir()
    for f in ("user-agreement.md", "privacy-policy.md"):
        shutil.copy(ROOT / "docs" / "legal" / f, legal / f)
    settings = make_settings(tmp_path, legal).with_(ai_enabled=ai_enabled, **settings_kw)

    class C:
        t = 1_800_000_000.0

        def __call__(self):
            return self.t

        def advance(self, s):
            self.t += s

    clock = C()
    app = create_app(settings, clock=clock, alerts=RecordingAlertSink())
    client = TestClient(app, base_url="https://testserver")
    app.state.runtime.write(registration_open=True)
    token = make_owner(_EnvProxy(app, client), name="boss")

    class E:
        pass

    e = E()
    e.app, e.client, e.token, e.clock, e.tmp, e.settings = app, client, token, clock, tmp_path, settings
    return e


class _EnvProxy:
    """make_owner 只用到 app.state.engine / client / settings。"""

    def __init__(self, app, client):
        self.app, self.client, self.settings = app, client, app.state.settings


@pytest.fixture
def off(tmp_path):
    return build(tmp_path)


@pytest.fixture
def on(tmp_path):
    return build(tmp_path / "on", ai_enabled=True)


def upload(env, name="clip.mp4", data=b"\x00\x00\x00\x18ftypmp42" + b"x" * 100):
    return env.client.post("/v2/recreations/upload", headers=hdr(env.token),
                           files={"file": (name, data, "video/mp4")})


def test_capabilities_off_and_guard(off):
    cap = off.client.get("/v2/recreations/capabilities", headers=hdr(off.token)).json()
    assert cap["available"] is False and cap["reason"]
    rec = upload(off).json()
    assert off.client.post(f"/v2/recreations/{rec['id']}/analyze", headers=hdr(off.token)).status_code == 503


def test_upload_and_source_stream(off):
    data = b"\x00" * 64
    r = upload(off, data=data)
    assert r.status_code == 201, r.text
    pid = r.json()["id"]
    assert r.json()["source_path"].startswith(f"ai/{pid}/")

    got = off.client.get(f"/v2/recreations/{pid}/source", headers=hdr(off.token))
    assert got.status_code == 200 and got.content == data
    part = off.client.get(f"/v2/recreations/{pid}/source",
                          headers={**hdr(off.token), "Range": "bytes=0-7"})
    assert part.status_code == 206 and len(part.content) == 8


def test_upload_too_large(tmp_path):
    env = build(tmp_path / "small", ai_enabled=True)
    env.settings  # 默认 200 MB；这里直接断言接口按 settings 判定
    big = b"x" * (env.app.state.settings.ai_max_upload_bytes + 1)
    r = upload(env, data=big)
    assert r.status_code == 413


def test_description_required_before_generate(on):
    pid = upload(on).json()["id"]
    r = on.client.post(f"/v2/recreations/{pid}/generate", headers=hdr(on.token))
    assert r.status_code == 409 and r.json()["code"] == "description_required"
    assert on.client.put(f"/v2/recreations/{pid}/description", headers=hdr(on.token),
                         json={"description": "换成赛博朋克风格"}).status_code == 200
    assert on.client.post(f"/v2/recreations/{pid}/generate", headers=hdr(on.token)).status_code == 202


def test_queue_claim_finish_and_video(on):
    pid = upload(on).json()["id"]
    on.client.put(f"/v2/recreations/{pid}/description", headers=hdr(on.token), json={"description": "d"})
    enq = on.client.post(f"/v2/recreations/{pid}/analyze", headers=hdr(on.token)).json()
    assert enq["status"] == "queued"

    svc = on.app.state.recreations
    claimed = svc.claim()
    assert claimed["run_id"] == enq["run_id"] and claimed["kind"] == "analyze"
    assert svc.claim() is None  # 一次只领一个

    out_dir = Path(on.settings.media_dir) / "ai" / str(pid)
    out = out_dir / "result.mp4"
    out.write_bytes(b"\x01" * 32)
    svc.finish(claimed["run_id"], "done", output_path=f"ai/{pid}/result.mp4")

    got = on.client.get(f"/v2/recreations/{pid}/runs/{claimed['run_id']}/video", headers=hdr(on.token))
    assert got.status_code == 200 and got.content == b"\x01" * 32
    assert on.client.post(f"/v2/recreations/{pid}/runs/{claimed['run_id']}/save",
                          headers=hdr(on.token)).json()["saved"] is True


def test_cancel_run(on):
    pid = upload(on).json()["id"]
    enq = on.client.post(f"/v2/recreations/{pid}/analyze", headers=hdr(on.token)).json()
    assert on.client.post(f"/v2/recreations/{pid}/runs/{enq['run_id']}/cancel",
                          headers=hdr(on.token)).json()["status"] == "cancelled"
    assert on.client.post(f"/v2/recreations/{pid}/runs/{enq['run_id']}/cancel",
                          headers=hdr(on.token)).status_code == 409
    assert on.app.state.recreations.claim() is None


def test_reset_stale_runs(on):
    pid = upload(on).json()["id"]
    enq = on.client.post(f"/v2/recreations/{pid}/analyze", headers=hdr(on.token)).json()
    on.app.state.recreations.claim()
    on.clock.advance(on.settings.ai_timeout_sec * 3)
    assert on.app.state.recreations.reset_stale() == 1
    job = on.app.state.recreations.get(owner_of(on), pid)
    assert job["runs"][0]["status"] == "queued"


def owner_of(env):
    return env.client.get("/account/me", headers=hdr(env.token)).json()["id"]


def test_worker_marks_unconfigured(on):
    from getmedia.ai_worker import run_once
    pid = upload(on).json()["id"]
    enq = on.client.post(f"/v2/recreations/{pid}/analyze", headers=hdr(on.token)).json()
    out = run_once(on.app.state.recreations, None)
    assert out["run_id"] == enq["run_id"] and out["error"] == "ai_unconfigured"
    job = on.app.state.recreations.get(owner_of(on), pid)
    assert job["runs"][0]["error_code"] == "ai_unconfigured"


def test_worker_runs_injected_runner(on):
    from getmedia.ai_worker import run_once
    pid = upload(on).json()["id"]
    on.client.post(f"/v2/recreations/{pid}/analyze", headers=hdr(on.token))

    def runner(kind, out_dir):
        target = out_dir / "made.mp4"
        target.write_bytes(b"z" * 16)
        return target

    assert run_once(on.app.state.recreations, runner)["status"] == "done"
    job = on.app.state.recreations.get(owner_of(on), pid)
    assert job["runs"][0]["status"] == "done"
