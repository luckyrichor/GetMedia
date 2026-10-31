# 开发与验证

## 服务端
```bash
python3 -m venv .venv && .venv/bin/pip install -r server/requirements.txt
.venv/bin/python -m pytest -q server/tests tools/tests
```

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
