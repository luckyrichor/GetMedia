# P5c 网页：反馈收件箱 + 媒体库与播放（2026-11-16）

本轮只做 5c 两栏：`/feedback`、`/library`。5d–5f 仍是占位页，页面上写明"还差什么"。
没有部署，没有改服务器配置。

---

## 1. 反馈收件箱

### 1.1 新接口（服务端唯一的跨账号读通道）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v2/feedback` | 分页 + 状态筛选（`new/handled/ignored/all`）+ 各状态计数 |
| GET | `/v2/feedback/{id}` | 正文、联系方式、诊断、图片清单（序号 + 字节数） |
| GET | `/v2/feedback/{id}/images/{n}` | 按序号取图；不接受文件名，也不回显存储名 |
| POST | `/v2/feedback/{id}/status` | `new/handled/ignored`；同值幂等，写审计 `feedback_status` |

全部挂 `require_tier("owner")`，边缘再挡一层。

**身份口径**：列表与详情都只给账号编号前 8 位（`user_ref`），不返回登录名——反馈邮件本来就只发编号，
收件箱沿用同一口径。`contact` 是用户自填的，详情里单独标注"未经核对"。

**图片**：只按"条目 + 下标"取，名字从库里读，不做字符串拼接；解析后还要落在
`feedback_image_dir` 内，否则 404（有用例专门塞了 `../outside.jpg` 验证只拒绝、不删数据）。

**不属于 5c 的**：回复用户（没有回信通道）、搜索正文、批量处理、删除反馈。
保留期清理仍由后台任务按 90 天（图）/180 天（文字）执行。

### 1.2 页面

- 四个状态筛选片（带计数）、分页"加载更多"、空态、错误态 + 重试；
- 行内显示正文摘要、相对时间、类别、图片张数、"留了联系方式"、版本与渠道；
  邮件没发出去（`pending/failed/skipped`）时单独标出来；
- 点开才请求单条详情：正文、诊断表、图片缩略图（点开原图）、"复制诊断信息"；
- 三个状态按钮；改完从服务端重取列表，不在本地猜计数。

## 2. 媒体库与播放

### 2.1 接口（沿用 P2a 已有的，无新增）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v2/tracks?since_seq=&limit=` | 游标翻页，按当前账号 |
| GET | `/v2/media/{id}` | 媒体流，支持 Range |
| PATCH | `/v2/tracks/{id}` | 改标题（只发 `title`） |
| DELETE | `/v2/tracks/{id}/file` | 只删文件，记录退回"仅元数据" |
| DELETE | `/v2/tracks/{id}` | 逻辑删除，同步到手机端 |

`/v2/tracks` 是 `updated_seq` 递增、没有"倒序"参数，所以前端顺着游标走完（每页 500，最多 20 页），
再按 `created_at` 倒序展示；筛选与搜索都在已取到的集合里做，不再打接口。

### 2.2 页面

- 类型筛选片（带计数）+ 搜索框（标题 / UP 主 / 作品号）；
- 行内：类型、标题、UP 主、时长、平台、入库时间、存储角标（归档 / 缓存 / 仅元数据）、是否有文件与占用；
- 播放：`<audio>` / `<video>` 直接取 `/v2/media/{id}`，靠服务端 Range 拖动进度条；
  没有落盘文件时如实说明"当场解析并中转、不落盘"；
- 改名（就地编辑、首尾空格裁掉）、删文件、删除记录，后两个都要二次确认；
- 空态（引导去"服务器提取"）、错误态 + 重试。

**已知边界**（页面上也写了）：不能上传/替换文件、不能建播放列表；条目没有"分类"字段
（旧版分类没有迁过来）；超过 1 万条的部分只能用搜索定位。

## 3. 验证

```
服务端：.venv/bin/python -m pytest -q server/tests tools/tests
        312 passed, 50 skipped
前端：  npx tsc --noEmit            通过
        npx vitest run              92 passed（原 78 + 5c 的 14）
        npm run build               两个产物成功
        npm run size                公开页 20.3 KB gzip（上限 60）；管理台 19.6 KB（上限 80）
```

新增用例：

| 文件 | 数 | 覆盖 |
|---|---|---|
| `server/tests/test_feedback_admin.py` | 8 | 匿名/普通账号 403、身份截断、详情、状态幂等与非法值、分页与筛选、图片按序号取、穿越只拒读、空收件箱 |
| `web/owner-app/tests/feedback.test.tsx` | 6 | 列表与计数、联系方式只在详情出现、改状态带 `{status}` 并从服务端重取、空态、失败重试、分页 offset |
| `web/owner-app/tests/library.test.tsx` | 7 | 倒序与角标、本地筛选/搜索不打接口、音频/视频切播放器、改名只发 title、删文件需确认、删除记录关播放器、空态与失败重试 |

既有用例也补了两条：

- `tools/tests/test_web_contract.py` 现在同时扫 `web/owner-app/src/api.ts`，
  校验管理台调的每个 `方法+路径` 都存在；并单独核对 `<audio>/<img>` 直接拼的
  `/v2/media/{id}`、`/v2/feedback/{id}/images/{n}` 是真实路由（这两条不经过 fetch，正则抓不到）。
- `web/owner-app/tests/gate.test.tsx` 加一条"5c 两栏不再是占位页"。

### 3.1 产物隔离（公开页零 owner 代码）

`tools/tests/test_web_contract.py::test_public_bundle_contains_no_owner_code` 在真实构建产物上断言：
公开页 JS 里没有 `config/owner`、`/owner/api`、`owner-app`、`auth_request` 任一标记，
只有站内相对链接 `/owner/`；管理台产物里有 `/owner/api` 与 `config/owner`，`index.html` 带 `noindex` 且无外链。

本轮构建后 9 项全过：

```
.venv/bin/python -m pytest -q tools/tests/test_web_contract.py     → 9 passed
```

体积对比（gzip）：公开页 20.0 → 20.3 KB（唯一变化是上一轮的 tier 跳转），
管理台 14.0 → 19.6 KB（两页 + 公共小件）。

### 3.2 一次性偶发

全量跑第一遍时 `server/tests/test_feedback.py::test_image_exif_stripped_and_reencoded` 失败一次，
单独跑与随后连续 4 轮全量都通过（312 passed × 4）。该用例与本轮改动无交集（图片处理逻辑没动），
怀疑是沙箱文件代理写入时的偶发 EEXIST/权限抖动；**没有复现，未改测试**，先记在这里。

## 4. 文件

新增：

- `server/getmedia/feedback_admin.py`、`server/tests/test_feedback_admin.py`
- `web/owner-app/src/pages/{feedback,library}.tsx`、`web/owner-app/src/ui.tsx`
- `web/owner-app/tests/{harness.tsx,feedback.test.tsx,library.test.tsx}`

修改：

- `server/getmedia/api.py`（注册收件箱路由）
- `web/owner-app/src/{api.ts,app.tsx,sections.tsx,styles.css}`
- `web/owner-app/tests/gate.test.tsx`（改用公共夹具，加"已接入"用例）
- `tools/tests/test_web_contract.py`（扫管理台接口层、查询串归一、媒体地址核对）
- `tools/scan_leaks.py`（跳过本地临时目录）
- `.gitignore`（`.tmp/`）
- `docs/STATUS.md`
