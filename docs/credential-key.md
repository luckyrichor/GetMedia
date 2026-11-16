# GETMEDIA_CRED_KEY：平台凭据加密密钥

**用途**：把用户在 App 里导入的 B 站 / 抖音 Cookie 加密后存进 `platform_credentials.cookie_blob`。
没有它，平台授权与「服务器代抓」相关功能不可用（接口返回 503），其余功能不受影响。

**算法**：AES-256-GCM（自带完整性校验）。密文格式 `b"gm1" + nonce(12 字节) + ciphertext`，
前缀用于日后换算法/换密钥；AAD 固定为 `gm1`，与用户、平台无关。

---

## 1. 格式（必须完全一致）

| 项 | 值 |
|---|---|
| 编码 | **base64url**（URL 安全字符集：`A–Z a–z 0–9 - _`），末尾带 `=` 补位 |
| 解码后长度 | **恰好 32 字节**（256 位） |
| 字符串长度 | **44 个字符**（含 1 个 `=`） |
| 存放位置 | `/etc/getmedia/credentials.env`，`root:root`、`600` |
| 变量名 | `GETMEDIA_CRED_KEY` |

服务端解码规则（`server/getmedia/config.py: decode_credential_key`）：

- 空值 → 视为未配置，授权功能 503（**不会**降级成明文存储）；
- 不是合法 base64url → 启动失败 `ConfigError: GETMEDIA_CRED_KEY 不是合法的 base64url`；
- 解码后不是 32 字节 → 启动失败 `ConfigError: GETMEDIA_CRED_KEY 解码后必须是 32 字节（AES-256）`。

---

## 2. 生成（在服务器上做，密钥不经过聊天/不落日志）

```sh
# 1) 目录（已存在就不要再 install -d，否则会把既有的 750 root:getmedia 改掉）
[ -d /etc/getmedia ] || sudo install -d -m 0750 -o root -g getmedia /etc/getmedia

# 2) 一次生成并直接写文件：umask 077 保证创建时就是 600
#    openssl 输出标准 base64（含 + / =），tr 换成 URL 安全字符集 - _
sudo sh -c 'umask 077; printf "GETMEDIA_CRED_KEY=%s\n" "$(openssl rand -base64 32 | tr "+/" "-_")" > /etc/getmedia/credentials.env'

# 3) 收紧属主与权限（只读自己）
sudo chown root:root /etc/getmedia/credentials.env
sudo chmod 600 /etc/getmedia/credentials.env
sudo ls -l /etc/getmedia/credentials.env      # 期望：-rw------- 1 root root
```

要点：

- 密钥**只在这一条命令的子 shell 里出现**，不进 shell 历史（命令文本里只有 `$(openssl rand …)`）。
- 生成后**不要** `cat` 到终端去看；需要核对时用第 4 节的校验命令（只打印字节数）。
- 等价的 Python 写法：`python3 -c "import os,base64;print(base64.urlsafe_b64encode(os.urandom(32)).decode())"`（同样是 44 字符）。
- 多台服务器**不要共用**同一个密钥；每台各生成一份。

---

## 3. 让服务读到它

`deploy/systemd/getmedia-api.service` 已包含（可选加载，缺文件不报错）：

```ini
EnvironmentFile=-/etc/getmedia/credentials.env
```

`EnvironmentFile` 由 systemd 以 root 读取、再以 `User=getmedia` 运行进程，
所以文件可以是 `root:root 600`——服务账号本身不需要有读权限。

顺序：

```sh
sudo install -m 600 -o root -g root /opt/getmedia/deploy/systemd/getmedia-api.service /etc/systemd/system/getmedia-api.service
sudo systemctl daemon-reload
sudo systemctl restart getmedia-api
sudo systemctl is-active getmedia-api
```

> 注意两种都不行：`systemctl show getmedia-api -p Environment` **看不到** `EnvironmentFile` 里的变量
> （它只反映单元里 `Environment=` 写的内容，容易误判成"没加载"）；而 `cat /proc/<pid>/environ`
> 会把密钥明文打到终端、进 shell 历史、进录屏。核对方式见 §4：只数键名，不取值。

---

## 4. 校验（只输出字节数，不回显密钥）

```sh
sudo /opt/getmedia/venv/bin/python - <<'PY'
import base64, os
from pathlib import Path
raw = dict(l.split("=", 1) for l in Path("/etc/getmedia/credentials.env").read_text().splitlines()
           if l and not l.startswith("#"))
key = base64.urlsafe_b64decode(raw["GETMEDIA_CRED_KEY"])
assert len(key) == 32, len(key)
print("GETMEDIA_CRED_KEY ok:", len(key), "bytes")
PY
```

更贴近真实启动路径的校验（把两个 env 文件都加载，看 Settings 解出来的长度）：

