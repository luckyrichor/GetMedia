# P2 服务端功能合并 · 完成情况（2026-11-16）

范围：把 GetAudio / GetVideo 的能力合并进 GetMedia 服务端，按你指定的顺序推进：
**媒体库与提取与媒体流 → 博主批量 → 定时 → Agent → 授权 → 上报 → AI（最后）**。
所有改动只在本地仓库；生产服务器未部署、未重启、未写库。

## 1. 总览

| 项 | 结果 |
|---|---|
| 新增 owner 接口 | **67 条**（全部挂 `require_tier("owner")`，启动自检未防护路由 **0**） |
| 服务端测试 | **214 passed**（P1 的 165 + P2 的 49） |
| 权限矩阵 | 67 条 owner 路由 × 匿名/free/owner，**违反 0**（`tools/permission_matrix.py`） |
| 数据迁移 | 新增 v5–v10 六个编号迁移，幂等；迁移前快照机制沿用 P1 |
| 服务器 IP 实测 | B 站全链路通过、抖音被 Argus 拦截，见 `platform-matrix-server-2026-11-16.md` |

按模块的接口数：

| 模块 | 接口 | 说明 |
|---|---|---|
| 媒体库/提取/媒体流 | 12 | `/v2/resolve`、`/v2/extract`、`/v2/tracks*`、`/v2/sync`、`/v2/media/{id}` |
| 维护 | 3 | `storage` 报告、`prune-cache`、`orphans` |
| 博主批量 | 12 | 含 preview 扩展（三种选取）、jobs CRUD、暂停/恢复/重试、状态回传、`items:import` |
| 定时 | 7 | CRUD + `run-once` + 运行历史 |
| Agent | 8 | 会话/消息 + 偏好记忆 |
| 平台授权 | 9 | 平台列表、绑定、授权会话、Cookie 导入、解绑、抖音回调 |
| 失败上报 | 4 | 上报、列表、详情、按平台/错误码汇总 |
| AI 重建 | 12 | 能力、资源、上传、分析、生成、运行取消/播放/保存 |

## 2. P2a 媒体库 / 提取 / 媒体流

**数据**：`tracks`（`media_type`、`storage`、`bytes`、`sha256`、`pinned`、`deleted`、`updated_seq`、`cache_expires_at`）、
`extract_jobs`、`extraction_failures`；`UNIQUE(user_id, platform, video_id, page)`。

**行为**：

- `storage` 四态：`metadata`（默认，只记账）/ `archive`（长期，3 GiB 配额）/ `cache`（1 天、5 GiB、LRU）/ `phone`（批量任务用，服务器不落盘）。
- `/v2/extract` 立即返回 202，后台执行；任务状态落 `extract_jobs`，失败带稳定 `error_code`（`quota_exceeded`、`download_failed`…）。
- `/v2/media/{id}`：有归档/缓存文件走本地文件（**手写 Range**：206 + `Content-Range`，越界 416）；没有文件才当场解析并中转，不落盘。
- 文件写入 `临时文件 → fsync → 原子改名`，失败不留半成品；配额不足先拒绝，缓存可先按 LRU 淘汰。
- `updated_seq` 单调递增，`/v2/sync?since_seq=` 返回增量（含 tombstone），手机换设备可重建视图。

**解析器**（`extractor.py`）：B 站走 `view` + wbi `playurl`（`fnval=1` 取混流 mp4，便于直接播放）；
抖音不逆向私有接口，直接返回 503 并提示走手机端。**服务器 IP 实测发现并修掉一个缺陷**：机房 IP 不带 `buvid3` 请求 `view` 会 412，
现在缺配置 Cookie 时自动预热访客 Cookie（与 `creators/` 一致），复测通过。

**测试**：`tests/test_media.py` 11 项（元数据/归档/缓存过期/配额拒绝/Range 206 与 416/增量同步与墓碑/隔离/回退解析/存储报告）。

## 3. P2b 博主批量

