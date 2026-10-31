# GetMedia

Android 本地音视频提取、管理与播放工具，附带账号系统与反馈服务。

- `server/`：服务端（FastAPI + SQLite），任务一只含账号、配置、协议、心跳、反馈、注销、管理 CLI、本地快照。
- `android/`：单个 Gradle 工程，`free` / `full` 两个风味（任务一只实现 `free`）。
- `web/`：`shared`（公共组件）、`public-app`（公开网页）。
- `deploy/`：nginx、systemd、env 的模板（全为占位值，由使用者手动部署）。
- `docs/`：计划、状态、决定、接口契约、协议文本、各工作包小结。
- `tools/`：泄露扫描、禁用词扫描、钩子与密钥脚本。

开始前阅读 `docs/STATUS.md`（当前进度）与 `docs/DEV.md`（开发与验证命令）。
仓库内不得出现凭据、真实邮箱、数据库、签名密钥、构建产物；`tools/scan_leaks.py` 在提交前强制检查。
