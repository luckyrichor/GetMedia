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

## 当前进度（2026-11-20）

- 阶段 4 已实现。唯一匹配只关联原 Room 行。无匹配项经主动重新下载、校验后可由用户确认上传。冲突和证据不足均保留原样。0.1.10 / versionCode 11 已签名归档；恢复、匹配、直取和重新下载纳入 [整体验收清单](docs/acceptance-report.md)。下一次 full versionCode ≥ 12。
- 阶段 3 已获用户认可，原条目直取通过。仅有元数据的分 P 真机未验，单测已覆盖。网页短链与显式分 P 问题列在 STATUS 待办。
- 0.1.9 / versionCode 10 已签名归档。点云端行按原服务器条目 ID 直取已存文件，元数据或已清理缓存才走带目标的提取。两端核对作品、分 P 和类型，手机核对长度和可用 SHA-256 后只更新原 Room 行。旧调用保持原行为。

- 0.1.8 / versionCode 9 已签名归档并覆盖手机 2，首次安装时间保持不变。导出确认页已区分云端未下载和真实本机文件缺失，混合界面回归通过，真机双云端项新文案已截图后取消。free 320 / full 458 单测通过，双版 Lint 0 错误。C 由用户上传成功；A 的 B 站 P3 触发服务器错误下载 P1，旧云端行仍在，未修改下载代码。抖音本机提取方案待用户决定。详见本地阶段 3 报告。

- 0.1.7 手机 2 追加真机验证：抖音云端行下载因 `platform_needs_phone` 未通过，导出确认页显示通用缺文件提示；公开 B 站 71 秒视频本机提取成功，候选 1 条，停在上传确认。曲库自动同步暂停，服务端仍 7 行。详见阶段 3 报告。

- full 0.1.7 / versionCode 8 修复了首次上传弹窗：返回键及点外部只关闭，明确点「暂不上传」才保存拒绝。owner 账号可在「我的 → 数据备份」使用各账号独立的自动同步开关和手动确认入口。免费版和免费档不显示；手动入口可在自动同步关闭时使用。
- 0.1.7 已签名归档并覆盖安装到手机 2，首次安装时间保持 2026-11-19 19:38:49。冷启动未弹，本机界面显示总计 36 个条目、合格上传候选 0 条；停在曲库同步设置，等待用户。未触碰手机 1。free 320 / full 457 项单测全过，双风味 Lint 0 错误，free 源码与 APK DEX 隔离扫描通过。下一次 full versionCode 至少 9。
- 线上阶段 1 写接口与隐私 v3 已生效（用户确认 release 20261119-2）。用户在安装 0.1.6 前已同意 v3，因此手机 2 拉取符合预期。未同意零请求真机未验，单测已覆盖。详见本地阶段 3 报告。

## 历史进度（2026-11-19）

- 按部署记录，公开页与 owner 管理台已上线。11-19 元数据备份部署为 `20261119-1`（schema 13、隐私 v2），见本地 `docs/metadata-backup-golive.md` §14。
- 批量导出 A/B/C 已交付，模拟器验收见 [导出方案](docs/export-plan.md) §12–13。曲库同步阶段 0–3 已提交至 `e0e3b05`，阶段 1 写入接口和隐私 v3 等待部署，部署前后核对与日期规则见本地 `docs/p0-2-golive.md`。
- 手机 2 的 0.1.5 负向验证在隐私 v2 下拉到了 6 行云端条目，这些行保留。0.1.6 的本地闸同时要求 owner、服务端未要求重新同意且该账号已同意隐私版本至少为 3。v2 或版本未知时拉取和上传均被拦截。部署并核对后再用手机 2 验收。
- 0.1.6 / versionCode 7 已签名归档，证书与 0.1.5 相同。free 320、full 454 项单测全部通过，两种风味 Lint 均为 0 错误、26 警告。PLAN §15-7 记录包哈希与下一次版本下限 8。
- 隐私 v3 定稿在本地 `local-artifacts/legal-v3/privacy-policy.md`，已用服务端解析器验证。部署时按 TX 上的中国日期核对首行，首次加载前调整日期需重新解析并取哈希。

## 历史进度（2026-11-17）

- 补充任务（2026-11-17）：定时倒计时挤掉首页排序控件的缺陷已修复并提交（`15a1481`，`LibraryToolbar` 抽出、定时独立成行、排序+导入改 FlowRow、48 dp 热区），`LibraryToolbarLayoutTest` 10 项在 free/full 通过，真机验收由用户执行；批量导出按 A → B → C 交付，**节点 A 已交付**（`data/export/ExportTrack|ExportNaming|ExportPlan` + 13 个编号用例，free 145 / full 165 单测全过），B/C 未开始。
- UI 方案 v3（`docs/ui-plan.md`）：v2 基础上加一条——free 的「我的」页账号卡片**不显示 tier**，只留设备数，tier 走 `OwnerGate.AccountTierBadge()`（free 空实现）。
- P6 部署材料 v4（本地文件，不入公开仓库）：v3 基础上修掉"会直接踩到"的几处——env 备份改 `sudo sh -c … "$B"` 在 root 下展开通配符并强制校验份数（0 份停手）；快照改 `ssh "sudo cat"` 落到本机并核 sha256（不用 `ssh -t`，PTY 会损坏库文件）；`rsync` 明确在本机执行、加 `--rsync-path="sudo rsync"` 与 `--chown`；**每个 release 独立 venv**（不装进线上共用 venv）；`awk` 长度校验改 `sub()`（base64 末尾 `=` 会让 `-F=` 少 1）；`sed` 只动 api/ai-worker/ai-gc 三单元并全局 grep 旧路径（snapshot 不动，改手动触发验证）；`python -m` 前先 `cd`；回退前先 disable timer/worker、`daemon-reload` 放最后；新增 §3.5 覆盖前 diff 线上 nginx 片段（防抹掉 register 精确 403 热修）；`create-account` 与写 Cookie 改为**你本人 `ssh -t` 执行**、Agent 只做只读核对；退役前置删掉"7 天观察期"。

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

- 2026-11-17：定时倒计时遮挡排序控件的缺陷（三处同源）修复已提交并推送（`15a1481`），工具栏回归 10 项，free/full 单测当轮全过（当前基线 145/165，含导出节点 A 的 16 项）；导出方案按用户 6 点决定修订并确认，节点 A 已交付；`ui-plan.md` 出到 v3；P6 部署材料改到 v4（本地）。未部署、未安装、未退役。

- 2026-11-01：Codex 同步 GitHub wp5a-android-base@0f4dfc3，完成首次本轮 Mac Debug 构建验证，补充 AGENTS/CLAUDE、构建报告和日志，更新 STATUS。没有改业务代码、提交、推送、安装或部署。
