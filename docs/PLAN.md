# GetMedia 开发方案

**方案版本：v3（2026-10-31）**　状态：**用户已确认，生效**
**用途**：多个 Agent 接力开发的唯一依据。开工前依次读：本文 → `docs/STATUS.md` → `docs/DECISIONS.md`。方案有改动，必须先改本文并在 §16 登记，再动手。
**v3 相对 v2 的变化**：①澄清"免费版必须注册、开放注册"，把"注册全关"明确为上线前准备期；②澄清免费版 / 完整版 / owner 账号的关系；③修正协议热加载漏洞（同意记录绑定文本哈希）；④Argon2id 用 `cryptography` 自带实现，补上遗漏的 `python-multipart`；⑤登记证书依赖风险；⑥写明"验收通过"的含义；⑦用户确认 v2 其余部分（含 `9443` 端口、音频+视频合并）。

来源标记：**[用户]** 用户明确说过　**[任务书]** 任务书条款　**[提议]** 本方案提出，用户已同意或未异议　**[待定]** 尚需用户决定（不阻塞当前工作包）

---

## 1. 目标、范围与硬约束

**目标**：在 GetMedia 仓库里做一个**同时能提取音频和视频**的新应用。第一期（称"任务一"）出**免费版**：Android 应用 + 网页端，共用同一套服务端账号；**用户必须注册账号才能使用，并且面向公众开放注册**；服务端按账号等级判权，一切付费/重型功能都在服务器上拦截。

**任务一交付物**
1. 服务端（账号与注册、会话、tier 判权骨架、config、心跳、协议、反馈、注销、管理 CLI、备份）。
2. Android 免费版 APK（`free` 风味；本地提取音频+视频、本地播放与管理；占位入口）。
3. 公开网页 `web-public`（介绍、下载、账号、联系作者；**不能提取、不能播放**）。
4. 部署模板与手册、验收报告。

**任务二（不在本期，仅预留接口）**：Android `full` 风味（`com.getmedia.app.full`，给 owner 自己用）、个人网页 `web-owner`（走服务器提取与播放，仅 owner 账号）、博主批量、定时抓取、Agent、AI 视频重建等重型功能，从 GetAudio / GetVideo 移植到 GetMedia 服务端的 owner 专属模块。

**硬约束**
1. **[用户] 一切开发都在 GetMedia 仓库里。GetAudio、GetVideo 只读，不得修改**（不开分支、不提交、不推送）。线上的 `getaudio-api`、GetVideo 服务和两个现有 App 不受影响。
2. **[用户] 免费版包名用 media。**
3. **[任务书] 不部署生产、不强推、不提交凭据/数据库/音频/构建产物**；改云防火墙/开端口必须先问；任务书文件本身不进仓库。
4. **[任务书] 不依赖"源码不公开"保证安全**（三个仓库都是公开的）：权限判定只在服务端；密钥与收件邮箱只在服务器环境变量文件；仓库只放模板和占位值。
5. **[任务书] `free` 构建里不得存在重型功能的真实实现**（编译期保证，不是运行时隐藏）；`web-public` 同理，不得出现任何 owner 页面、路由、接口路径或文案。
6. **[任务书] 范围外**：文件云备份、元数据双向同步、平台绑定、跨用户去重、公共媒体资源池、支付计费、公开网页端的提取/播放/媒体库、云端协助提取。
7. 真机验收不由 Agent 宣称完成：构建通过 ≠ 验收；未做的真机项必须在报告中单列。

## 2. 决定记录（已定）

| # | 决定 | 来源 |
|---|---|---|
| 1 | 全部开发在 GetMedia；Audio/Video 只读 | [用户] |
| 2 | 免费版包名用 media；**free = `com.getmedia.app`，full = `com.getmedia.app.full`**（单一常量处定义，发布前用户可改） | [用户]+[提议] |
| 3 | GetMedia 服务端是**全新独立服务**：独立目录/DB/密钥/env/systemd/端口；**对外走现有 HTTPS 443，路径前缀 `/getmedia/`**（nginx 剥掉前缀后转发），回环 `127.0.0.1:8700`；不新增对外端口；GetAudio 自身的路由与 GetVideo 的 8443 不改 | [提议]；2026-10-31 用户确认"其余按方案" |
| 4 | **公开网页端不能提取、不能播放**；**个人网页端（任务二）走服务器提取与播放，仅 owner 账号** | [用户] |
| 5 | **签名密钥：只一把，免费版/完整版/调试版共用**，固定存放、
| 6 | **协议文本**：产品名由 GetAudio 仅替换为 GetMedia，其余条款不动；**文本与发布信息放服务器、可改、无需重新构建**；APK 内置一份离线兜底 | [用户] |
| 7 | **免费版必须注册、面向公众开放注册。** 首次部署时 `registration_open` 先关，作为上线前准备期（见 §15、§17）；验收后由用户开放。用户也可选择一上线就开放 | [用户]，2026-10-31 澄清 |
| 8 | 接受**不备案**直接以 IP 对外服务，随时可关停 | [用户] |
| 9 | **连续推进**，不再每个工作包都停；只在"必须停下"的情形停（见 §14.3） | [用户] |
| 10 | APK 下载：GitHub Release 为主，服务器限速直链为备用 | [提议]，用户同意 |
| 11 | 登录名仅 ASCII 小写字母/数字/下划线/连字符，3–32 位，NFKC 规范化后唯一，禁止形如手机号；注册勾选语："我已年满 18 周岁，并已阅读并同意《用户协议》和《隐私说明》" | [提议]，用户同意 |
| 12 | 邮件配置位置 `/etc/getmedia/feedback.env`，变量名固定为任务书的七个 `FEEDBACK_*`；主题前缀 `[GetMedia 反馈]`；QQ 邮箱 SMTP 已由用户在现有服务器上测通（GetMedia 的 env 文件需用户另建一份） | [用户]+[提议] |
| 13 | 注销冷静期（7 天）内只能由管理 CLI 恢复；Python 包 `server/getmedia`，环境变量前缀 `GETMEDIA_`；Android 版本号从 `0.1.0` 起 | [提议]，用户同意 |
| 14 | 备份：本机每日快照、7 份滚动、不出服务器；注销台账长期保留；可长期保留不含个人信息的每日聚合计数 | [任务书] |
| 15 | Android 构建/单测/lint/反编译检查由用户在 Mac 上执行；Agent 提供命令并读取回传结果；未回传不得声称"构建通过" | [用户] |
| 16 | 飞书同步本任务不做，用户另行处理 | [提议] |
| 17 | 服务端现有账号（2 个）都是用户自己的，不迁移；在 GetMedia 注册新账号，由管理 CLI 设为 owner | [用户]+[提议] |
| 18 | **一个新应用同时做音频和视频**（WP5 合并 Audio+Video 本地功能），不再"先音频后视频" | [用户]，2026-10-31 确认 |
| 19 | **协议同意记录绑定文本哈希**：同意记录存版本号 + 两份文本的 sha256；服务器发现"版本号不变但文本变了"时拒绝加载新文本并告警，必须改版本号 | [提议]，用户同意 |
| 20 | **免费版 / 完整版 / owner 的关系**：owner 是账号等级，不是 App 类型。免费版 APK 里没有重型功能代码，用 owner 账号登录免费版也只有免费功能；重型功能只在任务二的完整版和个人网页里，且服务器按等级判权 | [用户]，2026-10-31 澄清 |

