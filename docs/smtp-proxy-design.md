# 反馈邮件走本机代理（FEEDBACK_SMTP_PROXY）设计

日期：2026-11-18　状态：**已实现、已跑单测，未部署、未提交**

## 1. 问题与判据

现象：SMTP 直连 Gmail，465（隐式 TLS）与 587（STARTTLS）都在握手阶段报 `SSLEOF`——
TCP 连上了，TLS 被中间设备重置，不是账号/授权码问题（否则会是 `535`）。

判据：`sudo getmedia-admin mail-test` 从"发送失败：SSLEOFError"变成"发送成功"，且
反馈队列里 `mail_state=pending` 的条目能被正常发走。

## 2. 变量语义

| 值 | 行为 |
|---|---|
| 不写 / 空串 | 直连，与改造前逐行等价 |
| `http://127.0.0.1:7890` | 先 HTTP `CONNECT` 建隧道，再在隧道上做 TLS |
| `127.0.0.1:7890` | 同上（简写，自动补 `http://`） |
| `http://user:pass@host:port` | 同上，附加 `Proxy-Authorization: Basic …`（密码按 URL 规则解码） |
| `socks5://…`、`https://…` | **启动失败**（`ConfigError`），不静默退化成直连 |

只做 HTTP CONNECT 的理由：本机 Clash 一类客户端的 7890 是 mixed 端口，同时讲 HTTP 与 SOCKS5，
一条 CONNECT 就够；SOCKS5 要自己实现握手（约 50 行 + 认证分支），收益为零。
"静默退化"这条是刻意不做的：如果写错协议却照常发信，会变成"以为走了代理、其实还在被墙"。

## 3. 两条路径的时序

**465（`FEEDBACK_SMTP_SECURITY=ssl`）**

```
TCP → 代理 127.0.0.1:7890
→ "CONNECT smtp.gmail.com:465 HTTP/1.1" + Host + [Proxy-Authorization]
← "HTTP/1.1 200 Connection established\r\n\r\n"    ← 逐字节读到这里为止
→ 对这个 socket 做 TLS（server_hostname=smtp.gmail.com）
→ 交回 smtplib：EHLO / AUTH / MAIL / DATA
```

**587（`starttls`）**

```
TCP → 代理 → CONNECT 200 → 明文 socket 交回 smtplib
→ EHLO（拿到 STARTTLS 能力）→ "STARTTLS" → 220 → smtplib 自己 wrap_socket
```

## 4. 实现要点

- **只替换一个方法**：`smtplib` 里建 TCP 的唯一出口是 `_get_socket(host, port, timeout)`。
  两个子类各覆盖它，其余（EHLO、AUTH、DATA、quit、超时、异常类型）全部沿用标准库。
  `SMTP.__init__` 在 `connect()` 之前就设好 `self._host`，所以 465 的 `wrap_socket` 与 587 的
  `starttls()` 都能拿到正确的主机名做 SNI 与证书校验——与直连时完全一致，不是"绕过校验"。
- **CONNECT 响应必须逐字节读到 `\r\n\r\n`**：代理回 200 之后，隧道里的 `220` 问候常常和 200 的响应
  在同一个 TCP 段里。用 `recv(4096)` 会把这几个字节一起吞掉，smtplib 就会一直等问候直到 20 秒超时。
  这是整套改动里唯一"看起来能跑、实际随机挂"的点，单测专门覆盖（假代理把 200 与 220 一次 `sendall`）。
- **失败即关 socket**：CONNECT 被拒、407、响应格式不对、TLS 握手失败，都在抛错前 `close()`，
  不泄漏 fd（服务是长跑进程，泄漏会累积）。
- **错误只带短原因**：`SmtpProxyError.reason ∈ {proxy_connect, proxy_timeout, proxy_auth,
  proxy_refused, proxy_bad_response}`，消息里不含地址与凭据，可以直接进日志和终端。

## 5. 影响面

| 调用方 | 是否受影响 |
|---|---|
| 反馈发信队列 `FeedbackService.process_queue` | 走 `mailer`，自动生效；失败仍按原 `_BACKOFF` 退避（60s→6h） |
| 告警 `MailAlertSink` | 同一个 `mailer`，自动生效（证书续期失败等告警也能发出去） |
| `getmedia-admin mail-test` | 自动生效；失败时多打印 `代理：<reason>`，便于区分"代理不通"与"认证失败" |
| 未填 `FEEDBACK_SMTP_PROXY` 的部署 | 行为不变（单测 `test_direct_path_unchanged` 守住） |

## 6. 安全

- 凭据只在 `ProxyConfig.auth_header` 里，`repr=False`；`MailConfig.proxy` 同样 `repr=False`，
  整个 `Settings` 打印出来也不会出现口令。
- 不新增日志输出；`mail-test` 只多一个短原因码。
- 配置仍只放 `/etc/getmedia/feedback.env`（root:root 600），权限模型不变。
- 前提：`127.0.0.1:7890` 必须对 `getmedia` 用户可达。当前 `getmedia-api.service` 没有
  `PrivateNetwork=true`，`RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX` 不影响本机回环，所以可直接用。
  **若将来加了 `PrivateNetwork=true`，回环会变成私有 netns，这条代理就失效**——届时要么改用 unix socket，
  要么把代理放同一 netns。

## 7. 验收与回滚

在服务器上（你来执行）：

```sh
# 1) 先只验隧道通不通（不经邮件代码，直接看有没有 220 问候）
sudo -u getmedia /opt/getmedia/current/venv/bin/python -c \
 "from getmedia.smtp_proxy import connect_via; from getmedia.config import parse_proxy; \
  s=connect_via(parse_proxy('127.0.0.1:7890'),'smtp.gmail.com',465,10); print(s.recv(120).decode('latin-1','replace').strip())"

# 2) 在 /etc/getmedia/feedback.env 加一行 FEEDBACK_SMTP_PROXY=http://127.0.0.1:7890 后
sudo systemctl restart getmedia-api
sudo getmedia-admin mail-test
```

回滚：删掉 `FEEDBACK_SMTP_PROXY` 那一行 + `systemctl restart getmedia-api`，无需回退代码
（不填就走直连，代码与改造前等价）。

## 8. 未做（明确不做，避免顺手扩大）

- SOCKS5（见 §2）、代理链上再套 TLS（`https://` 代理）。
- 代理探活 / 失败自动回退直连：回退会让"到底走没走代理"变成不可判定，出问题时更难查。
- 按收件域分流（例如 Gmail 走代理、QQ 邮箱直连）：当前只有一个出口，没必要。
