# P3 迁移工具与演练报告（2026-11-16）

**状态**：工具已完成并在**真实旧库的副本**上跑通；**生产库一行未写**，真实执行等你确认（P3 按约定需要你点头）。

---

## 1. 交付物

`tools/migrate_legacy.py`，四个子命令：

| 命令 | 作用 | 是否写库 |
|---|---|---|
| `plan` | 只读盘点：条数、字节、重复、孤儿、凭据可解密数、用户归并建议 | 否 |
| `apply` | 按 `--map` 分批写入目标库，幂等，`--snapshot` 先备份 | 是（目标库） |
| `verify` | 条数 / 字节 / 抽样 sha256 / 凭据状态 / 孤儿清单 | 否 |
| `rollback` | 用快照整体还原目标库（需 `--yes`） | 是（目标库） |

固定约束：旧库一律 `mode=ro` 打开；`apply` 不给 `--map` 直接退出；迁移阶段**不删除任何源文件**（`--files copy` 复制到新根，`--files skip` 只写库）。

**目标 schema 版本**：`TARGET_SCHEMA = 11`（= `server/getmedia/db.py` 的 `MIGRATIONS` 最新版本，含 P2 的 v5–v11）。
`apply` / `verify` 打开目标库后先校验 `schema_version` 最大值，不等于 11 直接报错退出（码 2），避免在旧表结构上写一半。

测试：`tools/tests/test_migrate_legacy.py`，**8 项通过**，覆盖跨库重复、已删除标记、孤儿文件、凭据重加密、解不开降级、测试残留丢弃、幂等、回滚、无 map 拒绝、未知目标用户拒绝、目标 schema 版本不匹配拒绝、日志不含 Cookie。

---

## 2. 生产旧库只读核对（2026-11-16）

用 `sqlite3` 的 `mode=ro` 读 `sqlite_master` 与 `PRAGMA table_info`，只统计行数与结构，**不读 `cookie_blob`**。
结论只写到"量级"，具体数字与逐账号分布留在服务器终端的 `plan` 输出里（不贴聊天、不进报告）。

| 库 | 结论 |
|---|---|
| 音频旧库 | 有真实历史数据：作品个位数、任务十余条、失败数条、凭据数条（其中 1 条是测试残留）、绑定 1 条 |
| 视频旧库 | 基本为空：作品 0 条、任务 0 条，只有数条凭据与 1 条绑定 |
| 两库其余表（`agent_*`、`crawl_state`、`creator_*`、`recreation_*`） | 全 0 或表不存在 |

**与计划（§9.2）不符、必须按实际实现的三处**

1. `tracks` 没有 `user_id`，归属在 `owner_scope`：既有具体账号 id，也有单用户时代写死的保底值 `legacy`（不是账号）。所以 map 必须同时覆盖 `legacy` 与真实 id。
2. `extract_jobs.status` 存的是**大写枚举** `DONE` / `FAILED`，不是小写。工具已统一 `lower()` 后映射。
3. `account_platform_bindings` 实际列是 `id,user_id,platform,subject,nickname,created_at`，**没有 `status`**；新表有。工具按列存在性读，缺列用默认值。

**另外**：`extract_jobs.user_id` 里有少量早期 UUID（`app_users` 里没有对应账号）和少量 `NULL`，
合计 10 条无法归属到任何人 —— 这正是"10 条无主任务跳过"这条口径的由来。

### 2.1 账号与归属的形态（不含任何个人数据）

四个真实账号（音频 2 个、视频 2 个）+ 两库各一个保底值 `legacy`。逐账号的条数、日期与 id 片段
留存在服务器的 `plan` 输出与你我确认过的本地文档里，本报告不重复。

**`owner_scope` 与 `user_id` 的区别（关键）**

- `tracks` 表**没有 `user_id`**，归属写在 `owner_scope`：`acct_…` 是具体账号的真实 id；`legacy` 是单用户时代
  写死的保底归属值，不是账号。音频库的作品绝大多数落在 `legacy`，所以 `map.json` 的 audio 段必须显式给出
  `legacy` 指向谁，否则这些作品会被整批跳过。
- 任务 / 失败 / 凭据 / 绑定用的是真实 `user_id`，没有 `legacy` 写法。

**因此 `map.json` 至少要覆盖这 5 个键**（值由你定，`*` 可作兜底但本库不需要）：

| 段 | 必须给的键 | 覆盖的数据 |
|---|---|---|
| `audio` | `legacy` | 单用户时代的全部作品 |
| `audio` | 音频账号 1 | 该账号的作品、凭据、任务、失败 |
| `audio` | 音频账号 2 | 该账号的凭据、失败、绑定 |
| `video` | `legacy` | 0 条（写上无害，避免将来补数据时漏） |
| `video` | 视频账号 1、视频账号 2 | 各 1 条凭据；前者另有 1 条绑定 |

不参与映射的：无主 `user_id`（共 10 条任务，按你的决定跳过）、测试残留账号（工具内置丢弃）。

---

## 3. 演练：在真实库副本上跑完整链路

环境：服务器 `/tmp/gm-drill`，用 `sqlite backup` 把两个旧库**复制**过去（源文件只读），用 GetMedia 的迁移建空目标库，两个演练 owner 账号，媒体复制到 `/tmp/gm-drill/media`。跑完整个目录删除，**没有写 `/opt`、没有写生产库、没有动旧服务**。

### 3.1 plan

- 识别出两组用户，提示需要 2 个目标账号；
- 报告了一批对不上 `app_users` 的 `user_id`（其中 2 条为 `NULL`），合计 10 条无主任务。