## 3. 现状事实（本方案的依据）

| 项目 | GetAudio | GetVideo |
|---|---|---|
| 服务端 | FastAPI + SQLite；127.0.0.1:8800，经 443 对外 | 127.0.0.1:8900，经 8443 对外 |
| Android | 80 个 Kotlin 文件；`com.getaudio.app` 0.9.10；Room schema 9 | 85 个文件；`com.getvideo.app` 0.3.10；Room schema 9 |
| 上游基线 | `dc2abeb` | `3dccb1c`（GetVideo 由 GetAudio 分叉） |

- 服务端两边结构同源：差异集中在 `extractors/bilibili.py`、`extractors/douyin.py`、`sharelink.py`、`api.py`。GetVideo 独有：`recreation_*`（AI 重建）、`extractors/video.py`、提示词。
- Android 两边大部分同名文件：差异最大的是库页、剪辑页、`DirectExtractor`、`TrackRepository`、`PlayerHolder`。Audio 独有：`BilibiliBrowserAudio`、`BilibiliWarmup`、`DouyinNoteAudio`、`PlaybackMemory`/`PlaybackSnapshot`。Video 独有：`VideoMuxer`、`VideoSources`、`CoverCache`、`VideoPlaybackPanel`、`VideoSurface`、`ServerProbe`、`Recreation*`。
- 数据模型几乎相同（Track 仅默认格式不同：`m4a` / `mp4`）。
- 文件数、行数、"逐字相同"等统计为前期估算，**WP5 对照表会重新核对**，以对照表为准。
- 旧服务端的游客体系、`legacy` 范围、共享 Basic 口令、旧路由，**GetMedia 不继承**。
- 运行环境：服务器 Python 3.12，Pillow 12.x；沙箱为 Python 3.13，测试须在两个版本语义下都通过（不使用 3.13 专有特性）。

## 4. 总体架构

### 4.1 仓库结构（单仓库）

```
GetMedia/
├─ server/                  Python 包 getmedia（FastAPI + SQLite）；测试在 server/tests
├─ android/                 单个 Gradle 工程、单个 app 模块、两个风味 free / full
│  └─ app/src/{main,free,full,test,androidTest}
├─ web/
│  ├─ shared/               认证、反馈表单、协议渲染等公共组件（不得引用 owner 代码）
│  ├─ public-app/           web-public（任务一）
│  └─ owner-app/            web-owner（任务二；任务一不建）
├─ deploy/                  只放模板：nginx、systemd、env 示例（全占位值）
├─ docs/
│  ├─ PLAN.md  STATUS.md  DECISIONS.md
│  ├─ contracts/            接口契约、数据表、错误约定（各端唯一依据）
│  ├─ legal/                协议文本种子（产品名已替换）；构建时内置进 APK
│  ├─ provenance.md         每个从 Audio/Video 带来的文件的来源与上游提交
│  └─ reports/              各工作包小结、验收报告、WP5 对照表
└─ tools/                   泄露扫描、禁用词扫描、密钥与构建脚本
```

### 4.2 复用上游：复制 + 溯源，不用子模块

1. `docs/provenance.md` 固定基线：GetAudio `dc2abeb`、GetVideo `3dccb1c`。
2. 把需要的文件**复制进 GetMedia 并改名**，逐个登记"来源文件、上游提交、改了什么、为什么"。
3. 公共部分以 **GetAudio 为底**（更完整：播放记忆、抖音图文配乐、B 站浏览器兜底），把 GetVideo 的视频能力以 `MediaKind`（`AUDIO` / `VIDEO`）并进来。
4. 上游基线之后的变动**不自动同步**；是否人工合入由用户决定，记入 DECISIONS。

### 4.3 服务端

