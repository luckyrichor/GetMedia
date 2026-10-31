# 服务端数据表与保留期

| 表 | 主要列 | 保留 |
|---|---|---|
| `users` | `id`(`acct_…`)、`login_name`、`login_name_norm`(唯一)、`password_hash`、`tier`、`tier_expires_at`、`status`、`recovery_confirmed_at`、`created_at` | 至注销 |
| `sessions`（即"设备"，`id` 为 `dev_…`；吊销写 `revoked_at`） | token 的 SHA-256、`user_id`、渠道、设备名、版本、创建/最近使用/过期时间、最近 IP | 至过期/吊销 |
| `recovery_codes` | `user_id`、HMAC、`rotated_at` | 至注销 |
| `legal_versions` | 文档名、版本号、sha256、生效日期、**全文 text**、首次加载时间；(文档名,版本号) 唯一。全文用于文本被改而版本号不变时继续提供已登记版本（含重启后） | 长期 |
| `consents` | `user_id`、两份版本号、两份 sha256、时间 | 至注销 |
| `invites` | 码哈希、用途、已用 | 至用完/过期 |
| `audit` | 时间、事件、`user_id`、IP、路径、结果 | 30 天 |
| `rate_limits` | 键、窗口、计数（落库） | 30 天 |
| `feedback` | UUID、`user_id`、`created_at`、`text`、`contact`、`diagnostics_json`、`image_paths`、`status`、`email_attempts`、`idempotency_key` | 文字 180 天、图片 90 天 |
| `heartbeats` | `day`(北京时间)、`session_id`、`user_id`、渠道、版本、系统、设备型号；(day,session_id) 唯一，即每设备每天一条 | 30 天 |
| `daily_stats` | 日期（北京时间）、注册数、活跃数（仅数字） | 长期 |
| `deletion_requests` | `user_id`、请求时间、执行时间 | 执行后清理 |
| `deletion_ledger` | `user_id`、删除时间；只追加；同时写入快照目录之外的文件 | 长期（只存编号和时间） |
| `schema_version` | 版本号、应用时间 | 长期 |

快照：每日一份，保留 7 份，仅本机受限目录，不外传。与隐私说明的保留期必须一致。
