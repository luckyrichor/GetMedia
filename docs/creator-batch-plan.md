# 博主批量提取（GetMedia）实施方案

日期：2026-11-15　分支：`wp6-free`　范围：仅 GetMedia（android + server），不改 GetVideo / GetAudio 任何文件

## 0. 与任务书的两处偏差（先写明）

任务书（`prompt3_creator_batch_app.md`）的两条前提在 GetMedia 上都不成立，处理如下。

### 偏差一：GetMedia 服务端没有 creator 能力

`server/getmedia/api.py` 原有 17 条路由全部是账号/协议/心跳/反馈/注销，`grep -ri creator server/` 零命中。

处理：**从 GetAudio 只读复制**平台适配器与解析代码到 GetMedia 服务端（GetAudio 仓库未改动一个字节）。

| GetAudio 源 | GetMedia 目标 | 改动 |
|---|---|---|
| `getaudio/creators/base.py` | `server/getmedia/creators/base.py` | 仅 import 路径 |
| `getaudio/creators/bilibili.py` | `server/getmedia/creators/bilibili.py` | import 指向 `creator_wbi` / `creator_guest` / `creator_links` |
| `getaudio/creators/douyin.py` | `server/getmedia/creators/douyin.py` | 同上；Playwright 桥接保留但默认关闭 |
| `getaudio/creators/__init__.py` | `server/getmedia/creators/__init__.py` | provider 注册表改为 `getmedia.creators.*` |
| `getaudio/guest.py`（访客 cookie 预热） | `server/getmedia/creator_guest.py` | 配置项改名 `creator_cookie_dir` |
| `getaudio/extractors/bilibili.py` 的 wbi/指纹部分 | `server/getmedia/creator_wbi.py` | 只取 `_get_mixin_key` / `_sign` / `dm_params` / `reset_wbi_cache` / `_parse_cookie_file`，不引入 yt-dlp 与转封装 |
| `getaudio/sharelink.py` 的博主部分 | `server/getmedia/creator_links.py` | 只保留 `Platform` / `CreatorRef` / `find_urls` / `expand` / `resolve_creator` / `parse_creator_input`；视频单条解析不进来 |
| `getaudio/creator_api.py`（预览 + 任务） | `server/getmedia/creator_api.py` | 只保留 `POST /v2/creators/preview` 与 `GET /v2/creators/limits` |
| `getaudio/creator_service.py` | `server/getmedia/creator_service.py` | 只保留 `resolve_ref` / `preview` / 范围校验；去掉任务表与 `pipeline.extract` |

新增运行时适配：`server/getmedia/creator_rt.py`（把 frozen `Settings` 注入移植模块）。

### 偏差二：任务表与「服务端下载 → 同步入库」在 GetMedia 不成立

任务书要求 `POST /jobs`、`GET /jobs/{id}`、轮询、取消/重试，产物按「服务器媒体入库/同步」出现在库里。这是 GetAudio 的形态：服务端用 yt-dlp 下载 → 落 `tracks` 表 → APK 通过 `/v2/sync` 拉取。

GetMedia 的形态不同，且是它免费版的核心决定：

- 服务端**没有** `tracks` 表、没有媒体文件、没有 `/v2/sync`；
- APK 没有 Retrofit / SyncWorker，媒体由 `DirectExtractor` 在手机上解析并写入 Room。

若照搬任务书，需要把 GetAudio 的 yt-dlp 下载管线、媒体存储、下载路由、APK 同步 Worker 整套搬进 GetMedia —— 这既违背「不要新造一套存储」，也会把「手机直连、服务器不碰媒体」的架构改掉。

因此本次的链路是：

```
APK 输入博主 → POST /v2/creators/preview（服务端抓列表：出口 + wbi + cookie）
            → 用户在窗口里勾选条目
            → APK 逐条调用 TrackRepository.extract()（现有手机直连链路，写本地 Room）
            → 进度 / 取消 / 失败重试在手机端状态机里完成
```

任务书第 6 条「复用现有链路由你读代码后在 plan.md 里写明；不要新造一套存储」对应的就是
`TrackRepository.extract(text, kind, onDiagnostic)` —— 与「提取」页单条提取完全同一条路径。

