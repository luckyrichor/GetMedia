# GetMedia 项目交接

## 项目与工作边界

GetMedia 是新的 Android 音视频提取、管理和播放项目，配套账号/反馈服务端与公开网页计划。具体有效方案是 [docs/PLAN.md](docs/PLAN.md) v4.1；先读该文件、[docs/STATUS.md](docs/STATUS.md)、[docs/DECISIONS.md](docs/DECISIONS.md)。本文件记录接手入口，详细契约仍以 `docs/contracts/` 为准。

- 一切改动在本项目；同级 GetAudio、GetVideo 只读，不改分支、不提交、不推送。
- 未经用户指示，不部署生产、不安装手机、不提交/推送，不修改签名密钥。
- 免费版只保留本地功能，重型能力留到后续 owner 专属模块；不得仅隐藏 UI 而保留重型功能实现。
- 既有 `GetAudio与GetVideo功能清单.md` 是旧项目静态盘点，不能作为 GetMedia 已实现功能说明，也不覆盖新版方案。

## 技术与环境

- Android：Kotlin 1.9.23、AGP 8.3.2、Gradle 8.7、JDK 17、Compose、Room 2.6.1、Media3 1.3.1、OkHttp；compile/target SDK 34，minSdk 26。
- 后端：Python、FastAPI、SQLAlchemy/SQLite，依赖见 `server/requirements.txt`，测试见 `server/tests`。
- 网页：`web/public-app`（公开页，`/getmedia/`）与 `web/owner-app`（管理台，`/getmedia/owner/`）两个独立构建，共用 `web/shared`。P5a/P5b 实现认证壳与公开页，P5c–P5f 接入管理台九个功能页；构建产物隔离由 `tools/tests/test_web_contract.py` 在真实 dist 上断言（公开页零 owner 代码）。
- Mac JDK 17：`/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`；SDK：`/Users/lc/Library/Android/sdk`。
- 后端配置变量、账号规则和部署路径详见方案 §9 与 `deploy/env/*.example`。仅记录变量名及用途，不写入真实凭据。
- Release 固定签名配置从本机 `~/.android-keys/getmedia.properties` 或项目忽略的 `android/keystore.properties` 读取；缺失时 release 构建应失败。Debug 构建不要求 release 密钥。

## 快速上手

```sh
cd /Users/lc/Project/GetMedia
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/Users/lc/Library/Android/sdk

# 首次构建前：准备编译期服务器地址（该文件不入库，仓库只有 .example）
cp android/app-config.properties.example android/app-config.properties
# 然后编辑 serverBase=https://<地址>/getmedia/api
# 缺这一步也能编译，但产物连不上服务器，构建日志会警告

sh android/gradlew -p android testDebugUnitTest lintDebug assembleDebug --console=plain

# 后端开发命令；不是本轮已执行验证
python3 -m venv .venv
.venv/bin/pip install -r server/requirements.txt
.venv/bin/python -m pytest -q server/tests tools/tests

# 仓库提交前扫描要求，详见 docs/DEV.md
python3 tools/scan_leaks.py --all
python3 tools/scan_forbidden.py legacy server android web
python3 tools/scan_forbidden.py heavy android/app/src/main android/app/src/free web/public-app web/shared
```

本轮未运行后端测试，勿把上述命令视为通过证据。部署前阅读 `deploy/DEPLOY.md`，本机不自动执行生产部署。

## 目录与核心流程

- `android/`：单 app 工程；入口 `MainActivity.kt`、`GetMediaApp.kt`；包名取自 `android/app-ids.properties`。
- `android/app/src/main/java/com/getmedia/app/data/localextract/`：分享链接解析、平台直提取、音轨处理。
- `data/local/`：Room 数据库、媒体/类别/播放列表；`data/repo/`：本地文件与库管理。
- `player/`：Media3、队列规则、播放状态持久化；`audioedit/`：本地音频剪辑；`ui/`：Compose 页面与背景。
- WP5a 流程：输入分享链接 → 手机解析与下载音频 → 本地文件/Room 入库 → 播放、分类整理、列表或剪辑；`MediaKind` 已预留 VIDEO，但本分支只写入 AUDIO。
- `server/getmedia/`：账号/配置/等级/反馈/协议/运维服务；入口见 `server/getmedia/main.py` 与 `server/getmedia/api.py`，契约见 `docs/contracts/`。
- `deploy/`：生产部署模板；`tools/`：扫描、签名工具及测试。
- `docs/reports/`：工作包报告；`docs/provenance.md` 与 `docs/reports/wp5-merge-map.md`：上游移植来源。

## 当前进度（2026-11-17）

