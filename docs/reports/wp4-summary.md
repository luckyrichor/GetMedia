# WP4 小结：服务端运维与 deploy 模板

日期：2026-10-31　分支：`wp4-ops`（基于 `wp3-feedback`）　实现者：Claude（两段接力：先完成服务端运维代码，后补 deploy/ 与手册）

## 做了什么
**服务端运维（先前提交 `a7ad01b`）**：注销与 7 天冷静期、保留期清理、每日快照与 7 份滚动、恢复与删除台账重放、邮件告警（同类每天一封）、管理 CLI。

**本次补完**
- `deploy/` 全部模板：nginx（http 级 `map` 白名单 + 前缀 snippet + 443 块 include 示例）、logrotate、systemd（api 服务、每日快照 service/timer）、env 与 json 示例、`getmedia-admin` 包装、`probe-server.sh`、`verify-deploy.sh`、证书续期脚本与 service/timer 模板（交接前不启用）。
- 手册：`deploy/README.md`、`DEPLOY.md`（对应方案 §15）、`CERT-HANDOVER.md`（证书续期交接与失败处置，对应 §15-8）。
- 服务端补充：CLI 增加 `invite-required on|off`（方案 §8 要求邀请码开关可由 CLI 切换，此前缺失）、`alert`、`mail-test`；Argon2id 的两个参数暴露为环境变量（WP1 小结承诺"WP4 暴露"但此前未落地），带取值范围校验。
- PLAN 升为 v4.1，DECISIONS 记录原因（见下"重要发现"）。

## 重要发现（真实 nginx 测试抓到的）
1. **rewrite 让认证限流静默失效**：`rewrite … break` 发生在 `limit_req` 之前，`$uri` 被改写后，按原始路径匹配的限流键永远不匹配，nginx 层对注册/登录/找回等于没限流。改用带 URI 的 `proxy_pass` 剥前缀；由 `test_auth_rate_limit_returns_429_and_is_scoped` 守护（改回 rewrite 会失败，已做变异验证）。
2. **白名单键过长导致 `nginx -t` 失败**（超过默认 `map_hash_bucket_size`）。常规做法是调大这个全局指令，但它在 http 级只能设一次，同机其他项目若也设了会让整个 nginx 配置失败，所以改用整串锚定的正则键。
3. **`limit_req_status` 不能放 http 级**，否则会改变同机其他项目的限流状态码；只写在 GetMedia 自己的 location 里，有测试验证别的项目仍返回 503。

## 测试
- 全量 **184 项通过**（Python 3.13 沙箱）：`server/tests` 109、`tools/tests` 75（其中 deploy 模板静态 27、真实 nginx 功能 29）。
- nginx 功能测试用 nginx 1.24 + 假上游，在模拟的"共用 443 块"里 include 模板，覆盖：白名单（含方法不对、设备 id 格式、多余斜杠、`%2e%2e` 绕过）、前缀剥离、`X-Forwarded-For` 覆盖而非追加、会话 Cookie/头透传、安全头与无 CORS、请求体 14m/1m 限制、认证与反馈限流（429，且不影响 `/health` 与网页）、静态 SPA 回退与资源缓存、APK 下载不按 html 执行且无目录列表、访问日志不含 Cookie/会话头/Authorization/Referer/查询串、不影响同块内其他项目的路径与限流状态码。
- 证书续期脚本用桩 certbot/nginx/systemctl 跑真实脚本：跳过、成功、演练不改动、强制、ACME 失败/证书未变化/`nginx -t` 失败（均告警且不在配置未通过时 reload）、并发加锁。
- 变异检查：故意破坏模板（删白名单项、加全局指令、日志加 Cookie、少安全头、XFF 改追加、端口不一致、DB URL 写错、续期后不 reload、改回 rewrite），对应测试全部失败，还原后全绿。
- 泄露扫描、legacy 与 heavy 禁用词扫描通过。

## 未验证 / 需要你在服务器上确认
- **证书续期工具是假设**：脚本按 certbot 写，开发环境看不到现有续期任务实际用什么。`CERT-HANDOVER.md` 第 0–1 步要求先只读确认；若是别的 ACME 客户端只改 `run_acme`。`--dry-run` 在短期 IP 证书场景下是否可用也未验证。
- **systemd 单元**只做了 `systemd-analyze verify`（无语法错误；仅提示沙箱里没有对应可执行文件），**没有实际启动**，加固选项（`ProtectSystem=strict`、`StateDirectory` 等）在服务器上的实际效果未验证。
- **`getmedia-admin`**（`systemd-run --pty/--pipe`，需要 systemd ≥ 235）只做了 `sh -n` 语法检查，没有实际运行。
- **`probe-server.sh`、`verify-deploy.sh`** 只做了语法检查与"只读"静态检查，没有在真实服务器上跑过。
- **真实 SMTP** 从未发过信（沙箱无法解析 SMTP 域名）；用 `getmedia-admin mail-test` 在服务器自检。
- nginx 只在 1.24 上测过；服务器 nginx 版本与既有 443 块、是否有 `conf.d/*.conf` include 未知（`probe-server.sh` 会列出）。
- Argon2id 在服务器规格上的耗时未测（环境变量已暴露，部署时调参）。
- 恢复演练、备份流出检查、保留期清理的证据需要在服务器上实际做一次，写进验收报告（方案 §17-9、10）。
- 网页（WP7）未构建，`/getmedia/` 在放入产物前是 404，`verify-deploy.sh after` 里对应一项会失败，属预期。

## 遗留 / 注意
- `getmedia-admin snapshot`（以及快照 service）会构造完整应用，所以**协议文件缺失或解析失败时快照也会失败**（启动即拒绝的同一规则）。这是有意保持的安全行为，但意味着协议文件损坏会同时让快照告警；已在 DEPLOY 里要求协议改版必须同时改版本号。
- 反馈发信队列与保留期清理在进程内运行，所以 `getmedia-api.service` 固定 `--workers 1`（有测试守护）。

## 下一步
WP4 的服务端与模板部分可以收口；剩下是你在服务器上的验证项（见上）。接力方向：
- **WP7（web-public）**：与服务端线互不依赖，契约已冻结，可直接开始；联调依赖 WP1–3（已完成）。
- **WP5a（Android 音频基座）**：需要你在 Mac 上构建、回传结果。
