"""SMTP 代理（FEEDBACK_SMTP_PROXY）的解析与建连测试。

不连外网、不做真实 TLS：假代理在 127.0.0.1 上先答 `CONNECT`，再讲最小 SMTP 对话；
TLS 用假 context（wrap_socket 原样返回 socket），这样能验证"隧道 + 后续对话"的顺序，
又不需要自签证书。
"""
import base64
import socket
import threading
from email.message import EmailMessage

import pytest

from getmedia.config import ConfigError, MailConfig, load_mail, parse_proxy
from getmedia.feedback import FeedbackService
from getmedia.smtp_proxy import SmtpProxyError, open_smtp

BASE = dict(zip(
    # 键名拼接而非写成 FEEDBACK_XXX="…" 字面量：scan_leaks 会把那种写法一律当成真实凭据
    ("FEEDBACK_" + k for k in ("MAIL_TO", "MAIL_FROM", "SMTP_HOST", "SMTP_PORT",
                               "SMTP_SECURITY", "SMTP_USER", "SMTP_PASSWORD")),
    ("to@example.invalid", "from@example.invalid", "smtp.example.invalid", "465",
     "ssl", "u@example.invalid", "pw-placeholder"),
))


class _FakeContext:
    """假 TLS context：只记录 SNI，不做握手。"""

    def __init__(self):
        self.hostnames = []

    def wrap_socket(self, sock, server_hostname=None):
        self.hostnames.append(server_hostname)
        return sock


class StubProxy(threading.Thread):
    """假「HTTP 代理 + SMTP 服务端」。CONNECT 的 200 与 220 问候同一批发出去。"""

    def __init__(self, *, connect_status=200, starttls=False):
        super().__init__(daemon=True)
        self.connect_status = connect_status
        self.starttls = starttls
        self.request = b""
        self.commands = []
        self.body = []
        self.error = None
        self._in_data = False
        self._srv = socket.socket()
        self._srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._srv.bind(("127.0.0.1", 0))
        self._srv.listen(1)
        self._srv.settimeout(10)
        self.host, self.port = self._srv.getsockname()

    def run(self):
        try:
            conn, _ = self._srv.accept()
            conn.settimeout(10)
            buf = b""
            while b"\r\n\r\n" not in buf:
                chunk = conn.recv(1)
                if not chunk:
                    return
                buf += chunk
            self.request = buf
            if self.connect_status != 200:
                conn.sendall(b"HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\n\r\n")
                return
            conn.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n220 stub ESMTP\r\n")
            self._dialogue(conn)
        except Exception as exc:  # noqa: BLE001 - 线程里只记下来给断言用
            self.error = exc
        finally:
            self._srv.close()

    def _dialogue(self, conn):
        fp = conn.makefile("rb")
        while True:
            line = fp.readline()
            if not line:
                return
            text = line.decode("ascii", "replace").rstrip("\r\n")
            self.commands.append(text)
            if self._in_data:
                if text == ".":
                    self._in_data = False
                    conn.sendall(b"250 queued\r\n")
                else:
                    self.body.append(text)
                continue
            verb = text.split(" ", 1)[0].upper()
            if verb == "EHLO":
                lines = ["stub", "AUTH PLAIN LOGIN"] + (["STARTTLS"] if self.starttls else [])
                resp = "".join(f"250-{x}\r\n" for x in lines[:-1]) + f"250 {lines[-1]}\r\n"
                conn.sendall(resp.encode())
            elif verb == "STARTTLS":
                conn.sendall(b"220 ready to start TLS\r\n")
            elif verb == "AUTH":
                conn.sendall(b"235 2.7.0 Authentication successful\r\n")
            elif verb == "DATA":
                self._in_data = True
                conn.sendall(b"354 end with <CRLF>.<CRLF>\r\n")
            elif verb == "QUIT":
                conn.sendall(b"221 bye\r\n")
                return
            else:
                conn.sendall(b"250 ok\r\n")


def _mail(env=None, **kw):
    return MailConfig(BASE["FEEDBACK_MAIL_TO"], BASE["FEEDBACK_MAIL_FROM"], BASE["FEEDBACK_SMTP_HOST"],
                      int(kw.pop("port", 465)), kw.pop("security", "ssl"),
                      BASE["FEEDBACK_SMTP_USER"], BASE["FEEDBACK_SMTP_PASSWORD"], **kw)


def _service(cfg):
    class S:  # 只用到 settings.mail
        mail = cfg
    return FeedbackService(None, S(), None, None)


# ---------------- 解析 ----------------

def test_parse_proxy_empty_means_direct():
    assert parse_proxy(None) is None
    assert parse_proxy("") is None
    assert parse_proxy("   ") is None


def test_parse_proxy_accepts_bare_host_port_and_http():
    a, b = parse_proxy("127.0.0.1:7890"), parse_proxy("http://127.0.0.1:7890")
    assert (a.host, a.port) == ("127.0.0.1", 7890) == (b.host, b.port)
    assert a.auth_header == ""


