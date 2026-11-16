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

测试：`tools/tests/test_migrate_legacy.py`，**7 项通过**，覆盖跨库重复、已删除标记、孤儿文件、凭据重加密、解不开降级、测试残留丢弃、幂等、回滚、无 map 拒绝、未知目标用户拒绝、日志不含 Cookie。

---

## 2. 生产旧库只读核对（2026-11-16）

用 `sqlite3` 的 `mode=ro` 读 `sqlite_master` 与 `PRAGMA table_info`，不读 `cookie_blob`。

| 项 | GetAudio | GetVideo |
|---|---|---|
| `tracks` | 6 条 / 10,207,014 字节 | 0 条 |
| `extract_jobs` | 13 条（`DONE` 8 / `FAILED` 5） | 0 条 |
| `extraction_failures` | 5 条 | 0 条 |
| `platform_credentials` | 3 条（含 1 条 `test_u` 测试残留） | 2 条 |
| `account_platform_bindings` | 1 条 | 1 条 |
| `agent_*` / `crawl_state` / `creator_*` | 全 0 | 全 0 |
| `recreation_projects` / `recreation_runs` | 不存在 | 0 条 |

**与计划（§9.2）不符、必须按实际实现的三处**

1. `tracks` 没有 `user_id`，归属在 `owner_scope`：Audio 是 **5 条 `legacy` + 1 条真实 `acct_…`**，不是"全是 legacy"。所以 map 必须同时包含 `legacy` 和那个真实 id。
2. `extract_jobs.status` 存的是**大写枚举** `DONE` / `FAILED`，不是小写。工具已统一 `lower()` 后映射。
3. `account_platform_bindings` 实际列是 `id,user_id,platform,subject,nickname,created_at`，**没有 `status`**；新表有。工具按列存在性读，缺列用默认值。

**另外发现**：`extract_jobs.user_id` 里有 8 条是早期 UUID（`05c1da45-…`、`0a2bd188-…`），`app_users` 里没有对应账号；另有 2 条 `user_id` 为 `NULL`。这 10 条无法归属到任何人。

---

## 3. 演练：在真实库副本上跑完整链路

环境：服务器 `/tmp/gm-drill`，用 `sqlite backup` 把两个旧库**复制**过去（源文件只读），用 GetMedia 的迁移建空目标库，两个演练 owner 账号，媒体复制到 `/tmp/gm-drill/media`。跑完整个目录删除，**没有写 `/opt`、没有写生产库、没有动旧服务**。

### 3.1 plan

- 识别出两组用户，提示需要 2 个目标账号；
- 报告 8 + 2 条对不上 `app_users` 的 `user_id`。

### 3.2 apply

```json
{
  "tracks":              { "inserted": 6,  "duplicate": 0, "orphan": 0 },
  "extract_jobs":        { "inserted": 3,  "skipped": 10 },
  "extraction_failures": { "inserted": 5 },
  "credentials":         { "inserted": 4,  "needs_reauth": 0, "skipped": 1 },
  "bindings":            2,
  "agent":               { "conversations": 0, "messages": 0, "memories": 0 },
  "recreations":         { "projects": 0, "runs": 0 }
}
```

- `credentials` 4 条全部重加密成功（Audio 2 条用 `data/.secret_key`，Video 2 条用 `GETVIDEO_SECRET_KEY`），`test_u` 那条按 §9.4 丢弃；
- `extract_jobs` 跳过 10 条 = 8 条 UUID 用户 + 2 条 `user_id` 为 NULL；
- `tracks` 6 条全部 `storage='archive'`，`media_path` 带原始子目录（`bilibili/`、`douyin/`、`accounts/<hash>/bilibili/`）。

### 3.3 媒体

复制到 `media/audio/...` 的 6 个文件，文件名含中文、emoji、空格：

