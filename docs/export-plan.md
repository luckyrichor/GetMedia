# 批量导出方案（音频 / 视频，可新建文件夹）

**状态：已确认（2026-11-17），按 A → B → C 三个节点推进，每个节点单独交付**
**进度**：**A / B / C 全部完成并已上线**——C（前台服务与收口）落在 `data/export/ExportService.kt`、`ExportController.kt`、`SafExportTarget.kt`，已随 0.1.20 起各版发布；当前线上 Android 为 0.1.48 / vc 49。
**范围**：仅 Android `main`（free 与 full 共用）。不涉及服务端、不涉及网页。
**前提**：本机功能，不申请「管理所有文件」，不依赖旧存储权限，不转码。
**已定的 13 个决定**：见 §11（原有 7 条 + B 阶段补定的 6 条）。

---

## 0. 一句话方案

多选 → 一个确认页（选目录、命名、是否新建文件夹）→ 前台服务串行流式复制 → 摘要（成功/失败/跳过）→ 失败项可重试。
写入走 SAF（`ACTION_OPEN_DOCUMENT_TREE` + `DocumentsContract`），先写临时名再改名，任何中断都不留半截文件。

列表页与类别页按**整表导出**（不在 sheet 里再造多选）；
「服务器库未下载」整块是 full 专属能力，通过 `OwnerGate` 接缝注入，free 产物里不编进文案与实现（§5.6）。

---

## 1. 交互与线框

### 1.1 入口一：媒体库多选顶栏（现有位置）

长按条目 → 多选（已有）；顶栏从「全选 / 删除 / 取消」扩为「全选 / 导出 / 删除 / 取消」。

```
┌────────────────────────────────────────────────────────────┐
│ 媒体库                                            ⚙ 帮助   │
├────────────────────────────────────────────────────────────┤
│ ☑ 全选 · 已选 12                    [导出] [删除] [取消]   │  ← FlowRow，窄屏换行
├────────────────────────────────────────────────────────────┤
│ 🔍 搜索名称                                                │
│ [全部] [音频] [视频]             剩余 14:59  [取消]        │  ← 定时条独立一行（本次同批修复）
│                                            [排序·手动] [导入]│
├────────────────────────────────────────────────────────────┤
│ ☑ 标题 A                              03:24   ▶  ⋮        │
│ ☑ 标题 B                              12:07   ▶  ⋮        │
└────────────────────────────────────────────────────────────┘
```

多选顶栏本身也用 `FlowRow`：320 dp + 字体 200% 时「导出/删除/取消」换到第二行，不压缩任何按钮宽度
（与定时缺陷修复使用同一套排版约束，见 `timer-bug-report.md`）。

### 1.2 入口二：播放列表详情

```
┌────────────────────────────────────────────────────────────┐
│ 我的收藏                                 12 项      完成   │
├────────────────────────────────────────────────────────────┤
│ [播放此列表]  [导出此列表]                                 │  ← 新增一个 pill
│ 标题 A                                                     │
│ 标题 B                                                     │
└────────────────────────────────────────────────────────────┘
```

### 1.3 入口三：类别详情

类别页工具栏（现有「新建类别 / 播放列表」那一行）增加「导出此类」，取该类别递归全部作品。

> 三个入口都只负责**产生选择集**，之后进入同一个确认页。列表与类别按**整表导出**（已确认：不在 sheet 里再造多选）。

### 1.4 确认页（全屏 sheet，沿用 `MusicSheet` 观感）

```
┌────────────────────────────────────────────────────────────┐
│ 导出                                             关闭      │
├────────────────────────────────────────────────────────────┤
│ 音频 9 项 · 视频 3 项 · 共 12 项，约 486 MB                │  ← 混选分别报数
│                                                            │
│ 保存位置                                                   │
│ ┌────────────────────────────────────────────────────────┐ │
│ │ 内部存储/Download/GetMedia                       更换 │ │
│ └────────────────────────────────────────────────────────┘ │
│ ⓘ 系统不允许直接选「Download」根目录，选它下面的子文件夹即可 │
│                                                            │
│ 文件夹名（可留空，直接放进上面这个目录）                   │
│ ┌────────────────────────────────────────────────────────┐ │
│ │ 2026-11 导出                                           │ │
│ └────────────────────────────────────────────────────────┘ │
│ ⚠ 该目录下已有同名文件夹    [使用已有]  [改名]              │  ← 仅冲突时出现
│                                                            │
│ ▸ 高级（默认收起）                                         │
│   ☐ 按类型分子文件夹（音频/、视频/）                       │
│   ☐ 按博主分子文件夹   ☐ 文件名带博主名                    │
│   ☐ 文件名带入库日期                                       │
│                                                            │
│ ⚠ 上次导出留下 3 个临时文件（~getmedia-…part），可手动删除 │  ← 只提示，不替用户删
│ ⚠ 其中 2 项本机没有文件        [跳过这 2 项]               │  ← full 专属，只有跳过（§5.6）
│ ⓘ 无法确认剩余空间，导出时若空间不足会自动中止             │  ← 查不到额度时只提示，不拦
│ ⚠ 目标可用空间不足（需 486 MB，可用 120 MB）               │  ← 只有算出来明确不足才禁用开始
│                                                            │
│                              [取消]      [开始导出]        │
└────────────────────────────────────────────────────────────┘
```

「先下载再导出」**本期不做**：full 里也只给「跳过这 N 项」，不摆一个按了会失败的按钮。
补下载再导出记进 `docs/p0-2-sync-design.md` §17，等按需下载落地后再接回这一段。

### 1.5 进度

前台服务通知（`dataSync` 类型）：

```
┌──────────────────────────────────────────────┐
│ GetMedia  正在导出 12 项              取消   │
│ ████████████░░░░░░░░  7/12 · 失败 1          │
│ 正在写入：某标题 (3).m4a                     │
└──────────────────────────────────────────────┘
```

应用内不弹全屏进度页；媒体库底部 snackbar 显示「导出中 7/12」，点击回到确认页看实时列表。
横竖屏切换、退到后台、进其他页面都不影响（状态在单例里，不随页面销毁）。

### 1.6 摘要页

