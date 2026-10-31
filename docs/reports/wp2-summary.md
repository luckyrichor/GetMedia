# WP2 小结：tier 判权、审计、config、心跳

日期：2026-10-31　分支：`wp2-tier`（基于 `wp1-accounts`）

## 做了什么
- `tiers.py`：免费白名单 `FREE_ROUTES`；`require_tier(...)` 依赖（等级不符 → 403 `forbidden_tier` 并写审计）；`unprotected_routes()` 遍历路由——**不在白名单、又没挂 owner 级依赖的路由会使应用拒绝启动**，测试里再查一遍。`tier_expires_at` 过期按 free 处理。
- `GET /v2/config`：`registration_open`、`invite_required`、`maintenance`、`min_version`、`latest{version,notes,apk,sha256}`、`server_time`；来自 `runtime.json` / `release.json`，改文件即生效。`release.json` 严格校验：APK 链接必须 `https://`，版本号/sha256 格式不符的字段丢弃，文件损坏按空处理。
- `POST /v2/heartbeat`（需登录）：每设备（会话）每天最多一条；同账号当天首次才计入活跃数；统计日按北京时间。`register` 同时累加 `daily_stats.registrations`。迁移 v2 新增 `heartbeats`（30 天保留）与 `daily_stats`（长期、仅数字）。

## 测试
- `server/tests` 76 项 + `tools/tests` 19 项，共 95 项通过（Python 3.13 沙箱）。
- 覆盖：路由遍历（干净、检测未登记路由、拒绝含 free 的依赖、接受 owner 依赖）；free 访问 owner 测试路由 403 + 审计；匿名 401；owner 放行；owner 过期降为 free；未登记路由使启动失败；config 缺文件/热加载/不安全字段/损坏文件；心跳去重、跨日、字符串清洗。

## 未验证 / 说明
- 仅有测试用的 owner 路由，没有真实 owner 功能（任务二）。
- `heartbeats` 与 `audit`、`rate_limits` 的保留期清理在 WP4 实现；目前只建表。
- 白名单里 `POST /v2/feedback`、`DELETE /account/me` 尚未实现（WP3、WP4）。
