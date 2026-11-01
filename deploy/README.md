# deploy

只放模板、脚本与手册。所有值为占位；占位邮箱使用保留域名 `example.invalid`。
部署由使用者手动执行；此处不放真实地址、实例 ID、防火墙规则、SSH 别名。

| 文件 | 装到哪里 | 说明 |
|---|---|---|
| `nginx/getmedia-http.conf` | `/etc/nginx/conf.d/getmedia-http.conf` | http 级：免费路径白名单 `map`、限流 zone、专用 `log_format`。不含任何全局调优指令 |
| `nginx/getmedia-prefix.conf` | `/etc/nginx/snippets/getmedia-prefix.conf` | `/getmedia/` 下全部路由；在 443 块里 include 这**一行** |
| `nginx/443-server-block.example.conf` | 不安装 | 示意那一行 include 放在哪 |
| `logrotate/getmedia` | `/etc/logrotate.d/getmedia` | 访问日志单独成文件，保留 14 天 |
| `systemd/getmedia-api.service` | `/etc/systemd/system/` | 单 worker、加固、`StateDirectory` |
| `systemd/getmedia-snapshot.service` `.timer` | `/etc/systemd/system/` | 每日快照 |
| `env/getmedia.env.example` | `/etc/getmedia/getmedia.env`（600） | 服务配置与 `GETMEDIA_SECRET_KEY` |
| `env/feedback.env.example` | `/etc/getmedia/feedback.env`（600） | 七个 `FEEDBACK_*`，**另建一份，不复用 GetAudio 的** |
| `env/release.json.example` | `/etc/getmedia/release.json` | APK 发布信息，改文件即生效 |
| `env/runtime.json.example` | `/var/lib/getmedia/runtime.json` | 注册开关等；由管理 CLI 改写，所以放数据目录 |
| `bin/getmedia-admin` | `/usr/local/sbin/getmedia-admin` | 管理 CLI 包装（以 getmedia 用户、同一套环境运行） |
| `bin/probe-server.sh` | 不安装，直接运行 | 服务器只读探测 |
| `bin/verify-deploy.sh` | 不安装，直接运行 | 部署前后对比，验收 §17-8 的证据 |
| `cert/*` | 见 `CERT-HANDOVER.md` | 证书续期任务模板；**交接前不得启用** |

手册：**[DEPLOY.md](DEPLOY.md)**（部署、上线前准备期、回退、恢复演练）· **[CERT-HANDOVER.md](CERT-HANDOVER.md)**（证书续期交接与失败处置）

## 模板的测试

```bash
.venv/bin/python -m pytest -q tools/tests/test_deploy_templates.py tools/tests/test_deploy_nginx.py
```

`test_deploy_nginx.py` 需要 nginx（沙箱里 `apt-get install nginx-light`）；没有 nginx 时整体跳过。
它起一个真实 nginx 和一个假上游，在一个模拟的"共用 443 块"里 include 本目录的模板，验证：
白名单、前缀剥离、`X-Forwarded-For` 覆盖、请求体大小、限流、静态回退、下载限制、
访问日志不含 Cookie/会话头/查询串，以及不影响同一块里其他项目的路径与限流状态码。
