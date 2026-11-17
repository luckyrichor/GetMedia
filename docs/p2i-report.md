# P2i 报告：AI 素材与产物的存储归属、保留期与清理

> 承接 `docs/p2-report.md` §8.1 的"决定已定、代码未实施"。这一期把它做完：
> AI 文件计入服务器库 3 GiB、保留期 14 天、到期清理、超限有统一文案与页面提示。

## 1. 结论（对用户的四句话）

1. **算在哪**：AI 的源文件与产物计入**服务器库（archive）3 GiB**，和媒体库归档共用一个池子；
   网页缓存 5 GiB 一分不动。
2. **留多久**：默认 **14 天**（`GETMEDIA_AI_RETENTION_DAYS`），从项目最后一次活动（`updated_at`）起算；
   改描述、入队都会顺延。
3. **到期怎么清**：只删文件、**保留记录**（`status=expired`、`source_path=NULL`），历史仍可查；
   有排队/执行中任务的项目跳过，等它跑完。
4. **满了怎么说**：409 `quota_exceeded`，一句话：
   `服务器库空间不足（AI 素材与产物计入 3.0 GB）：已用 2.7 GB / 3.0 GB，本次需要 180 MB。先清理缓存或旧项目。`

## 2. 服务端改动

| 位置 | 改动 |
|---|---|
| `media.py` | `ai_stats()` 遍历 `media_dir/ai` 求和；`storage_used("archive")` 加上这部分 |
| `recreations.py` | `_ensure_room()` 上传前判配额；`_expiry()` 给项目加 `expires_at`/`days_left`/`retention_days`；`gc()`；`fmt_size()` |
| `media_api.py` | `GET /v2/maintenance/storage` 增 `ai: {files, bytes}`（`used.archive` 已含 AI） |
| `recreations_api.py` | `POST /v2/recreations/gc?days=&dry_run=`，写审计 `ai_gc` |
| `config.py` | `ai_retention_days=14`，`GETMEDIA_AI_RETENTION_DAYS`（1–365） |
| `cli.py` | `ai-gc [--days N] [--dry-run]` |
| `deploy/systemd/` | `getmedia-ai-gc.service` + `.timer`（每天 04:10，`Persistent=true`） |
| `deploy/env/getmedia.env.example` | 新增 `GETMEDIA_AI_ENABLED` / `GETMEDIA_AI_RETENTION_DAYS` 与归属说明 |

两处刻意的口径：

- **`by_storage` 不加 `ai` 键**。`by_storage` 是 `tracks` 表的 `GROUP BY storage`，把目录求和的 AI 塞进去会让
  "条目数"语义变形；AI 单独给 `ai: {files, bytes}`。两者**不可相加**——AI 已经算在 `used.archive` 里了，
  页面上的说明文字也写明了这一点。
- **`_ensure_room` 在 `create_upload` 里、且在建项目行之前**。拒绝时不留项目行、不留空目录、不写 `.part`，
  和媒体库 `ensure_room` 的"先拒绝、不留半成品"同口径。`from-track` 不查配额——它不复制文件，只是引用。

## 3. 清理规则（`RecreationService.gc`）

1. `updated_at < now - N 天`，且 `status != 'expired'`；
2. **没有** `queued` / `running` 任务（`NOT EXISTS` 子查询，一条 SQL 选出来）；
3. 只 `rmtree` `media_dir/ai/<项目号>/`；源来自媒体库的项目其文件在 `tracks` 那边，不受影响；
4. 记录保留：`status='expired'`、`source_path=NULL`；`recreation_runs` 完全不动，历史仍可查。

`--dry-run` 走同一条查询、只累计不删，返回 `{projects, freed_bytes, days, dry_run: true}`，
所以"演练"和"真清"选中的是同一批项目，不会出现演练说 3 个、真清删了 5 个。

## 4. 页面

**AI 页（5f 的页面）**

- 新建区顶部一条配额：`已用 / 3.0 GB`、进度条、`其中 AI 100 MB（3 个文件）· 还可上传 … · 保留 14 天`；
- 项目列表每行末尾：`还剩 N 天`；已清理的显示 `文件已过期清理` 标签；
- 详情顶部一句保留期；`source_path` 为空时**不渲染 `<video>`**（渲染出来只会是个坏播放器），
  改为说明"文件已过期清理，记录还在，要重做就重新上传"；