```
bilibili/BV1GJ411x7h7-【官方 MV】Never Gonna Give You Up - Rick Astley.m4a   5,402,460
douyin/7392957955471199525-@力白不吃土豆创作的原声.m4a                        1,982,756
douyin/7537664997619535155-@音之乐🎙️创作的原声.mp3                          1,854,724
douyin/7691313513319261050-@你的小遇创作的原声.mp3                             297,711
douyin/7692331471981692388-@你的小遇创作的原声.mp3                             301,786
accounts/f4bc…/bilibili/BV1KG411w7r2-p3-… - P3 气荡山河.m4a                   367,577
```

### 3.4 verify

```
[audio] 源 6 条，目标命中 6 条（跨库重复 0，字节 10207014 → 10207014）
  抽样：6 个比对通过，0 个不一致，0 个文件缺失
[video] 源 0 条，目标命中 0 条
未发现条数/字节/抽样哈希问题。
```

抽样比例取 `1.0`（全量），sha256 全部一致。

---

## 4. 演练中发现并修掉的问题

| 问题 | 现象 | 处理 |
|---|---|---|
| `extraction_failures` 是复合主键 | `rows()` 按 `id` 排序直接报 `no such column: id` | 按 `PRAGMA table_info` 判断，无 `id` 时退回 `rowid` |
| 状态大小写 | 全部 `DONE/FAILED` 落到 `failed` | 先 `lower()` 再查映射表 |
| 绑定表缺 `status` | `INSERT` 报列不存在 | 按实际列读，缺列填 `active` |
| 跨库重复被 verify 误判为"未命中" | Video 库的 `BV1` 在目标里是 `audio` 类型 | verify 回退按 `(platform, video_id)` 找，记"跨库重复" |
| `apply` 少给参数时抛 `SystemExit` | 报错信息不友好 | 改为 `MigrationError`，打印"apply 必须给 --map" |
| 报告 JSON 含 `Path` | `TypeError: not JSON serializable` | 统一 `str()` + `default=str` |

---

## 5. 与计划的偏差（需要你知道）

1. **`config/keywords.yaml` 不自动导入**。旧定时组是"关键词搜索"，新 `schedules.input` 的语义是"博主/链接"，且新契约没有关键词搜索任务。强行导入会生成一批必然失败的任务。做法：`plan` 列出 6 组 / 24 个关键词，由你在 App 里用新的方式重建。若你要强行导入，我可以加 `--import-keywords`，但不建议。
2. **`creator_jobs` / `creator_items` 不迁移**（两库都是 0 条）。新表结构与旧表差异大（旧表有 `owner_scope`、按条存 `prefetched_json`），0 条时迁移没有收益。
3. **10 条无主 `extract_jobs` 不迁移**。如果要保留，可以给 map 加 `"*": "<new_user>"` 显式兜底。
4. **`recreations` 只做最小映射**（旧 `recreation_projects/runs` → 新 `recreations/recreation_runs`），字段有损；两库都是 0 条，实际不会触发。
5. **`agent_*` 全 0**，映射逻辑写了但没数据可验。

---

## 6. 真实执行需要你确认的四件事

1. **账号**：先建 2 个 owner（`getmedia create-account <登录名> --owner`），把新旧 id 写进 `map.json`；
2. **窗口**：`apply` 期间停旧服务、新服务不对外，避免旧库还在写；
3. **文件搬移**：默认 `--files copy`（复制 10 MB，旧目录保留）；确认后再考虑 `mv` 还是保留；
4. **凭据密钥**：新 `GETMEDIA_CRED_KEY` 要在服务端 `.env` 配好，`apply` 时通过环境变量传入，不落盘、不进日志。

回滚路径：`--snapshot` 生成的目标库快照 + 旧库完全没动，最坏情况删掉新库重来。

---

## 7. 本次没有做的事

- 没有写 `/opt/getmedia`、`/var/lib/getmedia`、生产 `getmedia.db`；
- 没有动 `/opt/getaudio`、`/opt/getvideo` 与两个旧库；
- 没有停任何服务、没有改 nginx；
- 演练目录 `/tmp/gm-drill` 已删除（只留下本报告里的数字）。
