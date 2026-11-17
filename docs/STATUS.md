# 状态

最后更新：2026-11-17

## 2026-11-17 补充任务（批量导出 + 定时倒计时缺陷）

| 事项 | 状态 | 证据 |
|---|---|---|
| 定时倒计时挤掉排序控件（缺陷） | **已修复并提交**（`15a1481`）；free/full 单测当轮全过（工具栏回归 10 项）；真机验收由用户执行 | `docs/timer-bug-report.md`、`docs/reports/timer-bug-shots/` |
| 批量导出 · 节点 A（纯逻辑 + 单测） | **已交付、未提交**：`ExportTrack` / `ExportNaming` / `ExportPlan` + 两个测试类（16 个方法，覆盖 §9.1 的 1–13），含整名 240 字节上限；free 145 项 / full 165 项单测全过 | `data/export/*`、`test/.../data/export/*` |
| 批量导出 · 节点 B / C | 未开始（B：确认页与 SAF；C：前台服务与收口） | `docs/export-plan.md` §10 |
| Android UI 方案 v3（P4a） | v2 + free 的「我的」页账号卡片不显示 tier（`OwnerGate.AccountTierBadge` 空实现），仅 full 显示 | `docs/ui-plan.md` |
| P6 部署材料 v3 | 密钥只写 `键=值`、llm.env 本次不建、daemon-reload 后 restart 并查 schema=11、代码回退必须配库快照、冒烟脚本临时目录、nginx 脚本插 include、只建 owner1/owner2、退役三条前置 | 本地 `docs/p6-deploy-plan.md`（不入公开仓库） |

## 2026-11-17 阶段进度（P1–P5f + P2h + P2i）

| 阶段 | 状态 | 证据 |
|---|---|---|
| P1 双风味与 owner 模块拆分 | 完成（未提交本轮之外的改动） | `docs/p1-evidence-2026-11-16.md` |
| P2 服务端功能合并（媒体/批量/定时/Agent/授权/上报/AI） | 完成 | `docs/p2-report.md`、`docs/p2-api-mapping.md`、`docs/p2-acceptance-evidence.md` |
| P2h Agent 接模型（方案 B）+ 动作闭环 | 服务端完成（schema v11）；**未部署、未用真实密钥调用** | `docs/agent-model-plan.md` §0 |
| P2i AI 存储归属 + 保留期清理 | 完成（未改 schema）：AI 计入服务器库 3 GiB、保留 14 天、超限 409、`ai-gc` + 每天 04:10 timer；**未部署** | `docs/p2i-report.md`、`docs/p2-report.md` §8.1 |
| P3 旧库迁移工具 | 工具与演练完成；**真实迁移等 P2 部署后、用户确认窗口** | `docs/p3-report.md` |
| P4a UI 方案 | 完成，等用户确认 5 个决策后才写 UI | `docs/ui-plan.md` |
| P5a web-public 接新服务 | 完成 | `docs/p5-web-report.md` |
| P5b owner 认证壳 | 完成 | `docs/p5-web-report.md` |
| P5c 反馈收件箱 + 媒体库与播放 | 完成（含收件箱读接口 4 条） | `docs/p5c-report.md` |
| P5d 提取 / 批量 / 定时 / Agent | 完成（未新增服务端接口） | `docs/p5d-report.md` |
| P5e 平台授权 + 库维护 | 完成（未新增服务端接口） | `docs/p5e-report.md` |
| P5f AI 视频重建 | 完成（未新增服务端接口）；九栏全部接真实页面 | `docs/p5f-report.md` |
| P6 部署材料 | 材料完成，**未部署**；等用户说"部署"。含只读冒烟脚本 `tools/smoke_deploy.py`（先不执行）与 AI 清理 timer | 本地 `docs/p6-deploy-plan.md`（不入公开仓库） |

> 说明：含真实服务器路径、旧账号片段、nginx 细节的文档只留本地，见 `.gitignore` 末段。
>
> **P2i 已实施**（2026-11-17）：AI 素材与产物计入服务器库 3 GiB，保留期 14 天，`getmedia-ai-gc.timer` 每天 04:10 清理过期文件（保留记录）。部署这件事本身仍等 P6，见 `docs/p2i-report.md`。

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