- 补充任务（2026-11-17）：定时倒计时挤掉首页排序控件的缺陷已修复并提交（`15a1481`，`LibraryToolbar` 抽出、定时独立成行、排序+导入改 FlowRow、48 dp 热区），`LibraryToolbarLayoutTest` 10 项在 free/full 通过，真机验收由用户执行；批量导出按 A → B → C 交付，**节点 A 已交付**（`data/export/ExportTrack|ExportNaming|ExportPlan` + 13 个编号用例，free 145 / full 165 单测全过），B/C 未开始。
- UI 方案 v3（`docs/ui-plan.md`）：v2 基础上加一条——free 的「我的」页账号卡片**不显示 tier**，只留设备数，tier 走 `OwnerGate.AccountTierBadge()`（free 空实现）。
- P6 部署材料 v3（本地文件，不入公开仓库）：密钥只写 `键=值`（`GETMEDIA_SECRET_KEY` 不动、`GETMEDIA_CRED_KEY` 现场生成 44 字符）、`llm.env` 本次不建、`daemon-reload` 后 `restart` 并查 `schema_version=11`/health/journalctl、代码回退必须配库快照且 `/opt/getmedia/server` 保留、冒烟脚本传 `/tmp` 用完删、nginx 脚本插 include、只建 `owner1`/`owner2`、退役三条前置。

- 服务端 P1–P3 完成（迁移工具就绪，真实迁移等部署后）；Android 双风味（free/full）已就绪。
- 网页：公开页接新服务 + owner 认证壳 + 功能页 5c–5f 全部完成，管理台九栏都接真实接口
  （`docs/p5{c,d,e,f}-report.md`）。
- 服务端 P2h（Agent 接模型 + 动作闭环，schema v11）与 P2i（AI 存储归属 + 14 天保留期清理）完成，
  均**未部署**（`docs/p2i-report.md`）。
- 部署材料 P6 已备好但**未部署**；用户明确说"部署"之前不改动服务器。
- Android UI 等 `docs/ui-plan.md` 的 5 个决策。
- 仓库为 public：含真实服务器路径/旧账号片段/nginx 细节的文档不入库（`.gitignore` 末段）。

## 历史进度（2026-11-01）

- 当前分支 `wp5a-android-base`，本轮同步源码提交 `0f4dfc3`，跟踪 `origin/wp5a-android-base`。
- 远端已有 WP0～WP4 代码与文档，其完成记录见 STATUS；本轮没有重新验证这些工作包。
- WP5a 音频 Android 底座由 Claude 实现。本轮 Codex 未改业务代码，按用户指定命令在 Mac 构建：81 项 JVM 单测通过，Lint 0 错误/19 警告/3 信息，Debug 构建成功。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`，`com.getmedia.app`，0.1.0 (1)。完整证据见 [本轮构建报告](docs/reports/wp5a-mac-build-2026-11-01.md)。
- 本轮新增交接文档、构建报告与日志，更新状态说明；未提交、未推送。
- WP5b 并入视频、WP6 客户端账号接入、后续网页及最终验收未在本轮执行，不从其他远端分支推断为当前工作区已完成。

## 已知限制与下一步

1. 用户验收前先检查 81 项单测、Lint 报告及 APK 版本；构建通过不等于真机验收。
2. 后续需真机验证 B 站/抖音提取、播放、拖动、导入、剪辑、背景、冷启动恢复；本轮全部未做。
3. Lint 和编译存在警告，详见报告；没有以升级依赖或改 UI 扩大本轮范围。
4. 本轮未验证 release 签名、服务器服务、证书续期、真实 SMTP 和备份恢复。
5. 内置壁纸公开发布前的替换要求见 DECISIONS，构建通过不代表素材已可发布。
6. 若继续 WP5b，严格按方案及移植映射执行，不改原项目；不要因构建成功自动进入下一工作包。

## 飞书与更新约定

- `docs/PLAN.md` §2 决定 16 明确本任务不做飞书同步，用户另行处理。本轮没有飞书写入或新建目录；尚无 GetMedia 飞书目录/文档链接。
- 后续用户明确恢复飞书同步时，先核对目录、身份和授权范围，再按要求创建及回查，不继承 GetAudio/GetVideo 的发布授权。
- 每阶段更新本交接、STATUS 及对应报告。CLAUDE.md 仅引用本文件，不重复维护正文。

## 更新日志

- 2026-11-17：定时倒计时遮挡排序控件的缺陷（三处同源）修复已提交并推送（`15a1481`），工具栏回归 10 项，free/full 单测当轮全过（当前基线 145/165，含导出节点 A 的 16 项）；导出方案按用户 6 点决定修订并确认，节点 A 已交付；`ui-plan.md` 出到 v3；P6 部署材料改到 v3（本地）。未部署、未安装、未退役。

- 2026-11-01：Codex 同步 GitHub wp5a-android-base@0f4dfc3，完成首次本轮 Mac Debug 构建验证，补充 AGENTS/CLAUDE、构建报告和日志，更新 STATUS。没有改业务代码、提交、推送、安装或部署。