### 3.2 apply

一条命令跑完；具体条数与字节只出现在服务器终端的账本里，下表只记结论：

| 段 | 结果 |
|---|---|
| `tracks` | 源记录全量写入，重复 0、孤儿 0 |
| `extract_jobs` | 可归属部分全部写入；10 条无主跳过（与口径一致） |
| `extraction_failures` | 源记录全量写入 |
| `credentials` | 源记录全部重加密成功（音频用库内 `.secret_key`，视频用 `GETVIDEO_SECRET_KEY`），测试残留 1 条丢弃 |
| `bindings` | 全量写入 |
| `agent` / `recreations` | 两库均无数据，0 写入 |

- `tracks` 全部 `storage='archive'`，`media_path` 保留原始子目录（`bilibili/`、`douyin/`、`accounts/<hash>/bilibili/`）。

### 3.3 媒体

`--files copy` 复制到 `media/audio/...`，覆盖了全部作品文件（个位数）。文件名含中文、emoji、空格，
子目录含平台名与账号哈希前缀 —— 这三类边界正好把"非 ASCII / emoji / 空格路径"全踩了一遍，复制与哈希都通过。
**具体文件名不入库**：本文档在公开仓库里，作品标题与作者昵称属于个人信息。

### 3.4 verify

```
[audio] 源 N 条，目标命中 N 条（跨库重复 0，字节 N → N，一致）
  抽样：全部比对通过，0 个不一致，0 个文件缺失
[video] 源 0 条，目标命中 0 条
未发现条数/字节/抽样哈希问题。
```

抽样比例取 `1.0`（全量），sha256 全部一致 —— 这就是"字节级一致"的结论，具体字节数不入库。

---

## 4. 演练中发现并修掉的问题

| 问题 | 现象 | 处理 |
|---|---|---|
| `extraction_failures` 是复合主键 | `rows()` 按 `id` 排序直接报 `no such column: id` | 按 `PRAGMA table_info` 判断，无 `id` 时退回 `rowid` |
| 状态大小写 | 全部 `DONE/FAILED` 落到 `failed` | 先 `lower()` 再查映射表 |
| 绑定表缺 `status` | `INSERT` 报列不存在 | 按实际列读，缺列填 `active` |
| 跨库重复被 verify 误判为"未命中" | 同一个视频 id 在视频库里是作品、在目标库里已按音频入库 | verify 回退按 `(platform, video_id)` 找，记"跨库重复" |
| `apply` 少给参数时抛 `SystemExit` | 报错信息不友好 | 改为 `MigrationError`，打印"apply 必须给 --map" |
| 报告 JSON 含 `Path` | `TypeError: not JSON serializable` | 统一 `str()` + `default=str` |

---

## 5. 与计划的偏差（需要你知道）

1. **`config/keywords.yaml` 不自动导入**。旧定时组是"关键词搜索"，新 `schedules.input` 的语义是"博主/链接"，且新契约没有关键词搜索任务。强行导入会生成一批必然失败的任务。做法：`plan` 列出旧定时组的全部关键词，由你在 App 里用新的方式重建。若你要强行导入，我可以加 `--import-keywords`，但不建议。
2. **`creator_jobs` / `creator_items` 不迁移**（两库都是 0 条）。新表结构与旧表差异大（旧表有 `owner_scope`、按条存 `prefetched_json`），0 条时迁移没有收益。
3. **10 条无主 `extract_jobs` 不迁移**。如果要保留，可以给 map 加 `"*": "<new_user>"` 显式兜底。
4. **`recreations` 只做最小映射**（旧 `recreation_projects/runs` → 新 `recreations/recreation_runs`），字段有损；两库都是 0 条，实际不会触发。
5. **`agent_*` 全 0**，映射逻辑写了但没数据可验。

---

## 6. 执行口径（2026-11-16 你已确认）

| 项 | 决定 |
|---|---|
| 无法归属的 10 条 `extract_jobs` | **跳过**，不写 `*` 兜底 |
| 媒体文件 | **`--files copy`**，旧目录原样保留（只读复制，不 `mv`、不删源） |
| 迁移窗口 | `apply` 期间**停旧服务**，新服务不对外 |
| 真实执行的时点 | **等 P2 完成并部署之后**再做（不是现在） |

剩下需要你给的东西只有一样：**映射**（§2.1 的 5 个键 → 新建的 owner 账号 id）。
执行前还需要两个空 owner 账号：`getmedia-admin create-account <登录名> --owner`（P2 的服务端已支持 `tier=owner`）。

### 6.1 `GETMEDIA_CRED_KEY`

- 格式：base64url 编码的 **32 字节**，共 **44 字符**（含末尾 `=`）；
- 生成在服务器上、直接写 `/etc/getmedia/credentials.env`（`root:root 600`），**不经过聊天、不进 Git、不进日志**；
- 完整步骤、校验命令、离线备份与「密钥丢失 = 已迁凭据需重新授权」的后果：**`docs/credential-key.md`**；
- `apply` 需要同一把密钥把旧 Cookie 重加密；未配置时整个凭据段会被跳过（账本有记录），不会写半条。

回滚路径：`--snapshot` 生成的目标库快照 + 旧库完全没动，最坏情况删掉新库重来。

---

## 7. 本次没有做的事

- 没有写 `/opt/getmedia`、`/var/lib/getmedia`、生产 `getmedia.db`；
- 没有动两个旧服务的安装目录与两个旧库；
- 没有停任何服务、没有改 nginx；
- 演练目录 `/tmp/gm-drill` 已删除（本报告只留方法与结论，不留条数、字节与文件名）。