- 新服务 `getmedia-api`：`server/getmedia`；SQLite（WAL、`busy_timeout`）+ 版本化迁移框架（`schema_version` 表 + 顺序脚本，不引 Alembic；迁移脚本须幂等，如"列不存在才添加"；有待执行迁移时先做一致快照，失败则中止启动）。
- **任务一的服务端很小**：账号与会话、tier 判权骨架、`/v2/config`、`/v2/heartbeat`、`/v2/legal`、`/v2/feedback`、注销、管理 CLI、备份。**不含**提取、博主批量、定时抓取、Agent、AI 重建。
- 所有接口从第一天起都是"账号 + 会话"，没有游客、没有共享口令。
- 单进程部署（uvicorn 1 worker）；后台任务（邮件发送、保留期清理）以进程内线程实现，**不新增长驻服务**；仅"每日快照"用 systemd timer 单独触发（因进程重启会漏跑）。
- **依赖**：FastAPI、SQLAlchemy、`cryptography`（Argon2id、HMAC）、Pillow、**`python-multipart`（反馈 multipart 必需）**、httpx；全部固定版本。**不新增 `argon2-cffi`**。

### 4.4 tier 判权

- 账号表 `tier`（`free` 默认、`owner`、预留 `plus`）+ `tier_expires_at`（可空，过期按 `free` 处理）。
- 路由器级依赖 `require_tier(...)`；**新增路由默认归 owner**，免费白名单显式列出。
- 一条测试遍历 `app.routes`：不在免费白名单、又未挂 owner 依赖的路由 → 测试失败。另有仅测试用的 owner 路由，验证 free 账号 403 且写审计。
- 免费档服务端接口只有：账号（含注册）、`/health`、`/v2/config`、`/v2/heartbeat`、`/v2/legal`、`/v2/feedback`、注销与设备管理。
- 任务一里**没有任何真实的 owner 功能**：owner 账号用于管理和上线前自测；真正的 owner 功能在任务二。

### 4.5 Android

- 包结构：`com.getmedia.app.{core, extract, library, player, edit, ui, account, feedback, placeholder}`。
- `main`：全部本地功能 + 免费版账号/反馈；`src/full`：任务二的重型功能（任务一只留空接口与占位实现）；`src/free`：占位入口与内置协议种子。
- **免费版与完整版是两个独立安装包**（包名不同，可同机并存）；同一份代码、不同风味，重型代码只在 `full` 里。
- `main` 对重型能力只依赖小接口（如 `OwnerFeatures`）；`full` 提供真实实现，`free` 提供"暂未开放"实现；`main` 中**不得出现**重型类名与接口路径（禁用词扫描强制）。
- 包名常量放 `android/app-ids.properties`（发布后不可改，所以只此一处）。
- 数据库：全新 Room 库，schema 从 1 起，实体取 Audio/Video 并集并加 `mediaType`；**不迁移旧应用（GetAudio/GetVideo）的本地数据**，旧应用库里的内容不会进入 GetMedia。
- 网络：只允许 HTTPS，关闭明文（`cleartextTrafficPermitted="false"`）；服务器地址为 `BuildConfig` 编译期常量，设置页不可改；证书校验保持开启，**不做证书固定**（IP 证书约 6 天轮换，固定叶证书会在续期时让全体用户失联）。
- 会话令牌用 Android Keystore 加密保存。
- **首次使用必须注册或登录**；登录后离线可继续使用全部本地功能。

### 4.6 网页端：公开版 vs 个人版

| | `web-public`（任务一） | `web-owner`（任务二） |
|---|---|---|
| 使用者 | 任何人 | 仅 owner 账号 |
| 能力 | 介绍、下载 APK、**注册**/登录/找回、账号与安全、联系作者、维护提示 | 在公开版基础上加：**服务器提取、服务器端媒体库与播放**、博主批量等 |
| 提取/播放 | **没有**（代码里也没有） | 有，走服务器 |
| 构建 | 独立产物，禁用词扫描保证无 owner 内容 | 独立产物 |

- 技术：**Vite + Preact + TypeScript**；预计 gzip 后 30–60 KB；无第三方脚本/字体/统计/CDN；系统字体栈。
- 同源：网页与接口同一来源；**不开 CORS**；`/getmedia/` 下的 nginx **只放行免费路径**。
- 会话：`__Secure-` 前缀 Cookie，`Secure; HttpOnly; SameSite=Strict; Path=/getmedia/`（`__Host-` 要求 `Path=/`，会让 Cookie 对同源的其他应用路径可见，故不用）；写操作要求自定义头 + `Origin` 校验；网页会话 14 天滑动、最多 3 个。
- 安全头：CSP `default-src 'self'`、`frame-ancestors 'none'`、`X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`、`X-Robots-Tag: noindex, nofollow`，`robots.txt` 全站 Disallow。
- 输出一律转义，不用 `innerHTML` 渲染用户内容；协议 Markdown 用自带的极小渲染器，**不透传 HTML**。
- **任务二的设计钩子（任务一先预留，不实现）**：`web-owner` 将通过 nginx `auth_request` 校验 owner 身份后才下发（避免任何人下载 owner 页面代码）；服务端需要一个仅限 nginx 内部调用的"是否 owner"检查接口；owner 的服务器提取受 10 Mbps 总带宽限制，需另做并发与限速设计。

### 4.7 服务器侧可改、无需重新构建的内容 [用户]

| 内容 | 位置（服务器） | 生效方式 |
|---|---|---|
| 用户协议、隐私说明 | `GETMEDIA_LEGAL_DIR`（默认 `/etc/getmedia/legal/`）下两个 `.md` 文件 | 改文件即生效（按修改时间热加载）；**必须同时改版本号**，否则拒绝加载（见下） |
| APK 发布信息（版本、sha256、下载地址、更新说明、最低版本） | `GETMEDIA_RELEASE_FILE`（`/etc/getmedia/release.json`） | 改文件即生效；网页下载页与 `/v2/config` 读取同一份 |
| 注册开关、维护提示 | `/etc/getmedia/runtime.json` 或管理 CLI | 即时生效 |

