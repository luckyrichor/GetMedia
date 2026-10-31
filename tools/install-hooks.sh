#!/bin/sh
# 安装 pre-commit：提交前跑泄露扫描（暂存区）。
set -eu
root=$(git rev-parse --show-toplevel)
hook="$root/.git/hooks/pre-commit"
cat > "$hook" <<'HOOK'
#!/bin/sh
exec python3 "$(git rev-parse --show-toplevel)/tools/scan_leaks.py" --staged
HOOK
chmod 755 "$hook"
echo "已安装 pre-commit：$hook"
