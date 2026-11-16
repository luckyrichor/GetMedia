# P5 网页：5a 公开页接新服务 + 5b owner 认证壳（2026-11-16）

本轮只做 5a、5b。**5c–5f 的功能页没有实现**，管理台里每一栏都明确写了自己"还差什么"。
没有部署，没有改服务器配置。

---

## 1. 5a：web-public 接新服务

### 1.1 做了什么

`wp7-web-public` 分支的 34 个文件合入 `wp6-free`（不是重写），然后按新服务契约校正：

| 改动 | 原因 |
|---|---|
| `Config` 增加 `cache_ttl_seconds` / `cache_quota_bytes` | 新 `/v2/config` 多返回这两个字段；缓存文案要读配置，不写死 |
| `Me` 增加 `legal_versions` | 新 `/account/me` 会返回当前协议版本号（只用于提示，不参与同意判断） |
| `createApi` 暴露 `request` | 管理台走另一条通道（`/getmedia/owner/api`），需要复用同一套错误/CSRF/同源处理，而不是复制一份 |
| 新增 `tools/tests/test_web_contract.py` | 静态校验"网页调的每个 方法+路径 都存在"；再守两条不变式（不存令牌、不写绝对 URL） |
| `package.json` / `tsconfig` / `vitest` / `size.mjs` | 两个工程两次构建；管理台单独的体积上限 |

接口层没有一处路径需要改：公开页用到的 16 个接口在新服务里全部存在（契约测试实测）。
这是有意为之——P2 合并时保留了旧路径与语义。

### 1.2 验证

```
npm ci                    169 packages
npx tsc --noEmit          通过
npx vitest run            78 passed（公开页 66 + 管理台 7 + 路由/校验单测）
npm run build             两个产物均成功
npm run size              公开页 20.0 KB gzip（上限 60）；管理台 14.0 KB（上限 80）
```

产物：

| 工程 | JS | CSS | index.html |
|---|---|---|---|
| public-app | 49.7 KB（gzip 18.5） | 6.1 KB（gzip 2.0） | 0.7 KB |
| owner-app | 30.3 KB（gzip 12.8） | 4.4 KB（gzip 1.6） | 0.8 KB |

### 1.3 契约测试（7 项）

`tools/tests/test_web_contract.py`：

1. `api.ts` 能解析出 ≥14 条调用，且包含 `/v2/config`、`/account/login`、`/v2/feedback`（防"正则失效导致空集通过"）；
2. 每一条 `方法+路径` 都在 FastAPI 应用里存在（路径参数归一成 `{}` 后比较）；
3. 接口层不出现 `localStorage` / `sessionStorage` / `Authorization` / `Bearer `（注释先剥掉）；
4. 基址是同源相对路径，源码里没有 `http://` / `https://`；
5. 管理台是独立构建、独立 base（`/getmedia/owner/`），产物不写进 public 的 outDir；
6. 公开页产物里没有 `config/owner`、`管理台`、`/owner/api`、`owner-app` 任一标记；
7. 管理台产物里确实使用 `/owner/api` 与 `config/owner`，`index.html` 带 `noindex` 且无外链。

第 6 条是"零 owner 代码"的机器证据；第 5–7 条在 `dist` 不存在时跳过（未构建的干净环境不会误报）。

---

## 2. 5b：owner 认证壳

### 2.1 入口：先登录，再看 tier

边缘对**匿名**和**非 owner**都给 404（见 2.3），所以正常路径不是"访问 → 被跳去登录"，而是：

1. 用户到公开站 `/getmedia/login` 登录；
2. 登录成功后公开页读返回账号的 `tier`：`owner` 才整页跳到 `/owner/`，否则就地说明"当前账号不是 owner"，
   不再往 `/owner/` 撞（那里只会回 404）；
3. 已登录的用户也可以在账户页看到"进入管理台"入口（只有 `tier=owner` 才出现）。

管理台是独立构建，跳转必须整页加载（`location.replace`），不能走公开页的 `pushState` 路由。

### 2.2 壳的状态（兜底用，生产上大多数状态走不到）

只调 `GET /v2/config/owner`（不新增探针接口）：

| 返回 | 壳的表现 |
|---|---|
| 404 | "页面不存在 / 这个地址下没有内容"——与边缘的隐藏策略一致，不暗示这是权限问题 |
| 401 | "需要先登录" + 手动入口；**不自动跳转**（跳转本身就是"这里需要登录"的信号） |
| 403 | "当前账号不是 owner" + 查看当前账号/返回公开站；不跳登录 |
| 200 | 再取 `/account/me`，进管理台，顶栏显示登录名与 tier |
| 其它（5xx/网络） | "管理台暂时打不开" + 原因 + 重试按钮（重试只重新探针，不改数据） |

