#!/bin/sh
# GetMedia 证书续期任务（模板；交接前不得启用，见 deploy/CERT-HANDOVER.md）
# 安装位置建议：/usr/local/sbin/getmedia-cert-renew.sh（root:root 755）
#
# 用法：getmedia-cert-renew.sh [--dry-run] [--force]
#   （无参数） 剩余有效期仍大于 RENEW_BEFORE_HOURS 就什么都不做；否则续期 → 校验 → reload nginx
#   --dry-run  只做续期演练（certbot --dry-run，不消耗正式额度），不改动证书、不 reload
#   --force    不看剩余有效期，立即续期（交接切换后的"立即手动续期一次"用它）
#
# !! 假设（我在写模板时看不到服务器上现有续期任务）：
#    本模板按 certbot 编写，并用 certbot 已保存的续期配置（--cert-name）重新签发，
#    签发方式（80 端口 http-01 / webroot / 短期 IP 证书配置）沿用现有证书当初的设置。
#    若现有任务用的是别的 ACME 客户端（acme.sh、lego 等），只需改下面 run_acme 一个函数。
#    交接手册第 1 步就是在服务器上只读确认这一点。
#
# 任何一步失败都会：写日志 → 通过 getmedia-admin 发 cert_renew_failed 告警（同类每天最多一封）→ 非零退出。
# 证书到期告警（<24 小时）由 getmedia-api 自己检查，是第二道保险。
set -u

CONF="${GETMEDIA_CERT_ENV:-/etc/getmedia/cert-renew.env}"
[ -r "$CONF" ] || { echo "缺少配置文件 $CONF" >&2; exit 1; }
# shellcheck disable=SC1090
. "$CONF"
: "${CERT_FILE:?}" "${CERT_NAME:?}"
RENEW_BEFORE_HOURS="${RENEW_BEFORE_HOURS:-56}"      # 总寿命约 160 小时，剩余不足约 1/3 时续期
CERTBOT_BIN="${CERTBOT_BIN:-certbot}"
ADMIN_BIN="${ADMIN_BIN:-/usr/local/sbin/getmedia-admin}"
LOCK="${LOCK_FILE:-/run/lock/getmedia-cert-renew.lock}"

DRY=0; FORCE=0
for a in "$@"; do
    case "$a" in
        --dry-run) DRY=1 ;;
        --force) FORCE=1 ;;
        *) echo "未知参数：$a" >&2; exit 2 ;;
    esac
done

log() { printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"; }
fail() {
    log "失败：$*"
    "$ADMIN_BIN" alert cert_renew_failed "$*" >/dev/null 2>&1 || log "（告警也没发出去，请看 getmedia-api 日志与到期告警）"
    exit 1
}

# 同一时刻只允许一个续期实例
exec 9>"$LOCK" || fail "无法创建锁文件"
flock -n 9 || { log "另一个续期实例正在运行，退出"; exit 0; }

run_acme() {   # 唯一依赖具体 ACME 客户端的地方
    if [ "$DRY" -eq 1 ]; then
        "$CERTBOT_BIN" renew --cert-name "$CERT_NAME" --dry-run
    else
        "$CERTBOT_BIN" renew --cert-name "$CERT_NAME" --force-renewal
    fi
}

[ -r "$CERT_FILE" ] || fail "证书文件不可读"
before="$(openssl x509 -in "$CERT_FILE" -noout -enddate 2>/dev/null)" || fail "证书文件无法解析"

if [ "$FORCE" -eq 0 ] && [ "$DRY" -eq 0 ] \
   && openssl x509 -in "$CERT_FILE" -noout -checkend $((RENEW_BEFORE_HOURS * 3600)) >/dev/null 2>&1; then
    log "距到期仍大于 ${RENEW_BEFORE_HOURS} 小时，无需续期（$before）"
    exit 0
fi

mode_note=""
[ "$DRY" -eq 1 ] && mode_note="续期演练"
log "开始${mode_note:-续期}（当前 $before）"
run_acme || fail "ACME 续期命令失败"

if [ "$DRY" -eq 1 ]; then
    log "演练通过（未改动证书，未 reload nginx）"
    exit 0
fi

# 校验：证书确实更新了，且新证书剩余有效期够长
after="$(openssl x509 -in "$CERT_FILE" -noout -enddate 2>/dev/null)" || fail "续期后证书无法解析"
[ "$after" != "$before" ] || fail "续期命令成功但证书有效期没有变化"
openssl x509 -in "$CERT_FILE" -noout -checkend $((RENEW_BEFORE_HOURS * 3600)) >/dev/null 2>&1 \
    || fail "续期后证书剩余有效期仍不足 ${RENEW_BEFORE_HOURS} 小时"

# 先检查配置再 reload；失败则不 reload（保留正在运行的旧配置）
nginx -t >/dev/null 2>&1 || fail "nginx -t 未通过，已放弃 reload"
systemctl reload nginx || fail "nginx reload 失败"
log "续期完成：$after；nginx 已 reload"
