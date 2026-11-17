# 开发与验证

## 服务端
```bash
python3 -m venv .venv && .venv/bin/pip install -r server/requirements.txt
.venv/bin/python -m pytest -q server/tests tools/tests
```

`tools/tests/test_deploy_nginx.py` 用真实 nginx 测 `deploy/nginx/` 模板，沙箱里先 `apt-get update && apt-get install -y nginx-light logrotate`；没有 nginx 时自动跳过。
证书续期脚本的测试需要 `flock` 与 `openssl`，同样缺失时跳过。

## 扫描（提交前强制）
```bash
python3 tools/scan_leaks.py --all
python3 tools/scan_forbidden.py legacy server android web   # 旧产品名残留
python3 tools/scan_forbidden.py heavy  android/app/src/main android/app/src/free web/public-app web/shared
sh tools/install-hooks.sh                                    # 安装 pre-commit
```

## 部署后冒烟（只读，不写生产数据）
```bash
sudo python3 tools/smoke_deploy.py --base https://<host> \
    --free-cookie /root/smoke-free.cookie --owner-cookie /root/smoke-owner.cookie
```

- 只发 `GET`：不写库、不写文件、不改 nginx；`nginx -T` / `systemctl show` 都是只读。
- **不自己登录**（登录会写一条会话）。Cookie 先自己取得并存成文件，Netscape 或 `name=value` 都认；
  不传就跳过需要身份的检查。
- **Cookie 文件的规矩**（服务器上那一次，别图省事）：
  - 放 `/root/`，`chmod 600`、`chown root:root`；不要放 `/tmp`、`/home`、网站目录或任何会被备份/同步的位置；
  - **用完立刻删除**（`shred -u /root/smoke-*.cookie`），连同取 Cookie 的那条命令历史一起清；
  - 不写进仓库、不贴聊天、不写进日志或脚本参数之外的任何文件；冒烟脚本本身**不回显 Cookie**，
    异常信息里也只给状态码。
- 读 `/proc/<pid>/environ` 时**只数键**（每个键输出 1 或 0），不输出值。
- 覆盖：健康检查、公开页与公开 config（含"不含 owner 字段"）、匿名/free/owner 对 owner 通道与
  `/getmedia/owner/` 的 404/200、404 是否与白名单外路径逐字节一致、nginx 四个 location、
  密钥是否加载、证书到期日。
- 退出码 = FAIL 条数（0 才算过）；`--json` 给机器读；不在服务器上跑时主机类检查自动 `SKIP`。
- 自带测试：`pytest -q tools/tests/test_smoke_deploy.py`（本地假服务端，不连外网）。

## AI 素材与产物的保留期
```bash
# 演练：只统计会清掉几个项目、能释放多少，不删文件
sudo -u getmedia /opt/getmedia/venv/bin/python -m getmedia.cli ai-gc --dry-run
# 真清（默认取 GETMEDIA_AI_RETENTION_DAYS=14；可临时 --days N）
sudo -u getmedia /opt/getmedia/venv/bin/python -m getmedia.cli ai-gc
```
每天 04:10 由 `getmedia-ai-gc.timer` 自动跑。只删超过保留期、且没有排队/执行中任务的项目**文件**，
记录保留并把状态置 `expired`；源来自媒体库的项目不会动到媒体库里的文件。

## Android（由使用者在 Mac 上执行）
命令在 WP5 之后补充；构建需要固定签名密钥，见 `tools/make-release-key.sh`。

## 分支与提交
每个工作包一个分支 `wp<N>-<简述>`，小步提交，只推分支；不推 main、不强推。
