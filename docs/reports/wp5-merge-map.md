# WP5 对照表（5a：音频部分）

基线：GetAudio `dc2abeb`（只读）。本表只覆盖 5a；GetVideo 差异与 5b 并入在 5b 开始时补写（§5b 待办）。
**状态：未编译。** 沙箱没有 Gradle/Android SDK，代码为盲写，下列“引用图检查”只证明 import 都能指到本仓库内存在的声明，不代表能编译。

## 统计（以本表为准，取代 PLAN §3 的估算）

- GetAudio android/app/src 文件数：156；GetMedia 5a 现有：88。
- 仅机械改名（包名与产品名）、内容相同：68 个。
- 有实质修改：17 个；新增：3 个；未带入：31 个（40 个壁纸素材已按用户决定带入）。

## 实质修改与新增

| 路径（相对 android/app/src） | 状态 |
|---|---|
| `androidTest/java/com/getmedia/app/BatchDeleteInstrumentation.kt` | 修改 +5/-3 |
| `main/java/com/getmedia/app/data/local/AppDatabase.kt` | 修改 +6/-83 |
| `main/java/com/getmedia/app/data/local/Entities.kt` | 修改 +6/-33 |
| `main/java/com/getmedia/app/data/local/TrackDao.kt` | 修改 +11/-72 |
| `main/java/com/getmedia/app/data/localextract/DirectExtractor.kt` | 修改 +1/-1 |
| `main/java/com/getmedia/app/data/repo/BackgroundStore.kt` | 修改 +4/-28 |
| `main/java/com/getmedia/app/data/repo/CategoryRepository.kt` | 修改 +0/-1 |
| `main/java/com/getmedia/app/data/repo/DiagnosticsStore.kt` | 修改 +1/-2 |
| `main/java/com/getmedia/app/data/repo/TrackRepository.kt` | 修改 +22/-325 |
| `main/java/com/getmedia/app/player/PlayerHolder.kt` | 修改 +0/-7 |
| `main/java/com/getmedia/app/ui/screen/AppearanceScreen.kt` | 修改 +36/-34 |
| `main/java/com/getmedia/app/ui/screen/ExtractScreen.kt` | 修改 +4/-55 |
| `main/java/com/getmedia/app/ui/screen/LibraryScreenV2.kt` | 修改 +2/-26 |
| `main/java/com/getmedia/app/ui/screen/MyScreen.kt` | 修改 +2/-81 |
| `main/res/xml/network_security_config.xml` | 修改 +2/-3 |
| `test/java/com/getmedia/app/data/localextract/BilibiliWarmupTest.kt` | 修改 +1/-1 |
| `test/java/com/getmedia/app/player/QueueRulesTest.kt` | 修改 +0/-2 |
| `main/java/com/getmedia/app/GetMediaApp.kt` | 新增 |
| `main/java/com/getmedia/app/data/local/MediaKind.kt` | 新增 |
| `main/java/com/getmedia/app/data/repo/ErrorText.kt` | 新增 |

修改原因：去掉服务器调用与账号/同步/上报；`TrackEntity` 加 `mediaType`；仓库只保留本机提取、导入、剪辑；网络配置禁明文；测试随被删代码同步调整。逐项原因见提交 `3944764` 及其后的提交。

## 未带入（按 PLAN §12-6 剔除或不属于免费版）

| 路径 | 说明 |
|---|---|
| `androidTest/java/com/getaudio/app/AccountIsolationInstrumentation.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/GetAudioApp.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/local/SyncMerge.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/remote/Dto.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/remote/GetAudioApi.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/repo/AccountScope.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/repo/AccountStore.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/repo/AgentOutbox.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/repo/ApiProvider.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/repo/CredentialMigrationStore.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/repo/FailureOutbox.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/repo/LoginRepository.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/repo/ServerAccessStore.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/repo/ServerConnectionPolicy.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/data/repo/Settings.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/sync/FailureReportWorker.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/sync/SyncWorker.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/ui/screen/AccountLoginDialog.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/ui/screen/AccountScreen.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/ui/screen/AgentScreen.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/ui/screen/LibraryScreen.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/ui/screen/LoginScreen.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/ui/screen/PlatformAccountPanel.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/ui/screen/ServerSettingsDialog.kt` | 服务器/账号/同步/上报相关 |
| `main/java/com/getaudio/app/ui/screen/SettingsScreen.kt` | 服务器/账号/同步/上报相关 |
| `test/java/com/getaudio/app/data/local/SyncMergeTest.kt` | 服务器/账号/同步/上报相关 |
| `test/java/com/getaudio/app/data/repo/AccountScopeTest.kt` | 服务器/账号/同步/上报相关 |
| `test/java/com/getaudio/app/data/repo/AgentProtocolTest.kt` | 服务器/账号/同步/上报相关 |
| `test/java/com/getaudio/app/data/repo/FailureOutboxTest.kt` | 服务器/账号/同步/上报相关 |
| `test/java/com/getaudio/app/data/repo/ServerAccessStoreTest.kt` | 服务器/账号/同步/上报相关 |
| `test/java/com/getaudio/app/data/repo/ServerConnectionPolicyTest.kt` | 服务器/账号/同步/上报相关 |

内置壁纸素材已按用户决定带入（见 DECISIONS），公开发布前须替换。

## 5a 做出的取舍

- `MediaKind { AUDIO, VIDEO }` 与 `mediaType` 列已就位，但 5a 只写入 `AUDIO`；库页“全部/音频/视频”筛选放 5b。
- Room schema 从 1 起，不迁移 GetAudio / GetVideo 旧数据（两个旧应用不同包名，数据本就不共享）。
- 依赖：删去 Retrofit、kotlinx-serialization（及插件）、WorkManager、DataStore、OkHttp logging-interceptor（源码零引用）；保留 OkHttp（`DirectExtractor` 用）。
- 包名来自 `android/app-ids.properties`；`versionName 0.1.0` / `versionCode 1`；签名只认 `~/.android-keys/getmedia.properties`，缺失则 release 构建失败。
- 失败上报（FailureOutbox）整体删除：免费版没有服务器直连，且属于重型标识。

## 检查（沙箱内能做的）

- `tools/scan_forbidden.py legacy android`、`heavy android/app/src`：通过。
- `tools/scan_leaks.py android`：通过。
- import 引用图：所有 `com.getmedia.app.*` import 均能解析到本仓库内的声明。

## 5b 待办

- 对照 GetVideo `3dccb1c`：`VideoMuxer`/`VideoSources`、`VideoPlaybackPanel`/`VideoSurface`、视频剪辑差异，逐文件补入本表。
