# 阶段 6 设计与测试计划：统一的「下载」入口

> 状态：**已实施**（0.1.14 / versionCode 15）。实施时按批准条件收紧了两处：
> ①条目菜单与恢复条目页的下载都不做任何预检（不预先拉清单，以前 `redownloadRestored` 会先做一次匹配）；
> ②「移除这条」只对没有本机文件的空壳开放，且不碰播放队列（空壳本来就进不了队列）。
> 真机 7 条验收结果见 `docs/metadata-backup-golive.md`（本地专用，不入库）。

## 1. 要解决的问题

真机验收（2026-11-20）暴露了同一件事的三种表现：

| 现象 | 行长什么样 | 现在能不能救 |
|---|---|---|
| 点▶ 什么都不发生，偶发「本机音频文件已丢失，请重新提取」 | `RESTORED`、`serverId=null`、`localPath=null`（清单恢复了、文件从没下过） | **不能**。没有入口，文案还误导 |
| 点▶ 才开始下载，但抖音行一律失败 | `CLOUD_ONLY`/`SYNCED`、`serverId≠null`、`localPath=null` | 半能。`refillOriginal` 只走服务器带 target 提取，抖音走不通 |
| 「云端有这项的删除记录，已保留原样」，点不动 | 墓碑命中后又从备份恢复出来的空壳 | **不能**。只该给"移除这条" |

共同点：**"这一条现在放不了"是已知状态，但界面上没有下一步动作**。阶段 6 只做这一件事——
让"缺文件"的行有一个点了能推进的地方，而且**永远不新增行**。

## 2. 入口设计（一处入口，三种分派）

### 2.1 界面上的位置

1. **条目「…」菜单的第一项**（`LibraryTarget.Audio` 那个 sheet，`LibraryScreenV2.kt:759` 附近）：
   按行状态显示一个主操作 pill：
   - 本机有可用文件 → 不显示（没必要）
   - `serverId != null`、`localPath == null` → **「下载到本机」**
   - `RESTORED`、`serverId == null`、有 `sourceUrl` → **「下载到本机」**
   - `RESTORED`、`serverId == null`、云端有删除记录 → **「移除这条」**（红色，见 §2.4）
   - 上述都没有可用的 `sourceUrl` → 显示「缺少来源链接」的禁用说明，不给假按钮
2. **「我的 → 恢复条目 → 核对云端匹配」**：未匹配行现在是死行，加同一个按钮（`RestoreDialogs.kt`
   的清单行）。这是用户发现"匹配不上"的地方，补救动作必须在这里。
3. **行内点▶ 且本机没有文件**：不再抛 `本机音频文件已丢失`，改成把上面那个菜单打开并高亮
   「下载到本机」（`LibraryScreenV2.queueItems` 的 catch 分支）。文案统一说"在本机还没有文件"。

不做自动批量：并发仍是 1，批量仍走 `RedownloadRunner`（§2.5），用户明确点了才跑。

### 2.2 分派表（关键：按"有没有服务器身份"和"平台"两个维度）

| 行的状态 | 平台 | 走哪条 | 落库方式 |
|---|---|---|---|
| `serverId != null`，`localPath == null` | bilibili | ① 服务器已存文件直取（`storedOnly`）；② 没有文件 → **服务器带 target 提取**（`ExtractTarget(id, platform, videoId, page)`） | `fillExistingFile`（按原 id） |
| 同上 | douyin | **在本机提取**（`DirectExtractor.extractTo`）→ 核对身份 → 回填 | `fillExistingFile`（按原 id） |
| `RESTORED`，`serverId == null`，有 `sourceUrl` | bilibili / douyin | 本机提取 → 核对身份（platform + videoId + page + media type）→ 回填 | `fillRestoredFile`（按原 id，**沿用现有 `RestoredRedownload`**） |
| `RESTORED`，云端有删除记录 | 任意 | 不下载，只给「移除这条」 | 见 §2.4 |