**协议文本机制（v3 含哈希绑定）**
- 文件首部必须含 `版本：vN　生效日期：YYYY 年 M 月 D 日`；服务端解析版本号，**解析失败则拒绝启动并报错**（不静默回退）。
- 服务端为每份文本计算 sha256，并在库表 `legal_versions` 里登记 `(文档, 版本号, sha256, 首次加载时间)`。
- **热加载规则**：新读到的文本若 `版本号相同但 sha256 不同` → **拒绝加载新文本，继续使用上一份已登记的文本**，写日志并发告警邮件（同类每天最多一封）；`版本号不同` → 登记新版本并生效，用户下次登录必须重新同意。
- `GET /v2/legal` 返回两份文本的 `{version, effective_date, sha256, text}`；App 启动时取并缓存；APK 内置的种子副本（来自 `docs/legal/`）仅作离线兜底。
- 注册请求须带客户端**实际展示的**两份版本号（及哈希）；与服务器当前版本不一致 → 409 `legal_outdated`，客户端刷新文本后重新展示并重新勾选。
- 登录响应携带 `consent_required` 与当前版本；版本变更后下次登录必须重新同意。
- 同意记录存：两份文本的**版本号、sha256、同意时间**（不存文本全文）。
- 协议文本**不改条款含义**；产品名替换只发生在 `docs/legal/` 种子文件与服务器文件，记入 DECISIONS；产品名替换后的首个版本沿用 `v1`（尚无任何同意记录），此决定记入 DECISIONS。

### 4.8 签名密钥（一把、任何地方都一样）[用户]

- **只生成一把** Media 密钥库 `getmedia-release.jks`，**免费版、完整版、调试版共用**（包名不同，不冲突）；不再依赖各机器自动生成的临时调试签名，彻底避免"这台能覆盖安装、那台不行"。
- 密钥与属性文件固定位置 `~/.android-keys/getmedia.properties`；**绝不入库**（`.gitignore` 含 `*.jks`、`*.keystore`、`keystore.properties`；泄露扫描也检查）。
- `tools/make-release-key.sh`：在用户的 Mac 上一次性生成密钥（Agent 不接触私钥与口令）。
- `tools/setup-release-key.sh`：把密钥从**用户指定的本机路径**复制到 `~/.android-keys/`，权限 600；不涉及服务器、不联网。
- `build.gradle.kts`：找不到密钥时 **release 与 debug 构建都直接失败**并提示运行 setup 脚本（逃生开关 `-PallowTempSign=true` 仅供明确不发布的本机试验，构建日志会打印警示）；每次构建打印签名证书 SHA-256 指纹，指纹记录在 `docs/`（指纹是公开信息，可入库）。
- 密钥丢失 = 已安装的 App 永远无法覆盖升级；密钥**只存在用户的 Mac**，外加用户自己的离线加密备份；**不放服务器、不入库**。

## 5. 免费版功能清单

**真实功能（手机本地）**：单条与多链接批量提取（**音频 + 视频**，B 站/抖音）、本地导入、媒体库（全部/音频/视频）、类别、播放列表、播放与队列（含视频播放）、定时停止、本地剪辑、背景与壁纸、本地诊断导出。

**账号与服务**：**首次使用必须注册/登录**（Keystore 加密会话）；账号与安全（设备列表、退出其他设备、改密码、注销、查看协议）；联系作者；"测试服务器连接"（只显示成功/失败，不显示地址）。**服务器不可达时不阻断本地功能**：已登录用户离线可用全部本地功能，仅注册/登录/找回/反馈需要联网。

**占位入口**（名称 + 一句话 + "暂未开放"；点开统一弹窗；不跳转、不联网、无业务代码/DTO/接口；静态数据生成；文案不写日期、不承诺、不做预约/投票）：多设备同步与云端备份、云端协助提取、博主主页批量提取、定时抓取与订阅、提取 Agent、AI 视频分析与重建。（占位文案属于 APK 内静态数据，改动需要重新构建。）

**明确不带入免费版**：服务器提取、服务器地址/凭据配置、失败自动上报、平台授权、Agent、博主批量、AI 重建。

## 6. 接口契约（摘要；完整版在 `docs/contracts/`，WP0 先写）

通用约定
- JSON 错误体 `{"detail": "...", "code": "..."}`；未登录 401；等级不足 403（写审计）；**他人资源一律 404**；限流 429 带 `Retry-After`；表单校验失败 422（不回显原始输入）。
- App 用请求头 `X-GetMedia-Session`；网页用 Cookie（写操作另带 CSRF 头）。
- 注册请求携带 `client`：`channel`（`android`/`web`）、`app_version`、`os`、`device`。

| 接口 | 说明 |
|---|---|
| `GET /health` | 连接测试，返回 `{ok:true}` |
| `GET /v2/config` | 版本、`registration_open`、维护提示、最低版本、最新 APK 信息 |
| `GET /v2/legal` | 两份协议的版本、生效日期、sha256、文本 |
| `POST /account/register` | 登录名、密码、邀请码（开关）、`age_confirmed`、两份协议版本与哈希；返回会话 + **一次性恢复码**。`registration_open` 为假且无有效邀请码时返回 403 `registration_closed` |
| `POST /account/login` · `/logout` · `GET /account/me` | 统一失败文案、恒定耗时；返回 `tier`、`consent_required` |
| `POST /account/consent` | 重新同意 |
| `POST /account/recovery/ack` | 用户确认已保存恢复码（客户端未确认不得继续） |
| `GET /account/devices` · `DELETE /account/devices/{id}` · `POST /account/devices/revoke-others` | App ≤5 台、网页 ≤3 个；超限由用户选一个退出 |
| `POST /account/password` | 改密码（需旧密码） |
| `POST /account/recover` | 登录名 + 恢复码 + 新密码；旧恢复码作废并发新码；吊销全部会话；同账号每小时 5 次 |
| `DELETE /account/me` | 输入密码；停用并吊销会话；7 天后物理删除（含反馈文字与图片）；写删除台账 |
| `POST /v2/heartbeat` | 版本、系统、设备型号、渠道；每端每天最多一次 |
| `POST /v2/feedback` | multipart，规则见 §8 |

