# P2 验收证据（2026-11-16）

本文件只放**可复现的输出**，不做结论包装。所有命令在本机仓库根执行，生产库一行未写。

## 0. 环境

| 项 | 值 |
|---|---|
| 时间 | 2026-11-16（本机 EST） |
| Python | 3.13.12（`.venv`，`pip install -r server/requirements.txt`） |
| FastAPI | 0.141.1 |
| 仓库 | `wp6-free` @ `341e95f` + 本轮改动（未提交） |
| 测试总数 | **302 passed / 50 skipped / 0 failed**（本机）；nginx 相关 41 项在服务器上 **41 passed** |

> `50 skipped` 全部是"本机没有 nginx 可执行文件"（`test_deploy_nginx` 41 项 + `test_deploy_templates` 9 项），
> 不是功能未实现。这 41 项**已在服务器上真实跑过**（真实 nginx 1.24 + 桩上游）：`41 passed in 22.28s`，
> 其中含「匿名 / free 访问 `/getmedia/owner/` 与 `/getmedia/owner/api/*` 一律 404」共 12 项。
> 另：在本沙箱里直接 `pytest` 会因临时目录被拦截而报 `FileExistsError`，需 `--basetemp=.run-tmp/xxx`；这是沙箱限制，与代码无关。

## 1. 全量测试

```
$ .venv/bin/python -m pytest -q --basetemp=.run-tmp/pt12
302 passed, 50 skipped, 1 warning in 39.78s
```

按模块：

| 测试文件 | 通过 | 跳过 |
|---|---|---|
| `server.tests.test_accounts` | 40 | 0 |
| `server.tests.test_agent` | 5 | 0 |
| `server.tests.test_agent_actions` | **19** | 0 |
| `server.tests.test_creator_api` | 11 | 0 |
| `server.tests.test_creator_jobs` | 10 | 0 |
| `server.tests.test_creator_links` | 27 | 0 |
| `server.tests.test_credentials` | 7 | 0 |
| `server.tests.test_db_security` | 8 | 0 |
| `server.tests.test_diagnostics` | 3 | 0 |
| `server.tests.test_feedback` | 19 | 0 |
| `server.tests.test_legal` | 10 | 0 |
| `server.tests.test_media` | **11** | 0 |
| `server.tests.test_ops` | 13 | 0 |
| `server.tests.test_permissions` | **11** | 0 |
| `server.tests.test_platform_auth` | **6** | 0 |
| `server.tests.test_recreations` | **9** | 0 |
| `server.tests.test_schedules` | **5** | 0 |
| `server.tests.test_tiers_config` | 19 | 0 |
| `tools.tests.test_android_release_guard` | 2 | 0 |
| `tools.tests.test_credential_merge` | **7** | 0 |
| `tools.tests.test_deploy_nginx` | 0 | **41**（服务器上 41 passed） |
| `tools.tests.test_deploy_templates` | **26** | 9 |
| `tools.tests.test_migrate_legacy` | 8 | 0 |
| `tools.tests.test_scan_forbidden` | 7 | 0 |
| `tools.tests.test_scan_leaks` | 12 | 0 |
| **合计** | **302** | **50** |

P2 新增模块合计 **68 项**（media 11 + creator_jobs 10 + schedules 5 + agent 5 + agent_actions 19 + platform_auth 6 + diagnostics 3 + recreations 9）；
P3 的迁移与凭据合并 **15 项**（`test_migrate_legacy` 8 + `test_credential_merge` 7）；
P6 的部署模板与 owner 通道 **8 项**（`test_deploy_templates`，含 owner 静态页断言）；
边缘行为 **41 项**（`test_deploy_nginx`，真实 nginx 跑，含本轮新增的匿名/free 一律 404 共 12 项）。
P5 的网页契约与产物隔离 **7 项**（`test_web_contract`，见 `docs/p5-web-report.md`）。

## 2. 权限矩阵（匿名 / free / owner × 全部 owner 路由）

```
$ .venv/bin/python tools/permission_matrix.py
```

