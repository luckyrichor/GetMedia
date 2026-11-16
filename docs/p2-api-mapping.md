# P2 接口对照表：旧 GetAudio / GetVideo → GetMedia（2026-11-16）

口径：旧两库的路由声明**并集 85 条**（Audio 72 / Video 85，Video 多出 13 条 AI 重建与 `/v2/video/{id}`）。
新服务：**免费 18 条**（`FREE_ROUTES`，默认拒绝之外的白名单）+ **owner 67 条**（全部 `require_tier("owner")`）。
本表逐条给出处置，不留"大概合并了"的模糊项。

图例：**保留**＝路径与语义不变；**改名**＝语义不变、路径按 GetMedia 规范调整；**合并**＝并入另一条资源；
**丢弃**＝旧版历史接口/新客户端不调用/与新约束冲突；**未实现**＝计划内但本轮没做（写明原因）。

---

## 1. 账号与会话（旧 4 条 + 旧扁平 2 条）

| 旧路由（A/V 相同） | 处置 | 新路由 | 说明 |
|---|---|---|---|
| `POST /account/register` | 保留 | `POST /account/register` | GetMedia 版；登录名 + Argon2id，不收手机号 |
| `POST /account/login` | 保留 | `POST /account/login` | 会话头 `X-GetMedia-Session` + 设备上限 |
| `GET /account/me` | 保留 | `GET /account/me` | 返回 tier、设备数 |
| `POST /account/logout` | 保留 | `POST /account/logout` | |
| `POST /v2/migration/claim` | 丢弃 | — | 旧版历史接口；新方案改为登录后按账号映射迁移（见 P3） |
| 旧 `AppUser.phone` + scrypt | 丢弃 | — | 改为登录名 + Argon2id（PLAN 决定 11） |

新服务另有 7 条旧版没有的账号安全路由（`DELETE /account/me`、`/account/consent`、`/account/recovery/ack`、
`/account/devices*`、`/account/password`、`/account/recover`），属 P1 已交付范围。

## 2. 媒体库 / 提取 / 媒体流

### 2.1 旧 `/v2` 资源（`account_api.py` + `library_api.py`，A/V 相同）

| 旧路由 | 处置 | 新路由 | 说明 |
|---|---|---|---|
| `POST /v2/extract` | 保留 | `POST /v2/extract` | 202 + `extract_jobs`；新增 `storage` 三态（metadata/archive/cache） |
| `GET /v2/tracks` | 保留 | `GET /v2/tracks` | 新增 `media_type`、`pinned`、`deleted` 过滤 |
| `GET /v2/sync` | 保留 | `GET /v2/sync` | 改为 `since_seq` 单调游标，带 tombstone |
| `GET /v2/audio/{track_id}`（A） | 合并 | `GET /v2/media/{track_id}` | 音视频统一入口，手写 Range 206/416 |
| `GET /v2/video/{track_id}`（V） | 合并 | `GET /v2/media/{track_id}` | 同上 |
| `GET /v2/tracks/{track_id}` | 保留 | 同 | |
| `PATCH /v2/tracks/{track_id}` | 保留 | 同 | 只允许 title/uploader/pinned |
| `DELETE /v2/tracks/{track_id}` | 保留 | 同 | 软删 + 墓碑 |
| `POST /v2/tracks/{track_id}/restore` | 保留 | 同 | |
| `GET /v2/extraction-jobs` | 保留 | 同 | |
| `POST /v2/maintenance/reclassify` | 丢弃 | — | 音频分类专属，视频库无对应物；且旧实现依赖模型分类 |
| `POST /v2/maintenance/repair-audio-sizes` | 丢弃 | — | 旧音频库的历史脏数据修复，新库无此问题 |
| `POST /v2/maintenance/fingerprints/backfill` | 丢弃 | — | 声纹指纹是音频独有；新方案不做指纹 |
| `GET /v2/maintenance/duplicates` | 未实现 | — | 去重改由 `(user_id, platform, video_id, page)` 唯一键在写入时保证；查询接口留到需要时加 |

### 2.2 旧扁平 `/` 路由（`api.py`，更早一代客户端）

| 旧路由 | 处置 | 新路由 | 说明 |
|---|---|---|---|
| `GET /health` | 保留 | `GET /health` | 新增 release/runtime 信息 |
| `POST /extract` | 丢弃 | `POST /v2/extract` | 与 `/v2/extract` 重复；旧 App 无调用（§1.3 扫描证据） |
| `GET /tracks` | 丢弃 | `GET /v2/tracks` | 同上 |
| `GET /sync` | 丢弃 | `GET /v2/sync` | 同上 |
| `GET /audio/{track_id}` | 丢弃 | `GET /v2/media/{id}` | 旧版历史接口，新客户端不调用；新路由统一为 `/v2/media/{id}`，归属在会话内校验 |
| `GET /video/{track_id}`（V） | 丢弃 | `GET /v2/media/{id}` | 同上 |

