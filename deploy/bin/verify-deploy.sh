#!/bin/sh
# 部署前后对比（验收 §17-8 的证据）：监听端口集合不变、其他项目入口行为不变、GetMedia 路由与安全头符合预期。
# 用法：
#   sh verify-deploy.sh before OUTFILE     部署 nginx 配置之前运行，记录端口与现有入口的状态码
#   sh verify-deploy.sh after  OUTFILE     部署之后运行，与 before 对比并检查 GetMedia 路由
# 环境变量：HOST（默认 127.0.0.1）、OTHER_PATHS（默认 "/getaudio/ /getvideo/"，空格分隔）
# 用 curl -k 访问本机 443：这里只验证路由与头，不验证证书（证书由到期告警负责）。
# 输出文件含端口与入口状态，不要提交进仓库。
set -u
MODE="${1:-}"; OUT="${2:-}"
[ -n "$MODE" ] && [ -n "$OUT" ] || { echo "用法：$0 before|after OUTFILE" >&2; exit 2; }
HOST="${HOST:-127.0.0.1}"
OTHER_PATHS="${OTHER_PATHS:-/getaudio/ /getvideo/}"
BASE="https://$HOST"

code() { curl -sk -o /dev/null -m 10 -w '%{http_code}' "$@"; }

snapshot() {
    echo "[ports]"
    ss -ltnH 2>/dev/null | awk '{print $4}' | sort -u
    echo "[others]"
    for p in $OTHER_PATHS; do echo "$p $(code "$BASE$p")"; done
}

if [ "$MODE" = before ]; then
    snapshot > "$OUT"
    echo "已记录到 $OUT"; cat "$OUT"; exit 0
fi
[ "$MODE" = after ] || { echo "模式只能是 before 或 after" >&2; exit 2; }
[ -r "$OUT" ] || { echo "找不到 before 记录：$OUT" >&2; exit 2; }

bad=0
check() {   # check 描述 期望 实际
    if [ "$2" = "$3" ]; then echo "  通过  $1"; else echo "  失败  $1（期望 $2，实际 $3）"; bad=1; fi
}

echo "== 与部署前对比 =="
snapshot > "$OUT.after"
if diff -u "$OUT" "$OUT.after" >/dev/null; then
    echo "  通过  监听端口集合与其他项目入口状态码均与部署前一致"
else
    echo "  失败  与部署前不一致："; diff -u "$OUT" "$OUT.after"; bad=1
fi

echo "== GetMedia 路由 =="
check "GET  /getmedia/api/health" 200 "$(code "$BASE/getmedia/api/health")"
check "GET  /getmedia/api/v2/config" 200 "$(code "$BASE/getmedia/api/v2/config")"
check "GET  /getmedia/api/v2/legal" 200 "$(code "$BASE/getmedia/api/v2/legal")"
check "白名单之外的路径 → 404" 404 "$(code "$BASE/getmedia/api/v2/not-a-real-path")"
check "白名单路径但方法不对 → 404" 404 "$(code -X DELETE "$BASE/getmedia/api/health")"
check "GET  /getmedia （无斜杠）→ 308" 308 "$(code "$BASE/getmedia")"
check "GET  /getmedia/ （网页）" 200 "$(code "$BASE/getmedia/")"
check "未登录访问 /account/me → 401" 401 "$(code "$BASE/getmedia/api/account/me")"

echo "== 安全头（/getmedia/ 与 /getmedia/api/health）=="
for u in /getmedia/ /getmedia/api/health; do
    h="$(curl -sk -m 10 -D - -o /dev/null "$BASE$u" | tr -d '\r' | tr 'A-Z' 'a-z')"
    for want in "content-security-policy:" "x-content-type-options: nosniff" "referrer-policy: no-referrer" "x-robots-tag: noindex"; do
        case "$h" in *"$want"*) echo "  通过  $u 含 $want" ;; *) echo "  失败  $u 缺少 $want"; bad=1 ;; esac
    done
    case "$h" in *"access-control-allow-origin"*) echo "  失败  $u 出现 CORS 头"; bad=1 ;; *) echo "  通过  $u 无 CORS 头" ;; esac
done

echo
if [ "$bad" -eq 0 ]; then echo "全部通过"; else echo "有失败项（见上）"; fi
exit "$bad"