```
┌────────────────────────────────────────────────────────────┐
│ 导出完成                                             关闭  │
├────────────────────────────────────────────────────────────┤
│ 成功 9 · 失败 2 · 跳过 1                                   │
│                                                            │
│ 失败                                                       │
│  标题 C   目标目录不可写（可能是云盘只读目录）              │
│  标题 D   本机文件缺失，需要重新提取                        │
│                                                            │
│                                   [重试失败项] [完成]      │
└────────────────────────────────────────────────────────────┘
```

不提供「打开文件夹」：SAF 目录 URI 无法用系统文件管理器直接定位，硬做只能跳到文件管理器首页，属于假承诺。

---

## 2. 状态机

```
                    ┌──────────────────────────────────────────┐
                    │ Idle                                     │
                    └───────┬──────────────────────────────────┘
       长按/多选/列表·类别导出 │
                    ┌───────▼──────────┐
                    │ Selecting        │  选择集 = 可见筛选结果 或 整表
                    └───────┬──────────┘
                    点「导出」│
                    ┌───────▼──────────┐   选目录/填名字/改选项
                    │ Confirming       │◄──────────────┐
                    └───┬─────────┬────┘               │ 改选项后重算
        未选目录/名字非法 │         │ 点「开始导出」      │
                    ┌───▼───┐  ┌──▼─────────────┐      │
                    │ 就地  │  │ Preflighting   │──────┘ 空间不足 / 授权失效 / 全部未下载
                    │ 提示  │  └──┬─────────────┘
                    └───────┘     │ 通过
                            ┌─────▼──────────┐
                            │ Running        │  前台服务串行执行
                            └──┬──────┬──────┘
                    全部结束    │      │ 用户取消 / 系统回收
                            ┌──▼───┐ ┌▼──────────┐
                            │ Done │ │ Interrupted│
                            └──┬───┘ └─────┬─────┘
                    点「重试失败项」│        │ 重新进入时给摘要
                            └──────►│◄───────┘
                                 Running（只跑失败项）
```

| 转移 | 触发 | 副作用 |
|---|---|---|
| Idle → Selecting | 长按条目、列表页「导出此列表」、类别页「导出此类」 | 冻结选择集快照（后续库变化不影响本次） |
| Selecting → Confirming | 点「导出」 | 分类计数、算总大小、读上次目录 |
| Confirming → Preflighting | 点「开始导出」 | 校验授权、目录可写、名字合法、空间预估 |
| Preflighting → Confirming | 任一校验失败 | 就地提示（不弹二级对话框） |
| Preflighting → Running | 全部通过 | 启动前台服务，`ExportController` 置 Running |
| Running → Done | 队列跑完 | 摘要（成功/失败/跳过），失败项可重试 |
| Running → Interrupted | 用户取消 / 进程被杀 | 删当前临时文档，摘要标注「已取消」 |
| Done → Running | 点「重试失败项」 | 只跑失败集合，计数重算 |

进程被杀后不恢复队列（不做持久化任务表）：下次进入只显示「上次导出未完成」，由用户重新发起。这条列为本期**不做**，理由是收益低、要引入数据库表与恢复逻辑。

---

## 3. 选择集与分类

| 来源 | 选择集 |
|---|---|
| 媒体库多选 | 勾选的作品（已按当前筛选可见范围求过交集，现有逻辑） |
| 播放列表 | 该列表全部条目，跳过库中已不存在的引用 |
| 类别 | 该类别递归全部作品（`categoryRepo.recursiveTracks`） |

- 分类只按 `TrackEntity.mediaType` 二分：`AUDIO` / `VIDEO`，确认页分别报数。
- 同一作品在库里只有一条记录（现有约束），无需去重；跨来源合并时按 `id` 去重。
- 条目在导出过程中被删除 → 记为失败（原因：本机文件缺失），不影响其余项。

---

## 4. 命名与文件夹规则

### 4.1 基础名

1. 取显示名：`localTitleOverride ?? title`。
2. 去非法字符：`\ / : * ? " < > |` 与控制字符 `U+0000–U+001F`，一律替换为单个空格。
3. 连续空白折成一个空格；去首尾空格；**去掉结尾的「.」与空格**（Windows/SMB 上会变成非法名）。
4. 结果为空 → `未命名`。
5. 截断：字符数 ≤ 80 **且** UTF-8 字节数 ≤ 200（给扩展名、序号、博主名留余量）。按字符边界截断，不切半个 emoji。
6. 保留名（`CON` `PRN` `AUX` `NUL` `COM1..9` `LPT1..9`，不区分大小写）→ 前缀加 `_`。用户把目录选在 exFAT/SMB/云盘时才会撞上，但成本极低。

### 4.2 可选后缀（默认都不加）

- 博主名：` - <uploader>`（uploader 同样过 4.1 的清洗，再截断到 30 字符）。
- 入库日期：` (yyyy-MM-dd)`，取 `addedAtMs`。界面文案统一写「入库日期」，不写「发布日期」——平台上发布时间没有入库，库里只有入库时间，写错会让人以为导出的是发布日。

组合顺序：`基础名 + 博主 + 入库日期 + 扩展名`。

**整名再收一次到 240 字节（UTF-8），只截标题。** 各部分单独有上限、加起来却没有：标题 200 + `" - "` 3 +
博主 120 + `" (yyyy-MM-dd)"` 13 + `".matroska"` 9 ≈ 345 字节，而 ext4 / Android（FUSE、sdcardfs）都卡
**255 字节**，超过就是 `ENAMETOOLONG`，写到一半整项失败。所以拼之前先算固定部分（博主 + 日期 + `.扩展名`）
的字节数，剩下的额度全给标题；扣到 240 是给重名序号留位——`" (999)"` 最多 6 字节，240 + 6 = 246 < 255。

### 4.3 扩展名

- 取 `audioFormat`（小写）。**空 → `m4a`**（与库内默认一致）；**有值就原样保留**——不转码，`webm`/`mkv` 该怎么落就怎么落。
- 白名单只用于 **MIME 判定**：`m4a mp3 aac flac wav ogg opus mp4 m4v webm mkv`；白名单外的 MIME 用 `application/octet-stream`（§5.7 的"不转码"）。
- 落盘前滤掉非字母数字字符；滤完长度不在 1–8 就回落 `m4a`——防止该字段里混进路径分隔符。
- 库内文件实际存为 `<id>.audio`，扩展名只存在于 `audioFormat` 字段，导出时按字段拼。

