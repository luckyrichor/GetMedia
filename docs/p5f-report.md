# P5f 网页：AI 视频重建（2026-11-16）

管理台最后一栏。**没有新增服务端接口**，页面只接已有的 `/v2/recreations` 一组。
九栏到这一期全部是真实页面，`Placeholder` 只剩兜底作用。

---

## 1. 这一页怎么用

一条线：**建项目 → 看源视频 → 写描述（可先让模型分析） → 入队生成 → 看进度 → 播产物**。

建项目两条路，都落在 `POST /v2/recreations/upload` 与 `POST /v2/recreations/from-track/{id}`：

| 入口 | 页面行为 | 说明 |
|---|---|---|
| 媒体库视频 | 下拉里只列 `media_type === 'video' && has_file` 的条目 | 只有"文件真在服务器上"的才能当素材；仅元数据的条目选了也没用，所以干脆不列 |
| 上传文件 | `<input type=file accept="video/*">`，`FormData` 直传 | 上限取 `capabilities.max_upload_bytes`（默认 200 MiB），服务端 600 权限落盘 |

项目列表按状态给标签（草稿/排队中/进行中/已完成/失败/已保存），显示来源（媒体库 `#id` 或上传）、
描述有没有写、多久前更新过。点「打开」才去取 `GET /v2/recreations/{id}`——列表接口不返回 tasks，
进详情才拉，避免一屏 N 个项目打 N 次详情。

详情区分三块：

1. **源视频**：`<video src="/v2/recreations/{id}/source">`，服务端支持 Range，能拖进度条；
2. **描述**：`PUT .../description`，上限 4000 字；描述为空时「生成视频」是禁用的（服务端也会拒，
   但按钮先禁用，不让人点了才看到 400）；
3. **任务与产物**：每次 analyze/generate 一条 run，显示状态、进度百分比、建于何时、错误码；
   `queued/running` 给「取消」，`done` 给「标记已保存」。

## 2. 三个刻意的限制

### 2.1 产物只认 `kind === 'generate'`

`analyze` 完成时也会带 `output_path`，但那是分析结果，不是能播的视频。页面取产物时加了类型条件：

```tsx
const doneRun = sel?.runs?.find((r) => r.status === 'done' && r.kind === 'generate');
```

之前按"任意 done 的 run"取，分析一完成就会在「产物」里塞一个播不了的播放器。现在分析完成后只给一句
"分析完成了。看一眼描述，满意就点生成视频"，不占产物位。

### 2.2 轮询只在真有活的时候开

`queued`/`running` 存在时每 2.5 秒重取一次详情（不是列表），全部结束立刻 `clearInterval`，
组件卸载也清。没有常驻定时器，页面挂在后台不会一直打接口。

### 2.3 服务端关闭时整页只读

`GET /v2/recreations/capabilities` 返回 `available: false`（默认 `GETMEDIA_AI_ENABLED=false`）时：

- 顶部黄色提示，写清是**服务端未开通**，不是页面故障；
- 新建（下拉、上传、建项目）、描述、分析、生成**全部禁用**；
- 已有项目与产物仍然能看、能播——只读不等于空白页。

不给"点了才报 503"的按钮。

## 3. 网页不做的事

- **不能删除项目**：服务端没有删除接口，产物与源文件由保留期清理任务统一处理；
- **不能下载产物**：只给播放与「标记已保存」。要原文件得去服务器取（`media/ai/{id}/`）；
- 「标记已保存」**不删产物**，只改状态，页面文案里写明了"产物仍留在服务器上，到期后统一清理"；
- 进度百分比是 worker 自己写的，没有"抽帧 / 推理 / 合成"这种分阶段说明；
- 不做流式输出、不做多轮改稿（每次生成都是一条新 run）。

## 4. 验证

```
npx tsc --noEmit                                    通过
npx vitest run                                      126 passed（14 个文件）
npm run build                                       两个产物成功
npm run size                                        公开页 20.3 KB gzip（上限 60）
                                                    管理台 33.1 KB gzip（上限 80）
pytest tools/tests/test_web_contract.py             9 passed
pytest server/tests tools/tests                     312 passed, 50 skipped
python3 tools/scan_leaks.py --all                   388 个文件，通过
scan_forbidden.py heavy  （免费版范围）              通过
scan_forbidden.py legacy （免费版范围 + 服务端 + 管理台） 通过
```

| 文件 | 数 | 覆盖 |
|---|---|---|
| `owner-app/tests/ai.test.tsx` | 5 | 未开通时新建/上传/描述/分析/生成全禁用且给出原因、用媒体库视频建项目（音频不在下拉里）、保存描述走 PUT、入队后轮询 + 取消 + 产物播放器 + 标记保存、入队失败显示服务端原因 |

### 4.1 测试口径的变化

`gate.test.tsx` 原来有「占位页写明还差什么」「5c 两栏已接入」「5d/5e 六栏已接入」三个用例。
这一期九栏全接完，占位页在正常路由下已不可达，三个用例合并成一个：

```tsx
for (const s of SECTIONS) { mount(s.path); 等空态文案; 断言不含「这一栏还没实现」; 断言 s.done }
```

好处是**以后新增栏目忘了注册页面，这个用例会直接红**（`SECTIONS` 长度与空态标记都对不上），
不再依赖手工维护的路径清单。

### 4.2 产物隔离

公开页构建产物（`web/public-app/dist`）对 owner 只有"一个链接"，没有任何代码：

| 标记 | 公开页产物 | 管理台产物 |
|---|---|---|
| `config/owner` | 0 | 有 |
| `/owner/api` | 0 | 有 |
| `owner-app` | 0 | — |
| `auth_request` | 0 | — |
| `__Secure-gm_session` | 0 | 有 |
| `/owner/`（站内跳转链接） | 有 | — |

管理台 `api.ts` 现在 **58 条 方法+路径**（5f 加 10 条，全部 `recreations` 相关），
`test_web_contract.py` 逐条对着 FastAPI 的真实路由表核对，一条不存在就红。

## 5. 一个取舍

**没做"下载到本机"**。产物在服务器上，`/runs/{rid}/video` 支持 Range 已经能边下边播；
再加一个下载按钮，等于把 owner 通道变成一个任意文件出口，而管理台本身没有"导出"这一层权限设计。
真需要原文件时，`scp` 或直接在服务器上取更合适。如果你希望网页能给下载，我可以加一个
只对 `done` 的 generate run 生效的下载链接（同样走 owner 鉴权），告诉我就行。

## 6. 文件

新增：`web/owner-app/src/pages/ai.tsx`、`web/owner-app/tests/ai.test.tsx`

修改：`web/owner-app/src/{api.ts,app.tsx,sections.tsx,styles.css}`、
`web/owner-app/tests/gate.test.tsx`、`docs/STATUS.md`
