# 接口清单（任务一：免费档）

> 此处为服务端内部路径；客户端访问时加基址 `https://<服务器>/getmedia/api`（见 conventions.md）。免费档服务端只有下列接口。新增路由默认归 owner，须显式加入免费白名单。

## GET /health
`200 {"ok": true}`。无需登录。

## GET /v2/config
无需登录。返回：`registration_open`（bool）、`invite_required`（bool）、`maintenance`（`null` 或文案）、`min_version`、`latest`（`version`、`notes`、`apk`：GitHub Release 链接、`sha256`）、`server_time`。内容来自 `GETMEDIA_RELEASE_FILE` / `GETMEDIA_RUNTIME_FILE`，改文件无需重启。

## GET /v2/legal
无需登录。返回 `documents`：`user_agreement`、`privacy_policy`，各含 `version`、`effective_date`、`sha256`、`text`。
同版本号不同哈希的文本拒绝加载（日志 + 告警），继续提供首次加载的版本。

## POST /account/register
请求：`login_name`、`password`、`invite_code?`、`age_confirmed`（必须为 true）、`consent`：`{user_agreement:{version,sha256}, privacy_policy:{version,sha256}}`、`client`。
成功 `201`：`session`（`token`、`expires_at`）、`account`（`id`、`login_name`、`tier`）、`recovery_code`（仅此一次）。
失败：`403 registration_closed`（未开放且无有效邀请码）、`409 name_taken`、`422`、`429`；协议版本或哈希与服务端当前不符 → `409 legal_outdated`（客户端刷新文本后重新展示并重新勾选）。

## POST /account/login
请求：`login_name`、`password`、`client`。成功返回 `session`、`account`、`consent_required`（bool）、`recovery_confirmed`（bool）。失败统一 `401 invalid_credentials`。设备数超限 → `409 device_limit`，返回设备列表供选择后重试（带 `evict_device_id`）。

## POST /account/logout
吊销当前会话。`204`。

## GET /account/me
返回 `id`、`login_name`、`tier`、`tier_expires_at`、`consent_required`、`recovery_confirmed`、`status`。

## POST /account/consent
请求同 `register.consent`。记录版本 + 哈希 + 时间。`204`。

## POST /account/recovery/ack
用户确认已保存恢复码。`204`。客户端未确认不得继续使用。

## GET /account/devices · DELETE /account/devices/{id} · POST /account/devices/revoke-others
App ≤ 5 台、网页 ≤ 3 个。设备项：`id`、`channel`、`device`、`app_version`、`created_at`、`last_used_at`、`current`。

## POST /account/password
`old_password`、`new_password`。成功后吊销其他会话。

## POST /account/recover
`login_name`、`recovery_code`、`new_password`。旧恢复码作废并发新码（响应返回一次）；吊销全部会话；同账号每小时 5 次。

## DELETE /account/me
`password`。停用并吊销会话；7 天后物理删除（含反馈文字与图片）；写删除台账。冷静期内仅管理 CLI 可恢复。

## POST /v2/heartbeat
`client`。每端每天最多记一次，多余的静默成功。`204`。

## POST /v2/feedback
`multipart/form-data`：`text`（必填，≤ 2000 字）、`category`、`contact?`（≤ 200，只进正文）、`diagnostics?`（JSON，白名单字段）、`idempotency_key`、`images`（≤ 3，每张 ≤ 4 MB）。
先落库，再异步发信；发信失败不影响响应。返回 `201 {"id": "<uuid>"}`；相同 `idempotency_key` 返回同一结果，不重复入库。
诊断白名单：`app_version`、`os`、`device`、`channel`、`network_type`、`locale`、`last_error_code`。服务端二次过滤，丢弃含 cookie / token / password / Authorization / 带查询串完整 URL 的内容。