### 4.4 重名

- 目标目录内已存在同名（**大小写不敏感**比较，兼容 SMB/云盘/Windows）→ `名称 (2).m4a`、`名称 (3).m4a` …
- 序号最多到 999，仍冲突 → 该项记为失败（原因：重名过多），不覆盖、不静默改名成随机串。
- **已确认：只加序号，不提供「覆盖」**。覆盖等于不可逆删除用户已有文件，与本任务"不覆盖已有文件"的硬要求冲突。

### 4.5 自动分子文件夹（默认关闭，只两项）

**已确认：只提供「按类型」与「按博主」两项，默认关闭**；不做按类别、按平台等更多维度。

| 选项 | 目标路径 |
|---|---|
| 关闭 | `<所选目录>/<可选文件夹名>/名称.m4a` |
| 按类型 | `<所选目录>/<文件夹名>/音频/`、`.../视频/` |
| 按博主 | `<所选目录>/<文件夹名>/<清洗后的博主>/`，空博主归 `未知博主/` |

两者可叠加（博主/类型）。目录层级固定 2 层，不按类别无限嵌套——类别是可以随时改名的软结构，导出目录要稳定。

### 4.6 文件夹名

- 与 4.1 同一套清洗与长度限制（上限 60 字符）。
- 已存在同名文件夹 → 确认页出现「使用已有 / 改名」，不静默合并、不静默改名。
- 留空 → 直接写入所选目录根。

---

## 5. 技术实现

### 5.1 目录与授权

- `ActivityResultContracts.OpenDocumentTree()`（`ACTION_OPEN_DOCUMENT_TREE`），`EXTRA_INITIAL_URI` 带上次目录（API 26+ 可用）。
- 返回后 `takePersistableUriPermission(uri, READ|WRITE)`；持久化授权失败 → 明确提示"该位置不支持长期授权，请换一个目录"，不带着一次性授权开始导出（否则后台服务里会失效）。
- 只保留最近 5 个持久化授权，多出的 `releasePersistableUriPermission`（系统对每应用授权数有上限，超出会抛 `SecurityException`）。
- 上次目录 URI 存 `SharedPreferences`（键 `getmedia:export.tree`）。这是用户自己选的路径，属非敏感偏好，但**不进日志、不进诊断包**。
- 开始前查 `contentResolver.persistedUriPermissions`：没有当前目录 → 阻止开始并提示重选。

### 5.2 写入（关键路径）

```
每项：
  1. 解析目标父目录 DocumentFile（按需 createDirectory，一次缓存）
  2. DocumentsContract.createDocument(resolver, parentDocUri, mime, "~getmedia-<uuid>.part")
  3. openOutputStream(tmpUri) ← FileInputStream(localPath).copyTo(out, 64 KiB)
  4. close；校验写入字节数 == 源文件长度
  5. DocumentsContract.renameDocument(resolver, tmpUri, "最终名.m4a")
  6. 任一步失败：NonCancellable 里 deleteDocument(tmpUri)，记失败原因
```

- 全流式，不整文件读进内存；不先复制到 app 私有目录（源文件本来就在私有目录）。
- 临时名以 `~` 开头且带 `.part`：用户中途看目录一眼就知道是半成品，且不会撞上正常文件名。
- **rename 降级（唯一触发条件：`rename` 返回 false 或抛非 `SecurityException` 异常）**：
  在同一目录按**最终名**重新 `createDocument` → 重新写一遍 → 校验字节数 → 删掉临时文件。
  重写也失败则**两个半成品都删掉**，该项记「目标目录不支持改名，直接写入也失败」。
  `SecurityException` 不走降级：授权没了，在只读目录里重写一次只会再失败一次，直接中止整批。
  实现见 `data/export/ExportWriter.kt`，假 sink 模拟"不支持改名"的用例见 `ExportWriterTest`。
- 目标目录被撤销授权（`SecurityException`/`FileNotFoundException`）→ 立即中止整批（不是逐项失败）：继续跑只会制造 N 条同样的失败。

### 5.3 空间预估

- 需求侧：选中项 `audioBytes`（空则用文件实际长度）求和，加上 2% 余量。
- 目标侧：`DocumentsContract` 查 `COLUMN_AVAILABLE_BYTES`。
- 三种结果（**"无法判断"这一态必须真实存在**，不能退化成二值）：
  - 可用 ≥ 需求 → 放行。
  - 可用 < 需求 → 禁用「开始导出」，给出「需 X，可用 Y」。
  - 提供商返回 -1/0（云盘常见）→ 只显示「无法确认剩余空间，导出时若空间不足会自动中止」，**不拦**、不再弹二次确认；
    运行中遇到 `ENOSPC` 记该文件失败并中止整批（空间类失败没有继续的意义，§5.5 的 `Outcome.noSpace`）。
- 实测：Android 14 模拟器的 `ExternalStorageProvider` 对 `Root.COLUMN_AVAILABLE_BYTES` 就返回 -1，
  所以这条不是理论分支，是默认会走到的路径（截图见 `docs/reports/export-b-2026-11-19/sheet-confirm.png`）。
- 列名在 `DocumentsContract.Root` 里，`Document` 没有同名常量；`Document` 只有 `COLUMN_SIZE`。

### 5.4 前台服务

