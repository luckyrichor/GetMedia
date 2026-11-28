# GetMedia

Android 本地音视频提取、管理与播放工具，附带账号系统、云端曲库同步与两个网页。

**当前版本：Android `0.1.48 / versionCode 49`（tag `v0.1.48`）**，服务端 release `20261128-2`（SQLite schema 21）。
现状、测试基线、部署路径与待办见 [docs/HANDOVER.md](docs/HANDOVER.md)。

- `server/`：服务端（FastAPI + SQLite）——账号、等级、会话、协议、心跳、反馈、注销、
  曲库同步（元数据 + 本机原件归档）、元数据备份、管理台接口、运维 CLI / 快照 / 告警。
- `android/`：单个 Gradle 工程，`free` / `full` 两个风味（包名不同，可同机并存）。
  `free` 只有本地功能；`full` 另有云端同步、本机原件归档与批量导出。
- `web/`：`shared`（公共组件）、`public-app`（公开页，`/getmedia/`）、
  `owner-app`（管理台，`/getmedia/owner/`）。
- `deploy/`：nginx、systemd、env 的模板（全为占位值，由使用者手动部署）。
- `docs/`：方案（`PLAN.md`）、接手说明（`HANDOVER.md`）、状态（`STATUS.md`）、决定（`DECISIONS.md`）、
  接口契约（`contracts/`）、协议文本（`legal/`）、各工作包报告（`reports/`）。
- `tools/`：泄露扫描、禁用词扫描、钩子、签名与冒烟脚本。

开始前阅读 [docs/HANDOVER.md](docs/HANDOVER.md)（唯一跟着版本走的现状说明）与
[docs/DEV.md](docs/DEV.md)（开发与验证命令）。
仓库内不得出现凭据、真实邮箱、数据库、签名密钥、构建产物；`tools/scan_leaks.py` 在提交前强制检查。