## 7. 服务端数据表（概要）

`users`（id `acct_…`、`login_name`、`login_name_norm` 唯一、`password_hash`、`tier`、`tier_expires_at`、`status`、`recovery_confirmed_at`、`created_at`）· `sessions`（token 哈希、user、渠道、设备名、版本、创建/最近使用/过期时间、最近 IP）· `recovery_codes`（HMAC、轮换时间）· `legal_versions`（文档、版本号、sha256、首次加载时间）· `consents`（user、两份版本号、两份 sha256、时间）· `invites`（码哈希、用途、已用）· `audit`（时间、事件、user、IP、路径、结果；**保留 30 天**）· `rate_limits`（键、窗口、计数；落库，重启不丢；**保留 30 天**）· `feedback`（id UUID、user、created_at、text、contact、diagnostics_json、image_paths、status、email_attempts、idempotency_key）· `daily_stats`（日期、注册数、活跃数；只有数字，长期保留）· `deletion_requests`（user、请求时间、执行时间）· `deletion_ledger`（user_id、删除时间；只追加；同时写入快照目录之外的文件）· `schema_version`。

## 8. 关键实现要求

**账号与注册**
- 密码 **Argon2id**，使用 `cryptography` 自带实现（沙箱实测 `cryptography 50.0.1`：内存 64 MiB、迭代 3、并行 1，单次约 126 ms）；并发哈希信号量 2；参数写入哈希串便于日后调整；WP1 在服务器规格上复测并调到单次约 100–200 ms。
- 会话令牌 32 字节随机，库内只存 SHA-256；App 90 天滑动、网页 14 天滑动；撤销即时生效；不做 refresh 轮换。
- 恢复码 16 位 base32（80 位熵），库内存 HMAC（密钥来自服务器环境）；注册后只显示一次。
- 限流（默认值，可配置）：登录每 IP 10 次/5 分钟 + 每登录名 5 次失败/15 分钟；注册每 IP 5 次/小时；恢复每账号 5 次/小时；全部落库；统一失败文案；对不存在用户做等价耗时的假哈希。
- 注册重名必然可被枚举，靠限流缓解；邀请码开关；`registration_open` 与邀请码开关均可由管理 CLI 即时切换。
- 注册开放时，所有人都可注册（可选再加邀请码）；注册关闭时，仅持有效邀请码或由 CLI 直接创建。

**反馈**（按任务书 §1.7）
- 字段、限流（每账号每天 5、每 IP 每天 10、间隔 ≥30 秒）、`idempotency_key`、诊断白名单 + 服务端二次过滤（丢弃含 cookie/token/password/Authorization/带查询串完整 URL 的内容）。
- 图片：校验魔数并 Pillow 真实解码，**限制像素总数（防解压炸弹）**，清除 EXIF/GPS，重新编码，最长边 ≤4096；随机文件名，存放在站点根之外；全站图片总量 ≤2 GB，超限拒绝新图片并告警；图片保留 90 天、文字 180 天。
- **先落库、再异步发信**；发信失败不影响用户看到"已提交"；数据库待发队列 + 退避重试，进程重启后继续。
- 邮件：纯文本；`FEEDBACK_*` 只从环境读取；收件邮箱与授权码只存在服务器环境变量文件；`contact` 只放正文，不作 Reply-To；防邮件头注入；全站每日发信上限，超出只入库不发信。**发信失败只记错误类别与 SMTP 数字码，不记异常原文**（原文可能含收件地址）。
- CLI：`feedback list / show / export / mark-handled`，邮件不可用时仍可查看（含图片路径）。**反馈库才是权威记录**。

**注销**：停用并吊销会话 → 7 天冷静期 → 物理删除账号、设备、反馈文字与图片 → 写台账。冷静期内仅管理 CLI 可恢复。

**保留期必须与隐私说明一致**：活跃信息与安全日志（审计、限流）最长 30 天；反馈文字 180 天、图片 90 天；快照最长 7 天（部署前手工备份同样留在服务器并 7 天内清理）；台账长期保留，只存账号编号与删除时间。

**备份**：每日 SQLite `.backup` 一致快照 → 受限目录（目录 700、文件 600，不在站点目录下）→ 完成后 `integrity_check` → 保留最近 7 份；**不上传任何外部位置**。恢复流程：停服务 → 换库 → `restore --replay-ledger`（重放删除台账，已注销账号不得复活）→ 启动。需实际演练一次，结果写入验收报告。已知并接受：整机丢失时快照一并丢失；平台凭据加密密钥不在快照里（任务二才涉及）。

**告警（复用邮件通道，同类每天最多一封）**：证书剩余有效期 <24 小时（可配置）、磁盘水位、快照失败、反馈图片总量接近上限、协议文本哈希不符。

**管理 CLI（仅 SSH）**：注册总数、近 1/7/30 天活跃、分渠道与版本分布、按日新增；**直接创建账号并可设为 owner**、停用/启用账号、吊销会话、设为 owner、生成邀请码、开关注册、查看审计、反馈管理、恢复注销账号、执行/校验备份与恢复。

