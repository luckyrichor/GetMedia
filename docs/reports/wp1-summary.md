# WP1 小结：服务端账号、注册与会话

日期：2026-10-31　分支：`wp1-accounts`　实现者：Claude

## 做了什么
- `server/getmedia/`：配置（只读环境）、SQLite WAL + 版本化迁移（迁移前对已有库做在线快照，失败中止启动）、Argon2id（`cryptography`，参数写入哈希串，并发信号量 2）、登录名/密码校验、落库固定窗口限流、`runtime.json` 热加载（缺失/损坏按"注册关闭"）、协议文件加载（版本 + sha256 绑定）、账号服务、HTTP 层。
- 接口：`/health`、`/v2/legal`、`/account/register|login|logout|me|consent|recovery/ack|devices|devices/{id}|devices/revoke-others|password|recover`。
- 网页渠道：`__Secure-gm_session` Cookie（`Secure; HttpOnly; SameSite=Strict; Path=/getmedia/api/`），响应体不含令牌；Cookie 写操作要求 `X-GetMedia-CSRF` 头且 `Origin == https://<Host>`。
- 通用：错误体不回显输入；`Cache-Control: no-store`；仅当对端为回环时才信任 `X-Forwarded-For`。

## 测试
- `server/tests` 58 项通过（Python 3.13 沙箱）；`tools/tests` 19 项通过；泄露扫描、legacy 禁用词扫描通过。
- 覆盖：注册（关闭/邀请码/重名规范化/手机号形/年龄/协议不符）、登录统一失败 + 恒定做一次哈希、限流（IP、登录名失败、重启不丢、Retry-After）、设备上限（App 5、网页 3、挤掉）、他人设备 404、会话滑动与硬过期、恢复码（旧码与全部会话失效、同账号每小时 5 次）、改密、协议（同版本改文本拒绝并告警、重启后仍然拒绝、版本升级强制重新同意、版本回退拒绝、热加载解析失败保留旧文本、启动时解析失败拒绝启动）、迁移（幂等、快照、失败回滚且不泄露 SQL）。

## 偏离/补充（均已写入契约）
- `legal_versions` 增加 `effective_date` 与 `text`：否则文本被改或重启后无法"继续使用上一份已登记文本"。
- 版本号回退（低于已登记最大版本）与"同版本不同哈希"同样拒绝。
- 登录名先 NFKC + 小写再校验（全角字母会归一到同一账号）。

## 未验证 / 需注意
- Argon2id 在 3.13 沙箱实测约 130 ms；**服务器规格上的耗时未测**，部署前需在服务器上复测并调参（`GETMEDIA_` 参数目前在 `Settings`，WP4 暴露为环境变量）。
- "恒定耗时"只验证了每次登录恰好做一次哈希，没有做统计学计时测试。
- 未测并发注册竞争（依赖 `login_name_norm` 唯一约束兜底，已有 IntegrityError 分支但无专门并发用例）。
- `DELETE /account/me`、管理 CLI、邮件告警、保留期清理在 WP4；`/v2/config`、`/v2/heartbeat`、tier 判权在 WP2。