- 新增 `ExportService`：`android:foregroundServiceType="dataSync"`，`android:exported="false"`。
- `AndroidManifest.xml` 增加 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />`（API 34 起为必需；低版本声明无副作用）。
- `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)`。
- 单线程串行：云盘 provider 并发写容易 429/写冲突，且逐项串行才能给出准确的"第几项"。
- 通知节流 500 ms（每项都刷新会让通知栏狂闪）；进度用 `setProgress(total, done, false)` + 小字当前文件名。
- 通知「取消」→ `stopSelf()` + job cancel。
- **Android 15 前台服务超时（必须在 C 落地）**：`dataSync` 类型在 Android 15 上有 6 小时/24 小时限额，
  到点系统回调 `Service.onTimeout(startId, fgsType)`；不处理就会崩。做法：
  `override fun onTimeout(...)` → `session.interrupt(Reason.SYSTEM_TIMEOUT)`（立刻把状态置为 `Interrupted`、
  文案「导出被系统中断，可重试失败项」）→ `stopSelf()`。
  - **targetSdk 现状**：`targetSdk = 34`、`minSdk = 26`。系统限额只对 targetSdk ≥ 35 的应用生效，
    所以当前装上不会真的回调；但 `onTimeout` 是 API 35 新增方法，**`compileSdk` 已从 34 升到 35**
    （AGP 8.3.2 下编译通过，运行时行为不变），C 阶段才能 override。
  - 状态机与文案在 B 就已经就位并有测试（`ExportSessionTest.systemTimeoutLeavesInterruptedStateWithRetry`），
    C 的 `onTimeout` 只是把这两步接起来。
  - 服务内仍保留自保护：跑满 5 小时主动收尾给摘要，不赌系统一定回调。
- 通知权限（API 33+）被拒：前台服务照常运行，进度只在应用内显示；不因此阻断导出。

**唤醒锁：要用，且必须带超时（C 阶段结论）**

前台服务只影响进程优先级与回收，**不阻止 CPU 进入 suspend**。SAF 的写是同步 IO，
屏幕一灭、又没有别的持锁者时，写循环会停在某个 64 KiB 块上等下一次唤醒——
用户看到的是进度条不动、通知停在 7/12，而设备其实没死。所以写文件期间持一把
`PARTIAL_WAKE_LOCK`（`WAKE_LOCK` 是 normal 权限，清单里声明即可）：

- 名字 `getmedia:export`，`setReferenceCounted(false)`；
- **超时取 5 小时自保护 + 1 分钟余量**：即使所有收尾路径都失效，这把锁也会自己到期，
  不留下一个永久持锁的进程；
- 释放在跑批协程的 `finally`（正常完成 / 取消 / 中断 / 异常都走到），`onDestroy` 再兜一次。

**取消要能停在半路：写入插检查点**

`ExportTarget.write(doc, source, stop)` 每写一块问一次 `stop`，为真抛 `WriteCancelled`。
没有这个检查点，取消只能等当前大文件整份写完（几十秒到几分钟），临时文件也一直躺着。
`ExportWriter` 先单独捕获 `WriteCancelled`：删掉临时文件、**不记失败**（这一项算"没跑"，
留在 `retryIds` 里等重试）。

**三条收尾路径最终状态一致**（通知取消 / `onTimeout` / 5 小时自保护）

| 路径 | 触发 | 落到 |
|---|---|---|
| 通知「取消」 | `PendingIntent.getService` → `ExportService` 的 `ACTION_CANCEL` | `session.cancel()` → `Reason.USER_CANCEL` |
| 系统超时 | Android 15 `Service.onTimeout(startId, fgsType)` | `session.interrupt(SYSTEM_TIMEOUT)` + `stopSelf()` |
| 自保护 | `run(deadlineMs = 启动时刻 + 5 小时)`，每个 64 KiB 检查点比对 | `stop(TIME_LIMIT)`（会话内部，与服务同一条退出路径） |

三者都进入同一个 `State.Interrupted`：已完成项保留、当前项半成品删掉、没跑到的进 `retryIds`，
只有 `Reason` 的文案不同。一致性由 `ExportSessionTest.allStopPathsLeaveTheSameShape` 用同一套断言钉住。

### 5.5 状态与 UI 通信

- 单例 `ExportController` 持有**会话与状态**（Compose 的 `mutableStateOf`，界面读它即订阅）：
  `session`、`state`、`pending`（服务取走即清）、`last`（重试用）。
- 服务跑批，把会话的 `StateFlow` 收集进 `state`；确认页只读 `ExportController.state`，
  不再自己起协程。因此**关掉页面不影响导出**，重进确认页能接回同一份会话。
- 重试复用同一个会话（`items` 是同一份时 `attach` 不新建）：摘要里的"成功 N"不会被重来一遍冲掉。
- 退出应用（进程结束）即结束，不恢复；用户目录里的 `~getmedia-…part` 只提示、不删。

### 5.6 「本机没有文件」整块：OwnerGate 接缝（不得编进 free）

这一行只对 full 有意义（免费版没有服务器库，也没有补下载这件事），所以整块走 `OwnerGate` 接缝，
做法与现有 `batchExtract` 一致——**main 里不出现这句文案，free 的实现是空函数**：

```kotlin
// main/ui/OwnerGate.kt —— 只声明，不实现
interface OwnerGate {
    // ……既有成员

    /**
     * 导出确认页里「其中 N 项在本机找不到文件」整块。
     * free：空实现，不渲染、不持有任何文案；
     * full：显示条数并给「跳过这 N 项」——本期只做跳过，不做"先下载再导出"。
     */
    @Composable fun ExportMissingNotice(missingCount: Int, onSkip: () -> Unit)
}
```

```kotlin
// free/ui/OwnerGate.kt —— 空实现，不 import 任何 data/creator、data/remote 类
@Composable override fun ExportMissingNotice(missingCount: Int, onSkip: () -> Unit) = Unit

// full/ui/OwnerGate.kt —— 转发到 full 源码集里的真实实现
@Composable override fun ExportMissingNotice(missingCount: Int, onSkip: () -> Unit) =
    ExportMissingNoticeBody(missingCount, onSkip)   // 该类/函数只存在于 full
```

约束（实现时逐条守）：

1. `main` 里**不出现**「本机没有文件」这句文案（连注释都不写，扫描是按子串命中源码的），也不出现服务器库仓库类。
2. free 的接缝实现是空函数体，**没有文案、没有服务器调用**；它允许被调用，但不渲染、不产生副作用。
3. full 的实现里才允许出现文案；本期 full 也只有「跳过这 N 项」，按钮不会失败。
4. free 的导出流程因此永远是"本机文件 → 目标目录"，不依赖网络；本机文件缺失的条目在摘要里记失败。

**产物验证（反编译扫描，节点 B 已做一次，节点 C 再跑一次）**

```sh
# 1) 两个风味各出一版（debug 即可看清字符串；release 用来做最终交付）
sh android/gradlew -p android assembleFreeDebug assembleFullDebug

