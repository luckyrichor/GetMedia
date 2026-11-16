# P5d 网页：提取、批量、定时、Agent（2026-11-16）

四栏一起交付，都是接已有接口，**没有新增服务端接口**。5e/5f 仍是占位页。

---

## 1. 服务器提取（`/extract`）

- 两种动作分开：「只解析」走 `POST /v2/resolve`（不入库，先看标题/时长/大小）；「提取」走
  `POST /v2/extract`（后台解析 + 下载 + 入库）。
- 任务列表读 `GET /v2/extraction-jobs`；**只有存在排队/进行中的任务时才轮询**（2 秒一次），
  跑完自动停，不留一个永远转的定时器。
- 失败条目直接显示服务端给的 `error_detail`，不改写成"网络不稳定"。
- 页面写明抖音在服务器 IP 上会被挡（403）。

已知缺口（页面上也写了）：没有单条任务详情页，也没有"失败重试"按钮——失败要回填链接重新提交。

## 2. 博主批量（`/creators`）

- 预览 `POST /v2/creators/preview`：返回博主名、会选中多少条、预计占用、列表来源（授权/游客/无）。
- 建任务 `POST /v2/creators/jobs`：三种选取方式（最近 N / 日期区间 / 全部），
  存放方式四选一（手机下载 / 仅元数据 / 归档 / 缓存）。
- 任务列表 `GET /v2/creators/jobs`，同样只在有活动任务时轮询（2.5 秒）。
- 操作按状态出现：进行中→暂停/取消；已暂停→继续/取消/重试；失败/取消→重试。
  重试调 `POST .../retry`（服务端把失败条目重置为 pending 并重新入队）。
- 「看条目」按需调 `GET .../items`（100 条一页，可加载更多），显示每个条目的状态与失败原因。

**没有做** `items:import`：那是"手机列作品、服务器只记账"的抖音路径，网页没有手机的网络环境，
写一个假入口只会让人以为能用。页面上直接说"改用手机端建任务"。

## 3. 定时任务（`/schedules`）

- 列表 `GET /v2/schedules`；间隔用服务端的 `interval_minutes`（15 分钟–7 天），文案换算成"每 N 小时/天"。
- 新建 `POST /v2/schedules`（名字、平台、博主、选取方式、间隔、存放、格式、置顶）。
- 停用/启用 `PATCH /v2/schedules/{id}`（只发 `enabled`），按返回值就地更新，不整页重取。
- 「立刻跑一次」`POST .../run-once`；执行历史 `GET .../runs` 展开才请求。
- 删除要二次确认（删的只是定时器，已经跑出来的批量任务不受影响）。

已知缺口：不能改间隔与选取条件（要改只能删了重建）。

## 4. Agent（`/agent`）

- 状态条读 `GET /v2/agent/status`：引擎（模型/规则）、今日调用/上限、模型名、保险箱是否可用。
  规则态时给出明确解释（没配 Key/模型 → 不发外网请求），把它说成"默认省流量"而不是故障。
- 会话：列表、新建、删除、点开读消息（`GET /v2/agent/conversations/{id}`）。
- 发消息 `POST /v2/agent/chat`，带上当前会话号；返回后刷新会话列表、消息与状态（今日调用会变）。
- 偏好记忆：列、增改（`PUT /v2/memories/{key}`）、删。

**故意不做的事**：网页不领取动作。`claim` 是"谁来干活"的约定，由手机端领取并 `result` 回报；
网页代领会把状态推到 `running` 然后没人执行，反而让手机端拿不到这个任务。页面上把动作列出来、
标注"由手机/服务器执行""未领取"，并写明不代领的原因。

## 5. 验证

```
npx tsc --noEmit          通过
npx vitest run            113 passed（11 个文件；5d 新增 22 项）
npm run build             两个产物成功
npm run size              公开页 20.3 KB gzip（上限 60）；管理台 27.8 KB gzip（上限 80）
python -m pytest tools/tests/test_web_contract.py    9 passed
```

新增用例：

| 文件 | 数 | 覆盖 |
|---|---|---|
| `owner-app/tests/extract.test.tsx` | 4 | 只解析的提示与字段、提交体（格式+存放）、任务列表、失败原因、空输入不发请求 |
| `owner-app/tests/creators.test.tsx` | 5 | 预览字段与提交体、建任务提交体与进度文案、暂停/继续/重试按状态出现、条目展开、抖音 403 文案 |
| `owner-app/tests/schedules.test.tsx` | 6 | 列表文案、停用只发 enabled、立刻跑一次后刷新、新建校验与提交体、删除二次确认、历史按需加载 |
| `owner-app/tests/agent.test.tsx` | 5 | 规则/模型两种状态、发消息带会话号、动作只读（无领取按钮、无 claim 请求）、记忆增删 |

既有用例改动：`gate.test.tsx` 的"占位页"用例改指向仍是占位的 `/platforms`，
并新增"5d 四栏都接上了真实页面"。

### 5.1 产物隔离

```
test_public_bundle_contains_no_owner_code     公开页 JS 无 config/owner、/owner/api、owner-app、auth_request
test_owner_bundle_uses_owner_channel          管理台 JS 有 /owner/api 与 config/owner，index.html 带 noindex
test_every_owner_call_exists_on_server        管理台接口层 37 条 方法+路径 全部在服务端存在
```

管理台这一轮从 19.6 → 27.8 KB gzip，主要是四个页面各自的状态与表单；公开页体积没变（20.3 KB）。

## 6. 文件

新增：`web/owner-app/src/pages/{extract,creators,schedules,agent}.tsx`、`web/owner-app/src/labels.ts`、
`web/owner-app/tests/{extract,creators,schedules,agent}.test.tsx`

修改：`web/owner-app/src/{api.ts,app.tsx,sections.tsx,styles.css}`、`web/owner-app/tests/gate.test.tsx`、
`docs/STATUS.md`
