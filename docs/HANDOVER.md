# GetMedia 接手说明（面向下一个 Agent）

写于 2026-11-27，对应 `main`（`v0.1.38` 那次提交）、tag `v0.1.38`。

本文件是**接手入口**，只讲"怎么接着干"。历史流水账在 `docs/STATUS.local.md`（本机，不入库），
方案与契约在 `docs/PLAN.md` / `docs/contracts/`。**旧文档与本文件冲突时，以本文件为准。**

`AGENTS.md` 的"当前进度"章节停留在 2026-11-20，讲的是 WP5a/WP6 时代的进度，**已过期，不要照着做**。

---

## 0. 三十秒了解现状

产品 = Android 音视频提取/管理/播放 App + Python/FastAPI 服务端 + 两个网页。

| 部分 | 状态 |
|---|---|
| Android | free / full 两个包，`0.1.39 / versionCode 40`（tag `v0.1.39`） |
| 服务端 | release `20261127-9`，SQLite schema **21**，运行中 |
| 账号等级 | `free` / `full`（可有时效，默认 6 个月）/ `admin`（唯一 `luckyrichor`，永久） |
| 线上协议 | 隐私说明 **v5**、用户协议 **v2**（2026-11-27 生效） |
| 线上管理台 | `index-iRvgLjpu.js` / `style-Dp7X_UUy.css`（单个提取页：作品名两行截断 + 点击展开全文、分页 5/10/20、宽屏表头吸顶、作者列收窄；两页的「存放与输出」拆成「存放」+「格式」；媒体库页改版 + 三个列表页的 SWR 缓存（内存 + sessionStorage）+ gate 两个请求并行，`src/swr.ts` 是缓存层，见 STATUS.local 2026-11-28 条目） |
| 线上公开站 | `index-CHhgq2uv.js` / `style-D1jGOt3t.css` |
| 用户 | 一个人在用（admin），手机 1 / 手机 2 两台 |

**工作节奏**：用户提具体界面问题 → 一轮改完 → 立刻部署 → 他验收 → 下一轮。
一轮改动通常很小，但要求"改完就上线"。**不要攒着不改，也不要擅自扩大改动范围。**

---

## 1. 先读什么

1. 本文件。
2. `docs/STATUS.local.md` — 最近 6 条（2026-11-27 那几节），看清上一轮改了什么、部署了什么。
3. `docs/PLAN.md` §2 决定记录、§14 协作规则、§15-7 发版记录（每次构建的哈希都在这）。
4. `docs/DECISIONS.md` — 计划之后的追加决定。
5. 需要动网页时看 `web/owner-app/src/pages/library.tsx`（最近迭代最密集的文件）。

---

## 2. 环境与命令

```sh
cd /Users/lc/Project/GetMedia
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/Users/lc/Library/Android/sdk

# 网页（在 web/ 下，npm 依赖已装好）
cd web && npm run typecheck          # tsc --noEmit
npm test                             # vitest，基线 254 项
npm run build:owner                  # 产物 web/owner-app/dist/

# 服务端（在仓库根）
.venv/bin/python -m pytest -q server/tests tools/tests        # 基线 537 passed / 50 skipped
python3 tools/scan_leaks.py --all                             # 提交前必跑
python3 tools/scan_forbidden.py legacy server android web
python3 tools/scan_forbidden.py heavy android/app/src/main android/app/src/free web/public-app web/shared

# Android（构建慢，务必后台跑并用日志文件接退出码）
sh android/gradlew -p android testDebugUnitTest lintDebug assembleFullRelease --console=plain > /tmp/build.log 2>&1; echo exit=$?
```

沙箱里跑 pytest 的两个坑：
- 不能重复 `mkdir`，用 `T=$(mktemp -d local-artifacts/tmp/run.XXXXXX) && TMPDIR="$T" .venv/bin/python -m pytest ...`，每次新建。
- 命令**不要接 `| tail`**，会吞掉退出码。写成 `命令 > 日志 2>&1; echo exit=$?`。

---

## 3. 代码地图

```
android/                 单 app 工程，free / full 两个 flavor，另有 preview 构建类型
  app/src/main/java/com/getmedia/app/
    data/localextract/   分享链接解析、平台直提取、音轨处理
    data/local/          Room 库（媒体/类别/播放列表/同步状态）
    data/repo/           本机文件与库管理
    player/              Media3、队列规则、播放状态持久化
    ui/                  Compose 页面
server/getmedia/
  api.py                 总装路由；main.py 入口
  media.py / media_api.py  曲库条目、文件归档（archive/cache/metadata 三种存放方式）
  ops.py                 删除/淘汰/注销级联
  creator_*.py           博主批量提取（抖音/B站）与任务调度
  legal.py               协议文本热加载（版本号从文首"版本：vN"解析）
  db.py                  SQLite WAL + 版本化迁移（迁移写在这个文件里）
web/
  owner-app/             管理台（Preact + @preact/signals）
    src/pages/library.tsx  媒体库（改得最多的页面）
    src/select.tsx         自绘下拉（原生 <select> 会盖住控件，不许换回去）
    src/styles.css         单一样式表
  public-app/            公开页
docs/legal/              协议文本（与 android assets/legal/ 必须字节一致）
tools/                   scan_leaks / scan_forbidden / smoke_deploy
local-artifacts/         本机产物（备份、APK、临时截图），**不入 git**
```