# 2) 解压 dex
rm -rf /tmp/gm-free /tmp/gm-full
unzip -q -o android/app/build/outputs/apk/free/debug/app-free-debug.apk -d /tmp/gm-free
unzip -q -o android/app/build/outputs/apk/full/debug/app-full-debug.apk -d /tmp/gm-full

# 3) 按字节计数（macOS 的 grep -c 对二进制会漏，别用它数中文串）
python3 - <<'EOF'
import glob
for flavor in ("free", "full"):
    for mark in ("本机没有文件", "ExportMissingNoticeBody"):
        n = sum(open(f, "rb").read().count(mark.encode()) for f in glob.glob(f"/tmp/gm-{flavor}/classes*.dex"))
        print(flavor, mark, n)
EOF

# 4) 既有重型词表（这两项已加进 tools/forbidden-heavy.txt，同时守住源码与产物）
python3 tools/scan_forbidden.py heavy android/app/src/main android/app/src/free web/public-app web/shared
python3 tools/scan_forbidden.py heavy /tmp/gm-free
```

**2026-11-19 实测**：`本机没有文件` free=0 / full=1，`ExportMissingNoticeBody` free=0 / full=34；
源码与 APK 的 heavy 扫描均通过。`OwnerGate` 的**方法名**本身会出现在 free 的 dex 里（接口在 `main`），
这是允许的——接口声明不是实现。

### 5.7 不做什么

- 不转码：按原容器复制。库里若出现 `webm/mkv` 等不通用容器，能不能播取决于目标设备——这条写进确认页的说明文案。
- 不做「导出后删除库内文件」（移动语义）。
- 不做后台定时导出、不做导出到网盘 API、不做压缩打包。
- **不做「导出清单」（已确认）**：不在目标目录写 txt/json 记录，清单会随文件流出去又没有还原用途；导出结果只在 App 内摘要页呈现。
- 不申请 `MANAGE_EXTERNAL_STORAGE`，不申请 `READ/WRITE_EXTERNAL_STORAGE`。

---

## 6. Android 版本差异（minSdk 26 → target 34）

| 版本 | 对本功能的影响 | 处理 |
|---|---|---|
| 8.0–8.1（26–27） | SAF 完整可用；`takePersistableUriPermission` 必须调用，否则进程重启即失效 | 统一走持久化授权 |
| 9（28） | 无差异 | — |
| 10（29） | 分区存储默认开启；本功能只走 SAF，不受影响 | 不开 `requestLegacyExternalStorage` |
| 11（30） | **不能选择内部存储根、SD 卡根、Download 根**（系统把确认按钮置灰），只能选其子目录 | 确认页常驻提示「选 Download 下的子文件夹」；SAF 选择器打开时清掉 `EXTRA_INITIAL_URI` 里失效的根路径 |
| 12–12L（31–32） | 无差异 | — |
| 13（33） | `POST_NOTIFICATIONS` 运行时权限（应用已声明）；拒绝后通知不可见 | 权限被拒时改走应用内进度，不阻断 |
| 14（34） | 前台服务必须声明类型 + `FOREGROUND_SERVICE_DATA_SYNC`，否则 `startForeground` 直接抛异常 | 清单声明 + `ServiceCompat.startForeground` 显式传类型 |
| 15+（35+） | `dataSync` 前台服务 6 小时/24 小时限额，到点回调 `Service.onTimeout` | `compileSdk` 已升 35、C 阶段实现 `onTimeout`（置 `Interrupted` + `stopSelf`）；`targetSdk` 仍 34，所以当前不会真被系统回调；服务内另有 5 小时自保护 |

另：`DocumentsContract` 的 `createDocument/renameDocument/deleteDocument` 自 API 21 起可用；`DocumentFile` 只是薄封装，重命名一律用 `DocumentsContract.renameDocument`（`DocumentFile.renameTo` 在部分 provider 上直接返回 false）。
`COLUMN_AVAILABLE_BYTES` 自 API 21 存在，但很多 provider 返回 -1，所以按 5.3 的三态处理。

---

## 7. 失败与边界

| 情况 | 表现 | 处置 |
|---|---|---|
| 源文件缺失（`localPath` 为 null 或文件不存在） | 该项失败 | 摘要里写「本机文件缺失，需要重新提取」，不自动重下（免费版没有服务器） |
| 目标目录只读 / 云盘受限 | 首项即失败 | 中止整批，提示换目录 |
| 授权被撤销 | 首项抛 `SecurityException` | 中止，提示「目录授权已失效，请重新选择」 |
| 空间不足 | 预估阶段或运行中 | 预估不足则拦在确认页；运行中不足则中止并给已完成数 |
| 同名冲突 | 自动加序号 | 序号用尽 → 该项失败（「目标目录里同名文件过多」） |
| provider 不支持改名 | 降级为按最终名重写一遍 | 重写成功照常完成，摘要注明「已直接写入」；重写失败两个半成品都删 |
| 用户取消 | 停止，删当前临时文件 | 已完成文件保留，摘要标「已取消」 |
| 进程被杀 | 服务停止，临时文件残留 | 临时名可辨认（`~getmedia-…part`）；**不删**用户目录里的东西，下次进确认页只提示「上次导出留下 N 个临时文件」并给出个数 |
| 单文件 > 2 GB | SAF 与 FAT32 目标可能失败 | 记该项失败并给原因；不拆分文件 |
| 横竖屏 / 息屏 / 切页 | 进度不丢 | 状态在单例 + 服务，页面无状态依赖 |

---

## 8. 隐私

- 日志与诊断包只记：导出条数、成功/失败/跳过数、失败原因类别。**不写目标目录 URI、不写本机绝对路径**（URI 里含用户目录名）。
- 通知栏只显示条数与清洗后的文件名。
- 诊断包不含导出记录（`DiagnosticsStore.operation("批量导出")` 只写计数与类别）。

---

## 9. 测试清单

### 9.1 JVM 单测（可在本机跑，纳入 `testDebugUnitTest`）

| # | 用例 | 断言 |
|---|---|---|
| 1 | 文件名清洗 | `a/b:c*?"<>|.m4a` → 非法字符变空格、结尾点去掉 |
| 2 | 控制字符与空白折叠 | `a\u0001  b` → `a b` |
| 3 | 首尾空格与空名 | `"  "` → `未命名` |
| 4 | 长度截断 | 200 个汉字 → 字符 ≤80 且字节 ≤200，扩展名保留 |
| 5 | 保留名 | `CON.m4a` → `_CON.m4a` |
| 6 | 重名序号 | 已有 `x.m4a` → `x (2).m4a`；已有 `X (2).M4A` → 跳过 2 |
| 7 | 大小写不敏感去重 | `Song.m4a` 存在时不产生 `song.m4a` |
| 8 | 混选分类 | 9 音频 + 3 视频 → 分别计数、总计 12 |
| 9 | 空间预估 | 需求 > 可用 → 不足；可用 = -1 → 未知 |
| 10 | 子文件夹路径 | 按类型 + 按博主 → `音频/博主名/` |
| 11 | MIME 映射 | m4a→`audio/mp4`、mp4→`video/mp4`、未知→`application/octet-stream` |
| 12 | 失败归类 | `SecurityException` → 「目录授权已失效」；`IOException(ENOSPC)` → 「空间不足」；文案内无路径 |
| 13 | 整名字节上限 | 300 字标题 + 300 字博主 + 日期 + 8 字符扩展名：整名 ≤ 240 字节，且博主/日期/扩展名都还在；再叠 ` (999)` 后 ≤ 255 |
| 24 | 写入：临时名 → 改名 | 成功后只剩最终名，`~getmedia-…part` 不存在，未走降级 |
| 25 | 写入：改名不被支持 | 降级按最终名重写，`rewritten = true`，临时文件被删 |
| 26 | 写入：重写也失败 | 两个半成品都删掉，原因「目标目录不支持改名，直接写入也失败」，目录里不留东西 |
| 27 | 写入：字节数对不上 | 记「写入不完整，已删除半成品」并删临时文件 |
| 28 | 写入：空间不足 / 授权失效 | `noSpace` / `abort` 置位，为整批中止提供依据 |
| 29 | 会话：系统超时中断 | `interrupt(SYSTEM_TIMEOUT)` → `Interrupted`、文案「导出被系统中断，可重试失败项」、未跑项可重试 |
| 30 | 会话：取消 / 空间不足中止 | 取消文案为「已取消导出」；空间不足时剩余项记 `SKIPPED` 而不是 `FAILED` |
| 31 | 会话：只重试未完成项 | 已成功项不被重写，结果表累加不丢上一轮 |
| 32 | 写入：中途取消 | 临时文件被删、`cancelled = true`、`reason == null`（取消不算失败） |
| 33 | 会话：5 小时时限 | `deadlineMs` 到点 → `Reason.TIME_LIMIT`，当前项半成品删掉，只留已完成项 |
| 34 | 会话：三条路径一致 | 取消 / `SYSTEM_TIMEOUT` / `TIME_LIMIT` 的结果列表、`notRun`、`retryIds`、目录残留完全相同 |