**数据**：`creator_jobs`（`mode`、`since_ts`/`until_ts`/`limit_n`、`include_pinned`、`cursor_json`、`status`、计数、预估字节）、`creator_items`。

**三种选取**共用一条"按发布时间倒序翻页"的循环：

- `all`：翻到底；单次运行最多 `creator_max_pages_per_run`（默认 200 页）后转 `paused/segment_limit`，`cursor.start` 已落库，`resume` 继续，不重复（`INSERT OR IGNORE` + 唯一键）。
- `date_range`：日期按**北京时间**解释，`until` 取当日 23:59:59；本页最旧一条早于 `since` 即停（列表本身倒序）。
- `latest_n`：入库条数达到 n 即停，**不会多插一页**。

置顶：B 站用 `x/space/top/arc`（公开、无需登录，实测通过）；抖音读 `is_top`。`is_pinned` 只作角标与筛选，
**排序仍按发布时间**（置顶不插队）；`include_pinned=false` 时在筛选后剔除。

断点与状态：任务 `pending → running → (paused|done|failed|cancelled)`；条目 `pending/downloading/done/failed/skipped`。
手机按 `/items?state=pending` 领取，完成后 `POST /items/{video_id}/state` 回传；`items:import` 是"手机列"兜底（去重后入库）。
`retry` 只重置失败条目并让任务可重跑。

**测试**：`tests/test_creator_jobs.py` 10 项（n 截断、日期区间与提前停止、分段续跑不重复、置顶开关、状态回传计数、导入去重、暂停/取消/重试、预览三种模式、隔离、参数校验）。

## 4. P2c 定时

`keywords.yaml` 的 groups 改为 `schedules` 表（`interval_minutes` 15–10080，不用 cron 表达式，网页可安全改写）+ `schedule_runs`。
每次触发只是**建一个批量任务**并跑一次，任务自己带游标；`next_run` 从"应跑时间"往后推，手动 `run-once` 不会打乱节奏。
后台线程每小时 `tick()` 一次，最多同时跑 5 个到期任务。

旧 YAML 的导入函数已实现（`schedules.import_legacy`，幂等、跳过同名），但**不在启动时自动执行**——放到 P3 迁移 CLI 里调，避免启动时依赖 PyYAML 与"哪个账号是 owner"。

**测试**：`tests/test_schedules.py` 5 项。

## 5. P2d Agent

会话 / 消息 / 偏好记忆三张表；`/v2/agent/chat` 落库后再生成回复。
**模型是可选件**：配了 `GETMEDIA_LLM_API_KEY` + `GETMEDIA_LLM_MODELS` 才走模型（Ark，OpenAI 兼容，
多模型 fallback、每日 200 次上限、超限回落规则），否则用确定性规则应答（批量、定时、库统计、授权、
失败上报五类意图 + 兜底说明）。两种情况响应里都如实标 `engine`（`model` / `rules`）与原因，不假装。
`brain` 仍是可注入的 callable，替换这一处就能换模型。

**动作闭环（P2h，schema v11）**：消息里出现可解析的作品链接 → 服务端建一条 `pending` 动作；
手机 `POST /v2/agent/actions/{id}/claim` 领取，`/result` 回报（同结果幂等、换结果 409、未领取回报 409）。
动作由服务端按链接生成，**不依赖模型**——模型挂了这条链路照常走。

**测试**：`tests/test_agent.py` 5 项 + `tests/test_agent_actions.py` 19 项（含假上游的多模型降级、配额、凭据护栏）。

## 6. P2e 平台授权

