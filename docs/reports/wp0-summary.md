# WP0 小结：脚手架、契约、扫描工具、协议种子

日期：2026-10-31　分支：`wp0-scaffold`

## 完成
- 仓库结构、`.gitignore`、各目录 README、`docs/{PLAN,STATUS,DECISIONS,DEV,provenance}.md`。
- `docs/contracts/`：通用约定、接口清单、数据表与保留期（路径相对基址 `/getmedia`）。
- `docs/legal/`：用户协议、隐私说明种子（产品名已替换，v1，2026-10-30 生效）。
- `tools/scan_leaks.py`（文件名 + 内容规则，只报位置不打印命中内容，支持 `--staged/--all/路径`）、
  `tools/scan_forbidden.py` 与 `forbidden-heavy/legacy.txt`、`install-hooks.sh`（pre-commit）、
  `make-release-key.sh`（Mac 上生成）、`setup-release-key.sh`（从本机路径复制，不联网）。
- PLAN v3.1：删除线上弱点表述；密钥只存用户 Mac；证书告警阈值 24 小时；对外改走 443 的 `/getmedia/`。

## 验证
- `tools/tests` 19 项通过（Python 3.13 沙箱）；泄露扫描对全部 34 个文件通过。
- 密钥脚本仅做了语法层面的编写，未在 Mac 上实跑。

## 未验收 / 需用户
- `make-release-key.sh` 需在 Mac 上执行并核对指纹。
- 443 路径前缀方案的 nginx 片段在 WP4 产出；上线前需用户在现有 443 块里加一行 include。
