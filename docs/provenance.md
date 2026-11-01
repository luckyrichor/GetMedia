# 来源登记

GetMedia 由 GetAudio 与 GetVideo 复制改名而来，不用子模块；基线之后上游的变动不自动同步。

| 项目 | 基线提交 |
|---|---|
| GetAudio | `dc2abeb0cc6cafe1a1bb7c5823835ee8a8a9ff9f` |
| GetVideo | `3dccb1ccfb5b42ab98cd96765df29fa9f1ef195c` |

## 文件登记

每复制一个文件追加一行：来源文件 | 上游项目@提交 | 本仓库路径 | 修改内容与原因。

| 来源文件 | 上游 | 本仓库路径 | 修改与原因 |
|---|---|---|---|
| docs 协议文本（用户提供） | 用户 | `docs/legal/user-agreement.md`、`docs/legal/privacy-policy.md` | 产品名 GetAudio→GetMedia；其余原文 |
| 以下 5a 复制，源路径把 `com/getmedia/app` 对应为 GetAudio 的 `com/getaudio/app` | | | |
| `android/app/src/androidTest/java/com/getaudio/app/BatchDeleteInstrumentation.kt` | GetAudio@dc2abeb | `android/app/src/androidTest/java/com/getmedia/app/BatchDeleteInstrumentation.kt` | 改名；修改 +5/-3（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/androidTest/java/com/getaudio/app/FirstPlaybackInstrumentation.kt` | GetAudio@dc2abeb | `android/app/src/androidTest/java/com/getmedia/app/FirstPlaybackInstrumentation.kt` | 包名与产品名改名 |
| `android/app/src/debug/java/com/getaudio/app/ui/screen/ExtractionDiagnostics.kt` | GetAudio@dc2abeb | `android/app/src/debug/java/com/getmedia/app/ui/screen/ExtractionDiagnostics.kt` | 包名与产品名改名 |
| `android/app/src/main/AndroidManifest.xml` | GetAudio@dc2abeb | `android/app/src/main/AndroidManifest.xml` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/MainActivity.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/MainActivity.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/audioedit/AudioEditor.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/audioedit/AudioEditor.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/audioedit/EditRules.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/audioedit/EditRules.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/local/AppDatabase.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/local/AppDatabase.kt` | 改名；修改 +6/-83（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/data/local/CategoryDao.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/local/CategoryDao.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/local/Entities.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/local/Entities.kt` | 改名；修改 +6/-33（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/data/local/PlaylistDao.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/local/PlaylistDao.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/local/TrackDao.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/local/TrackDao.kt` | 改名；修改 +11/-72（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/data/localextract/AudioPriming.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/localextract/AudioPriming.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/localextract/BilibiliBrowserAudio.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/localextract/BilibiliBrowserAudio.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/localextract/BilibiliWarmup.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/localextract/BilibiliWarmup.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/localextract/DirectExtractor.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/localextract/DirectExtractor.kt` | 改名；修改 +1/-1（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/data/localextract/DirectLink.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/localextract/DirectLink.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/localextract/DouyinBrowserDetail.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/localextract/DouyinBrowserDetail.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/localextract/DouyinNoteAudio.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/localextract/DouyinNoteAudio.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/localextract/DouyinSharePageParser.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/localextract/DouyinSharePageParser.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/localextract/LinkScanner.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/localextract/LinkScanner.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/localextract/MuxerSampleFlags.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/localextract/MuxerSampleFlags.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/repo/BackgroundStore.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/repo/BackgroundStore.kt` | 改名；修改 +4/-28（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/data/repo/CategoryRepository.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/repo/CategoryRepository.kt` | 改名；修改 +0/-1（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/data/repo/DiagnosticArchive.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/repo/DiagnosticArchive.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/repo/DiagnosticsStore.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/repo/DiagnosticsStore.kt` | 改名；修改 +1/-2（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/data/repo/PendingNotice.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/repo/PendingNotice.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/repo/PlaylistNames.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/repo/PlaylistNames.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/repo/PlaylistRepository.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/repo/PlaylistRepository.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/repo/TrackRepository.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/repo/TrackRepository.kt` | 改名；修改 +22/-325（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/data/repo/WallpaperFrame.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/repo/WallpaperFrame.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/data/repo/WaveformCache.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/data/repo/WaveformCache.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/player/PlaybackMemory.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/player/PlaybackMemory.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/player/PlaybackRules.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/player/PlaybackRules.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/player/PlaybackService.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/player/PlaybackService.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/player/PlaybackSnapshot.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/player/PlaybackSnapshot.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/player/PlayerHolder.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/player/PlayerHolder.kt` | 改名；修改 +0/-7（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/player/QueueReorder.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/player/QueueReorder.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/player/QueueRules.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/player/QueueRules.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/Format.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/Format.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/components/BackgroundLayer.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/components/BackgroundLayer.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/components/MotionBackground.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/components/MotionBackground.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/components/UiKit.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/components/UiKit.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/components/WallpaperView.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/components/WallpaperView.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/ActionRegions.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/ActionRegions.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/AppearanceScreen.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/AppearanceScreen.kt` | 改名；修改 +36/-34（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/ui/screen/AudioEditScreen.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/AudioEditScreen.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/DateTimeRangeDialog.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/DateTimeRangeDialog.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/ExtractScreen.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/ExtractScreen.kt` | 改名；修改 +4/-55（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/ui/screen/LibraryScreenV2.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/LibraryScreenV2.kt` | 改名；修改 +2/-26（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/ui/screen/MusicMenuElements.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/MusicMenuElements.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/MusicSheet.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/MusicSheet.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/MyScreen.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/MyScreen.kt` | 改名；修改 +2/-81（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/main/java/com/getaudio/app/ui/screen/PlaybackModeDialog.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/PlaybackModeDialog.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/PlaybackProgress.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/PlaybackProgress.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/QueueBar.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/QueueBar.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/ReorderableQueue.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/ReorderableQueue.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/TimerStatus.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/TimerStatus.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/screen/WallpaperFrameEditor.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/screen/WallpaperFrameEditor.kt` | 包名与产品名改名 |
| `android/app/src/main/java/com/getaudio/app/ui/theme/Theme.kt` | GetAudio@dc2abeb | `android/app/src/main/java/com/getmedia/app/ui/theme/Theme.kt` | 包名与产品名改名 |
| `android/app/src/main/res/drawable/ic_launcher_foreground.xml` | GetAudio@dc2abeb | `android/app/src/main/res/drawable/ic_launcher_foreground.xml` | 包名与产品名改名 |
| `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | GetAudio@dc2abeb | `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 包名与产品名改名 |
| `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | GetAudio@dc2abeb | `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | 包名与产品名改名 |
| `android/app/src/main/res/values/ic_launcher_background.xml` | GetAudio@dc2abeb | `android/app/src/main/res/values/ic_launcher_background.xml` | 包名与产品名改名 |
| `android/app/src/main/res/values/strings.xml` | GetAudio@dc2abeb | `android/app/src/main/res/values/strings.xml` | 包名与产品名改名 |
| `android/app/src/main/res/values/themes.xml` | GetAudio@dc2abeb | `android/app/src/main/res/values/themes.xml` | 包名与产品名改名 |
| `android/app/src/main/res/xml/network_security_config.xml` | GetAudio@dc2abeb | `android/app/src/main/res/xml/network_security_config.xml` | 改名；修改 +2/-3（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/release/java/com/getaudio/app/ui/screen/ExtractionDiagnostics.kt` | GetAudio@dc2abeb | `android/app/src/release/java/com/getmedia/app/ui/screen/ExtractionDiagnostics.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/audioedit/EditRulesTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/audioedit/EditRulesTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/data/WallpaperFrameTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/data/WallpaperFrameTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/data/localextract/AudioPrimingTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/data/localextract/AudioPrimingTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/data/localextract/BilibiliWarmupTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/data/localextract/BilibiliWarmupTest.kt` | 改名；修改 +1/-1（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/test/java/com/getaudio/app/data/localextract/DirectLinkTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/data/localextract/DirectLinkTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/data/localextract/DouyinNoteAudioTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/data/localextract/DouyinNoteAudioTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/data/localextract/DouyinSharePageParserTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/data/localextract/DouyinSharePageParserTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/data/localextract/MuxerSampleFlagsTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/data/localextract/MuxerSampleFlagsTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/data/repo/DiagnosticArchiveTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/data/repo/DiagnosticArchiveTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/data/repo/PendingNoticeTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/data/repo/PendingNoticeTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/data/repo/PlaylistNamesTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/data/repo/PlaylistNamesTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/player/PlaybackRulesTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/player/PlaybackRulesTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/player/PlaybackSnapshotTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/player/PlaybackSnapshotTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/player/QueueDeletionTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/player/QueueDeletionTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/player/QueueReorderTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/player/QueueReorderTest.kt` | 包名与产品名改名 |
| `android/app/src/test/java/com/getaudio/app/player/QueueRulesTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/player/QueueRulesTest.kt` | 改名；修改 +0/-2（去服务器/账号代码，详见 wp5-merge-map） |
| `android/app/src/test/java/com/getaudio/app/ui/QueueDragStepTest.kt` | GetAudio@dc2abeb | `android/app/src/test/java/com/getmedia/app/ui/QueueDragStepTest.kt` | 包名与产品名改名 |
| `android/build.gradle.kts` | GetAudio@dc2abeb | `android/build.gradle.kts` | 改名；build.gradle.kts 另改包名常量/签名/版本/依赖 |
| `android/settings.gradle.kts` | GetAudio@dc2abeb | `android/settings.gradle.kts` | 改名；build.gradle.kts 另改包名常量/签名/版本/依赖 |
| `android/gradle.properties` | GetAudio@dc2abeb | `android/gradle.properties` | 改名；build.gradle.kts 另改包名常量/签名/版本/依赖 |
| `android/gradlew` | GetAudio@dc2abeb | `android/gradlew` | 改名；build.gradle.kts 另改包名常量/签名/版本/依赖 |
| `android/gradlew.bat` | GetAudio@dc2abeb | `android/gradlew.bat` | 改名；build.gradle.kts 另改包名常量/签名/版本/依赖 |
| `android/gradle/wrapper/gradle-wrapper.properties` | GetAudio@dc2abeb | `android/gradle/wrapper/gradle-wrapper.properties` | 改名；build.gradle.kts 另改包名常量/签名/版本/依赖 |
| `android/gradle/wrapper/gradle-wrapper.jar` | GetAudio@dc2abeb | `android/gradle/wrapper/gradle-wrapper.jar` | 改名；build.gradle.kts 另改包名常量/签名/版本/依赖 |
| `android/app/build.gradle.kts` | GetAudio@dc2abeb | `android/app/build.gradle.kts` | 改名；build.gradle.kts 另改包名常量/签名/版本/依赖 |
| `android/app/src/main/assets/backgrounds/motion/furina.webp` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/motion/furina.webp` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/motion/hutao.webp` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/motion/hutao.webp` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/motion/lauma.webp` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/motion/lauma.webp` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/furina.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/furina.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/furina_art.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/furina_art.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/hutao.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/hutao.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/ineffa.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/ineffa.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/lauma.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/lauma.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/nahida.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/nahida.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/nefer.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/nefer.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/nicole.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/nicole.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/odette.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/odette.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/raiden.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/raiden.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/sandrone.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/sandrone.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/vesna.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/vesna.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/vodyanitsa.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/vodyanitsa.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/static/yae.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/static/yae.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/furina.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/furina.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/furina_art.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/furina_art.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/hutao.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/hutao.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/ineffa.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/ineffa.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/lauma.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/lauma.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/m_furina.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/m_furina.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/m_hutao.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/m_hutao.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/m_lauma.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/m_lauma.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/nahida.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/nahida.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/nefer.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/nefer.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/nicole.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/nicole.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/odette.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/odette.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/raiden.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/raiden.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/sandrone.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/sandrone.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/v_nicole.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/v_nicole.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/v_raiden.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/v_raiden.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/v_sandrone.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/v_sandrone.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/vesna.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/vesna.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/vodyanitsa.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/vodyanitsa.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/thumb/yae.jpg` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/thumb/yae.jpg` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/video/nicole.mp4` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/video/nicole.mp4` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/video/raiden.mp4` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/video/raiden.mp4` | 原样复制（内置壁纸，版权不明，待替换） |
| `android/app/src/main/assets/backgrounds/video/sandrone.mp4` | GetAudio@dc2abeb | `android/app/src/main/assets/backgrounds/video/sandrone.mp4` | 原样复制（内置壁纸，版权不明，待替换） |