服务端仍然需要登录：列表抓取消耗服务端出口与平台配额，按账号每小时 30 次限流。

## 1. 接口契约

### POST `/v2/creators/preview`

请求头：`X-GetMedia-Session`（现有 App 会话令牌）。

```json
{
  "url": "https://space.bilibili.com/2/video",
  "platform": "bilibili",
  "start": 1,
  "count": 10,
  "include_stats": true,
  "include_comments": false,
  "comment_limit": 5
}
```

- `url` 也接受 `input` / `text` 作为同义字段（与 GetAudio 契约一致）。
- `url` 可以是主页链接、带链接的分享文案、裸抖音号、B 站 UID、昵称；裸数字必须带 `platform`，否则 400。
- 单条视频链接 400，并提示「这是单个视频链接；批量提取请传 UP 主空间主页」。
- `start` 1–500，`count` 1–50（服务端 `GETMEDIA_CREATOR_MAX_ITEMS` 可下调）。

响应（字段与 GetAudio 对齐，另加 `total` / `has_more` / `cookie_source`）：

```json
{
  "profile": {"platform":"bilibili","creator_id":"2","name":"碧诗","avatar_url":"...","url":"...","total_videos":43,"follower_count":1234,"signature":"..."},
  "range": {"start":1,"count":3},
  "items": [{"position":1,"video_id":"BV1ujNV6qEXg","title":"...","source_url":"https://www.bilibili.com/video/BV1ujNV6qEXg","cover_url":"...","duration_sec":22.0,"published_at":"...","stats":{"view_count":817855},"comments":[],"comments_status":null}],
  "total": 43,
  "has_more": true,
  "fetched_at": "2026-11-15T03:20:00+00:00",
  "cookie_source": "guest"
}
```

错误码（`code` 稳定，`detail` 可直接展示）：

| HTTP | code | 场景 |
|---|---|---|
| 400 | `creator_input` | 输入不是博主主页、裸数字没选平台、范围超限 |
| 401 | `unauthenticated` | 未登录 / 会话失效 |
| 404 | `creator_not_found` | 博主不存在、主页不可见、昵称无法唯一定位 |
| 428 | `creator_auth` | 平台需要重新授权（抖音登录态/Cookie 缺失） |
| 429 | `rate_limited` | 查询太频繁（30 次/小时/账号） |
| 503 | `creator_unavailable` | 平台风控、验证码、浏览器桥接不可用 |
| 502 | `creator_failed` | 其他平台侧失败 |

`cookie_source` 只回 `configured` / `guest` / `none`，不暴露任何 cookie 内容。

### GET `/v2/creators/limits`

返回 `{"max_items": 50, "max_start": 500}`，手机端不再硬编码上限。

## 2. Android 改动

新增：

| 文件 | 职责 |
|---|---|
| `data/creator/CreatorModels.kt` | 平台、博主、条目、预览窗口、失败分类 |
| `data/creator/CreatorJson.kt` | 响应 JSON → 模型；HTTP 状态/错误码 → 中文提示（纯逻辑，可单测） |
| `data/creator/CreatorApi.kt` | OkHttp 调 `/v2/creators/preview`，复用 `AccountApi.defaultClient()` 与 `SessionStore` 令牌 |
| `data/creator/CreatorBatch.kt` | 本机批量状态机：勾选、领下一条、成功/失败、取消、只重试失败项、摘要文案（纯逻辑） |
| `ui/screen/CreatorBatchScreen.kt` | 输入 → 预览 → 提取三段式页面，含进度、停止、重试失败 |

改动：

| 文件 | 改动 |
|---|---|
| `ui/screen/ExtractScreen.kt` | 顶部加「博主批量提取」入口；子页用 `BackHandler` 返回（约 20 行） |
| `server/getmedia/config.py` | 新增 `creator_cookie_dir`、`http_timeout`、`max_redirects`、`creator_max_items/start/preview_limit`、抖音浏览器桥接开关 |
| `server/getmedia/api.py` | 启动时 `creator_rt.configure(settings)` 并注册两条路由 |
| `server/getmedia/tiers.py` | 两条路由登记进 `FREE_ROUTES`（仍需登录，见该文件说明） |