### 9.2 Robolectric / 仪器测试

| # | 用例 | 断言 |
|---|---|---|
| 14 | 确认页在 320 dp + 字体 200% | 「开始导出」可见可点、高 ≥48 dp，亮暗各一张（`ExportSheetLayoutTest`，**已做**） |
| 15 | 目录选择返回 | 授权写入、上次目录被记住（真机链路覆盖，见 §12.2） |
| 16 | 中途取消 | 临时文档被删除、已完成项保留（真机 1000 项覆盖，见 §13.3） |
| 17 | 授权撤销 | 阻止开始并提示重选（`start()` 里先 `isPersisted` 再看） |
| 18 | 同名冲突 | 生成 `(2)` 而不是覆盖（`ExportWriterTest`） |
| 19 | 通知进度与取消 | 通知动作能停止服务（`ExportServiceTest.cancelActionFromNotificationStopsWithCancelWording`） |
| 20 | 系统超时 | `onTimeout` → `Interrupted(SYSTEM_TIMEOUT)` + `stopSelf`（`ExportServiceTest`） |
| 21 | 空启动 | 没有待跑请求时不进前台、直接停（`ExportServiceTest`） |

### 9.3 产物隔离（反编译扫描，节点 C 验收）

| # | 检查 | 通过标准 |
|---|---|---|
| 20 | free release 解压后扫标记串 | 「尚未下载到本机」「先下载再导出」与 full 实现类名 0 命中（命令见 §5.6） |
| 21 | full release 解压后扫同一组标记串 | 全部 ≥1 命中（证明能力真的在 full） |
| 22 | `scan_forbidden.py heavy` 扫 free 源码与 APK | 通过（两句文案需先加进 `tools/forbidden-heavy.txt`，防止以后误回流） |
| 23 | free 侧接缝不渲染 | 调用 `ownerGate.ExportServerNotice(...)` 后 semantics 树无新增节点；free 源码里 0 处文案 |

### 9.4 真机验收（用户在手机上做，报告单列「未做」）

SD 卡目录、USB OTG、云盘目录（受限时的提示）、Download 子目录、横竖屏切换、息屏继续、通知权限拒绝、空间不足、单文件 > 2 GB、一次导出 200 项、导出后文件能在系统播放器打开。

---

## 10. 改动文件