### 2.3 新增（旧版没有）

| 新路由 | 用途 |
|---|---|
| `GET /v2/extraction-jobs/{job_id}` | 单任务详情（旧版只能靠列表轮询） |
| `GET /v2/media/{track_id}` | 统一流式出口（本地文件优先，缺失才当场解析） |
| `DELETE /v2/tracks/{track_id}/file` | 只删服务器文件、保留记录（配额管理） |
| `GET /v2/maintenance/storage` | 归档/缓存/配额报告 |
| `POST /v2/maintenance/prune-cache` | 按 TTL/LRU 清缓存 |
| `POST /v2/maintenance/orphans` | 孤儿文件清理 |

## 3. 博主批量（旧 7 条 → 新 12 条）

| 旧路由（`/v2/creators`） | 处置 | 新路由 | 说明 |
|---|---|---|---|
| `POST /preview` | 保留 + 扩展 | `POST /v2/creators/preview` | 入参新增 `mode`（window/all/date_range/latest_n）、`include_pinned` |
| `POST /jobs` | 保留 | `POST /v2/creators/jobs` | 新增 `mode`、`since/until`、`limit_n`、`include_pinned` |
| `GET /jobs` | 保留 | 同 | 新增 `status` 过滤与游标 |
| `GET /jobs/{job_id}` | 保留 | 同 | 带计数与 `cursor` |
| `GET /jobs/{job_id}/items` | 保留 | 同 | 新增 `state=pending` 领取视图 |
| `POST /jobs/{job_id}/cancel` | 保留 | 同 | |
| `POST /jobs/{job_id}/retry` | 保留 | 同 | 只重置失败条目 |
| — | 新增 | `POST /jobs/{job_id}/pause` | 断点续跑必需 |
| — | 新增 | `POST /jobs/{job_id}/resume` | 从 `cursor.start` 续跑，不重复 |
| — | 新增 | `POST /jobs/{job_id}/items/{video_id}/state` | 手机回传下载状态（下载在手机） |
| — | 新增 | `POST /jobs/{job_id}/items:import` | 抖音"手机列"兜底：手机抓到列表后回传入库 |
| — | 新增 | `GET /v2/creators/limits` | 预览次数/条数上限，供 App 显示 |

## 4. 定时（旧 2 条 → 新 7 条）

| 旧路由 | 处置 | 新路由 | 说明 |
|---|---|---|---|
| `GET /v2/scheduler` | 改名 | `GET /v2/schedules` | 旧版读 YAML 的只读视图 |
| `POST /v2/scheduler/run-once` | 改名 | `POST /v2/schedules/{id}/run-once` | 改为按资源操作 |
| — | 新增 | `POST/GET/PATCH/DELETE /v2/schedules[/{id}]` | YAML → 表，网页可安全改写 |
| — | 新增 | `GET /v2/schedules/{id}/runs` | 运行历史 |

旧 `config/keywords.yaml` 的 `groups` 由 `schedules.import_legacy()` 在 P3 迁移 CLI 里导入（幂等、跳过同名），不在启动时自动跑。

## 5. Agent 与偏好记忆（旧 10 条 → 新 8 条）

| 旧路由 | 处置 | 新路由 | 说明 |
|---|---|---|---|
| `GET /agent/v2/conversations` | 改名 | `GET /v2/agent/conversations` | 统一 `/v2` 前缀；旧 App 兼容别名见 §9 |
| `GET /agent/v2/conversations/{cid}` | 改名 | `GET /v2/agent/conversations/{id}` | |
| `DELETE /agent/v2/conversations/{cid}` | 改名 | `DELETE /v2/agent/conversations/{id}` | |
| `POST /agent/v2/chat` | 改名 | `POST /v2/agent/chat` | |
| `POST /agent/v2/actions/{aid}/claim` | 未实现 | — | 动作闭环，见 `docs/agent-model-plan.md` 方案 B |
| `POST /agent/v2/actions/{aid}/result` | 未实现 | — | 同上 |
| `GET /v2/memories/{user_id}` | 改名 | `GET /v2/memories` | **去掉 URL 里的 user_id**，统一从会话取（新接口不接收调用方传入的用户标识） |
| `POST /v2/memories` | 改名 | `PUT /v2/memories/{key}` | 幂等 upsert |
| `PUT /v2/memories/{user_id}/{memory_id}` | 改名 | `PUT /v2/memories/{key}` | 改按 key 定位 |
| `DELETE /v2/memories/{user_id}/{memory_id}` | 改名 | `DELETE /v2/memories/{key}` | |
| `POST /agent/chat`（旧扁平） | 丢弃 | — | 无客户端调用 |
| `GET/POST/PUT/DELETE /agent/memories[/{user_id}…]`（旧扁平） | 丢弃 | — | 无客户端调用 |

