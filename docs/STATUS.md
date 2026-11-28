# 状态

最后更新：2026-11-28

> **这份文件是流水账式的里程碑记录。** 想知道「现在是什么版本、下一步做什么」，
> 看 [HANDOVER.md](HANDOVER.md)——那份跟着版本走。下面是本文件里最新的几段。

## 当前状态（2026-11-28）

| 项 | 现状 |
|---|---|
| Android | `free` / `full` **0.1.48 / versionCode 49**（tag `v0.1.48`），两台手机已装 |
| 服务端 | release `20261128-2`，schema **21**，无待部署改动 |
| 线上协议 | 隐私 **v5**、用户协议 **v2** |
| 测试基线 | Android full **691** / free **411**（0 failed）、vitest **259**、pytest **544 passed / 50 skipped** |

0.1.48 修的是「手机上删掉本机导入的条目、云端那份没删」——`LibraryLocalEdits.canQueueCloudDelete`
最后一句是 `restoredKey() != null`，而 `restoredIdentity` 对 `local` 平台返回 null，于是本机导入的条目
一条都进不了删除队列。新增 `localIdentity()` 后走通，服务端 `delete_for_sync` 本来就支持 `local` 行
（`_unlink` + 四个字段清回 metadata）。同版把量词统一成「个」、网页端上传上限从 200 MiB 放到 1 GB、
413 改成说人话。细节见 `PLAN.md` §15-7。

## 已完成的旧待办（2026-11-20 那张表的去向）

| 原待办 | 结论 |
|---|---|
| 会话 Cookie 路径由部署配置提供 | **已完成**（0.1.38）：`GETMEDIA_COOKIE_PATH`，删掉 nginx 两处 `proxy_cookie_path` |
| 部署探测检查证书有效期 | **已完成**：`ops.py` 报 `cert_expiring`，`smoke_deploy.py` 输出 `days_left` |
| owner 网页展开 B 站 `b23.tv` 短链 | **已完成**（0.1.38）：`creator_links.py` 白名单主机、仅 http/https 80/443、最多 5 跳 |
| owner 网页保留 B 站 `?p=N` | **已完成**（0.1.38）：短链展开后保留 `?p=N` |
| 阶段 5 服务端小补丁部署后真机验收 | **已完成**：0.1.45 / 0.1.46 两轮真机验过条件写、墓碑与改名 |

## 2026-11-20 曲库同步阶段 5

Android full 0.1.11 / versionCode 12 已实现删除同步与标题推送。服务端新增手机专用条件 `DELETE /v2/sync/tracks/{id}` 与 `PATCH /v2/sync/tracks/{id}`（`If-Match` 必填，事务内校验账号/版本/存储类型，archive 拒绝写墓碑，旧网页接口行为不变）；**未部署**。手机端删除只对已绑定的非归档行入队，Room v3→v4 新增版本/世代/状态与墓碑待确认表；重放支持离线持久化、退避、幂等、先删后加、迟到旧响应与归档保护；墓碑只在批量确认里默认「保留本机文件」，任何自动路径都不删本机文件；绑定行改名只写待推送草稿，条件 PATCH 以服务器为准，备份恢复不生成 PATCH。单测 free 326 / full 508 通过，Lint 双风味 0 错误 / 26 警告；pytest 408 passed / 50 skipped。APK 已签名归档，未安装、未部署；真机验收等服务端部署后另行批准。

## 2026-11-20 曲库同步阶段 4

Android full 0.1.10 / versionCode 11 实现备份恢复项原行关联及缺文件恢复。升级和备份预览已验证。恢复、匹配、直取与重新下载纳入整体真机验收；Android 单测已覆盖。下一次 full versionCode ≥ 12。

## 2026-11-17 补充任务（批量导出 + 定时倒计时缺陷）

| 事项 | 状态 | 证据 |
|---|---|---|
| 定时倒计时挤掉排序控件（缺陷） | **已修复并提交**（`15a1481`）；free/full 单测当轮全过（工具栏回归 10 项）；真机验收由用户执行 | `docs/timer-bug-report.md`、`docs/reports/timer-bug-shots/` |
| 批量导出 · 节点 A（纯逻辑 + 单测） | **已交付、未提交**：`ExportTrack` / `ExportNaming` / `ExportPlan` + 两个测试类（16 个方法，覆盖 §9.1 的 1–13），含整名 240 字节上限；free 145 项 / full 165 项单测全过 | `data/export/*`、`test/.../data/export/*` |
| 批量导出 · 节点 B / C | 未开始（B：确认页与 SAF；C：前台服务与收口） | `docs/export-plan.md` §10 |
| Android UI 方案 v3（P4a） | v2 + free 的「我的」页账号卡片不显示 tier（`OwnerGate.AccountTierBadge` 空实现），仅 full 显示 | `docs/ui-plan.md` |
| P6 部署材料 v3 | 已准备部署、验证与回滚步骤 | 部署记录在本地维护 |

## 2026-11-17 阶段进度（P1–P5f + P2h + P2i）

