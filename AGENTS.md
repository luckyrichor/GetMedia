# GetMedia 项目交接

## 项目与工作边界

GetMedia 是新的 Android 音视频提取、管理和播放项目，配套账号/反馈服务端与公开网页计划。具体有效方案是 [docs/PLAN.md](docs/PLAN.md) v4.1；先读该文件、[docs/STATUS.md](docs/STATUS.md)、[docs/DECISIONS.md](docs/DECISIONS.md)。本文件记录接手入口，详细契约仍以 `docs/contracts/` 为准。

- 一切改动在本项目；同级 GetAudio、GetVideo 只读，不改分支、不提交、不推送。
- 未经用户指示，不部署生产、不安装手机、不提交/推送，不修改签名密钥。
- 免费版（`free`）只保留本地功能；云端同步、本机文件归档与重型能力只在完整版（`full` / `admin`）。
  不得仅隐藏 UI 而保留重型功能实现——隔离由 `scan_forbidden.py heavy` 扫 `src/free` 与 `src/main` 兜底。
  注意术语：代码与文档里的「owner」是**通道名**（`/getmedia/owner/`、`owner-app`），
  等级早已改成 `free` / `full`（可有时效）/ `admin`（唯一，永久），不要再把等级写成 owner。
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
- `data/cloud/`：曲库同步（`LibrarySyncEngine` / `LibrarySyncRunner` / `LibrarySyncClient` / 本地删除队列 `LibraryLocalEdits` 与重放 `LibraryRemovalReplay`）。
- `data/backup/`：元数据备份与恢复（`BackupManager`、`SnapshotReader`）。
- `data/download/`：下载中心与 `DownloadProgress`（与上传的 `UploadProgress` 是两个独立槽位，别合并）。
- `data/library/`：`LocalArchiveBackup`（本机导入原件自动上传，退避/冷却/`archiveState`）、`LibraryUploader`、`RepairRules`。
- `data/export/`：批量导出（命名、计划、SAF 落地）。
- `player/`：Media3、队列规则、播放状态持久化；`audioedit/`：本地音频剪辑；`ui/`：Compose 页面与背景。
- 主流程：分享链接或本机文件导入（音频/视频）→ 本地文件 + Room 入库 → 播放、分类整理、播放列表、剪辑；
  完整版另有曲库同步（元数据 + 本机原件归档）与批量导出。
- `server/getmedia/`：账号/配置/等级/反馈/协议/运维服务；入口见 `server/getmedia/main.py` 与 `server/getmedia/api.py`，契约见 `docs/contracts/`。
- `deploy/`：生产部署模板；`tools/`：扫描、签名工具及测试。
- `docs/reports/`：工作包报告；`docs/provenance.md` 与 `docs/reports/wp5-merge-map.md`：上游移植来源。

## 当前进度（2026-11-28）

**接手先看 [docs/HANDOVER.md](docs/HANDOVER.md)**——那份是唯一跟着版本走的现状说明（版本、基线、
部署路径、踩坑、待办）。本节只给一个快照，细节与判据一律以 HANDOVER 为准。

| 项 | 现状 |
|---|---|
| Android | `free` / `full` 两个包，**0.1.48 / versionCode 49**（tag `v0.1.48`），两台手机已 `adb install -r -d` |
| 服务端 | release `20261128-2`，SQLite schema **21**，运行中 |
| 线上协议 | 隐私说明 **v5**、用户协议 **v2**；`LibrarySyncGate.MIN_PRIVACY_FOR_SYNC = 5` |
| 账号等级 | `free` / `full`（可有时效，默认 6 个月）/ `admin`（唯一 `luckyrichor`，永久） |
| 测试基线 | Android **full 692 / free 411**（全 0 failed）、网页 vitest **259**、pytest **544 passed / 50 skipped** |

### 0.1.48 这一版做了什么

1. **删本机导入的条目，云端那份也一起删**——`LibraryLocalEdits.canQueueCloudDelete` 原来最后一句是
   `restoredKey() != null`，而 `restoredIdentity` 只解析 bilibili/douyin，`local` 落到 `else -> null`，
   于是本机导入的条目一条都进不了删除队列（云端行与归档文件原地不动，另一台手机还能下回来）。
   新增 `localIdentity()`，`identityOf = restoredKey() ?: localIdentity()`；**不按 `loc_` 前缀筛**
   （0.1.36 之前的老行 `videoId` 是标题算的），**也不改 `restoredIdentity`**（`RestoredMatcher` 与
   `ServerExtractClient` 也在用）。服务端不用改：`delete_for_sync` 对 `local` 行会 `_unlink` 文件并把
   `media_path/bytes/sha256/storage` 清回 metadata，重放幂等。
2. **量词统一用「个」**——`MediaWording.counted` 去掉 `music` 分支，`deleteOneQuestion` 改「删除这个音频？」，
   界面 10 处「首」→「个」。
3. **网页端上传上限 200 MiB → 1 GB**——`UP_LIMIT = 1024 * 1024 ** 2`、`FILE_LIMIT` 再减 1 MiB。
   原来那道 200 MiB 是前端自己挡的（注释还停在 nginx 210m 的年代）。手机端本来没有检查，
   真边界是 nginx `client_max_body_size 1024m` 与归档池 5 GiB（`ensure_room`）。
