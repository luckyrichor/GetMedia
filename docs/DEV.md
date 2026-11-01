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

## Android（由使用者在 Mac 上执行）
命令在 WP5 之后补充；构建需要固定签名密钥，见 `tools/make-release-key.sh`。

## 分支与提交
每个工作包一个分支 `wp<N>-<简述>`，小步提交，只推分支；不推 main、不强推。

## 网页（web/）
```
cd web && npm ci && npm run build && npm test && npm run size      # 构建、组件测试、体积检查（JS+CSS gzip ≤ 60 KB）
.venv/bin/python -m pytest -q web/e2e                                # 真实 Chromium × 真实服务端 × 构建产物（需先 build；需要 playwright 与 Chromium）
```
`web/e2e` 单独运行（它和 `server/tests` 各有一个 `conftest.py`，同一次收集会冲突）。
