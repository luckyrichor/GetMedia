import shutil
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from getmedia.alerts import RecordingAlertSink
from getmedia.api import create_app
from getmedia.config import testing_settings

ROOT = Path(__file__).resolve().parents[2]


class Clock:
    def __init__(self, t=1_800_000_000.0):
        self.t = t

    def __call__(self):
        return self.t

    def advance(self, s):
        self.t += s


@pytest.fixture
def env(tmp_path):
    legal = tmp_path / "legal"
    legal.mkdir()
    for f in ("user-agreement.md", "privacy-policy.md"):
        shutil.copy(ROOT / "docs" / "legal" / f, legal / f)
    settings = testing_settings(tmp_path, legal)
    clock, alerts = Clock(), RecordingAlertSink()
    app = create_app(settings, clock=clock, alerts=alerts)
    client = TestClient(app, base_url="https://testserver")

    class E: pass
    e = E()
    e.settings, e.clock, e.alerts, e.app, e.client, e.legal_dir, e.tmp = settings, clock, alerts, app, client, legal, tmp_path
    e.svc = app.state.svc
    return e


@pytest.fixture
def open_reg(env):
    env.app.state.runtime.write(registration_open=True)
    return env


def consent_body(env):
    d = env.client.get("/v2/legal").json()["documents"]
    return {k: {"version": d[k]["version"], "sha256": d[k]["sha256"]} for k in ("user_agreement", "privacy_policy")}


def register(env, name="alice", password="correct horse 1", channel="android", **kw):
    body = {"login_name": name, "password": password, "age_confirmed": True, "consent": consent_body(env),
            "client": {"channel": channel, "app_version": "0.1.0", "os": "Android 14", "device": "Pixel"}}
    body.update(kw)
    return env.client.post("/account/register", json=body)


def login(env, name="alice", password="correct horse 1", channel="android", **kw):
    body = {"login_name": name, "password": password, "client": {"channel": channel, "device": kw.pop("device", "Pixel")}}
    body.update(kw)
    return env.client.post("/account/login", json=body)


def hdr(token):
    return {"X-GetMedia-Session": token}
