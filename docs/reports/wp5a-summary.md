# WP5a 小结：Android 底座（音频部分）

日期：2026-11-01　分支：`wp5a-android-base`（基于 `wp4-ops`）　实现者：Claude

## 结论先说
代码在沙箱内盲写（无 Gradle/SDK）。**用户在 Mac 上对提交 `0f4dfc3` 的回传结果（2026-11-01，用户口述，未附完整日志）**：JVM 单测 81 项通过；Lint 0 错误、19 警告、3 条信息；debug 构建成功，耗时 1 分 40 秒。release 构建未做；真机项全部未做。

## 做了什么
- 以 GetAudio `dc2abeb` 为底复制 Android 工程，改包名 `com.getmedia.app`，产品名 GetMedia（逐文件见 `wp5-merge-map.md`，来源登记见 `docs/provenance.md`）。
- 剔除账号、平台授权、Agent、云同步、失败上报、服务器设置等全部服务器相关代码与测试；仓库只保留本机提取、导入、剪辑、本机删除/排序。
- `MediaKind { AUDIO, VIDEO }` 与 `TrackEntity.mediaType` 骨架；5a 只写 `AUDIO`。
- Room schema 从 1 起，库名 `getmedia.db`。
- 网络配置只允许 HTTPS。
- `app/build.gradle.kts`：包名取自 `android/app-ids.properties`；版本 0.1.0 / code 1；签名只认 `~/.android-keys/getmedia.properties`，缺失时 release 构建直接失败（`-PallowDebugSignRelease=true` 可绕过，仅限不发布）；去掉源码零引用的依赖。
- 内置壁纸按你的决定先原样带入（14 静态 + 3 动图 + 3 视频，18 MB），公开发布前须替换，见 DECISIONS。

## 沙箱内做过的检查
- `scan_forbidden.py legacy android`、`heavy android/app/src` 通过；`scan_leaks.py android` 通过。
- import 引用图：所有 `com.getmedia.app.*` 的 import 都指得到本仓库里存在的声明（只查 import，不查调用与类型）。

## 构建命令（已由用户执行 debug 部分）
```sh
export JAVA_HOME=<JDK17 路径>
export ANDROID_HOME=<Android SDK 路径>
cd <GetMedia 仓库>
git fetch && git checkout wp5a-android-base
sh android/gradlew -p android testDebugUnitTest lintDebug assembleDebug --console=plain
```
请把完整输出（尤其编译错误）贴回来，我据此修。release 构建需先装好密钥：
`GETMEDIA_KEY_SRC=<本机密钥目录> sh tools/setup-release-key.sh`，再 `assembleRelease`。

## 未验收
- release 构建（需先装签名密钥）：未做。
- 真机：提取（B 站/抖音）、播放、队列拖动、导入、剪辑、背景、冷启动恢复：全部未做。
- 5a 的 GetAudio 单测迁移后哪些因删代码被删，见 merge-map「未带入」。

## 提醒
- 沙箱会话里 `getaudio/` 基线工作区曾有未提交的快照/依赖改动；GetMedia 已有等价实现，已丢弃（补丁备份在会话临时目录，不在仓库）。
