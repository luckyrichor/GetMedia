#!/bin/sh
# 在 Mac 上一次性生成 GetMedia 的固定签名密钥（free/full/debug 共用一把）。
# 只打印 SHA-256 指纹；口令随机生成，仅写入属性文件（权限 600）。已存在则拒绝覆盖。
set -eu
dir="${GETMEDIA_KEY_DIR:-$HOME/.android-keys}"
jks="$dir/getmedia.jks"
props="$dir/getmedia.properties"
if [ -e "$jks" ] || [ -e "$props" ]; then
  echo "拒绝覆盖：$dir 下已有 getmedia 密钥。换签名会让已安装用户无法升级。" >&2
  exit 1
fi
command -v keytool >/dev/null || { echo "需要 JDK 的 keytool（JDK 17）。" >&2; exit 1; }
mkdir -p "$dir"; chmod 700 "$dir"
rand() { LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 32; }
store_pw=$(rand); key_pw=$store_pw
keytool -genkeypair -v -keystore "$jks" -storetype PKCS12 -alias getmedia \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass "$store_pw" -keypass "$key_pw" -dname "CN=GetMedia" >/dev/null 2>&1
chmod 600 "$jks"
umask 077
cat > "$props" <<PROPS
storeFile=$jks
storePassword=$store_pw
keyAlias=getmedia
keyPassword=$key_pw
PROPS
chmod 600 "$props"
echo "已生成。证书 SHA-256 指纹："
keytool -list -v -keystore "$jks" -storepass "$store_pw" 2>/dev/null | grep 'SHA256:' | head -1
echo "请把 $dir 整体备份到仓库之外的安全位置；丢失则无法再发布可升级的版本。"