```
data/export/ExportTrack.kt        导出用快照（A，纯数据）
data/export/ExportNaming.kt       文件名清洗/截断/保留名/序号/子目录（A，纯函数）
data/export/ExportPlan.kt         分类计数、大小估算、空间三态、MIME、失败归类（A，纯函数）
data/export/ExportTarget.kt       目标写入口（接口，B；无 Android 类型，便于假实现）
data/export/ExportWriter.kt       单项写出：临时名→改名→降级重写→清理（B）
data/export/SafExportTarget.kt    SAF 实现 + 目录授权（persist/判定/只留 5 个/显示名）（B）
data/export/ExportPrefs.kt        上次目录、文件夹名、四个开关、通知是否问过（B）
data/export/ExportSession.kt      状态机：串行/取消/中断/中止/只重试未完成（B）
ui/screen/ExportSheet.kt          确认页 + 应用内进度 + 摘要（只经 OwnerGate 取服务器文案）（B）
ui/OwnerGate.kt                   main：加 ExportMissingNotice 声明（B）
free/ui/OwnerGate.kt              空实现（不渲染、无文案）（B）
full/ui/OwnerGate.kt              转发到 ExportMissingNoticeBody（B）
full/ui/screen/ExportMissingNoticeBody.kt  full 专属的「N 项本机没有文件 + 跳过」（B）
ui/screen/LibraryScreenV2.kt      多选顶栏改 FlowRow 并加「导出」、播放列表/类别两个入口、sheet 接线（B）
data/export/ExportController.kt   会话与状态的唯一持有者（单例，页面只观察）（C）
data/export/ExportService.kt      dataSync 前台服务 + onTimeout + 5 小时自保护 + 唤醒锁（C）
data/export/ExportNotifications.kt 进度/结果通知 + 「取消」PendingIntent（C）
AndroidManifest.xml               ExportService + FOREGROUND_SERVICE_DATA_SYNC + WAKE_LOCK（C）
tools/forbidden-heavy.txt         加「本机没有文件」「ExportMissingNoticeBody」（B）
test/.../ExportWriterTest.kt      9.1 的 24–28 + 取消清理（B/C）
test/.../ExportSessionTest.kt     9.1 的 29–31 + 时限与三路径一致（B/C）
test/.../ExportServiceTest.kt     9.2 的 15–17（C，Robolectric）
test/.../ExportSheetLayoutTest.kt 9.2 的第 14 项：320 dp + 200%（C，Robolectric）
androidTest/.../ExportFixtures.kt 端到端素材铺设（C，经 BatchDeleteInstrumentation 的 mode 驱动）
```

交付顺序（A → B → C，每个节点单独交付，交付时给该节点的构建/测试证据）：

- **A（纯逻辑，无 UI）**：`ExportNaming` + `ExportPlan` + 9.1 的 1–13。
  **状态：已交付（2026-11-17）**，13 个编号用例全过。
- **B（确认页与目录）**：SAF 选目录 + 授权持久化 + 上次目录记忆 + 文件夹名/重名提示 + 应用内进度与摘要，
  并**真的导出 1 项走通全链路**。
  **状态：已交付（2026-11-19）**，证据见 §12。
- **C（服务与收口）**：`ExportService`（dataSync 前台服务）+ `onTimeout` + 通知 + 取消 + 重试失败项 + 9.3 复扫。
  **状态：已交付（2026-11-19）**，证据见 §13。

## 11. 已确认的决定（2026-11-17）

| # | 决定 | 落地位置 |
|---|---|---|
| 1 | 播放列表与类别**整表导出**，不在 sheet 里加多选 | §1.2 / §1.3 |
| 2 | 自动分子文件夹只提供「按类型」「按博主」，**默认关闭** | §4.5 |
| 3 | 重名**只加序号、不覆盖** | §4.4 |
| 4 | **记住上次目录**（持久化授权只留最近 5 个） | §5.1 |
| 5 | 文件名日期取**入库日期**，界面文案写「入库日期」 | §4.2 |
| 6 | **不做导出清单** | §5.7 |
| 7 | 服务器相关的导出提示只经 `OwnerGate` 进 full，用反编译扫描验证 | §5.6 / §9.3 |

### 11.1 B 阶段补定的 6 条（2026-11-19）

| # | 决定 | 落地位置 |
|---|---|---|
| 8 | B、C **分两个提交**，各自附本节点的证据 | 交付顺序 §10 |
| 9 | full 里**只做「跳过这 N 项」**，不摆会失败的「先下载再导出」；补下载导出记进 P0-2 待办 | §1.4 / §5.6 / p0-2 §17 |
| 10 | 通知权限在**首次点「开始导出」时问一次**，拒绝不影响导出 | §5.4 |
| 11 | 进程被杀：**只提示、不删**用户目录里的临时文件，提示里带残留个数 | §7 |
| 12 | Android 15 `dataSync` 超时：**必须实现 `onTimeout`**（停服务、置 `Interrupted`、通知写「导出被系统中断，可重试失败项」）；`compileSdk` 升 35 才有这个方法 | §5.4 / §6 |
| 13 | 空间**三态**：「查不到」要真实存在——只显示「无法确认剩余空间」，不拦；运行中不足再中止 | §5.3 |

无待确认项。开工顺序 A → B → C，每个节点单独交付并附证据。

---

## 12. 节点 B 交付证据（2026-11-19）

环境：模拟器 `gm-p0-1`（Android 14 / API 34 / arm64），free debug 包 `com.getmedia.app`。

### 12.1 导出 1 项走通全链路（sha256 一致）

1. `adb push` 一个 3 秒 440 Hz WAV 到 `/sdcard/Download/导出验证.wav`（264 644 B）；
2. 应用内「导入」→ Downloads → 选中文档 → 曲库出现「导出验证 0:03」（源文件落进私有目录 `files/audio/-343607218361811487.audio`）；
3. 「类别 → 播放列表 → 列表1 → 加入列表 → 按文件选择 → 勾选 → 加入列表」；
4. 点新增的「**导出此列表**」→ 确认页 → 选择目录（DocumentsUI 根目录被系统禁用，提示语正好命中）
   → `Download/GetMedia` → 使用此文件夹 → 允许；
5. 开始导出 → 通知权限弹一次（点允许）→ 摘要页「导出完成 · 成功 1 · 失败 0」。

```
源文件  files/audio/-343607218361811487.audio
        9ac65f904e08798169d0465a013cc8aababed84f0d16d3d43f51147d63f780d1   264644 B
导出文件 /sdcard/Download/GetMedia/2026-11 导出/导出验证.wav
        9ac65f904e08798169d0465a013cc8aababed84f0d16d3d43f51147d63f780d1   264644 B
```

导出后目标目录里**只有**这一个文件，没有 `~getmedia-…part` 残留（临时名被成功改名）。

### 12.2 同一次跑动顺带验证到的四件事

| 项 | 实测结果 |
|---|---|
| 目录授权持久化 + 记住上次目录 | 杀掉进程、`install -r` 覆盖安装后重进确认页，保存位置仍是「内部存储/Download/GetMedia」 |
| 文件夹名记忆 | 自动带出上次的「2026-11 导出」 |
| 重名提示 | 第二次进入即出现「该目录下已有同名文件夹 + [使用已有] [改名]」，未静默合并 |
| 空间"查不到"态 | 该 provider 返回 -1 → 显示「无法确认剩余空间」，**开始导出仍可点**（截图里可见） |