- 上传被拒时直接显示服务端那句话（`messageFor` 透传 `detail`），不自己编文案。

**库维护页（5e 的页面）**

- 归档卡片下方注明"（含 AI …）"，表格里单列一行「AI 素材与产物（计入归档）」；
- 「清理」多一个「清理过期的 AI 文件」，二次确认，完成后显示释放量与项目数。

## 5. 验证

```
服务端：.venv/bin/python -m pytest -q server/tests tools/tests
        332 passed, 50 skipped（新增 test_recreations_quota.py 10 项）
前端：  npx tsc --noEmit            通过
        npx vitest run              129 passed（14 文件；ai 7、maintenance 6）
        npm run build               两个产物成功
        npm run size                公开页 20.3 KB gzip（未变）；管理台 34.0 KB（上限 80）
契约：  pytest -q tools/tests/test_web_contract.py     9 passed（新增的 gc 调用对得上真实路由）
扫描：  scan_leaks --all 通过；scan_forbidden heavy/legacy 通过
```

新增用例（`server/tests/test_recreations_quota.py`，10 项）：

| 用例 | 守住什么 |
|---|---|
| `test_upload_counts_into_archive_quota` | AI 文件进 `used.archive`，`by_storage` 里看不到（不是靠 tracks 记账） |
| `test_over_quota_upload_rejected_without_side_effects` | 409 + 四个文案要素；不留项目行、不留第二个目录 |
| `test_project_exposes_remaining_days` | 14 天、随活动顺延、到期归零 |
| `test_gc_dry_run_then_apply` | 演练不删；真清删文件、置 `expired`、源接口 409、配额归零 |
| `test_gc_skips_project_with_active_run` | 有 queued/running 不动 |
| `test_gc_respects_explicit_days` | `--days` 覆盖配置 |
| `test_gc_does_not_touch_media_library_file` | 只删 `ai/<项目号>/`，媒体库文件不受牵连 |
| `test_gc_is_owner_only` | 匿名 401、普通账号 403 |
| `test_media_archive_room_accounts_for_ai_bytes` | 同一个 3 GiB：AI 占满后媒体入库也拒 |
| `test_cli_ai_gc_prints_days_and_dry_run` | CLI 接上配置与演练标志 |

## 6. 已知边界（都是明确的取舍，不是漏做）

- **并发上传**：配额是"上传前"判断，两个并发上传可能同时通过（最多超出约一个文件大小）。
  单 owner 场景没加锁；真要收紧，可以在 `recreations` 上加一把 SQLite 写事务。
- **worker 不判 AI 配额**：产物由 worker 写盘，只查磁盘剩余（`ai_min_free_mb`），
  不查 3 GiB 池子——产物大小不可预知，事后超了由下一次上传被拒来体现。
- **没有"立即删除项目"**：页面仍不能删项目，只能等保留期或手动跑 `ai-gc --days 1`。
  这是 5f 就写明的边界，本期没有扩大范围。
- **记录不删**：`gc` 只删文件，行永久保留（`status=expired`），所以配额会归零、但项目数会累加；
  要彻底清理需要另做"删除项目"接口。
- **保留期从 `updated_at` 起算**：只要还在动这个项目就会顺延，长时间不动的才会被清。

## 7. 顺手修掉的一个偶发失败

`server/tests/test_feedback.py::test_image_exif_stripped_and_reencoded` 前后失败过两次，
两次都被记成"沙箱文件写入抖动，未复现"。这次按"先定性再改"的要求查到底：

```
>       assert name.endswith(".jpg") and "a.jpg" not in name
E       AssertionError: assert (True and 'a.jpg' not in '399db612...1f57015a.jpg')
E         'a.jpg' is contained here: ...1f57015a.jpg
```

落盘名是 `secrets.token_hex(16)` + `.jpg`，末位恰好是 `a` 时文件名就是 `…a.jpg`，里面自然含 `a.jpg`。
概率 1/16，正好解释"十几轮挂两次"。**不是 429 限流，也不是 EXIF 残留，更不涉及跨测试共享限流状态**
（每个用例各自有独立临时库，`fb:acct` / `fb:ip` 的计数天然隔离）。

改法：断言换成 `re.fullmatch(r"[0-9a-f]{32}\.jpg", name)`——既守住"不用客户端文件名"，
又精确校验随机名格式，不再有随机碰撞。`docs/p5c-report.md` §3.2 已补记根因。
