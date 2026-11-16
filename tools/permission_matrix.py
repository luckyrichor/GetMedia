#!/usr/bin/env python3
"""权限矩阵报告：匿名 / free / owner × 全部 owner 路由。

与 `server/tests/test_permissions.py` 同源，但输出一张可贴进验收文件的表，
并在有违反时以非 0 退出。只读：用临时目录建库，不碰任何真实数据。

用法（仓库根）：
    /tmp/gm-venv/bin/python tools/permission_matrix.py
"""
from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))
sys.path.insert(0, str(ROOT / "server" / "tests"))

from fastapi.routing import APIRoute  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import text  # noqa: E402

from getmedia.alerts import RecordingAlertSink  # noqa: E402
from getmedia.api import create_app  # noqa: E402
from getmedia.config import testing_settings  # noqa: E402
from getmedia.tiers import FREE_ROUTES, _tiers_of, unprotected_routes  # noqa: E402

PARAM = re.compile(r"\{[^}]+\}")


def build(tmp: Path):
    legal = tmp / "legal"
    legal.mkdir()
    for name in ("user-agreement.md", "privacy-policy.md"):
        (legal / name).write_bytes((ROOT / "docs" / "legal" / name).read_bytes())
    settings = testing_settings(tmp, legal)

    class Clock:
        def __init__(self):
            self.t = 1_800_000_000.0

        def __call__(self):
            return self.t

        def advance(self, s):
            self.t += s

    clock = Clock()
    app = create_app(settings, clock=clock, alerts=RecordingAlertSink())
    return app, clock


def legal_body(client):
    docs = client.get("/v2/legal").json()["documents"]
    return {k: {"version": docs[k]["version"], "sha256": docs[k]["sha256"]}
            for k in ("user_agreement", "privacy_policy")}


def register(client, name):
    body = {"login_name": name, "password": "correct horse 1", "age_confirmed": True,
            "consent": legal_body(client),
            "client": {"channel": "android", "app_version": "0.1.0", "os": "Android 14", "device": "Pixel"}}
    return client.post("/account/register", json=body).json()["session"]["token"]


def main() -> int:
    with tempfile.TemporaryDirectory() as td:
        app, _clock = build(Path(td))
        app.state.runtime.write(registration_open=True)
        client = TestClient(app, base_url="https://testserver")

        free_token = register(client, "free_user")
        owner_token = register(client, "owner_user")
        me = client.get("/account/me", headers={"X-GetMedia-Session": owner_token}).json()
        with app.state.engine.begin() as c:
            c.execute(text("UPDATE users SET tier='owner' WHERE id=:u"), {"u": me["id"]})

        routes = []
        for r in app.routes:
            if not isinstance(r, APIRoute):
                continue
            if "owner" not in (_tiers_of(r) or set()):
                continue
            for m in sorted(r.methods - {"HEAD", "OPTIONS"}):
                routes.append((m, r.path))
        routes.sort(key=lambda x: (x[1], x[0]))

        def call(method, path, token):
            headers = {"X-GetMedia-Session": token} if token else {}
            kwargs = {"headers": headers}
            if method in ("POST", "PUT", "PATCH"):
                kwargs["json"] = {}
            return client.request(method, PARAM.sub("x", path), **kwargs)

        rows, bad = [], []
        for method, path in routes:
            anon = call(method, path, None)
            free = call(method, path, free_token)
            own = call(method, path, owner_token)
            ok = anon.status_code == 401 and free.status_code == 403 and own.status_code not in (401, 403)
            if free.status_code == 403 and free.json().get("code") != "forbidden_tier":
                ok = False
            rows.append((method, path, anon.status_code, free.status_code, own.status_code, ok))
            if not ok:
                bad.append(f"{method} {path}: anon={anon.status_code} free={free.status_code} owner={own.status_code}")

        print("权限矩阵（服务端内部路径；对外为 /getmedia/api 前缀）")
        print(f"owner 路由 {len(routes)} 条；免费白名单 {len(FREE_ROUTES)} 条；启动自检未防护路由 {len(unprotected_routes(app))} 条")
        print()
        print(f"{'METHOD':7} {'PATH':52} {'匿名':>6} {'FREE':>6} {'OWNER':>6}  {'判定'}")
        print("-" * 92)
        for method, path, a, f, o, ok in rows:
            print(f"{method:7} {path:52} {a:>6} {f:>6} {o:>6}  {'OK' if ok else 'FAIL'}")
        print("-" * 92)
        print(f"违反项：{len(bad)}")
        for b in bad:
            print("  " + b)
        if unprotected_routes(app):
            print("未防护路由：", unprotected_routes(app))
            return 1
        return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