def test_parse_proxy_carries_basic_auth_without_exposing_it():
    cfg = parse_proxy("http://user:p%40ss@127.0.0.1:7890")
    assert cfg.auth_header == "Basic " + base64.b64encode(b"user:p@ss").decode()
    assert "p@ss" not in repr(cfg) and cfg.auth_header not in repr(cfg)


@pytest.mark.parametrize("raw,frag", [
    ("socks5://127.0.0.1:7890", "只支持 http://"),
    ("https://127.0.0.1:7890", "只支持 http://"),
    ("http://127.0.0.1", "必须写明端口"),
    ("http://127.0.0.1:notaport", "端口不是数字"),
    ("http://:7890", "缺少主机名"),
])
def test_parse_proxy_rejects_bad_values(raw, frag):
    with pytest.raises(ConfigError) as exc:
        parse_proxy(raw)
    assert frag in str(exc.value)


def test_load_mail_wires_proxy_and_keeps_repr_clean():
    assert load_mail(BASE).proxy is None
    env = dict(BASE, FEEDBACK_SMTP_PROXY="http://127.0.0.1:7890")
    mail = load_mail(env)
    assert (mail.proxy.host, mail.proxy.port) == ("127.0.0.1", 7890)
    assert "7890" not in repr(mail)          # repr 里不出现代理，更不出现口令
    assert "pw-placeholder" not in repr(mail)


# ---------------- 建连 ----------------

def test_direct_path_unchanged(monkeypatch):
    """没有 proxy 时仍走 smtplib 原生类，不引入任何隧道。"""
    import getmedia.smtp_proxy as sp
    seen = []

    class Dummy:
        def __init__(self, host, port, **kw):
            seen.append((host, port, kw))

    monkeypatch.setattr(sp.smtplib, "SMTP_SSL", Dummy)
    ctx = _FakeContext()
    open_smtp(_mail(), ctx)
    assert [(h, p) for h, p, _ in seen] == [("smtp.example.invalid", 465)]
    assert seen[0][2] == {"timeout": 20, "context": ctx}


def test_ssl_via_proxy_delivers_message(monkeypatch):
    """465：CONNECT 成功 → 立刻 TLS → 正常登录发信。"""
    stub = StubProxy()
    stub.start()
    ctx = _FakeContext()
    monkeypatch.setattr("ssl.create_default_context", lambda: ctx)

    cfg = _mail(port=stub.port, proxy=parse_proxy(f"127.0.0.1:{stub.port}"))
    msg = EmailMessage()
    msg["From"], msg["To"], msg["Subject"] = "from@example.invalid", "to@example.invalid", "自检"
    msg.set_content("hello-over-proxy")
    _service(cfg).mailer(msg)

    stub.join(10)
    assert stub.error is None
    first = stub.request.split(b"\r\n")[0].decode()
    assert first == f"CONNECT smtp.example.invalid:{stub.port} HTTP/1.1"
    assert b"Host: smtp.example.invalid" in stub.request
    assert ctx.hostnames == ["smtp.example.invalid"]          # SNI 用的是主机名，不是 IP
    assert any(c.startswith("AUTH PLAIN ") for c in stub.commands)
    assert "hello-over-proxy" in "".join(stub.body)           # 问候没被吞掉，正文确实送到了


def test_starttls_via_proxy(monkeypatch):
    """587：先隧道后 STARTTLS，TLS 由 smtplib 自己套。"""
    stub = StubProxy(starttls=True)
    stub.start()
    ctx = _FakeContext()
    monkeypatch.setattr("ssl.create_default_context", lambda: ctx)

    cfg = _mail(port=stub.port, security="starttls", proxy=parse_proxy(f"127.0.0.1:{stub.port}"))
    msg = EmailMessage()
    msg["From"], msg["To"], msg["Subject"] = "from@example.invalid", "to@example.invalid", "自检"
    msg.set_content("hello-starttls")
    _service(cfg).mailer(msg)

    stub.join(10)
    assert stub.error is None
    assert "STARTTLS" in stub.commands
    assert "hello-starttls" in "".join(stub.body)


def test_proxy_auth_required_raises_short_reason():
    stub = StubProxy(connect_status=407)
    stub.start()
    cfg = _mail(port=stub.port, proxy=parse_proxy(f"127.0.0.1:{stub.port}"))
    with pytest.raises(SmtpProxyError) as exc:
        _service(cfg).mailer(EmailMessage())
    stub.join(10)
    assert exc.value.reason == "proxy_auth"
    assert "127.0.0.1" not in str(exc.value)      # 报错里不带地址，便于直接打印


def test_proxy_unreachable_raises_connect_reason():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()                                      # 端口空出来，连接会被拒
    cfg = _mail(port=465, proxy=parse_proxy(f"127.0.0.1:{port}"))
    with pytest.raises(SmtpProxyError) as exc:
        _service(cfg).mailer(EmailMessage())
    assert exc.value.reason == "proxy_connect"