为什么 B 站云端行走服务器、抖音云端行走本机：服务器的 `startTargetedExtract` 是"给已有行补文件"的
唯一不新增行的路径，B 站的 `_ROUTER_DATA` + playurl 稳定；抖音的服务端提取既没有 target 语义、
也经常被风控，走本机 `DirectExtractor`（带 WebView 兜底）成功率更高，且**结果只用来填这一行**。

为什么未匹配行不能走 target：`ExtractTarget` 的第一个参数是**服务器 track id**，未匹配行没有
`serverId`，服务端会拒。所以只能用"本地提取出新结果 → 核对 → 按原 id 回填"。

### 2.3 身份核对（不许回填错行）

`RestoredRedownload` 已经写好了这段，直接复用、不再写第二份：

```
expected = restoredKey(platform, videoId, serverPage, sourceUrl, mediaType)
extract(...) → result
check(result.restoredKey() == expected)   // 作品、分集、类型三者都要一致
check(文件长度 == result.audioBytes && sha256 非空)
check(stillUnmatched())                    // 期间云端匹配结果变了就放弃
save(original, staged, bytes, hash, format)
```

B 站分 P 交给 `withPage(url, page)`（`RedownloadRunner.kt:178`）补 `?p=N`——服务器写的
`sourceUrl` 不带页码，只存在 `serverPage` 列里，漏了就会把 P2 下成 P1 写进 P2 那一行。

抖音云端行（§2.2 第 2 行）复用的是同一套核对，只是 `save` 换成 `fillExistingFile`（保留
`serverId/serverSeq`，`syncState` 保持 `SYNCED`）。

### 2.4 「移除这条」

只对**墓碑命中后又从清单恢复出来的空壳**出现（判据：`syncState == RESTORED`、
`localPath == null`、且云端该键 `deleted=true`，`RestoreMatchIssue.REMOVED_ON_CLOUD`）。

- 必须确认：`移除「标题」？这条是云端已删除、从备份清单恢复的空壳，本机没有文件。移除只影响这台手机，云端记录不动。`
- 动作：`DELETE tracks WHERE id=?`（本机行）+ 清 `playlist_items` 引用，**不删任何文件**（它没有文件）；
  **不写 `track_removals`**（云端已经是墓碑，再删一次是多余请求，而且会被 404 当成功，白白多一条排队）。
- 幂等：行删掉了菜单就没了；同步下次拉取不会因为它再弹墓碑框（它已经不在本机）。

### 2.5 批量「全部重新下载」要跟着按平台分派

`RedownloadRunner`（`BackupManager.runner()` 构造）现在一律 `DirectExtractor`，抖音没问题，
但**云端行（`serverId != null`）它会去直提取**，等于绕过服务器已存文件，白跑一遍还可能失败。
阶段 6 把 `extract` 注入改成"分派函数"：

```
if (track.serverId != null && track.platform == "bilibili") 服务器 target 提取
else 本机 DirectExtractor（现有行为）
```

熔断（连续 3 条失败暂停）与 3±1 秒间隔保持不变。

## 3. 不做什么

- 不做"队列里缺文件的行自动补下载"（一次点一首才下载，避免 30 条一起打平台）。
- 不做抖音的服务器提取改造（服务端没有 target 语义，改了要动 API 契约）。
- 不引入新表/新列：`localPath / audioBytes / audioSha256 / audioFormat / syncState` 够用。
- 不解决抖音直提取的 403 成功率（那是 `p0-1` 的 WebView 兜底问题，见批量提取 24/50 失败的分析）。

## 4. 失败时的说法（沿用 `ServerProblem` 分类）