## 6. 平台授权（旧 9 条 → 新 9 条）

| 旧路由（`/account`） | 处置 | 新路由 | 说明 |
|---|---|---|---|
| `GET /platforms` | 保留 | `GET /account/platforms` | 增加 `capabilities`（webview/cookie） |
| `GET /platform-bindings` | 保留 | `GET /account/platform-bindings` | **不回显 Cookie 密文或明文** |
| `POST /platform-auth/start` | 保留 | 同 | 返回会话 id 与授权入口 |
| `POST /platform-auth/status` | 保留 | 同 | 轮询会话状态 |
| `POST /platform-auth/complete` | 保留 | 同 | WebView 完成后回调 |
| `POST /platform-auth/cancel` | 保留 | 同 | |
| `GET /platform-auth/douyin/callback` | 保留 | 同 | 抖音开放平台回调；未配置时 501，不伪造成功 |
| `POST /platform-bindings/{platform}/unbind` | 保留 | 同 | |
| `POST /platform-auth/image` | 丢弃 | `POST /account/platform-auth/cookie` | 旧版上传二维码图片；新版改为直接导入 Cookie（更少中间态） |

**还有一代更早的授权接口**（`account_api.py`，前缀是 `/v2` 而不是 `/account`，容易被漏掉）：

| 旧路由 | 处置 | 新路由 | 说明 |
|---|---|---|---|
| `GET /v2/platforms` | 合并 | `GET /account/platforms` | 同语义，路径统一到 `/account` |
| `POST /v2/platforms/qr` | 合并 | `POST /account/platform-auth/start` | |
| `GET /v2/platforms/qr/{sid}` | 合并 | `POST /account/platform-auth/status` | 由轮询 GET 改为带会话 id 的 POST |
| `POST /v2/platforms/cookies` | 合并 | `POST /account/platform-auth/cookie` | |
| `GET /v2/platforms/credentials` | 合并 | `GET /account/platform-bindings` | 只返回绑定状态，不回显 Cookie |
| `DELETE /v2/platforms/credentials/{platform}` | 合并 | `POST /account/platform-bindings/{platform}/unbind` | |
| `GET /v2/platforms/qr/{sid}/image` | 丢弃 | — | 二维码图片渲染由客户端做，服务端不再转图 |

旧扁平 `/login/*`（5 条）与 `/credentials/{user_id}`（2 条）全部**丢弃**：旧版历史接口，新客户端不调用，
功能已由 `/account/platform-*` 与 `/account/platform-bindings` 覆盖。

新服务新增 `POST /account/platform-auth/cookie`（导入 Cookie，AES-256-GCM 加密入库，未配密钥时 503）。

## 7. 失败上报（旧 3 条 → 新 4 条）

| 旧路由（`/v2/diagnostics/extractions`） | 处置 | 新路由 | 说明 |
|---|---|---|---|
| `POST ''` | 保留 | `POST /v2/diagnostics/extractions` | 只允许 full；free 不上报 |
| `GET ''` | 保留 | 同 | owner 只读 |
| `GET /{report_id}` | 保留 | 同 | |
| — | 新增 | `GET /v2/diagnostics/summary` | 按平台/错误码 7 天汇总 |

## 8. AI 视频重建（仅 Video，12 条 → 12 条）

12 条**路径与语义全部沿用**，只把鉴权换成 `require_tier("owner")`：

| 旧路由（`/v2/recreations`） | 新路由 | 说明 |
|---|---|---|
| `GET /capabilities` | 同 | 增加 `available`（模型未配置时 503 的前置判断） |
| `GET ''` | 同 | 列表 |
| `GET /{pid}`、`GET /{pid}/source` | 同 | 详情与源文件（Range） |
| `POST /upload`、`POST /from-track/{track_id}` | 同 | 上传受 `client_max_body_size` 与磁盘配额约束 |
| `POST /{pid}/analyze`、`PUT /{pid}/description`、`POST /{pid}/generate` | 同 | 只入队 202，执行在 `getmedia-ai-worker` |
| `POST /{pid}/runs/{rid}/cancel`、`GET /{pid}/runs/{rid}/video`、`POST /{pid}/runs/{rid}/save` | 同 | 运行态管理 |

