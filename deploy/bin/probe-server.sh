#!/bin/sh
# 服务器只读探测（部署/交接前在服务器上运行，把输出贴回给开发者）。
# 只读：不写任何文件、不改任何配置、不重启任何服务。
# !! 输出含服务器内部细节（端口、路径、任务名），只贴给开发者，不要提交进仓库。
# 用法：sh probe-server.sh            （部分项目需要 root 才能看全，建议 sudo）
set -u
sec() { printf '\n===== %s =====\n' "$1"; }

sec "系统与运行时"
uname -sr 2>/dev/null
python3 --version 2>&1
nginx -v 2>&1
command -v certbot >/dev/null 2>&1 && certbot --version 2>&1 || echo "certbot: 未找到"
command -v acme.sh >/dev/null 2>&1 && echo "acme.sh: 在 PATH 中" || true
command -v flock >/dev/null 2>&1 && echo "flock: 有" || echo "flock: 无（续期脚本需要）"
systemctl --version 2>/dev/null | head -1

sec "监听端口（用于部署前后对比：GetMedia 不应新增任何端口）"
ss -ltnH 2>/dev/null | awk '{print $4}' | sort -u || echo "ss 不可用"

sec "nginx：443 块与各项目 include（只列相关行）"
nginx -T 2>/dev/null | grep -nE '^\s*(listen|server_name|ssl_certificate |include|location .*(getaudio|getvideo|getmedia))' | head -80
nginx -T 2>/dev/null | grep -nE 'conf\.d/\*\.conf|map_hash_bucket_size' | head -5
echo "-- 是否已存在 gm_ 前缀/getmedia 配置："
nginx -T 2>/dev/null | grep -cE 'gm_api_allowed|getmedia-prefix' || true

sec "证书（ssl_certificate 指向的文件）"
for f in $(nginx -T 2>/dev/null | awk '$1=="ssl_certificate"{gsub(";","",$2); print $2}' | sort -u); do
    echo "$f"
    openssl x509 -in "$f" -noout -subject -enddate 2>&1 | head -3
done

sec "现有续期任务（只看名字与时间，不看内容）"
systemctl list-timers --all --no-pager 2>/dev/null | grep -iE 'cert|acme|renew|ssl' || echo "(timer 里没有匹配项)"
ls /etc/cron.d 2>/dev/null | grep -iE 'cert|acme|renew' || true
crontab -l 2>/dev/null | grep -iE 'cert|acme|renew' | sed 's/[^ ]*@[^ ]*/<已隐藏>/g' || true

sec "运行用户与目录"
id getmedia 2>&1 | head -1
ls -ld /opt/getmedia /etc/getmedia /var/lib/getmedia /var/lib/getmedia-ledger /var/www/getmedia 2>&1

sec "资源"
df -h / 2>/dev/null | tail -1
free -m 2>/dev/null | sed -n '1,2p'
nproc 2>/dev/null