| 失败 | 文案 | 行状态 |
|---|---|---|
| `STORAGE_FULL` | 已回退到"只存清单"，本机仍无文件 | 不变 |
| `RATE_LIMITED` | 平台限流，稍后再试（不清进度、不自动重试） | 不变 |
| `stored_file_missing` | 服务器上没有这份文件了，正在重新提取 | 自动转 target 提取 |
| `target_mismatch` | 返回的作品、分集或媒体类型与这条不符，已保留原样 | 不变 |
| 抖音 403 / 解析失败 | 这次没取到，换网络或稍后再试（**不新建行**） | 不变 |
| 账号/隐私闸门 | 请使用原账号并同意最新隐私说明后再下载 | 不变 |

## 5. 测试计划

### 5.1 单元（JVM / Robolectric）

1. **分派表**：`downloadRoute(track)` 纯函数——6 种输入（serverId×平台×RESTORED）→ 期望的 route。
   这是本阶段唯一值得新抽的纯函数，其余复用现有实现。
2. **墓碑行拒绝下载**：`REMOVED_ON_CLOUD` 的行调下载 → 必须返回"只能移除"，且**不发任何请求**
   （假 transport 记数）。
3. **按原 id 回填**：本地提取返回 `videoId` 相同、`page` 不同的结果 → 必须抛 `target_mismatch`，
   行不变、不新增行（复用 `CloudRefillTest` 的假 gateway 风格）。
4. **幂等**：同一行连点两次下载 → 第二次进 `downloadLock` 后直接复用结果，只发一次请求。
5. **移除这条**：删行 + 清 `playlist_items`，`track_removals` 不新增，文件不动（构造一行有假路径的
   空壳，断言文件仍在）。
6. **批量分派**：`RedownloadRunner` 注入分派后，遇到 `serverId` 行不再调本机提取（记账断言）。

### 5.2 界面渲染

7. 三种行的菜单项集合：`有文件`（无下载项）/ `云端行`（下载）/ `未匹配`（下载）/ `墓碑`（只有移除）。
8. 下载中状态：行内进度与"取消"（沿用 `ServerExtractScreen` 的 progress 文案）。

### 5.3 真机（沿用开工自检：恰好一台、序列号 6c3619c、不新建账号）

| # | 场景 | 通过标准 |
|---|---|---|
| 1 | 云端行 B 站 P3（`serverId=6`，服务器有文件） | `GET /v2/media/6` 200·367577 字节；**0 次 POST /extract**；行徽标消失、可播放 |
| 2 | 云端行 B 站 metadata（Rick Astley，服务器无文件） | 走带 target 的提取，日志里 `targets` 非空；成功后本机有文件、可播放 |
| 3 | 云端行抖音 | 走本机提取；成功后 `tracks` 行数不变、该行 `serverId` 仍为原值 |
| 4 | 未匹配 RESTORED（抖音） | 本机提取 → 核对 → 回填；**服务器 `tracks` 行数不变**（这是硬指标） |
| 5 | 墓碑空壳（小棉袄） | 只出现「移除这条」；确认后行消失、`playlist_items` 引用清掉、云端仍是墓碑 |
| 6 | 断网点下载 | 明确提示、行不变、不新增行；恢复网络后可重试 |
| 7 | 批量"全部重新下载"含抖音行 | 不再连续 3 条熔断；B 站行走服务器路径 |

### 5.4 回归

- `free` 风味不出现任何下载入口（`cloudSync` 空实现，文案不出现在 free）。
- `scan_leaks.py --all` 三条扫描通过；`LibrarySyncEngineTest` / `RestoredMatcherTest` /
  `RedownloadRunner` 相关用例全绿。

## 6. 落地顺序（建议）

1. 纯函数 `downloadRoute` + 单测（无 UI、无网络，先把分派钉死）；
2. `refillOriginal` 按平台分派（服务端 bilibili / 本机 douyin）；
3. 菜单与恢复条目页的入口 + 渲染测试；
4. 「移除这条」+ 单测；
5. 批量分派；
6. 真机按 §5.3 逐条走。
