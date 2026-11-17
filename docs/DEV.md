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
- 读 `/proc/<pid>/environ` 时**只数键**（每个键输出 1 或 0），不输出值。
- 覆盖：健康检查、公开页与公开 config（含"不含 owner 字段"）、匿名/free/owner 对 owner 通道与
  `/getmedia/owner/` 的 404/200、404 是否与白名单外路径逐字节一致、nginx 四个 location、
  密钥是否加载、证书到期日。
- 退出码 = FAIL 条数（0 才算过）；`--json` 给机器读；不在服务器上跑时主机类检查自动 `SKIP`。
- 自带测试：`pytest -q tools/tests/test_smoke_deploy.py`（本地假服务端，不连外网）。

## Android（由使用者在 Mac 上执行）
命令在 WP5 之后补充；构建需要固定签名密钥，见 `tools/make-release-key.sh`。

## 分支与提交
每个工作包一个分支 `wp<N>-<简述>`，小步提交，只推分支；不推 main、不强推。