## 9. 配置与环境变量（只列名字）

`GETMEDIA_DATA_DIR`、`GETMEDIA_DATABASE_URL`、`GETMEDIA_SECRET_KEY`（HMAC 用）、`GETMEDIA_LEGAL_DIR`、`GETMEDIA_RELEASE_FILE`、`GETMEDIA_RUNTIME_FILE`、`GETMEDIA_BACKUP_DIR`、`GETMEDIA_LEDGER_PATH`、`GETMEDIA_FEEDBACK_IMAGE_DIR`、`GETMEDIA_BIND`（默认 `127.0.0.1:8700`）；邮件：`FEEDBACK_MAIL_TO`、`FEEDBACK_MAIL_FROM`、`FEEDBACK_SMTP_HOST`、`FEEDBACK_SMTP_PORT`、`FEEDBACK_SMTP_SECURITY`（`ssl`/`starttls`）、`FEEDBACK_SMTP_USER`、`FEEDBACK_SMTP_PASSWORD`；告警阈值 `GETMEDIA_ALERT_CERT_HOURS`（默认 24）。仓库只提供 `*.example`，全占位值（占位邮箱用保留域名 `example.invalid`）。

## 10. nginx 模板要点（`deploy/`，用户手动部署）

- 在**现有 443 server 块**内只加一行 `include /etc/nginx/snippets/getmedia.conf;`，全部 GetMedia 配置（`location = /getmedia` 重定向到 `/getmedia/`、`location /getmedia/` 等）放进该片段文件；沿用该块已有的 IP 证书，不新增监听端口。回滚 = 删除这一行并 reload。片段内用更长前缀，不与 GetAudio 的根路径路由冲突；`access_log`、`add_header`、`client_max_body_size` 在片段内显式重设（不继承外层）。
- **证书依赖说明**：该证书由 GetAudio 现有的续期任务维护。GetMedia 与它共用同一张证书，因此只要该续期任务在，GetMedia 自动跟着续；**若日后停用或迁移 GetAudio，必须先给 GetMedia 建立独立的续期任务**（见 §16 风险）。部署手册写明证书路径、续期任务归属与失败处置。
- 只放行免费路径：`/account/*`、`/health`、`/v2/config`、`/v2/legal`、`/v2/heartbeat`、`/v2/feedback`；其余 404。静态文件根在仓库之外。
- `client_max_body_size`：全站小值，`/v2/feedback` 单独放到 14m；对注册/登录/恢复/反馈设 `limit_req`。
- 反向代理到 `127.0.0.1:8700`，`X-Forwarded-For` 覆盖为 `$remote_addr`。
- 安全头同 §4.6；**不开 CORS**；访问日志不记录 Cookie 与令牌（令牌只在头/Cookie 里，默认格式不记录；模板显式固定 `log_format`，并有测试断言不含 `$http_cookie`、`$http_authorization`、`$http_x_getmedia_session`）。
- APK 直链备用：`limit_conn` 每 IP 1 个、全站同时下载数上限 3、`limit_rate` 约 300 KB/s（总带宽只有 10 Mbps）。主下载走 GitHub Release。
- **不需要**新增防火墙规则（沿用已开放的 443）。
- 路径前缀处理：nginx `proxy_pass http://127.0.0.1:8700/;`（带尾斜杠）剥掉 `/getmedia`，服务端契约与路由不变；客户端基址为 `https://<服务器 IP>/getmedia`；网页以 `base=/getmedia/` 构建；服务端响应不得出现以 `/` 开头的绝对路径跳转。同源意味着与 GetAudio 共享 Web 来源：GetMedia 网页不得写 localStorage 存敏感数据，CSP 与安全头在片段内设置。

## 11. 工作包（WP）与依赖

三条线在 WP0 后并行：**服务端线**（WP1–4）、**Android 线**（WP5–6）、**网页线**（WP7）。契约由 WP0 冻结，各线按契约开发；改契约先改本文与契约并登记。

| WP | 内容 | 依赖 | 完成标准 |
|---|---|---|---|
| **WP0 脚手架与契约** | 仓库结构；`provenance.md`；`docs/contracts/`；`.gitignore`；`tools/` 的泄露扫描与禁用词扫描；协议种子（产品名替换，记 DECISIONS）；STATUS/DECISIONS 模板；构建与测试命令文档；`make-release-key.sh`/`setup-release-key.sh` | 无 | 扫描在干净树上通过；契约自洽 |
| **WP1 服务端：账号、注册与会话** | 配置与 SQLite 初始化、迁移框架与迁移前快照；登录名；Argon2id；注册（含邀请码、`registration_open`、年龄确认）；会话/设备（App ≤5、网页 ≤3）；恢复码；同意记录（版本+哈希）；协议文件加载、哈希绑定与 `/v2/legal`；落库限流 | WP0 | 单测：恢复码重置后旧码与全部会话失效；设备超限；版本变更触发重新同意；`legal_outdated`；**同版本号文本被改时拒绝加载并告警**；注册关闭时无邀请码被拒、有邀请码可注册；限流重启不丢；恒定耗时 |
| **WP2 服务端：tier、审计、config、心跳** | `require_tier`、默认拒绝路由遍历测试、审计、`/v2/config`、`/v2/heartbeat`、`daily_stats` | WP1 | free 访问测试 owner 路由 403 + 审计；遍历测试通过 |
| **WP3 服务端：反馈** | 接口、图片处理、限流、幂等、保留期清理、后台发信队列、CLI | WP1 | 任务书验收项逐条测试；SMTP 不可用仍落库；仓库内无邮箱字符串（扫描证明）；发信失败日志中无收件地址 |
| **WP4 服务端：运维** | 管理 CLI；注销与冷静期；保留期清理；快照 + 7 份滚动 + 台账重放；告警；`deploy/` 全部模板与部署手册（含证书依赖说明） | WP1–3 | 恢复演练实测；已注销账号不复活；告警去重测试 |
| **WP5 Android 基座** | 合并 Audio+Video 本地功能（见 §12） | WP0 | JVM 单测通过；Mac 构建通过（回传证据）；真机项单列 |
| **WP6 Android free** | 风味拆分、**注册**/登录/同意、账号与安全、联系作者、占位入口、禁明文、编译期地址、统一签名配置、内置协议种子与在线刷新 | WP5 + 契约 | 反编译 free APK 搜不到重型类名/路径/邮箱（Mac 回传）；离线时本地功能不受阻 |
| **WP7 网页 web-public** | 页面（含注册）、协议渲染、下载页（读 release 信息）、Cookie/CSRF/CSP、noindex、产物体积、禁用词扫描 | 契约；联调依赖 WP1–3 | 无头 Chromium 验证 Cookie 属性、CSP、无第三方请求；360 px 可用 |
| **WP8 验收** | `acceptance-report.md`，含义见 §17 | 全部 | 按任务书逐条给证据 |