- 平台与能力：B 站 / 抖音，方式 `webview`（网页登录取 Cookie）与 `cookie`（导入）。
- 授权会话 10 分钟有效；Cookie 解析支持 `a=1; b=2` 与 Netscape 多行；B 站要求含 `SESSDATA`，抖音要求含 `sessionid`/`ttwid`。
- **Cookie 只存密文**（`CredentialVault` AES-256-GCM）；明文只以 600 权限的 Netscape 文件落在 `creator_cookie_dir`，供 `creators/` 使用；接口响应从不回显 Cookie。
- 未配置 `GETMEDIA_CRED_KEY` 时导入返回 503，不降级成明文。
- 抖音开放平台 OAuth 回调保留为匿名路由，**未配置时明确 501**（线上从未启用，不伪造成功）。

**测试**：`tests/test_platform_auth.py` 7 项。

## 7. P2f 失败上报

`POST /v2/diagnostics/extractions` 落 `extraction_failures`，`GET` 列表/详情 + 按平台与错误码的 7 天汇总。
字段长度在入口裁剪；不接受 Cookie/直链字段。

**测试**：`tests/test_diagnostics.py` 3 项。

## 8. P2g AI 重建（最后做）

资源 `recreations` + 队列 `recreation_runs`；API 只入队（202），执行在独立进程 `python -m getmedia.ai_worker`：

- `--once` / 常驻 / `--status`；启动时把卡死的 `running` 重置（`attempts>=3` 直接失败，不无限重试）；
- 磁盘保护：剩余空间 < `ai_min_free_mb` 直接 `failed(no_space)`；产物写临时目录后原子改名；
- 模型执行函数由 `GETMEDIA_AI_PLUGIN=包.模块:函数` 注入，**没有配置时任务标 `ai_unconfigured`**，不假装成功；
- `capabilities.available=false` 时 `analyze`/`generate` 返回 503。

**测试**：`tests/test_recreations.py` 8 项（能力开关、上传与 Range、超限 413、描述必填、领取/完成/产物播放、取消、卡死重置、worker 未配置与注入 runner）。

### 8.1 存储归属、清理时机与超限提示

**结论：AI 的源文件与产物计入「服务器库」（archive，默认 3 GiB），不计入网页缓存（5 GiB）。**

理由：

- **语义**：`cache` 是"随时可丢、到期即删"的中转；AI 源文件是用户自己传的素材，产物是花钱算出来的，
  删了不能自动重建（要重新生成）。它和 `archive` 同构，不同 `cache`。
- **生命周期**：`cache` 由 TTL + LRU 自动淘汰；`archive` 只由用户主动删或 owner 清理。AI 也应当如此。
- **容量**：单文件上限 200 MiB，一次上传 + 一份产物就可能占掉 400 MiB。放进 5 GiB 的缓存会把网页缓存挤掉，
  而网页缓存被挤掉的代价是"下次重新解析"，AI 被挤掉的代价是"素材和结果都没了"。

#### 实施状态：P2i 已落地（2026-11-17）

原先这两个配额**都不计**：AI 文件在 `media_dir/ai/<项目号>/`，不在 `tracks` 表里，而
`storage_used()` / `stats()` 只按 `tracks` 求和；上传只拦单文件 200 MiB，`purge_orphans()`
只删两个目录的**顶层文件**（`ai/` 是目录，误删不了也回收不了），也没有任何保留期任务。

P2i 之后的行为（逐条对应下面"目标行为"）：

| 行为 | 实现位置 |
|---|---|
| `storage_used("archive")` = tracks 的 archive 字节 + `media_dir/ai` 目录字节 | `media.py::storage_used`、`ai_stats` |
| 上传前判配额，超了 409 `quota_exceeded`，**不建项目、不写盘** | `recreations.py::_ensure_room` |
| `/v2/maintenance/storage` 多一个 `ai: {files, bytes}`；`used.archive` 已含 AI | `media_api.py` |
| 保留期 `GETMEDIA_AI_RETENTION_DAYS`，默认 **14 天** | `config.py` |
| `cli ai-gc [--days N] [--dry-run]` + `getmedia-ai-gc.timer`（每天 04:10） | `cli.py`、`deploy/systemd/` |
| 项目带 `expires_at` / `days_left` / `retention_days`；管理台显示"还剩 N 天" | `recreations.py`、`web/owner-app` |