未引入任何新依赖：服务端用已有 `httpx`，APK 用已有 `okhttp` + `coil`。

## 3. 与任务书功能的对应

| 任务书要求 | 本次实现 |
|---|---|
| 入口在「提取」页 | ✅ 顶部「博主批量提取」 |
| 输入：链接/分享文案/抖音号/UID/昵称，裸数字选平台 | ✅ 平台 chips + 本地拦截「纯数字需要先选平台」 |
| 预览：昵称/头像/简介/粉丝/作品数 + 窗口列表 | ✅ 服务端 `profile` + `items` |
| `start`/`count`，默认 ≤50，说明可分多次 | ✅ 起点/数量输入 + 10/20/50 快捷 + 文案说明 |
| 任务列表与详情、轮询 | ⚠️ 改为**本机任务状态**：无服务端任务表，逐条进度实时显示，不需要轮询 |
| 取消与重试 | ✅ 停止（不再开始新条目）+ 只重试失败项 |
| 产物进库、可播放 | ✅ `TrackRepository.extract()` → Room → 媒体库（与单条提取同一链路） |
| 失败友好 | ✅ 按 `code` 分类：登录/输入/找不到/平台授权/风控/网络/服务器 |

## 4. 服务端配置（都可选，默认即可跑）

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `GETMEDIA_CREATOR_COOKIE_DIR` | `<data>/creator-cookies` | 放 `bilibili.txt` / `douyin.txt`（Netscape 格式）可提高通过率；没有则自动取访客 cookie |
| `GETMEDIA_CREATOR_MAX_ITEMS` | `50` | 单次条数上限（1–50） |
| `GETMEDIA_DOUYIN_BROWSER` | `false` | 打开后抖音直连失败会尝试 Playwright 桥接（需自行 `pip install playwright`） |
| `GETMEDIA_DOUYIN_BROWSER_HEADLESS` | `true` | 首次人工验证需要设 `false` |

抖音：直连失败是常态（Argus 风控）。没装 Playwright 时会返回 503 并给出明确提示，不会假装「博主没有视频」。

## 5. 测试

- 服务端：`pytest` 147 项通过（新增 `test_creator_links.py` 21 项、`test_creator_api.py` 17 项）。
- APK：`testFreeDebugUnitTest` 139 项通过（新增 `CreatorBatchTest` 9 项、`CreatorJsonTest` 11 项）。
- 真实联网冒烟：`space.bilibili.com/2` 返回「碧诗 · 43 条」，前 3 条标题/时长/播放量正确；裸 UID `2` + `platform=bilibili` 起点 2 返回第 2、3 条；单条视频链接被正确拒绝。
- 构建：`assembleFreeDebug` 通过，产物 `android/app/build/outputs/apk/free/debug/app-free-debug.apk`（37 MB，0.1.0 / versionCode 1）。

## 6. 已知限制

1. 单次最多 50 条，起点最大 500；不按日期区间筛选。
2. 抖音昵称搜索依赖服务端 Playwright + 登录 Cookie；默认关闭，仅支持抖音号 / sec_uid / 主页链接的直连尝试。
3. 抖音列表可能被风控或验证码拦截，此时返回 503，需要人工在服务端完成验证或更新 Cookie。
4. B 站昵称重名时只接受完全匹配；找不到唯一结果会返回 404 并列出候选。
5. 批量提取是串行下载，长视频较多时耗时较长；页面只保留本轮结果，退出重进需要重新预览。
6. 博主批量需要登录；未登录时页面只显示提示，不发起任何请求。

## 7. 需要你决定的事

1. 是否给服务端配一份长期抖音 Cookie（`douyin.txt`），或安装 Playwright 打开浏览器桥接 —— 这决定抖音能不能稳定用。
2. 50 条上限是否要按账号等级区分（当前所有登录账号都是 50）。
3. 是否要把「已提取过的条目」在窗口里标灰（需要服务端按 `platform:video_id` 回传是否已入库）；当前每次都会重新解析，重复条目靠本地去重跳过下载。