401/403 只在"没经过 nginx"（本地 dev、或边缘配置缺失）时才会出现，留作第三层兜底。

### 2.3 导航与占位页

概览 + 9 栏：反馈收件箱、媒体库与播放、服务器提取、博主批量、定时任务、Agent、平台授权、库维护、AI 视频。
每栏都标了交付阶段（5c / 5d / 5e / 5f）、"这一栏还没实现"、**将要使用的接口清单**（含尚未存在、标"待建"的），
以及"还差什么"。这样不会出现"点进去空白但看起来已完成"。

概览页读的是 `/v2/config/owner` 的真实返回值：缓存保留、缓存配额、归档配额、凭据保险箱是否可用、批量上限。
凭据保险箱不可用时整页给出警告（`GETMEDIA_CRED_KEY` 未配置），为 5e 的只读态做前置提示。

### 2.4 静态页与接口的守门（nginx）

`deploy/nginx/getmedia-prefix.conf` 新增 `location ^~ /getmedia/owner/` 与
`location ^~ /getmedia/owner/api/`，两条通道共用同一个探针：

- `auth_request` 复用同一个探针；
- 匿名（401）与非 owner（403）**都回 404**，落在 `@gm_owner_hidden`（`return 404`，与"文件不存在"完全一致，
  没有 JSON、没有 `X-GM-Owner`、没有 `302`）；
- 200 → `alias /var/www/getmedia/owner/` + `try_files` 回退到 owner 的 `index.html`（前端路由）；
- 产物目录与公开页物理隔离（`/var/www/getmedia/owner/` vs `/var/www/getmedia/public/`）；
- 安全头与专用访问日志与公开页一致。

**验证方式**（临时目录 + 临时 nginx 实例，未 reload、未改生产配置）：

| 场景 | 结果 |
|---|---|
| `nginx -t`（含真实 snippet） | `syntax is ok` / `test is successful` |
| `alias` + `try_files` 取文件 / 取资源 / 未知子路径 | 200 / 200 / 200（回退到 index.html，路径没有重复拼接） |
| 桩上游返回 200（owner） | 管理台首页 / 深链接回退 / 接口剥前缀转发，均 200 |
| 桩上游返回 401（匿名）/ 403（free），`/getmedia/owner/` 与 `/getmedia/owner/api/*` 共 5 条路径 | **全部 404**，且与随机不存在路径的响应体逐字节相同；请求没有到达应用 |
| 未登录不得出现 `302` | 已断言 |

顺带确认：`/getmedia/owner/api/` 前缀比 `/getmedia/owner/` 长，最长前缀匹配会先命中接口通道。

---

## 3. 没做的、以及为什么

| 项 | 状态 | 原因 |
|---|---|---|
| 5c 反馈收件箱、媒体库与播放 | 未做 | 按用户指定顺序，5a/5b 之后再排；且反馈的**读接口**还不存在（现在只有提交） |
| 5d 提取/批量/定时/Agent 页 | 未做 | 同上；Agent 还等模型接入（方案 B）落地 |
| 5e 授权/库维护、5f AI | 未做 | 同上 |
| Playwright 端到端 | 未跑 | 本机没装 playwright；`web/e2e` 会在缺依赖时自动跳过。构建产物、契约、隔离、nginx 行为都已单独验证 |
| 本地 `npm run dev` 登录联调 | 不可用（预期） | 服务端要求 `Origin === https://<host>` 且 Cookie 是 `Secure`；本地 http 只能看页面。已在 `web/README.md` 写明，不为此放宽服务端校验 |

## 4. 文件

新增：

- `web/public-app/*`（34 个文件来自 `wp7-web-public`）
- `web/owner-app/{index.html,vite.config.ts,public/favicon.svg}`
- `web/owner-app/src/{main.tsx,app.tsx,api.ts,router.tsx,sections.tsx,styles.css}`
- `web/owner-app/tests/gate.test.tsx`（7 项，含「边缘 404 → 中性页面」）
- `tools/tests/test_web_contract.py`（7 项）

修改：

- `web/shared/api.ts`（`Config`/`Me` 字段、暴露 `request`）
- `web/{package.json,tsconfig.json,vitest.config.ts,scripts/size.mjs,README.md}`
- `deploy/nginx/getmedia-prefix.conf`（owner 静态页与接口 location、统一 404）
- `tools/tests/test_deploy_templates.py`（location 集合 + owner 静态页断言）
- `tools/tests/test_deploy_nginx.py`（真实 nginx + 桩上游：匿名/free 一律 404，共 12 项）
- `web/public-app/src/router.tsx`、`pages/auth.tsx`、`pages/account.tsx`（登录后按 tier 决定是否进管理台）
- `docs/STATUS.md`、`AGENTS.md`（进度口径）