4 GiB 的事不再有歧义：媒体库归档与 AI 共用一个 3 GiB 池子，`ensure_room("archive", …)` 在两个入口
判的是同一笔账；缓存 5 GiB 一分不动。

清理规则（`RecreationService.gc`）：

1. `updated_at` 早于 N 天、且状态不是 `expired`；
2. **没有** `queued`/`running` 任务——删了文件会让 worker 半路失败，等它跑完下一轮自然会清；
3. 只删 `media_dir/ai/<项目号>/`；源来自媒体库的项目，其真身在 `tracks` 那边，**不受影响**；
4. 记录保留，`status='expired'`、`source_path=NULL`；`recreation_runs` 不动，历史仍可查；
   页面显示"文件已过期清理"且不再渲染源播放器（渲染出来只会是个坏播放器）。

#### 超限提示（服务端与客户端用同一句）

- 服务端 409 `quota_exceeded`：
  `服务器库空间不足（AI 素材与产物计入 3.0 GB）：已用 2.7 GB / 3.0 GB，本次需要 180 MB。先清理缓存或旧项目。`
  体积用 `fmt_size()` 统一格式化（GB 一位小数 / MB 整数），所以窗口变大变小文案都跟着变。
- 管理台：AI 页配额条（已用 / 3 GiB、其中 AI 多少、"还可上传"）+ 上传失败直接显示服务端那句话；
  库维护页单列一行「AI 素材与产物（计入归档）」并给「清理过期的 AI 文件」按钮（二次确认）。
- 手机端：显示服务端 `detail`，不自己编文案（`error_code` 用于分支，文案只透传）。

#### 为什么单独一期（P2i）

配额判断、统计口径、保留期清理、维护接口与 CLI 要一起改，散着改容易出现"文档说计了、代码没计"。
P2i 的细节与测试见 `docs/p2i-report.md`。

## 9. 与 `plan.md` 的偏差（请重点看这一节）

| # | 计划写法 | 实际做法 | 原因 |
|---|---|---|---|
| D1 | Agent 沿用 `/agent/v2/conversations`、`/v2/memories/{userId}` | 改为 `/v2/agent/conversations`、`/v2/agent/chat`、`/v2/memories` | GetMedia 统一 `/v2` 命名；`user_id` 从会话取已落地。旧 App 在 P4 会改，若要兼容旧路径可加别名（1 条 include 的事），你说要就加 |
| D2 | 库维护含 `reclassify` / `repair-audio-sizes` / `fingerprints/backfill` / `duplicates` | 只做 `storage`、`prune-cache`、`orphans` | 前三项是音频库专属语义（分类、音频大小、声纹），视频库无对应物；去重可在 P3 按 `(platform, video_id)` 做 |
| D3 | AI 的 systemd 单元、`/etc/getmedia/ai.env` | 代码与 CLI 就绪，unit 与 env 放到 P6 部署 | 现在没有模型密钥，先不写部署文件，避免留下"看起来能用"的单元 |
| D4 | 启动时导入 `keywords.yaml` | 提供 `import_legacy()`，由 P3 迁移 CLI 调用 | 启动时导入需要先知道 owner 账号，且会给启动增加 YAML 依赖 |
| D5 | `/v2/extract` 由独立 worker 跑 | `BackgroundTasks` 执行（进程内线程），状态落库 | Q10 允许"进程内线程 + 持久游标"；长任务阻塞只在单请求线程，P6 视负载再拆 |

## 10. 未做 / 下一步

- **P3 迁移工具**：`tools/migrate_legacy.py` 已完成并在真实库副本上演练，见 `p3-report.md`；真实执行等 P2 部署后。
- **P4 Android 契约**：full 接这批新接口（媒体库同步、批量任务、定时、Agent、授权、AI），free 保持"暂未开放"；`ui-plan.md` 先出。