---

## 4. 三类改动，三套交付流程（最容易搞错的地方）

| 你动了什么 | bump Android 版本？ | 建 tag/Release？ | 怎么上线 |
|---|---|---|---|
| 只 `web/owner-app` 或 `web/public-app` | **不用** | **不用** | 构建 → rsync dist → `chown www-data` |
| `server/` 代码 | 不用 | 不用 | 新 release 目录 → 切 `current` → restart → `chown getmedia:getmedia` |
| `docs/legal/` 协议文本 | **要** | **要** | 两份副本同步 → 服务器 `/etc/getmedia/legal/` 替换（热加载）→ 抬客户端 `MIN_PRIVACY_FOR_SYNC` |
| `android/` | **要** | **要** | 构建 free+full → 登记哈希 → 归档 → 上传 → `release.json` → tag/Release |

### 4.1 纯网页改动（最常见）

```sh
cd web && npm run build:owner
# 1) 先备份旧目录（命名沿用 _owner-bak-<日期>-<时分>，位置照上一次的记录）
# 2) 再覆盖（--delete，属主必须修）
rsync -rlpt --delete --rsync-path="sudo rsync" web/owner-app/dist/ <别名>:/var/www/getmedia/owner/
ssh <别名> "sudo chown -R www-data:www-data /var/www/getmedia/owner"
```

- `<别名>` 是服务器 SSH 别名，向用户索取或看 `~/.ssh/config`；绝对路径与历次备份目录名在本地
  `docs/STATUS.local.md` 里能查到（**本文件不写主机信息，仓库是 public**）。
- 公开页同理，目标是 `/var/www/getmedia/`（`base` 已经是 `/getmedia/`）。**不要动 owner 子目录。**
- 忘 `chown` → 页面 403。
- `/getmedia/owner/` 匿名 404 是 nginx `auth_request` 的**预期行为**，不是坏了。
- 改完在 `docs/STATUS.local.md` 顶部加一节：起因 / 改动 / 部署（新产物名 + 备份目录）/ 验证。

### 4.2 服务端改动

关键顺序（踩过两次）：

1. `cp -a` 上一个 release 目录为新 `<日期>-<n>`，只换改动的文件。
2. **`chown -R getmedia:getmedia`** —— 属主是 `root:root` 时服务起不来（`PermissionError creators/__init__.py`），
   因为运行用户读不到 600 的配置文件。
3. 切 `current` 软链 → `systemctl restart getmedia-api` → `curl` 一下 `/getmedia/api/v2/config` 确认 200。
4. 有 DDL 时先在发布前做库快照，并记下 schema 版本变化。
5. 回滚 = 把 `current` 切回上一个目录 + restart，目录先别删。

检查备份库/快照**只能**用：
```sh
sqlite3 "file:路径.db?mode=ro&immutable=1" "…"
```
单独用 `mode=ro` 也会在同目录生成 `-wal` / `-shm`，等于改了备份目录。

### 4.3 Android 发版

1. 改 `android/app/build.gradle.kts` 的 `versionName` / `versionCode`（**必须递增**，否则覆盖安装被系统拒绝）。
   当前 0.1.37 / 38，**下次 full ≥ 39**。
2. 构建：`assembleFreeRelease` + `assembleFullRelease`。
3. 把版本、versionCode、两个包的 SHA-256 登记到 `docs/PLAN.md` §15-7。
4. 归档到 `local-artifacts/getmedia-apk/`，并上传到服务器 `/var/www/getmedia/download/`。
5. 更新 `/etc/getmedia/release.json`（`latest.apk` 指向 full 直链，**App 内更新走这里，与 GitHub Release 无关**）。
6. `gh release create` / `gh release upload` —— **只挂 `GetMedia-free-*.apk`**，full 包绝不进 Release。

---

## 5. 硬规则（违反会被用户直接指出来）