推荐顺序：WP0 → {WP1→WP2→WP3→WP4} 与 {WP5→WP6} 与 WP7 并行推进（WP7 联调等 WP1–3）。

## 12. WP5 细则：Audio + Video → 一个本地应用

1. 先写**对照表** `docs/reports/wp5-merge-map.md`：逐文件列 Audio/Video 差异、取舍、归宿（并重新核对 §3 的统计）；以 Audio 为底。
2. **数据层**：`TrackEntity` 增加 `mediaType`；`TrackDao`/仓库按类型过滤；库页提供"全部 / 音频 / 视频"。
3. **提取器**：`DirectExtractor` 重构为"解析 → 候选 → 下载 → 抽轨或封装 → 原子保存"一条流水线；`MediaKind` 决定走抽轨（Audio）还是封装（`VideoMuxer`/`VideoSources`）；并入抖音图文配乐与 B 站浏览器兜底。
4. **播放**：音频沿用 Media3 会话；视频并入 `VideoPlaybackPanel`/`VideoSurface`；队列、播放记忆、定时停止对两种类型统一。
5. **剪辑与背景**：以 Audio 剪辑页为底；核对 Video 版差异是否为视频专有逻辑。
6. **剔除**：全部服务器调用代码、`Recreation*`、`GetVideoApi`/`GetAudioApi`、`ServerAccessStore`（死代码）；重型接口只在 `full`。
7. **测试**：迁移并改名 Audio/Video 的 JVM 单测；拖动判定等纯函数测试必须保留。
8. 版本 `0.1.0`，`versionCode` 从 1 起。

## 13. 验证环境与限制

- **服务端、网页**：沙箱内完整测试（pytest、无头 Chromium、本地模拟 SMTP、备份演练）。
- **Android**：沙箱无法访问 Google/Gradle/Maven。构建、单测、lint、反编译检查由用户在 Mac 上执行；Agent 提供确切命令并读取回传结果。可在 Mac 上执行的部分尽量打包成**一条命令**与一份结果文件。
- **服务器现状 / SMTP**：只能由用户在服务器上实测；Agent 提供只读探测脚本与邮件自检脚本。
- **真机**：全部由用户验收，验收报告中列清单并标注"未做"。

## 14. 协作规则

### 14.1 开工与交接
读 PLAN → STATUS → DECISIONS；在 STATUS 里把自己领取的 WP 标为"进行中 + Agent 标识 + 日期"；完成写 `docs/reports/wp<N>-summary.md`（做了什么、测试结果与数量、未验证项、遗留问题、下一步）并更新 STATUS。

### 14.2 目录归属与提交
服务端线只改 `server/`、`deploy/`；Android 线只改 `android/`；网页线只改 `web/`；`docs/contracts/` 与 PLAN 的修改先记 DECISIONS。每个 WP 一个分支 `wp<N>-<简述>`，小步提交，提交信息写明 WP；不强推；**合并进主分支由用户决定**。

### 14.3 连续推进与必须停下的情形 [用户]
默认**一直推进**，工作包之间不等确认。仅在以下情形停下并向用户说明：
1. 出现必须由用户决定的事项（写入 DECISIONS"待决"并明确提问）；
2. 需要用户在 Mac 上做事（构建 Android、生成密钥、真机验收），此时其他线继续；
3. 涉及生产/云控制台/防火墙/真实凭据的动作；
4. 发现与契约或本方案冲突、或需要扩大范围；
5. 测试失败且无法在当前范围内修复。

### 14.4 禁止
修改 GetAudio/GetVideo；提交密钥、邮箱、授权码、数据库、音频、构建产物；部署或改线上；把阶段报告里的服务器细节（实例 ID、防火墙规则、SSH 别名）写进仓库；测试夹具里使用真实邮箱（一律占位地址）。

## 15. 部署与上线（均由用户手动执行；Agent 只产出模板与手册）

