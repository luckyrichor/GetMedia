# 部署手册

对应方案 `docs/PLAN.md` §15。**全部由你手动执行**；Agent 只提供模板与脚本，不碰服务器、不改云控制台、不改防火墙。
GetMedia **不新增任何端口**，不需要云防火墙规则。

约定路径（可整体替换）：代码 `/opt/getmedia`，虚拟环境 `/opt/getmedia/venv`，配置 `/etc/getmedia`，
数据 `/var/lib/getmedia`，删除台账 `/var/lib/getmedia-ledger`，网页 `/var/www/getmedia/public`，APK 备用 `/var/www/getmedia/download`。

> 先说明哪些我**没法**在开发环境验证（需要你在服务器上确认）：systemd 单元的实际启动与加固选项、
> `getmedia-admin`（用了 `systemd-run`）、真实 SMTP 发信、服务器 nginx 的具体版本与既有配置、证书续期工具。
> 对应的探测与自检命令都在下面各步里。

## 0. 只读探测与"部署前"记录

```bash
sudo sh deploy/bin/probe-server.sh > /root/gm-probe.txt     # 只读；把输出贴回给开发者；不要提交进仓库
sudo sh deploy/bin/verify-deploy.sh before /root/gm-before.txt
```

确认：`nginx.conf` 的 http 块里有 `include /etc/nginx/conf.d/*.conf;`；服务器 Python ≥ 3.12；记下现有 GetAudio / GetVideo 入口状态码（`before` 已记录）。

## 1. 用户、目录、代码、依赖

```bash
sudo useradd --system --home-dir /var/lib/getmedia --shell /usr/sbin/nologin getmedia
sudo git clone https://github.com/luckyrichor/GetMedia /opt/getmedia      # 代码属 root，服务只读
sudo git -C /opt/getmedia checkout <你决定部署的分支或标签>
sudo python3 -m venv /opt/getmedia/venv
sudo /opt/getmedia/venv/bin/pip install -r /opt/getmedia/server/requirements.txt
```

## 2. `/etc/getmedia`：配置、协议、发布信息

```bash
sudo install -d -m 750 -o root -g getmedia /etc/getmedia /etc/getmedia/legal
sudo install -m 600 -o root -g root /opt/getmedia/deploy/env/getmedia.env.example  /etc/getmedia/getmedia.env
sudo install -m 600 -o root -g root /opt/getmedia/deploy/env/feedback.env.example  /etc/getmedia/feedback.env
sudo install -m 640 -o root -g getmedia /opt/getmedia/docs/legal/user-agreement.md  /etc/getmedia/legal/
sudo install -m 640 -o root -g getmedia /opt/getmedia/docs/legal/privacy-policy.md  /etc/getmedia/legal/
sudo install -m 640 -o root -g getmedia /opt/getmedia/deploy/env/release.json.example /etc/getmedia/release.json
```

然后编辑两份 env：

- `getmedia.env`：把 `GETMEDIA_SECRET_KEY` 换成随机值（`python3 -c "import secrets; print(secrets.token_urlsafe(48))"`），
  `GETMEDIA_CERT_PATH` 填 443 块里 `ssl_certificate` 指向的文件。占位密钥过短，**忘记改会让服务拒绝启动**（有测试守护）。
- `feedback.env`：填你为 GetMedia **另建**的邮箱配置（QQ 邮箱 SMTP 授权码等）。

- 可选：`GETMEDIA_ARGON_MEMORY_KIB` / `GETMEDIA_ARGON_ITERATIONS`。沙箱里单次哈希约 130 ms，**服务器规格上的耗时没测过**，
  请在服务器上测一次注册/登录耗时，调到约 100–200 ms（方案 §8）。

> **密钥备份**：`GETMEDIA_SECRET_KEY` 用于恢复码与邀请码的 HMAC。丢失或更换后，所有尚未使用的恢复码与邀请码都会失效。
> 请单独离线备份，不要和数据库快照放在一起。

协议文本改版：改 `/etc/getmedia/legal/*.md` 即生效，**必须同时改文件首部的版本号**；同版本号文本被改，服务拒绝加载并发告警（方案 §4.7）。

## 3. 服务、快照定时器、管理命令

```bash
sudo install -m 644 /opt/getmedia/deploy/systemd/getmedia-api.service      /etc/systemd/system/
sudo install -m 644 /opt/getmedia/deploy/systemd/getmedia-snapshot.service /etc/systemd/system/
sudo install -m 644 /opt/getmedia/deploy/systemd/getmedia-snapshot.timer   /etc/systemd/system/
sudo install -m 755 /opt/getmedia/deploy/bin/getmedia-admin /usr/local/sbin/getmedia-admin
sudo systemctl daemon-reload
sudo systemctl enable --now getmedia-api
curl -s http://127.0.0.1:8700/health                    # 期望 {"ok":true}
sudo systemctl enable --now getmedia-snapshot.timer
```

先让服务启动一次再用管理命令（数据目录由 systemd 的 `StateDirectory` 创建并赋权）。然后：

```bash
sudo getmedia-admin snapshot          # 手动做一次快照
sudo getmedia-admin verify-backup     # 期望 “快照 1 份，校验失败 0 份”
sudo getmedia-admin mail-test         # 邮件通道自检；成功只提示成功，失败只给异常类别与 SMTP 数字码
```

