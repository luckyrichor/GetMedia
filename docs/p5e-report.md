# P5e 网页：平台授权与库维护（2026-11-16）

两栏都接已有接口，**没有新增服务端接口**。5f（AI 视频）仍是占位页。

---

## 1. 平台授权（`/platforms`）

### 1.1 展示

- `GET /account/platforms`：平台名、可用授权方式、OAuth 是否配置；
- `GET /account/platform-bindings`：绑定（来源、时间）+ 凭据状态（`label`、`status`、`decryptable`）。
  接口本身就不返回 Cookie 内容，页面也没有任何地方能读到它。

页面按平台列卡片：已绑定/未绑定、绑定来源（网页登录 / 导入 Cookie）、绑定时间、凭据条数、
**能不能解密**（密钥换过就会解不开，这时要重新授权）。抖音 OAuth 未配置时单独一行说明。

### 1.2 授权流程

两条路都从 `POST /account/platform-auth/start` 建一个 10 分钟会话：

| 方式 | 页面行为 | 后续 |
|---|---|---|
| 手机网页登录 | 建会话 → 提示"去手机 App 里完成登录，回来点检查状态" | `POST .../status` 拉状态；`done` 后刷新绑定 |
| 粘贴 Cookie | 建会话 → 出现粘贴框（`spellcheck=off`、`autocomplete=off`） | `POST .../platform-auth/cookie` 提交 |

导入 Cookie 的几条硬规则（页面与代码都遵守）：

1. 提交前必须二次确认，说明"提交后页面立刻清空"；
2. 成功回调后**立即清空**输入框、关闭会话面板——页面不保留明文；
3. 服务端拒绝（缺 SESSDATA / 缺 sessionid / 保险箱不可用）时显示原因，且**不假装成功**；
4. 保险箱不可用（`credential_vault=false`）时整页只读：三个按钮全禁用，顶部给出原因与
   "需要配置 `GETMEDIA_CRED_KEY` 并重启"。

解绑 `POST /account/platform-bindings/{platform}/unbind` 要二次确认，并说明"只删凭据与文件，媒体库不受影响"。

## 2. 库维护（`/maintenance`）

- `GET /v2/maintenance/storage`：归档/缓存各一条进度条（已用 / 配额 / 百分比），
  下面按存放方式列条目数与字节（仅元数据 / 归档 / 缓存）。
- 「清理过期缓存」`POST /v2/maintenance/prune-cache`：确认后执行，返回删了多少条、释放多少字节。
- 「清理孤儿文件」`POST /v2/maintenance/orphans`：确认后执行，返回删除个数。
- 两个清理都写明是**全库**操作（不按账号过滤）——服务器上只有 owner 会用服务端媒体库。
- 「提取失败上报」`GET /v2/diagnostics/extractions`：最近 50 条，显示错误码、说明、平台、
  作品号、关联的媒体记录、客户端版本与阶段。

## 3. 验证

```
npx tsc --noEmit          通过
npx vitest run            123 passed（12 个文件；5e 新增 10 项）
npm run build             两个产物成功
npm run size              公开页 20.3 KB gzip（上限 60）；管理台 30.7 KB gzip（上限 80）
python -m pytest tools/tests/test_web_contract.py    9 passed
```

| 文件 | 数 | 覆盖 |
|---|---|---|
| `owner-app/tests/platforms.test.tsx` | 5 | 绑定/凭据展示且无 Cookie 内容、保险箱不可用整页只读、导入 Cookie 的成功路径（提交体、提交后清空、页面不再含明文）、服务端拒绝时显示原因、解绑二次确认 |
| `owner-app/tests/maintenance.test.tsx` | 5 | 占用与配额、清理缓存（取消不发请求 / 确认后显示释放量并刷新）、孤儿文件、上报列表与空态、读取失败重试 |

`gate.test.tsx` 的占位页用例改指向 `/ai`（5f），"已接入"用例扩到 5d/5e 六栏。

### 3.1 产物隔离

公开页构建产物仍然没有任何 owner 标记（`config/owner`、`/owner/api`、`owner-app`、`auth_request`），
管理台 41 条 方法+路径 全部在服务端存在。管理台 gzip 27.8 → 30.7 KB。

## 4. 一个需要你知道的取舍

平台授权页**保留了 Cookie 导入入口**（服务端本来就有这个接口，手机端也用它）。理由是：换机或
Cookie 过期时，没有这个入口就只能靠手机；而这一页已经在 owner 认证 + nginx 404 隐藏 + CSRF 之后。
页面为此加了三条限制：粘贴框不自动填充不拼写检查、提交前二次确认、提交成功立刻清空。

如果你希望管理台彻底不给这个入口（只保留"手机网页登录"），说一声，删掉一个按钮和一段表单即可。

## 5. 文件

新增：`web/owner-app/src/pages/{platforms,maintenance}.tsx`、
`web/owner-app/tests/{platforms,maintenance}.test.tsx`

修改：`web/owner-app/src/{api.ts,app.tsx,sections.tsx,styles.css}`、`web/owner-app/tests/gate.test.tsx`、
`docs/STATUS.md`