1. 建运行用户/目录；放代码、建虚拟环境、装依赖。
2. 在 `/etc/getmedia/` 放 `feedback.env`（`root:root 600`，内容由用户填，**需另建一份，不复用 GetAudio 的**）、`legal/`（协议文件）、`release.json`、`runtime.json`。
3. 安装 `getmedia-api.service`（`EnvironmentFile=` 注入）与每日快照 timer。
4. 部署 nginx 新 server 块，`nginx -t` 通过后 reload。
5. 把 `getmedia.conf` 片段放到 `/etc/nginx/snippets/`，在现有 443 server 块里加 `include` 一行，`nginx -t` 后 reload（无需防火墙操作）。
6. **上线前准备期（注册暂关）**：`registration_open` 默认关。用管理 CLI 直接创建 owner 账号并生成邀请码；用免费版 App / 网页在真实服务器上走一遍注册（凭邀请码）、登录、改密码、联系作者、注销，确认整套在真实环境里可用。陌生人此时进不来。
7. 跑验收清单（§17）；**通过后由用户用 CLI 开放注册**（可先仅邀请码，再全面开放；随时可再关）。用户也可以选择跳过准备期、一上线就开放。
8. 在 Mac 上用统一密钥构建 free APK，真机验收；发布到 GitHub Release，把版本与 sha256 写入 `release.json`。
- 证书续期（既有定时器）若失败的处置手册随部署手册交付；上线前确认到期告警已生效。

## 16. 风险与变更记录

| 风险 | 缓解 |
|---|---|
| WP5 合并两个应用体量最大、易回归 | 先写对照表；以 Audio 为底增量并入视频；纯函数测试全迁移；真机验收单列 |
| 沙箱无法构建 Android | Mac 构建 + 回传；契约先行，服务端/网页独立推进 |
| IP 证书约 6 天有效，续期连续失败时 App 与网页同时连不上 | 到期前 24 小时告警（`GETMEDIA_ALERT_CERT_HOURS`，证书总寿命约 6 天 16 小时，上线后按实际续期周期再调）；续期手册；80 端口长期保留 ACME |
| **GetMedia 共用 GetAudio 的证书与续期任务；GetAudio 停用后证书会过期** | 部署手册写明归属；停用 GetAudio 前先建独立续期任务；告警覆盖 |
| 无备案以 IP 对外服务可能被阻断 | 用户已接受；随时可关停；无域名依赖 |
| QQ 邮箱限频/授权码失效 | 反馈库为权威；发信日上限；CLI 可查 |
| 公开仓库 + 已知 IP → 扫描、撞库 | 默认拒绝、限流、审计、注册先关 |
| **协议文本在服务器上被改而版本号不变** | 同意记录绑定 sha256；同版本号文本变化则拒绝加载并告警 |
| 上游 Audio/Video 后续演进，GetMedia 与之分叉 | provenance 记录基线；合入由用户决定 |
| 签名密钥丢失 → 已安装 App 无法升级 | 只存用户 Mac + 用户自己的离线加密备份 |

| 版本 | 日期 | 变更 |
|---|---|---|
| v1 | 2026-10-31 | 首版 |
| v2 | 2026-10-31 | 完整重写：并入用户全部答复；新增网页公开/个人划分、协议与发布信息服务器侧可改、统一签名密钥、数据表/配置/限流/nginx/备份细则、连续推进规则 |
| v3.1 | 2026-10-31 | ①删除两处涉及线上现有服务弱点的表述（公开仓库不写线上弱点）；②签名密钥只存用户 Mac + 离线备份，不放服务器；③证书到期告警改为 <24 小时；④对外改走现有 443 的 `/getmedia/` 路径前缀，不再新增 9443，免防火墙规则，Cookie 改 `__Secure-`+`Path=/getmedia/`。 |
| v3 | 2026-10-31 | 澄清免费版必须注册并开放注册（"注册全关"仅为上线前准备期）；澄清免费版/完整版/owner 关系；协议同意绑定文本哈希；Argon2id 改用 `cryptography`、补 `python-multipart`；登记证书依赖风险；写明"验收通过"含义（§17）；用户确认 `9443` 与音频+视频合并；用户确认生效 |

## 17. "验收通过"的含义

WP8 产出 `acceptance-report.md`，按任务书逐条给证据；以下全部满足、且用户阅读后认可，才算"验收通过"，之后由用户决定开放注册：

1. **越权**：账号 A 用 B 的 id 访问每个接口（设备、反馈编号、网页会话等），全部 404；匿名同样不可访问。
2. **tier**：免费账号直接构造请求访问重型/owner 接口，全部 403 且有审计；路由遍历测试通过。
3. **会话**：撤销设备/网页会话、停用用户、注销后，下一次请求即 401；本地库与本地播放不受影响。
4. **恢复**：恢复码重置后旧码作废、所有设备登出。
5. **注册与同意**：注册开关与邀请码行为；未勾选不能注册；同意记录含版本与哈希；版本变更后重新同意；同版本号文本被改时拒绝加载并告警。
6. **反馈**：文字/图片/诊断三种组合；超限、非图片、伪造扩展名、超大图/像素炸弹、含 EXIF GPS 的图（落盘后无 GPS）、邮件头注入、限流、重复提交；SMTP 不可用仍落库且可由 CLI 查看；邮件内容正确；日志与响应中无收件地址。
7. **反编译与产物检查**：反编译 free APK、检查 `web-public` 产物，搜索重型功能类名、接口路径、模型调用代码与邮箱字符串，均应没有（给出命令与结果，Android 部分由用户在 Mac 回传）。
8. **网页安全**：Cookie 属性、CSRF、CSP、无 CORS、无第三方请求、noindex、仅 HTTPS、不新增对外端口（走 443 的 `/getmedia/`）。
9. **备份**：每日快照、7 份滚动、恢复演练与台账重放的执行证据；已注销账号不复活；确认没有任何备份流出服务器。
10. **保留期**：审计/限流 ≤30 天、反馈文字 180 天、图片 90 天、快照 7 天的清理证据，与隐私说明一致。
11. **自动测试**与手工流程记录。
12. **真机项**：由用户在手机上验证（提取、播放、联系作者、离线可用、覆盖安装等），报告中单列，未做的明确标注"未做"，Agent 不代替宣称通过。