`runtime.json`（注册开关等）放数据目录，由管理命令改写；文件不存在时按"注册关闭"处理，**无需手工创建**。

## 4. nginx（443 块共用，先备份，一行 include，可回退）

```bash
sudo cp -a /etc/nginx /root/nginx-backup-$(date +%F)          # 备份留在服务器，不进仓库
sudo install -m 644 /opt/getmedia/deploy/nginx/getmedia-http.conf   /etc/nginx/conf.d/getmedia-http.conf
sudo install -m 644 /opt/getmedia/deploy/nginx/getmedia-prefix.conf /etc/nginx/snippets/getmedia-prefix.conf
sudo install -m 644 /opt/getmedia/deploy/logrotate/getmedia          /etc/logrotate.d/getmedia
sudo install -d /var/www/getmedia/public /var/www/getmedia/download
```

在 443 的 server 块里，**既有 include 之后**加这一行（只此一行；GetMedia 不改其他项目的 include 文件）：

```nginx
include /etc/nginx/snippets/getmedia-prefix.conf;
```

```bash
sudo nginx -t && sudo systemctl reload nginx                   # 必须先 -t 通过再 reload
sudo sh /opt/getmedia/deploy/bin/verify-deploy.sh after /root/gm-before.txt
```

`verify-deploy.sh after` 会对比：监听端口集合与 GetAudio / GetVideo 入口状态码是否与部署前一致，并检查 GetMedia 的路由、白名单之外 404、安全头、无 CORS。
注意：网页（WP7）尚未构建，`/getmedia/` 在放入 `web-public` 构建产物之前会是 404，`after` 里对应一项会失败，属预期。

日志属主：`logrotate/getmedia` 里的 `create 0640 www-data adm` 适用于 Debian/Ubuntu；其他发行版按实际 nginx 用户改。

**回退**：删除那一行 include，`sudo nginx -t && sudo systemctl reload nginx`。GetAudio、GetVideo 不受影响。
（`getmedia-http.conf` 可以保留；它只定义以 `gm_` 开头的变量与 zone，不影响其他项目。）

## 5. 上线前准备期（注册暂关）

`registration_open` 默认关闭，陌生人此时进不来。

```bash
sudo getmedia-admin create-account <你的登录名> --owner      # 会提示输入密码；恢复码只显示一次，请立即保存
sudo getmedia-admin invite --purpose self-test --days 7       # 生成一次性邀请码
sudo getmedia-admin stats
```

用免费版 App / 网页，在真实服务器上凭邀请码走一遍：注册 → 登录 → 改密码 → 联系作者 → 注销（含 7 天冷静期内用 `restore-account` 恢复）。
反馈邮件、证书到期告警（`GETMEDIA_CERT_PATH`）是否生效，也在这一步确认。

## 6. 验收后开放注册

验收按 `docs/PLAN.md` §17，由你阅读报告后认可。之后：

```bash
sudo getmedia-admin invite-required on     # 先"仅邀请码"
sudo getmedia-admin registration open
# 观察一段时间后全面开放：
sudo getmedia-admin invite-required off
# 随时可再关：
sudo getmedia-admin registration close
```

你也可以跳过准备期，一上线就直接开放。

## 7. 备份、恢复演练、保留期

- 每日 03:30（服务器本地时间）快照 → `/var/lib/getmedia/snapshots`（目录 700）→ `integrity_check` → 保留最近 7 份；**不上传任何外部位置**。
- 删除台账在 `/var/lib/getmedia-ledger/`（快照目录之外，只追加）；恢复时重放，**已注销账号不会复活**。

恢复流程（**需要在服务器上实际演练一次，把结果交给开发者写进验收报告**）：

```bash
sudo systemctl stop getmedia-api
sudo mkdir -p /var/lib/getmedia/old && sudo mv /var/lib/getmedia/getmedia.db* /var/lib/getmedia/old/   # 连同 -wal / -shm 一起挪开
sudo getmedia-admin restore /var/lib/getmedia/snapshots/daily-<时间戳>.db /var/lib/getmedia/getmedia.db
sudo systemctl start getmedia-api
sudo getmedia-admin stats
# 确认无误后，7 天内删掉 /var/lib/getmedia/old（快照最长保留 7 天，隐私说明按此承诺）
```

`restore` 默认就重放删除台账，目标库必须不存在（拒绝覆盖）。

保留期（与隐私说明一致）：审计、限流、活跃明细最长 30 天；反馈文字 180 天、图片 90 天；快照最长 7 天；nginx 访问日志 14 天；台账长期保留，只存账号编号与删除时间。
服务内每小时自动清理；服务日志只记错误类别，不记请求内容。

## 8. 告警（走反馈邮件通道，同类每天最多一封）

证书剩余 <24 小时、磁盘水位、快照失败、反馈图片总量接近上限、协议文本哈希不符、证书续期脚本失败（`cert_renew_failed`）。
邮件不可用时仍落库，管理命令可查看：`sudo getmedia-admin feedback list`。

## 9. 常用管理命令

```
getmedia-admin stats | audit | feedback list|show|export|mark-handled
getmedia-admin create-account NAME [--owner] | disable|enable|revoke-sessions|set-owner|unset-owner|restore-account NAME
getmedia-admin invite [--purpose X] [--days N] | invite-required on|off | registration open|close
getmedia-admin snapshot | verify-backup | restore SNAPSHOT TARGET | alert KIND [DETAIL] | mail-test | tick
```