```
权限矩阵（服务端内部路径；对外为 /getmedia/api 前缀）
owner 路由 71 条；免费白名单 18 条；启动自检未防护路由 0 条

METHOD  PATH                                                     匿名   FREE  OWNER  判定
--------------------------------------------------------------------------------------------
POST    /account/platform-auth/cancel                           401    403    422  OK
POST    /account/platform-auth/complete                         401    403    422  OK
POST    /account/platform-auth/cookie                           401    403    422  OK
POST    /account/platform-auth/start                            401    403    422  OK
POST    /account/platform-auth/status                           401    403    422  OK
GET     /account/platform-bindings                              401    403    200  OK
POST    /account/platform-bindings/{platform}/unbind            401    403    400  OK
GET     /account/platforms                                      401    403    200  OK
POST    /v2/agent/chat                                          401    403    422  OK
GET     /v2/agent/conversations                                 401    403    200  OK
POST    /v2/agent/conversations                                 401    403    201  OK
DELETE  /v2/agent/conversations/{conversation_id}               401    403    404  OK
GET     /v2/agent/conversations/{conversation_id}               401    403    404  OK
GET     /v2/config/owner                                        401    403    200  OK
GET     /v2/creators/jobs                                       401    403    200  OK
POST    /v2/creators/jobs                                       401    403    422  OK
GET     /v2/creators/jobs/{job_id}                              401    403    404  OK
POST    /v2/creators/jobs/{job_id}/cancel                       401    403    404  OK
GET     /v2/creators/jobs/{job_id}/items                        401    403    404  OK
POST    /v2/creators/jobs/{job_id}/items/{video_id}/state       401    403    422  OK
POST    /v2/creators/jobs/{job_id}/items:import                 401    403    422  OK
POST    /v2/creators/jobs/{job_id}/pause                        401    403    404  OK
POST    /v2/creators/jobs/{job_id}/resume                       401    403    404  OK
POST    /v2/creators/jobs/{job_id}/retry                        401    403    404  OK
GET     /v2/creators/limits                                     401    403    200  OK
POST    /v2/creators/preview                                    401    403    422  OK
GET     /v2/diagnostics/extractions                             401    403    200  OK
POST    /v2/diagnostics/extractions                             401    403    422  OK
GET     /v2/diagnostics/extractions/{report_id}                 401    403    404  OK
GET     /v2/diagnostics/summary                                 401    403    200  OK
POST    /v2/extract                                             401    403    422  OK
GET     /v2/extraction-jobs                                     401    403    200  OK
GET     /v2/extraction-jobs/{job_id}                            401    403    404  OK
POST    /v2/maintenance/orphans                                 401    403    200  OK
POST    /v2/maintenance/prune-cache                             401    403    200  OK
GET     /v2/maintenance/storage                                 401    403    200  OK
GET     /v2/media/{track_id}                                    401    403    404  OK
GET     /v2/memories                                            401    403    200  OK
DELETE  /v2/memories/{key}                                      401    403    404  OK
PUT     /v2/memories/{key}                                      401    403    422  OK
GET     /v2/recreations                                         401    403    200  OK
GET     /v2/recreations/capabilities                            401    403    200  OK
POST    /v2/recreations/from-track/{track_id}                   401    403    404  OK
POST    /v2/recreations/upload                                  401    403    422  OK
GET     /v2/recreations/{pid}                                   401    403    404  OK
POST    /v2/recreations/{pid}/analyze                           401    403    404  OK
PUT     /v2/recreations/{pid}/description                       401    403    422  OK
POST    /v2/recreations/{pid}/generate                          401    403    404  OK
POST    /v2/recreations/{pid}/runs/{rid}/cancel                 401    403    404  OK
POST    /v2/recreations/{pid}/runs/{rid}/save                   401    403    404  OK
GET     /v2/recreations/{pid}/runs/{rid}/video                  401    403    404  OK
GET     /v2/recreations/{pid}/source                            401    403    404  OK
POST    /v2/resolve                                             401    403    422  OK
GET     /v2/schedules                                           401    403    200  OK
POST    /v2/schedules                                           401    403    422  OK
DELETE  /v2/schedules/{schedule_id}                             401    403    404  OK
GET     /v2/schedules/{schedule_id}                             401    403    404  OK
PATCH   /v2/schedules/{schedule_id}                             401    403    404  OK
POST    /v2/schedules/{schedule_id}/run-once                    401    403    404  OK
GET     /v2/schedules/{schedule_id}/runs                        401    403    404  OK
GET     /v2/sync                                                401    403    200  OK
GET     /v2/tracks                                              401    403    200  OK
DELETE  /v2/tracks/{track_id}                                   401    403    404  OK
GET     /v2/tracks/{track_id}                                   401    403    404  OK
PATCH   /v2/tracks/{track_id}                                   401    403    404  OK
DELETE  /v2/tracks/{track_id}/file                              401    403    404  OK
POST    /v2/tracks/{track_id}/restore                           401    403    404  OK
--------------------------------------------------------------------------------------------
违反项：0
```

