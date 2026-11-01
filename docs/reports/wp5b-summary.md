# WP5b 小结：并入视频

日期：2026-11-01　分支：`wp5b-video`（基于 `wp5a-android-base`）　实现者：Claude

## 结论先说
代码在沙箱里盲写，已由用户在 Mac 上构建验证（见下）。

## 做了什么
- `DirectExtractor.extract(text, kind)`：`AUDIO` 走原有抽轨；`VIDEO` 下载画面流（B 站另下声音流）后用 `VideoMuxer` 封装成 MP4，存 `files/video/<id>.mp4`，`mediaType=VIDEO`。抖音视频不允许用音乐替代；图文作品选“视频”会明确报错。
- 提取页顶部“音频/视频”选择，单条与批量共用。
- 库页“全部/音频/视频”筛选；手动排序只重排当前可见槽位；视频不显示“剪辑音频”；导出不再限定 `audio/*`。
- 播放条：当前曲目是视频时在上方展开 `VideoPlaybackPanel`（内嵌+全屏），与音频共用同一 Media3 会话；点标题可重新展开。
- 标签“音频库”→“媒体库”。
- 带入 `VideoSourcesTest`（来自 GetVideo）。逐文件取舍见 `wp5-merge-map.md` 的 5b 一节。

## 沙箱内检查
legacy/heavy 禁用词扫描、泄露扫描通过；所有 `com.getmedia.app.*` import 能解析（只查 import）。

## Mac 构建结果（用户回传，提交 `e1258bb`）
- `testDebugUnitTest lintDebug assembleDebug` 一次执行，`BUILD SUCCESSFUL in 33s`，无失败任务。
- 编译警告 4 条：`VideoMuxer.kt:47` 变量 `started` 赋值未使用（GetVideo 原文，无害）；`LibraryScreenV2.kt:626` “检查恒为 true”；`LibraryScreenV2.kt:824` 与 `WallpaperFrameEditor.kt:146` 的图标弃用（沿用 GetAudio）。
- 未回传：单测通过数量、Lint 错误/警告计数（`app/build/reports/` 里有）、release 构建。

## 复现命令
```sh
git fetch && git checkout wp5b-video
sh android/gradlew -p android testDebugUnitTest lintDebug assembleDebug --console=plain
```
把完整输出贴回来。

## 未验收 / 未做
- 单测数量与 Lint 计数：待回传（构建成功即无失败）。
- 真机：B 站/抖音视频提取、封装后有声、播放、全屏与旋转、切后台只剩声音、队列里音视频混排、冷启动恢复视频曲目：全部未做。
- 未做：导入本地视频、视频剪辑、列表封面抽帧接入、清晰度选择。
- 已知风险：`PlaybackService` 切后台时视频画面脱离、声音继续，行为未验证；全屏对话框沿用 GetVideo 的实现，未在本应用里验证。
