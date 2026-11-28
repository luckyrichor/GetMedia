# web

两个**独立构建**的前端工程，共用 `shared/` 里的类型与无副作用工具：

| 目录 | 产物 | 部署路径 | 谁能看 |
|---|---|---|---|
| `public-app/` | 公开页（Vite + Preact + TS） | `/getmedia/` | 所有人 |
| `owner-app/` | 管理台（同一个技术栈，独立 build） | `/getmedia/owner/` | `full` / `admin` 账号（nginx `auth_request` 守门，应用再 `require_tier` 复核） |
| `shared/` | 接口客户端、错误文案、校验、Markdown 渲染 | — | 两边都用；**不得引用任何 owner 代码** |

## 开发与验证

```sh
cd web
npm ci

npm run dev          # 公开页（vite，默认 5173）
npm run dev:owner    # 管理台

npm run typecheck
npm run test         # vitest，基线 259 项（含公开页、管理台与 shared）
npm run build        # 两个产物都构建（tsc --noEmit 先跑）
npm run size         # 产物体积：公开页 ≤ 60 KB、管理台 ≤ 80 KB（JS+CSS gzip）
```

## 与后端的连接

- 公开页：同源 `/getmedia/api/…`（nginx 按免费白名单放行，其余 404）。
- 管理台：同源 `/getmedia/owner/api/…`（nginx 先 `auth_request` 判身份，再由应用 `require_tier(*FULL_TIERS)` 复核等级）。
- 基址都从 Vite 的 `base` 推导（`import.meta.env.BASE_URL`），**源码里没有绝对 URL**，换域名/前缀不用改代码。
- 会话只在 HttpOnly Cookie（`__Secure-gm_session`）里；前端不接触令牌，也不写 localStorage/sessionStorage。
  写操作带 `X-GetMedia-CSRF`，服务端同时校验 `Origin`。

### 本地联调为什么需要 HTTPS

服务端对 Cookie 会话的写操作要求 `Origin === https://<host>`，Cookie 本身是 `Secure; HttpOnly; SameSite=Strict`。
所以 `npm run dev` 起在 `http://localhost` 时，**只能看页面，登录/提交会被 403 挡下**，这是预期行为，不是 bug。
需要完整联调时，用 `python -m pytest web/e2e`（自签 HTTPS + 真实服务端 + 构建产物，见 `e2e/conftest.py`），
或者把产物放到 nginx 后面联调。不要为了本地方便放宽服务端的 Origin 校验。

## owner 认证壳（P5b）

- 门：只调 `GET /v2/config/owner` —— 401 去登录（回跳 `/owner/`）、403 显示"不是 owner"、200 进壳。
- 导航：概览 + 9 个栏目，每个栏目都标了交付阶段（5c–5f）与"还差什么"，不做"看起来已完成"的空页面。
- 退出：`POST /account/logout` 后回公开站。
- 静态页本身也由 nginx 守门：未登录 302 到登录页、非 owner 404（不暴露管理台存在）。

## 产物隔离

公开页与管理台是两次构建，`public-app/dist` 里不含任何 owner 代码/接口路径。
`tools/tests/test_web_contract.py` 会在构建后检查这一点（找不到 `dist` 时跳过），
另有一条测试检查 `shared/api.ts` 里出现的每个"方法 + 路径"都真实存在于服务端路由。