## 11. 进度快照与目标 schema（2026-11-16 补）

### 11.1 七个工作包全部完成

| 工作包 | 接口 | 数据表 | 迁移版本 | 测试 | 状态 |
|---|---|---|---|---|---|
| P2a 媒体库/提取/媒体流 | 12 + 维护 3 | `tracks`、`extract_jobs`、`extraction_failures` | v5 | `test_media.py` 11 项 | 完成 |
| P2b 博主批量 | 12 | `creator_jobs`、`creator_items` | v6 | `test_creator_jobs.py` 10 项 | 完成 |
| P2c 定时 | 7 | `schedules`、`schedule_runs` | v7 | `test_schedules.py` 5 项 | 完成 |
| P2d Agent | 8 | `agent_conversations/messages/memories` | v8 | `test_agent.py` 5 项 | 完成 |
| P2e 平台授权 | 9 | `platform_credentials`、`platform_auth_sessions`、`account_platform_bindings` | v9 | `test_platform_auth.py` 6 项 | 完成 |
| P2f 失败上报 | 4 | 复用 `extraction_failures` | — | `test_diagnostics.py` 3 项 | 完成 |
| P2g AI 重建 | 12 | `recreations`、`recreation_runs` | v10 | `test_recreations.py` 9 项 | 完成，默认关闭 |
| P2h Agent 模型 + 动作 | 5 | `agent_actions`、`agent_usage` | v11 | `test_agent_actions.py` 18 项 | 完成；无密钥时纯规则 |
| P2i AI 存储归属 + 保留期 | 1 | 无新表（`recreations.status` 复用 `expired`） | 仍 v11 | `test_recreations_quota.py` 10 项 | 完成；14 天保留期 |

合计 **72 条 owner 路由**、P2 测试 **78 项**；全仓当前 **332 passed / 50 skipped**（skip 是需要 nginx 的原有用例，
其中 41 项已在服务器上跑过）。启动自检未防护路由 **0**，权限矩阵违反 **0**。

### 11.2 目标 schema 版本 = v11

- 目标库由服务端 `server/getmedia/db.py` 的 `MIGRATIONS` 建到 **v11**；版本号记在 `schema_version` 表的最大值。
- `tools/migrate_legacy.py` 顶部有 `TARGET_SCHEMA = 11`，`apply` / `verify` 打开目标库后先校验：
  版本不等于 11 直接 `错误：目标库 schema v10，本工具要求 v11…`（退出码 2），**不会写一半**。
- `plan` 不校验（它只读旧库），所以可以在目标库还没建好时先盘点。
- v11 只加了两张 Agent 动作表（`agent_actions`、`agent_usage`），**旧数据没有任何字段变动**，迁移工具的字段映射
  （`tracks.media_type/storage/bytes/sha256`、`extract_jobs` 状态、`platform_credentials.cookie_blob`、
  `account_platform_bindings`、`recreations`）不受影响，不需要补迁。
- 以后若再加迁移（v12…），**必须同时改 `TARGET_SCHEMA`**，否则 `apply` 会拒绝在新库上写入——这是故意的。

### 11.3 尚未完成、但不属于 P2 的

- 抖音"带 Cookie"那一行实测：服务器上还没有任何抖音 Cookie，要等授权流程上线后导入一次再补；
- AI 真模型：没有密钥，`GETMEDIA_AI_PLUGIN` 留空，任务一律 `ai_unconfigured`；
- 部署（P6）与 Android 契约（P4）未开始。
- **P5 网页**：`web-owner` 需要 nginx `location ^~ /getmedia/owner/api/` + `auth_request`，否则服务端判权再对也进不来。
- **抖音带 Cookie 行**：服务器上没有可用 Cookie，实测矩阵缺这一行；等 P2e 授权流程上线后补测。
- **AI 真模型**：没有密钥，`GETMEDIA_AI_PLUGIN` 留空；接入时只写一个函数并配置该变量。