```sh
sudo sh -c 'set -a; . /etc/getmedia/getmedia.env; . /etc/getmedia/credentials.env; set +a; \
  cd /opt/getmedia/server && /opt/getmedia/venv/bin/python -c \
  "from getmedia.config import load_settings; print(\"credential_key bytes:\", len(load_settings().credential_key))"'
```

期望输出 `credential_key bytes: 32`。

服务进程里是否真的拿到了（**只数键名，不取值**）：

```sh
for k in GETMEDIA_CRED_KEY GETMEDIA_SECRET_KEY; do
  printf '%s=' "$k"
  sudo tr '\0' '\n' < /proc/$(systemctl show -p MainPID --value getmedia-api)/environ \
    | grep -c "^$k="
done
```

各输出 `1` 即已注入；`0` 说明 `EnvironmentFile` 没被读到，或 `MainPID` 还是旧进程（重启后再查）。
`grep -c` 只匹配键名，密钥值不会出现在输出里，也不会进 shell 历史（命令本身不含值）。

写入后的端到端验证（需要 owner 会话，且已有导入过的 Cookie）：
`GET /account/platform-bindings` 返回的 `credentials[].decryptable` 为 `true`，说明当前密钥能解开已存密文；
为 `false` 说明密钥与密文不匹配（换过密钥或密文损坏）。
未配置密钥时，`POST /account/platform-auth/cookie` 返回 **503 `vault_unavailable`**，不会写任何东西。

---

## 5. 离线备份（必做，且与数据库快照分开存放）

密钥**不写进数据库**，数据库快照里也没有它。丢库可以重建，丢密钥则所有已存 Cookie 全部作废。

1. 备份时机：生成并校验通过之后，**在部署/迁移之前**。
2. 备份内容：`/etc/getmedia/credentials.env` 这一行（`GETMEDIA_CRED_KEY=<44 字符>`）。
3. 存放方式（至少两份，其中一份离线）：
   - 密码管理器里新建一条「GetMedia 服务器凭据密钥」，粘贴整行；
   - 离线介质（加密 U 盘 / 打印后入柜），介质上只写「GetMedia credentials env」与日期；
4. 导出到离线介质时不要经过中转机：
   ```sh
   sudo cp /etc/getmedia/credentials.env /media/<加密U盘>/getmedia-credentials-$(date +%F).env
   sudo chmod 600 /media/<加密U盘>/getmedia-credentials-*.env
   sync && sudo umount /media/<加密U盘>
   ```
5. **不要**把它和数据库快照、备份 tar 包放在同一个目录/同一个网盘：
   两者合在一起等于「数据 + 解密钥匙」，泄露即等于全部账号 Cookie 泄露。
6. 备份后做一次恢复演练：在临时目录放一份副本，用第 4 节的脚本校验，确认能解出 32 字节。

---

## 6. 丢失或更换的后果

| 情形 | 后果 | 处理 |
|---|---|---|
| 密钥文件丢失、且无离线备份 | `cookie_blob` 里的旧密文**永远解不开**（AES-GCM 直接 `InvalidTag`，没有后门、无法爆破） | 重新生成密钥 → 所有平台授权状态变为待授权 → 用户在 App 里**重新导入 Cookie / 重新授权** |
| 主动更换密钥（未做重加密） | 同上：旧密文全部失效 | 同上；当前版本**没有**在线换钥匙重加密的工具 |
| 只删掉 env 文件、不换密钥 | 授权功能 503，已有密文仍在（换回原密钥即可恢复） | 恢复文件后重启 |
| 密钥泄露 | 拿到库文件的人可直接解出全部 Cookie | 立即换密钥 + 让所有用户重新授权；同时按 Cookie 泄露处置（改密码、踢会话） |

**这些后果只影响平台凭据**：账号密码（Argon2id 哈希）、作品库、任务记录、失败上报都不依赖这把钥匙。

---

## 7. 迁移（P3）时怎么用

`tools/migrate_legacy.py apply` 需要**同一把**密钥来把旧库 Cookie 重加密：

```sh
# 用行内环境变量传入，不写进命令行历史里的值（命令文本只有变量名）
sudo sh -c 'set -a; . /etc/getmedia/credentials.env; set +a; \
  /opt/getmedia/venv/bin/python /opt/getmedia/tools/migrate_legacy.py apply …'
```

- 未配置密钥时：`apply` 会跳过整个凭据段（`stats.credentials.skipped` 增加），账本上有记录，不会写半条；
- 旧密钥用 `--audio-key-file / --video-key-file` 指定，工具只读、不复制；
- 迁移日志只记录平台与错误类型，不写 Cookie 明文；`plan`/`apply` 的输出里也没有密文。

---

## 8. 禁止事项

- 不要贴进聊天、工单、文档、Git、CI 变量、截屏；
- 不要写进 `getmedia.env`（和业务配置混在一起容易被整体导出）；
- 不要用短口令或 `openssl rand -hex 16` 之类（那是 16 字节，会被拒绝）；
- 不要在多个环境/多台机器复用同一把；
- 不要放进数据库快照目录或备份包。