1. **GitHub Release 只发免费版**。full 只放本机归档 + 服务器下载目录。
2. **不要自动删旧包**。清哪几个等用户逐个说；删本机包用 `~/.Trash`，删服务器包用 `rm`。
3. `local-artifacts/` 只在 `.git/info/exclude` 里忽略（`/local-artifacts/`）。**不要 `git add` 它，也不要写进 `.gitignore`。**
4. 逐文件 `git add`，提交前 `git status` 核对 + 跑 `tools/scan_leaks.py --all`。
5. 仓库是 **public**：文档里不写服务器路径/别名/实例信息、不写真实博主名与真实条目、不写现网弱点。
   这类内容只进 `docs/STATUS.local.md`（本地忽略）。
6. **不擅自部署生产、不擅自装手机、不碰 `~/.android-keys/`**。用户说"部署"/"发版"才做。
7. 协议文本**只能升版本，不能降**；`docs/legal/` 与 `android/app/src/main/assets/legal/` 必须字节一致。
8. 免费版不得只隐藏 UI 而保留重型功能实现。
9. 用户说"优化一下"时**别删功能**。之前删掉行内按钮被明确纠正过：他只是要优化，不是要砍。

---

## 6. 当前待办

| # | 事项 | 状态 / 判据 |
|---|---|---|
| 1 | 真机验收 0.1.39 | **等用户**。装上后重点看：永久完整版（owner1/owner2）的「我的」页**没有**邀请码入口、free 和有时限 full 仍有、完整版到期后同步入口消失、免费版没有任何同步入口。 |
| 2 | 管理台「权限管理」「邀请码」两页的真实浏览器验收 | 只做过构建 + vitest（数据层桩），没在真账号上点过。要验：改等级是否踢下线、待注销行的截止时间、邀请码明文只出现一次、**「改有效期」设完 /account/me 是否立刻反映新时间且设备没被踢**。 |
| 3 | 邀请码发放流程 | 只能由 admin 在 `/invites` 生成，1 天有效、一码一人。生成后关掉面板就没了，必须当场「下载 .txt」。 |
| 4 | 网页端「上传本地文件」 | 已做（媒体库页顶部），上限 200 MiB，串行上传并汇总失败项。未在真机上跑过大文件。 |

> 2026-11-27 之前的四项待办（上传入口、Cookie 路径、B 站短链 `b23.tv`/`?p=N`、运维页「只记清单」→「元数据」）
> 都已在 0.1.38 这一批做完，不要再按旧的 `docs/STATUS.local.md` 里"下个版本待办"照着做一遍。

---

## 7. 踩过的坑（省时间用）

**网页**
- 原生 `<select>` 的弹层会盖住控件本身 → 一律用自绘 `Select`；写测试时先点触发器再点 `.select-opt`。
- jsdom 里 `HTMLMediaElement.play()` 返回 `undefined` 不是 Promise，直接 `.catch()` 会崩。写成
  `const p = el.play(); if (p && typeof p.catch === 'function') p.catch(...)`。
- 播放器每次实例持**自己的** ref（`own`），不要共用 `mediaRef.current`：切到上一条时旧实例的 cleanup
  晚于新实例挂载，会把新实例的引用抹成 null，导致"切上去那条播不出声"。
- 标签要整列对齐：`min-width`（按最长值 em 算，`box-sizing:border-box`，记得加 padding+边框的 16px）
  + `text-align:center` + `font-variant-numeric: tabular-nums`。少一样就差 2~3px。
- 测试里 `button('删除')` 会同时命中行内和批量条 → 加容器限定（`.selectbar button`）。
- 核对排版用 puppeteer-core + 本机 Chrome 无头渲染 dist，截图放 `local-artifacts/tmp/preview/`（不入库）。

**服务端**
- release 目录 `chown` 不对 → 服务起不来，报 `PermissionError`。
- `ops.run_deletions` 要"先收文件名 → 删记录 → 删盘"，顺序反了文件就再也对不上，永久占盘。
- 测试里写死协议版本号的断言会随升版失效，改成相对计算。

**Android**
- `org.json.optString` 对 JSON `null` 返回字符串 `"null"`，`ifBlank` 拦不住 → 用统一的 `JSONObject.text()`。
- versionCode 同号或降号，覆盖安装会被系统拒绝。
- Robolectric 里 `runBlocking` 占主线程时，`withContext(Dispatchers.Main)` 会死锁，测试要
  `Dispatchers.setMain(UnconfinedTestDispatcher())`。

---

## 8. 交接检查单

接手后第一次动手前：

- [ ] 跑一遍前端 `npm test` 和服务端 `pytest`，确认与 §2 的基线一致（169 / 382）。
- [ ] `git status` 干净、`git log -1` 对得上 §0。
- [ ] 确认这次改动属于 §4 的哪一类，**按对应流程走完整套**，不要只改代码不部署。
- [ ] 改完在 `docs/STATUS.local.md` 顶部补一节（起因/改动/部署/验证），并提交。
- [ ] 用户问到"某功能有没有"时，先读代码确认，不要凭历史文档回答——旧文档滞后很多。
