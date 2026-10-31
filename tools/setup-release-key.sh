#!/bin/sh
# 把签名密钥从用户指定的本机路径复制到标准位置（不联网、不涉及服务器）。
# 用法：GETMEDIA_KEY_SRC=<含 getmedia.jks 与 getmedia.properties 的本机目录> sh tools/setup-release-key.sh
# 注意：properties 里的 storeFile 需指向复制后的位置，脚本会改写该行。
set -eu
src="${GETMEDIA_KEY_SRC:-}"
[ -d "$src" ] || { echo "请设置 GETMEDIA_KEY_SRC=<本机目录>（需含 getmedia.jks 与 getmedia.properties）" >&2; exit 1; }
dir="${GETMEDIA_KEY_DIR:-$HOME/.android-keys}"
[ ! -e "$dir/getmedia.jks" ] || { echo "已存在：$dir/getmedia.jks，不覆盖。" >&2; exit 1; }
for f in getmedia.jks getmedia.properties; do [ -f "$src/$f" ] || { echo "缺少 $src/$f" >&2; exit 1; }; done
mkdir -p "$dir"; chmod 700 "$dir"
umask 077
cp "$src/getmedia.jks" "$dir/getmedia.jks"
sed "s|^storeFile=.*|storeFile=$dir/getmedia.jks|" "$src/getmedia.properties" > "$dir/getmedia.properties"
chmod 600 "$dir/getmedia.jks" "$dir/getmedia.properties"
echo "已复制到 $dir（权限 600）。请用 keytool -list 核对 SHA-256 指纹后再构建。"
