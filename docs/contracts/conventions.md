# 通用约定

## 错误体
`{"detail": "<面向用户的中文文案>", "code": "<稳定的机器码>"}`。`detail` 不得回显原始输入、令牌、异常原文。

## 状态码
| 状态码 | 含义 |
|---|---|
| 200/201 | 成功 |
| 401 | 未登录或会话失效（`code=unauthenticated`） |
| 403 | 等级不足（`code=forbidden_tier`，写审计）；注册未开放（`code=registration_closed`） |
| 404 | 不存在；**他人的资源一律 404**，不区分"不存在"与"无权" |
| 413 | 请求体过大 |
| 422 | 表单校验失败；不回显原始输入 |
| 429 | 限流；带 `Retry-After`（秒） |
| 503 | 维护中 |

## 基址与路径前缀
对外接口基址为 `https://<服务器>/getmedia/api`，网页在 `https://<服务器>/getmedia/`。nginx 去掉 `/getmedia/api` 后转发，**服务端路由不含前缀**，因此 `api.md` 中的路径（`/account/…`、`/v2/…`、`/health`）都是服务端内部路径，客户端访问时加上基址（如 `POST https://<服务器>/getmedia/api/account/login`）。服务端响应不得出现以 `/` 开头的绝对路径跳转。

## 认证
- App：请求头 `X-GetMedia-Session: <token>`。
- 网页：`__Secure-gm_session` Cookie（`Secure; HttpOnly; SameSite=Strict; Path=/getmedia/api/`）；写操作另带自定义头并校验 `Origin`。CSRF 头 `X-GetMedia-CSRF`。
- 同源、不开 CORS。令牌只放头或 Cookie，不放 URL、不进日志。

## 客户端信息 `client`
注册与心跳携带：`channel`（`android`｜`web`）、`app_version`、`os`、`device`（均为短字符串，服务端截断并过滤控制字符）。

## 登录名
先做 NFKC 规范化并转小写，再要求只含 ASCII 字母、数字、下划线、连字符，长度 3–32；不得形似手机号（全数字且长度 ≥ 7 拒绝）。入库同时存规范化形式（小写）并唯一。

## 密码
长度 ≥ 8，≤ 128；不与登录名相同。哈希为 Argon2id，参数写入哈希串。

## 限流（默认，可配置，落库）
登录：每 IP 10 次/5 分钟 + 每登录名失败 5 次/15 分钟；注册：每 IP 5 次/小时；恢复：每账号 5 次/小时；反馈：每账号 5 次/天、每 IP 10 次/天、间隔 ≥ 30 秒。
统一失败文案；对不存在的用户做等价耗时的假哈希。