读法：

- **匿名列**全 `401`、**free 列**全 `403`、**owner 列**是"业务层响应"（200/201/404/422），说明鉴权先于业务执行，且没有一条 owner 路由漏在免费白名单里。
- `401 vs 403` 的区分是刻意的：未登录 → 401（可去登录），已登录但 tier 不够 → 403（不要去登录）。
- 同一份逻辑在 `server/tests/test_permissions.py`（11 项）里也有断言，两处同源。

## 3. P2 各模块的关键断言（抽查，不是全部）

| 模块 | 断言要点（测试文件） |
|---|---|
| 媒体库 | 四态存储；配额拒绝先于写盘；`/v2/media/{id}` 手写 Range → 206 + `Content-Range`，越界 416；缓存按 TTL 过期；`/v2/sync` 增量含墓碑（`test_media` 11 项） |
| 博主批量 | `latest_n` 精确截断不多取一页；`date_range` 按北京时间、本页最旧早于 `since` 即停；分段续跑不重复（唯一键）；置顶只筛不插队；`items:import` 去重（`test_creator_jobs` 10 项） |
| 定时 | `interval_minutes` 边界 15–10080；`run-once` 不打乱节奏；暂停后 `next_run` 不漂移（`test_schedules` 5 项） |
| Agent | 会话归属校验（别人的会话 404）；空消息/超长拒绝；无模型时 `meta.engine="rules"`（`test_agent` 5 项）；动作生成/领取/幂等/隔离、状态口径、凭据护栏、模型降级与配额（`test_agent_actions` 19 项） |
| 平台授权 | 授权会话 10 分钟过期；Cookie 只存密文、响应不回显；未配 `GETMEDIA_CRED_KEY` → 503 而非明文降级；抖音回调未配置 → 501（`test_platform_auth` 6 项） |
| 失败上报 | 字段长度裁剪；不接受 Cookie/直链字段（`test_diagnostics` 3 项） |
| AI 重建 | 能力开关；上传超限 413；未配置 plugin → `ai_unconfigured` 而非假成功；卡死 `running` 重置（`test_recreations` 9 项） |
| 迁移/凭据 | 凭据冲突按"最近更新"决胜、落选标 `needs_reauth`、报告脱敏（`test_credential_merge` 7 项）；跨库重复、孤儿、幂等、回滚（`test_migrate_legacy` 8 项） |
| 部署模板 | owner 通道必须有 `auth_request` 且探测 owner 级接口、匿名与 free 一律 404（不出现 401/302）、AI worker 单元独立加固、`llm.env` 可选加载（`test_deploy_templates`）；真实 nginx 下 404 与「不存在的路径」响应体逐字节相同（`test_deploy_nginx`） |

## 4. 复现命令

```sh
cd /Users/lc/Project/GetMedia
python3.13 -m venv .venv && .venv/bin/pip install -r server/requirements.txt pytest

# 全量测试（沙箱必须给 --basetemp）
.venv/bin/python -m pytest -q --basetemp=.run-tmp/pt

# 权限矩阵（71 × 3）
.venv/bin/python tools/permission_matrix.py

# 泄露扫描（产物与日志里不得出现密钥/路径）
.venv/bin/python tools/scan_leaks.py
.venv/bin/python tools/scan_forbidden.py --rules tools/forbidden-heavy.txt <apk 目录>
```

## 5. 未覆盖 / 需要你知道的

1. **服务器端真机联调未做**：本轮所有 API 断言都在 TestClient + 临时库上；真实 nginx 前缀、TLS、Range 透传要等 P6 部署。
2. **抖音服务端链路为 0**：矩阵文件已记录机房 IP 403 Argus，属外部限制，不是本服务缺陷；应对方式是"手机列 + `items:import`"。
3. **AI 重建的模型执行未验证**：`getmedia-ai-worker` 在无 plugin 情况下只验证了"正确标失败"，没有真实生成过产物。
4. **Agent 无模型**：`meta.engine` 恒为 `rules`；接回方案见 `docs/agent-model-plan.md`，等你决定。
