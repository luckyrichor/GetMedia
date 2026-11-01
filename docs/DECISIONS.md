# 决定记录

详见 `docs/PLAN.md` §2。此处只追加计划之后的新决定，每条写日期、内容、原因、谁决定。

- 2026-10-31：免费版也必须注册，公开注册；仅上线前自测期间 `registration_open` 保持关闭（用户确认）。
- 2026-10-31：协议热加载按版本+sha256 绑定：同版本不同哈希拒绝加载、记日志、告警（用户确认）。
- 2026-10-31：owner 是账号等级，不是独立应用；完整版为另一安装包 `com.getmedia.app.full`，任务二实现。
- 2026-10-31：公开仓库的文档不写线上现有服务的弱点（用户要求）；PLAN 已删除相关两处。
- 2026-10-31：签名密钥只在用户 Mac + 用户自己的离线加密备份，不放服务器；`setup-release-key.sh` 改为从本机路径复制（用户确认）。
- 2026-10-31：证书到期告警阈值 24 小时，可配置，上线后按实际续期周期再调（用户确认）。
- 2026-10-31：对外不再用 9443，改走现有 443 的 `/getmedia/` 路径前缀；nginx 剥前缀，片段文件 include 进现有 443 块；Cookie 改 `__Secure-` + `Path=/getmedia/`；不需要防火墙规则（用户决定，取代 PLAN v3 的 9443）。
- 2026-10-31：PLAN v4 生效（用户："没有问题就对着这个方案开发"）。取代上面关于路径的条目：接口为 `/getmedia/api/`（网页 `/getmedia/`），Cookie 为 `__Secure-gm_session`、`Path=/getmedia/api/`；错误码用 `legal_outdated`；登录名先 NFKC 与小写再校验 ASCII；WP5 拆 5a/5b；证书续期任务最终由 GetMedia 维护（用户手动交接，见 PLAN §15-8）。
- 2026-10-31（WP4，Agent 实现中的决定，PLAN 已同步为 v4.1）：
  - nginx 去前缀改用带 URI 的 `proxy_pass`，不再用 `rewrite … break`。原因：真实 nginx 功能测试发现 rewrite 阶段早于 limit_req，`$uri` 被改写后 `$gm_auth_key`（按原始路径匹配）永远不匹配，nginx 层对注册/登录/找回的限流静默失效。
  - 免费路径白名单放在 http 级文件的 `map`，键用整串锚定的正则。字面键会进哈希表，键长超过默认 `map_hash_bucket_size`（`nginx -t` 实测报错）；而该指令在 http 级只能设一次，同机其他项目若也设了会让整个 nginx 配置失败，所以不碰全局指令。
  - `limit_req_status`/`limit_conn_status` 只写在 GetMedia 自己的 location 里，不放 http 级，避免改变同机其他项目的限流状态码（测试守护）。
  - `runtime.json` 放 `/var/lib/getmedia/`（`GETMEDIA_RUNTIME_FILE`），不放 root 只读的 `/etc/getmedia/`：管理 CLI 要同目录写临时文件再原子替换。
  - 管理 CLI 增加 `invite-required on|off`（方案 §8 要求邀请码开关可由 CLI 切换，此前缺失）、`alert KIND`（证书续期脚本失败时发告警）、`mail-test`（邮件通道自检，失败只输出异常类别与 SMTP 数字码）。
  - 证书续期脚本按 certbot 编写，**这是假设**（开发环境看不到服务器上现有续期任务）；交接手册第 0–1 步要求先只读确认，若是别的 ACME 客户端只改 `run_acme` 一个函数。
- 2026-11-01（WP5a，Agent 提议，**待用户确认**）：免费版不带任何内置壁纸与 `assets/backgrounds`（GetAudio 的内置图是游戏角色立绘，公开发布有版权风险）；保留用户自选背景。若要内置，需换成有版权的素材。
- 2026-11-01（WP5a，按 PLAN §12 执行）：Android 本地库 Room schema 从 1 起，不迁移 GetAudio / GetVideo 旧数据；`TrackEntity.mediaType`（`MediaKind`）5a 只写 `AUDIO`；删除失败上报与全部服务器调用代码；依赖去掉 Retrofit / kotlinx-serialization / WorkManager / DataStore / logging-interceptor（源码零引用）。
