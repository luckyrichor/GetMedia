"""SMTP 经 HTTP 代理（CONNECT 隧道）发送。

只在 `FEEDBACK_SMTP_PROXY` 非空时启用；为空时走原来的直连路径（逐行等价，不改行为）。

为什么是 HTTP CONNECT 而不是 SOCKS5：本机代理（Clash 一类）的 7890 是 mixed 端口，
同时讲 HTTP 与 SOCKS5，一条 CONNECT 就够，不必自己实现 SOCKS 握手。只支持 http://；
写了 socks5:// 在**启动时**就报错，不静默退化成直连——那种"看起来发出去了、其实走了别的路"
的问题，排查成本远高于一条启动错误。

标准库没有"先连代理再握手"的入口，这里只替换 `smtplib` 的 `_get_socket`（它建 TCP 的唯一出口）：

- 465 隐式 TLS：CONNECT 成功后立刻 `wrap_socket`，再把 TLS socket 交回 smtplib；
- 587 STARTTLS：先交回明文 socket，EHLO 之后由 `SMTP.starttls()` 自己 wrap（它用 `self._host` 做 SNI）。

一个坑：代理回 `200` 之后，隧道里的 `220` 问候可能和 `200` 的响应**在同一个 TCP 段**里到达。
所以读 CONNECT 响应必须逐字节停在 `\\r\\n\\r\\n`；一旦多读一个字节，问候就被吞掉，
接下来 smtplib 会一直等 220 直到超时。
"""
from __future__ import annotations

import smtplib
import socket

from .config import ProxyConfig


class SmtpProxyError(OSError):
    """代理段失败。只带可读的短原因，不含地址与凭据（它会进日志和 CLI 输出）。"""

    def __init__(self, reason: str):
        super().__init__(f"SMTP 代理失败（{reason}）")
        self.reason = reason


# 单个 CONNECT 响应的上限：正常代理只回几行，超过这个长度按协议错误处理
_MAX_RESPONSE = 16 * 1024


def connect_via(proxy: ProxyConfig, host: str, port: int, timeout: float | None) -> socket.socket:
    """建立到 host:port 的 TCP 隧道，返回已打通的 socket（尚未 TLS）。"""
    try:
        sock = socket.create_connection((proxy.host, proxy.port), timeout)
    except OSError as exc:
        raise SmtpProxyError("proxy_connect") from exc

    try:
        # 超时已由 create_connection 设到 socket 上（sentinel 由它自己处理，这里不再碰）
        lines = [f"CONNECT {host}:{port} HTTP/1.1", f"Host: {host}:{port}"]
        if proxy.auth_header:
            lines.append(f"Proxy-Authorization: {proxy.auth_header}")
        sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("ascii", "replace"))
        status = _read_connect_response(sock)
        if status == 200:
            return sock
        raise SmtpProxyError("proxy_auth" if status == 407 else "proxy_refused")
    except SmtpProxyError:
        sock.close()
        raise
    except OSError as exc:
        sock.close()
        raise SmtpProxyError("proxy_connect") from exc


def _read_connect_response(sock: socket.socket) -> int:
    """逐字节读到 `\\r\\n\\r\\n`，返回状态码。多读一个字节就会吞掉隧道里的 SMTP 问候。"""
    buf = b""
    while not buf.endswith(b"\r\n\r\n"):
        try:
            chunk = sock.recv(1)
        except TimeoutError as exc:
            raise SmtpProxyError("proxy_timeout") from exc
        if not chunk:
            raise SmtpProxyError("proxy_bad_response")
        buf += chunk
        if len(buf) > _MAX_RESPONSE:
            raise SmtpProxyError("proxy_bad_response")

    first = buf.split(b"\r\n", 1)[0].decode("latin-1", "replace")
    fields = first.split()
    if len(fields) < 2 or not fields[0].startswith("HTTP/") or not fields[1].isdigit():
        raise SmtpProxyError("proxy_bad_response")
    return int(fields[1])


class SMTPOverProxy(smtplib.SMTP):
    """明文 SMTP（通常 587 + STARTTLS）：先打通隧道，TLS 由 starttls() 负责。"""

    def __init__(self, host: str, port: int, *, proxy: ProxyConfig, timeout: float = 20.0):
        self._gm_proxy = proxy
        super().__init__(host, port, timeout=timeout)

    def _get_socket(self, host, port, timeout):  # noqa: D102 - 覆盖标准库
        return connect_via(self._gm_proxy, host, port, timeout or self.timeout)


class SMTPSSLOverProxy(smtplib.SMTP_SSL):
    """隐式 TLS（通常 465）：CONNECT 打通后立刻对隧道做 TLS。"""

    def __init__(self, host: str, port: int, *, proxy: ProxyConfig,
                 timeout: float = 20.0, context=None):
        self._gm_proxy = proxy
        super().__init__(host, port, timeout=timeout, context=context)

    def _get_socket(self, host, port, timeout):  # noqa: D102 - 覆盖标准库
        raw = connect_via(self._gm_proxy, host, port, timeout or self.timeout)
        try:
            # 用 host（不是 IP）做 SNI 与证书校验，与直连时完全一致
            return self.context.wrap_socket(raw, server_hostname=host)
        except BaseException:
            raw.close()
            raise


def open_smtp(cfg, context, *, timeout: float = 20.0) -> smtplib.SMTP:
    """按 `MailConfig` 建连并完成 EHLO / STARTTLS，返回可直接 login 的对象。

    `cfg.proxy` 为 None 时等价于改造前那两行（直连）。
    """
    if cfg.proxy is None:
        if cfg.security == "ssl":
            return smtplib.SMTP_SSL(cfg.host, cfg.port, timeout=timeout, context=context)
        smtp = smtplib.SMTP(cfg.host, cfg.port, timeout=timeout)
        smtp.ehlo()
        smtp.starttls(context=context)
        return smtp

    if cfg.security == "ssl":
        return SMTPSSLOverProxy(cfg.host, cfg.port, proxy=cfg.proxy, timeout=timeout, context=context)
    smtp = SMTPOverProxy(cfg.host, cfg.port, proxy=cfg.proxy, timeout=timeout)
    smtp.ehlo()
    smtp.starttls(context=context)
    return smtp
