"""端到端夹具：真实服务端 + 构建产物，放在同一个 https 来源的 /getmedia/ 前缀下（模拟 nginx 去前缀）。"""
import json
import shutil
import socket
import ssl
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
DIST = ROOT / "web" / "public-app" / "dist"

pytest.importorskip("playwright")
if not (DIST / "index.html").exists():
    pytest.skip("先运行 npm run build（web/）", allow_module_level=True)


def _selfsigned(d: Path):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")])
    now = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(1)
            .not_valid_before(now - timedelta(days=1)).not_valid_after(now + timedelta(days=2))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), False).sign(key, hashes.SHA256()))
    (d / "c.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    (d / "k.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))


@pytest.fixture(scope="session")
def site(tmp_path_factory):
    import uvicorn
    from starlette.applications import Starlette
    from starlette.routing import Mount
    from starlette.staticfiles import StaticFiles
    from getmedia.api import create_app
    from getmedia.config import testing_settings

    tmp = tmp_path_factory.mktemp("e2e")
    legal = tmp / "legal"; legal.mkdir()
    for f in ("user-agreement.md", "privacy-policy.md"):
        shutil.copy(ROOT / "docs" / "legal" / f, legal / f)
    s = testing_settings(tmp, legal).with_(register_ip_limit=(50, 3600), feedback_min_interval=0)
    gm = create_app(s)
    gm.state.runtime.write(registration_open=True)
    (tmp / "release.json").write_text(json.dumps({"min_version": "0.1.0", "latest": {
        "version": "0.1.0", "notes": "首个版本", "apk": "https://example.invalid/getmedia.apk", "sha256": "ab" * 32}}))
    class SpaFiles(StaticFiles):
        """模拟 nginx 的 try_files $uri $uri/ /getmedia/index.html：无对应文件的路径回退到入口页。"""
        async def get_response(self, path, scope):
            from starlette.exceptions import HTTPException
            try:
                return await super().get_response(path, scope)
            except HTTPException as e:
                if e.status_code == 404 and "." not in path.rsplit("/", 1)[-1]:
                    return await super().get_response("index.html", scope)
                raise

    site_app = Starlette(routes=[Mount("/getmedia/api", app=gm), Mount("/getmedia", app=SpaFiles(directory=DIST, html=True))])

    async def csp(scope, receive, send):  # 模拟 nginx 加的安全头
        async def snd(m):
            if m["type"] == "http.response.start":
                m["headers"] = list(m["headers"]) + [(b"content-security-policy", b"default-src 'self'; frame-ancestors 'none'"),
                                                      (b"referrer-policy", b"no-referrer"), (b"x-content-type-options", b"nosniff")]
            await send(m)
        await site_app(scope, receive, snd)

    _selfsigned(tmp)
    sock = socket.socket(); sock.bind(("127.0.0.1", 0)); port = sock.getsockname()[1]; sock.close()
    cfg = uvicorn.Config(csp, host="127.0.0.1", port=port, ssl_certfile=str(tmp / "c.pem"), ssl_keyfile=str(tmp / "k.pem"), log_level="warning")
    server = uvicorn.Server(cfg)
    th = threading.Thread(target=server.run, daemon=True); th.start()
    for _ in range(100):
        if server.started:
            break
        time.sleep(0.05)

    class Site:
        base = f"https://127.0.0.1:{port}/getmedia/"
        settings = s
        app = gm
        dir = tmp
    yield Site
    server.should_exit = True