截图：`docs/reports/export-b-2026-11-19/sheet-confirm.png`（确认页）、`sheet-done.png`（摘要页）。

### 12.3 构建/测试/扫描

| 项 | 结果 |
|---|---|
| `testFreeDebugUnitTest` | 247 passed（B 新增 18：`ExportWriterTest` 12 + `ExportSessionTest` 6） |
| `testFullDebugUnitTest` | 321 passed |
| `lintFreeDebug` | 0 error / 26 warning（既有基线） |
| `scan_forbidden.py heavy`（源码 + free APK） | 通过 |
| `scan_leaks.py --all` | 通过（473 文件） |
| 产物隔离（§5.6） | `本机没有文件` free=0 / full=1；`ExportMissingNoticeBody` free=0 / full=34 |

### 12.4 当时已知未做（均已在 C 阶段处理）

- `androidTest` 源码集当时编译不过（`submitViaServer` 已改名、`TrackEntity` 参数错位）——
  C 之前单独提交修掉（`5b191cd`），`compileFreeDebugAndroidTestKotlin` / full 同名任务都过。
- 9.2 第 14 项（320 dp + 字体 200% 的确认页）—— C 补了 `ExportSheetLayoutTest`，见 §13.2。
- 前台服务、通知、`onTimeout`、取消/重试的服务侧接线 —— 见 §13。

---

## 13. 节点 C 交付证据（2026-11-19）

### 13.1 服务侧测试（Robolectric，`ExportServiceTest`）

| 用例 | 钉住什么 |
|---|---|
| `onTimeoutInterruptsWithSystemWordingAndStopsService` | API 35 的 `onTimeout` 当场把状态置 `Interrupted(SYSTEM_TIMEOUT)`（文案「导出被系统中断，可重试失败项」），未跑项进 `retryIds`，并 `stopSelf()`——不给系统留一个不肯停的前台服务 |
| `cancelActionFromNotificationStopsWithCancelWording` | 通知的 `ACTION_CANCEL` 回到服务后走 `session.cancel()`，文案「已取消导出」 |
| `startWithoutPendingRequestDoesNotStartForeground` | 没有待跑请求时直接停掉，不挂空通知 |

**三条路径最终状态一致**：`ExportSessionTest.allStopPathsLeaveTheSameShape` 用同一套断言跑
`USER_CANCEL` / `SYSTEM_TIMEOUT` / `TIME_LIMIT` 三遍——结果列表、`notRun`、`retryIds`、目标目录里剩下的文件
三者必须完全相同，并额外钉住"具体长什么样"（只留第 1 项成品、`notRun = 2`、目录里没有 `~getmedia-`），
避免三条一起错也会"一致地通过"。

**取消能停在半路**（`ExportWriterTest.cancelledWriteDeletesThePartAndIsNotAFailure`）：`stop` 返回 true 时
临时文件被删、`Outcome.cancelled = true`、`reason == null`——取消不是失败。

### 13.2 320 dp + 字体 200%（`ExportSheetLayoutTest`）

模拟器改系统字号再截图容易漏，改用 Robolectric 一次跑亮暗两版，断言：

- 「开始导出」**可见、可用、有点击动作、高度 ≥ 48 dp**；
- 「无法确认剩余空间，导出时若空间不足会自动中止」与「上次导出留下 3 个临时文件」都在（滚动后可见）。

截图：`docs/reports/export-c-2026-11-19/confirm-320dp-200-light.png`、`confirm-320dp-200-dark.png`。
（测试里给 `ExportSheet` 传 `ExportPreview` 跳过 SAF 交互，否则 JVM 里拿不到持久化授权。）

### 13.3 真机链路：1000 项批量导出，中途点通知「取消」

环境：模拟器 `gm-p0-1`（Android 14 / API 34），free debug，素材由 `ExportFixtures` 铺 1000 项
（500 音频 + 500 视频，各 1 MB，合计约 1020 MB），目标目录 `Download/GetMedia/C-verify`（已授权、空目录）。

1. 列表详情 →「导出此列表」→ 确认页「音频 500 项 · 视频 500 项 · 共 1000 项，约 1020.0 MB」；
2. 开始导出 → 下拉通知 →「正在导出 145/1000」+ 当前文件名（截图 `notify-running-1000.png`）；
3. 展开通知点「取消」→ 通知变「导出已停止 / 已取消导出」（`notify-cancelled.png`）；
4. 回到应用：摘要页「导出被中断 / 成功 191 · 失败 0 / 已取消导出 / 还有 809 项未执行 /
   重试未完成项（809）」（`summary-interrupted.png`）。

```
目标目录成品数                    191
目标目录 ~getmedia-*.part 残留      0
191 + 809                         1000
```

三件事同时成立：**已完成的保留**（191 个成品）、**当前项的临时文件被删**（0 残留）、
**没跑到的可重试**（809）。

### 13.4 构建/测试/扫描

| 项 | 结果 |
|---|---|
| `testFreeDebugUnitTest` | 255 passed（C 新增 8） |
| `testFullDebugUnitTest` | 329 passed |
| `lintFreeDebug` / `lintFullDebug` | 0 error / 26 warning（既有基线；新代码用 `@SuppressLint("InlinedApi")` 说明 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` 是 API 29 常量） |
| `scan_forbidden.py heavy`（源码 + free APK） | 通过 |
| `scan_leaks.py --all` | 通过 |
| 产物隔离（§5.6 复扫） | `本机没有文件` free=0 / full=1；`ExportMissingNoticeBody` free=0 / full=34 |

C 新增的字符串（`导出被系统中断，可重试失败项`、`无法确认剩余空间`、`~getmedia-`）在两个风味里**都**有——
导出是 main 的功能，不属于服务器接缝。

### 13.5 已知未做

- `targetSdk` 仍是 34，所以 Android 15 的 `dataSync` 限额当前不会真的回调 `onTimeout`；
  方法已 override 且有测试，升级 `targetSdk` 时不用再改代码。
- 进程被杀后不恢复队列、不删用户目录里的 `~getmedia-…part`（设计如此，见 §7）。
- 9.4 的真机验收（用户手机）仍待用户完成。

