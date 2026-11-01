# 状态

最后更新：2026-11-01

| 工作包 | 状态 | 分支 |
|---|---|---|
| WP0 脚手架、契约、扫描工具、协议种子 | 完成（已推送） | `wp0-scaffold` |
| WP1 账号/注册/会话/同意/协议 | 完成（已推送） | `wp1-accounts` |
| WP2 tier/审计/配置/心跳 | 完成（已推送） | `wp2-tier` |
| WP3 反馈 | 完成（已推送） | `wp3-feedback` |
| WP4 运维：CLI/注销/快照/告警/部署模板 | 完成（已推送）；**服务器侧验证项由用户执行**，见 `docs/reports/wp4-summary.md`（证书续期工具为假设、systemd 实际启动、真实 SMTP、恢复演练等未验证） | `wp4-ops` |
| WP5a Android 底座（音频部分） | Mac 回传（提交 `0f4dfc3`）：JVM 单测 81 项通过；Lint 0 错误 / 19 警告 / 3 信息；debug 构建成功（1 分 40 秒）。release 构建、真机均未验。见 `docs/reports/wp5a-summary.md` | `wp5a-android-base` |
| WP5b 并入视频 | Mac 回传（提交 `e1258bb`）：`testDebugUnitTest`、`lintDebug`、`assembleDebug` 一次构建全部成功（33 秒，无失败任务）；输出未含单测数量与 Lint 计数；release 与真机未验。见 `docs/reports/wp5b-summary.md` | `wp5b-video`（基于 wp5a） |
| WP6 Android free | 代码完成、**未编译**（沙箱无 Gradle）：账号/同意/恢复码/账号与安全/联系作者/占位入口/心跳；待 Mac 回传构建、反编译与真机。见 `docs/reports/wp6-summary.md` | `wp6-free`（基于 wp5b） |
| WP7 web-public | 已完成于 `wp7-web-public`（见该分支 STATUS 与 `docs/reports/wp7-summary.md`）；本分支未合并，合并由用户决定 | `wp7-web-public` |
| WP8 验收报告 | 未开始 | |

未验收内容、阻塞项与需要用户操作的事项在各 `docs/reports/wp*-summary.md` 中列出。