| 阶段 | 状态 | 证据 |
|---|---|---|
| P1 双风味与 owner 模块拆分 | 完成（未提交本轮之外的改动） | `docs/p1-evidence-2026-11-16.md` |
| P2 服务端功能合并（媒体/批量/定时/Agent/授权/上报/AI） | 完成 | `docs/p2-report.md`、`docs/p2-api-mapping.md`、`docs/p2-acceptance-evidence.md` |
| P2h Agent 接模型（方案 B）+ 动作闭环 | 服务端完成（schema v11）；**未部署、未用真实密钥调用** | `docs/agent-model-plan.md` §0 |
| P2i AI 存储归属 + 保留期清理 | 完成（未改 schema）：AI 计入服务器库 3 GiB、保留 14 天、超限 409、`ai-gc` + 每天 04:10 timer；**未部署** | `docs/p2i-report.md`、`docs/p2-report.md` §8.1 |
| P3 旧库迁移工具 | 工具与演练完成；**执行手册已写（2026-11-17），待用户审**；真实迁移需先定账号映射与时间窗 | `docs/p3-report.md`、本地 `docs/p3-migration-runbook.md` |
| P4a UI 方案 | 完成，等用户确认 5 个决策后才写 UI | `docs/ui-plan.md` |
| P5a web-public 接新服务 | 完成 | `docs/p5-web-report.md` |
| P5b owner 认证壳 | 完成 | `docs/p5-web-report.md` |
| P5c 反馈收件箱 + 媒体库与播放 | 完成（含收件箱读接口 4 条） | `docs/p5c-report.md` |
| P5d 提取 / 批量 / 定时 / Agent | 完成（未新增服务端接口） | `docs/p5d-report.md` |
| P5e 平台授权 + 库维护 | 完成（未新增服务端接口） | `docs/p5e-report.md` |
| P5f AI 视频重建 | 完成（未新增服务端接口）；九栏全部接真实页面 | `docs/p5f-report.md` |
| P6 部署 | 公开页与管理台已上线；部署和联调细节在本地维护 | — |

> 说明：含真实服务器路径、旧账号片段、nginx 细节的文档只留本地，见 `.gitignore` 末段。
>
> **P2i 已实施**。AI 文件按保留期清理，元数据按既有规则保留。部署记录在本地维护。

> 批量条目支持直接下载到本机。
> `GET /v2/creators/jobs/{job}/items/{video_id}/file` 按附件转发（B 站无直链时回退现解析）。

## 工作包（历史）

| 工作包 | 状态 | 分支 |
|---|---|---|
| WP0 脚手架、契约、扫描工具、协议种子 | 完成（已推送） | `wp0-scaffold` |
| WP1 账号/注册/会话/同意/协议 | 完成（已推送） | `wp1-accounts` |
| WP2 tier/审计/配置/心跳 | 完成（已推送） | `wp2-tier` |
| WP3 反馈 | 完成（已推送） | `wp3-feedback` |
| WP4 运维：CLI/注销/快照/告警/部署模板 | 完成（已推送）；**服务器侧验证项由用户执行**，见 `docs/reports/wp4-summary.md`（证书续期工具为假设、systemd 实际启动、真实 SMTP、恢复演练等未验证） | `wp4-ops` |
| WP5a Android 底座（音频部分） | Mac 回传（提交 `0f4dfc3`）：JVM 单测 81 项通过；Lint 0 错误 / 19 警告 / 3 信息；debug 构建成功（1 分 40 秒）。release 构建、真机均未验。见 `docs/reports/wp5a-summary.md` | `wp5a-android-base` |
| WP5b 并入视频 | Mac 回传（提交 `e1258bb`）：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 一次构建全部成功（33 秒，无失败任务）；输出未含单测数量与 Lint 计数；release 与真机未验。见 `docs/reports/wp5b-summary.md` | `wp5b-video`（基于 wp5a） |
| WP6 Android free | 代码完成。Mac 回传（提交 `cef7bd9`）：`testFreeDebugUnitTest`、`lintFreeDebug`、`assembleFreeDebug` 一次构建全部成功（58 秒）；输出未含单测数量与 Lint 计数。free debug APK：包名 `com.getmedia.app`、无明文流量声明、禁用词(legacy)与泄露扫描通过，heavy 扫描仅 AndroidX 的 `Recreation` 误报（词表已收窄）；`assembleFreeRelease` 成功（1 分 10 秒）；解压 release APK 后 heavy / legacy / 泄露扫描均通过（465 个文件）；真机与服务端联调未验。见 `docs/reports/wp6-summary.md` | `wp6-free`（基于 wp5b） |
| WP7 web-public | 已完成于 `wp7-web-public`；**已作为 P5a 的基础合入 `wp6-free` 并按新服务契约校正**（见 `docs/p5-web-report.md`） | `wp7-web-public` |
| WP8 验收报告 | 未开始 | |

未验收内容、阻塞项与需要用户操作的事项在各 `docs/reports/wp*-summary.md` 中列出。