4. **413 说人话**——413 只可能来自边缘（应用自己的配额错误是 409 `quota_exceeded`），
   以前落到 `INPUT` 兜底被 `LocalArchiveBackup.permanent()` 判成永久失败，现在单独回
   「文件太大，服务器不接受这么大的上传（单个上限 1 GB）」。

### 更早的里程碑（只记结论，过程见 PLAN §15-7）

- 0.1.44：本机原件上传/下载闭环（nginx 子请求 413、裸 400 误判永久失败、下载进度透传）。
- 0.1.45：改名跨设备同步（`localTitleOverride` 曾经永久挡住服务器的新名字）、「未备份」原因可见。
- 0.1.46：下载可取消（靠写盘循环每 64 KB 查 `cancelled()`，只 cancel 协程停不下来）、草稿基线修正。
- 0.1.47：上传进度条（`UploadProgress` + `ForwardingSink` 只在真写 socket 时回调）。
- 阶段 1–6（0.1.6–0.1.16）：账号、同步写接口、备份恢复、原行匹配、直取、墓碑与条件写。
- 0.1.29–0.1.41：收藏/类别/播放列表跨设备、列表占位行与补绑定、孤儿行对账、`optString` 的 `"null"` 坑。

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

**待办以 [docs/HANDOVER.md](docs/HANDOVER.md) §6 为准**，那里带判据。当前只剩三类：

1. **真机验收 0.1.48 已过（2026-11-28）**：删本机导入条目 → 云端行 `deleted=1` / `storage='metadata'` /
   `media_path=NULL` / `bytes=0`，媒体目录 mtime 与该行 `updated_at` 同秒、盘上文件确实消失、另一台手机同步后看不到；
   「管理云端备份」显示「5 个」。**只剩「网页端真传一个 >200 MB 的文件」没跑过全程**（文案已是 1 GB）。
2. **还没验的零碎**：断网导入时进度停住 → 消失 → 行内「未备份 · …」红字、恢复网络后自动重试。
   批量「下载缺的 N 项」中途取消**已验通过**。
   注意：改了 `web/owner-app` 的源码必须重新 `npm run build:owner` 并 rsync 部署——0.1.48 就漏过一次，
   线上文案还停在旧的 200MB。
3. **管理台两页没在真账号点过**：`/invites` 与「权限管理」（改等级是否踢下线、改有效期是否即时生效）。

几条长期约束，别踩：

- 构建通过 ≠ 真机验收；发版前必须双端 md5/sha256 核对（本地归档 = `build/outputs/apk` = 服务器 `download/`）。
- `versionCode` 必须递增，否则覆盖安装被系统拒绝（当前 49，下次 ≥ 50）。
- 单文件上限 1 GiB 与归档池 5 GiB 是绑在一起的；只改 nginx 会把 413 换成 `quota_exceeded` 409。
- 内置壁纸公开发布前要替换，见 DECISIONS；构建通过不代表素材已可发布。
- 仓库是 public：服务器路径、账号片段、nginx 细节只写 `docs/STATUS.local.md`（不入库）；
  `local-artifacts/` 只进 `.git/info/exclude`。

## 飞书与更新约定

- `docs/PLAN.md` §2 决定 16 明确本任务不做飞书同步，用户另行处理。本轮没有飞书写入或新建目录；尚无 GetMedia 飞书目录/文档链接。
- 后续用户明确恢复飞书同步时，先核对目录、身份和授权范围，再按要求创建及回查，不继承 GetAudio/GetVideo 的发布授权。
- 每次发版更新本文件、[docs/HANDOVER.md](docs/HANDOVER.md)、[docs/STATUS.md](docs/STATUS.md) 与 `docs/PLAN.md` §15-7；
  流水账写 `docs/STATUS.local.md`（本机，不入库）。CLAUDE.md 只有一行 `@AGENTS.md`，不重复维护正文。

## 更新日志

- 2026-11-28：**0.1.48 / vc 49 已发布**（tag `v0.1.48`，GitHub Release 只挂免费版，两台手机已装）。
  本版修掉「手机上删本机导入的条目、云端那份没删」（`localIdentity` 补上 `local` 行的队列身份）、
  量词统一用「个」、网页端上传上限 200 MiB → 1 GB、413 改成说人话。基线 full 692 / free 411 / vitest 260（发版时是 691 / 259，之后补了「墓碑删本机文件」与「上限文案写 1 GB」两个用例）。
  本文件重写「当前进度」「已知限制」两节（原内容停在 0.1.11 / 2026-11-01，已过期），
  并订正「owner 是等级」的旧说法——等级是 free/full/admin，owner 只是通道名。

- 2026-11-17：定时倒计时遮挡排序控件的缺陷（三处同源）修复已提交并推送（`15a1481`），工具栏回归 10 项，free/full 单测当轮全过（当前基线 145/165，含导出节点 A 的 16 项）；导出方案按用户 6 点决定修订并确认，节点 A 已交付；`ui-plan.md` 出到 v3；P6 部署材料改到 v4（本地）。未部署、未安装、未退役。

- 2026-11-01：Codex 同步 GitHub wp5a-android-base@0f4dfc3，完成首次本轮 Mac Debug 构建验证，补充 AGENTS/CLAUDE、构建报告和日志，更新 STATUS。没有改业务代码、提交、推送、安装或部署。