不改路径的原因：这条链路涉及外部模型调用与产物路径，改名收益不抵回归风险。

## 9. 旧 App 兼容（可选，未做）

旧 App 调的是 `/agent/v2/*` 与 `/v2/memories/{user_id}`。P4 会改客户端，所以服务端**没有加别名**。
若你希望旧包装上新服务后还能用 Agent 页，可加 3 条别名（`/agent/v2/conversations`、`/agent/v2/chat`、
`/v2/memories/{user_id}` → 内部转 `/v2/agent/*` 并从会话取 user_id）。这是 1 个 include 的事，等你一句话。

### 9.1 三条"未实现"逐条说明（2026-11-16 用户问）

| 旧路由 | 为什么当时没做 | 现在要不要补 | 补的话落到哪 |
|---|---|---|---|
| `POST /agent/v2/actions/{aid}/claim` | P2 的 Agent 只有确定性规则应答，**没有任何"动作"可领**；先做一条只能返回 404/空的接口是假实现 | **要补** | Agent 方案 B（已定）：新路径 `POST /v2/agent/actions/{id}/claim`，配 `agent_actions` 表（schema v11）。旧路径不改名兼容，P4 客户端直接调新路径 |
| `POST /agent/v2/actions/{aid}/result` | 同上：没有 claim 就没有 result 的状态机（`claimed → done/failed`、重复 claim 409） | **要补** | 同上，`POST /v2/agent/actions/{id}/result`；前端只能回传 `source=device_reported` |
| `GET /v2/maintenance/duplicates` | 旧的"查重复"是给旧音频库做历史脏数据清理用的；新库在写入时就靠 `(user_id, platform, video_id, page)` 唯一键挡住重复，**正常路径下不会产生重复**，这个查询接口没有消费者 | **不补** | —（若真出现重复，先用 `GET /v2/maintenance/storage` + 唯一键冲突日志定位，不为此加一个只读接口） |

结论：3 条里 **2 条跟着 Agent B 一起补**（不是"遗留欠账"，是方案 B 的一部分），1 条确认不做。
补完后本表"未实现"应只剩 0 条，届时在 P4/P5 的验收里更新这张表。

## 10. 汇总

| 处置 | 条数 | 明细 |
|---|---|---|
| 保留（路径与语义不变） | **41** | 账号 4、媒体库 9（含 `/v2/resolve`）、批量 7、授权 9、上报 3、AI 重建 12、`/health` 1 |
| 改名 / 合并 | **24** | 媒体流统一 2（audio/video→media）、定时 2、Agent 会话与聊天 4、偏好记忆 4、`/v2/platforms/*` 6、`/login/*` 5、`POST /account/platform-auth/image` 1 |
| 丢弃 | **17** | 旧扁平 `/extract`、`/tracks`、`/sync`、`/audio/{id}`、`/video/{id}`（5）；旧扁平 `/agent/*` 5；`/credentials/*` 2；`/migration/claim` 1；音频维护 3；`/v2/platforms/qr/{sid}/image` 1 |
| 未实现 | **3 → 1** | `claim`、`result` 两条随 Agent 方案 B 补（§9.1）；`GET /v2/maintenance/duplicates` 确认不做 |
| **旧合计** | **85** | 41 + 24 + 17 + 3 = 85，与并集一致 |

新增（旧版完全没有）：

| 模块 | 条数 | 路由 |
|---|---|---|
| 媒体库/维护 | 6 | `/v2/media/{id}`、`/v2/extraction-jobs/{id}`、`DELETE /v2/tracks/{id}/file`、`/v2/maintenance/{storage,prune-cache,orphans}` |
| 博主批量 | 5 | `pause`、`resume`、`items/{video_id}/state`、`items:import`、`/v2/creators/limits` |
| 定时 | 5 | `POST/GET/PATCH/DELETE /v2/schedules*`、`GET /v2/schedules/{id}/runs`（`run-once` 见上表） |
| Agent | 1 | `GET /v2/memories` 的"无 user_id"形态（其余为改名） |
| 平台授权 | 1 | `POST /account/platform-auth/cookie` |
| 失败上报 | 1 | `GET /v2/diagnostics/summary` |
| 账号安全（P1） | 7 | `DELETE /account/me`、`/account/consent`、`/account/recovery/ack`、`/account/devices*`（3）、`/account/password`、`/account/recover` |
| 配置 | 1 | `GET /v2/config/owner` |

**新服务合计：67 条 owner 路由 + 18 条免费路由 = 85 条**（与旧并集的数量巧合一致，但不是一一对应——
旧侧 41 条保留、24 条换路径、17 条被丢弃、3 条未实现，新侧有 32 条是旧版没有的）。

