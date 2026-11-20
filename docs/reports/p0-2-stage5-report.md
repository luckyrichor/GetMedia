# P0-2 阶段 5 报告：删除同步与标题推送

日期：2026-11-20　分支：`wp6-free`　版本：full 0.1.11 / versionCode 12
范围：`docs/p0-2-sync-design.md` §18.5 阶段 5；计划见 `docs/superpowers/plans/2026-11-20-getmedia-stage5.md`（本地）。
**未部署生产、未安装手机、未改线上数据。**

## 结论先说

- 服务端新增两条手机专用条件写路径：`DELETE /v2/sync/tracks/{id}` 与
  `PATCH /v2/sync/tracks/{id}`，都要求 `If-Match`；归档行拒绝写墓碑；旧的网页
  `DELETE/PATCH /v2/tracks/{id}` 行为未改。
- 手机端删除只对"已绑定当前账号、非归档、非本机剪辑派生"的条目入队；队列持久化在 Room，
  支持离线、重启、退避、幂等、先删后加、迟到旧响应和归档保护。
- 拉到云端墓碑时**不自动删任何本机文件**，只摆一次批量确认，默认「保留本机文件」。
- 已绑定行改名只写待推送草稿，条件 PATCH 以服务器为准；待推送草稿与已确认标题分开存；
  备份恢复不生成 PATCH。
- 单测 free 325 / full 506 通过；pytest 408 passed / 50 skipped；双风味 Lint 0 错误 / 26 警告；
  源码与 free/full release APK 的 heavy 扫描、`scan_leaks --all` 通过。

## 服务端改了什么

`server/getmedia/media.py`、`server/getmedia/media_api.py`：

| 接口 | 语义 |
|---|---|
| `DELETE /v2/sync/tracks/{id}` | 必须带 `If-Match: "<updated_seq>"`；缺失 428 `precondition_required`，格式不对 400 `bad_version`；同账号内检查归属；`archive` 行 409 `archive_protected`；版本不符 412 `track_version_conflict`；已删除行重复请求 200（幂等）；条件更新与检查在同一事务/同一句 SQL 里 |
| `PATCH /v2/sync/tracks/{id}` | 只收 `title`（`extra="forbid"`，多字段 422）；必须带 `If-Match`；空标题 400 `bad_title`；墓碑 409 `track_deleted`；版本不符 412；成功返回完整条目与新 `updated_seq` |

- 旧网页接口 `PATCH /v2/tracks/{id}`（可改 title/uploader/pinned、无版本条件）与
  `DELETE /v2/tracks/{id}`（归档行也照旧可写墓碑）**保持原样**，有兼容测试。
- 不改数据库结构；不新增迁移；nginx 不需要改（owner 通道按前缀转发）。
- 新增 10 个 pytest 用例，覆盖：缺版本/坏版本、归档保护且文件字节与 SHA 不变、旧网页删除兼容、
  cache 墓碑保留文件、重复删除幂等、删除后复活再收到旧 DELETE 被 412 挡住、PATCH 只改标题、
  并发同版本只有一个成功、墓碑 PATCH、越权与匿名。

## 手机端改了什么

### Room v3 → v4

- `tracks` 新增 `serverStorage`、`confirmedTitle`、`pendingTitle`、`pendingTitleBaseSeq`、
  `pendingTitleGen`；全部是 `ALTER TABLE ADD COLUMN`，旧行不变。
- `track_removals` 新增 `expectedSeq`、`generation`、`state`、`attempts`、`lastError`。
- 新表 `remote_marks`：云端墓碑待确认（账号、远端 ID、平台、键、标题、版本、发现时间）。
- 迁移用 Room 真实打开路径验证：退回 v3 结构后升级，`tracks` 逐字段不变、播放列表与类别不变。

### 删除

- 删除确认：已绑定且非归档的行显示「同时从云端曲库移除（其他设备也会同步到）」，**默认勾选**；
  已知 `archive` 行不显示勾选项，只提示「云端归档会保留，只删除本机」。
- 批量删除按条目分别判断，弹窗分别计数：会从云端移除 / 云端归档保留 / 仅本机。
- 入队与删行、播放列表引用清理在**同一个 Room 事务**；`generation` 用单调值，迟到响应只能清同代。
- 重放前先重读本机：同键已有"删除之后重新添加"的文件且请求尚未发出 → 取消删除；
  已发出/结果未知（`SENDING`）→ 不取消，成功后把新行脱钩（保留文件、不再自动上传）。
- 归档保护、版本冲突进入 `WAITING`，不自动改用旧的无条件接口，也不再每轮重试。
- 业务 404 `track_not_found` 与入口裸 404 分开：前者结项，后者保留队列等部署/权限恢复。
- 任何重放、拉取、墓碑、失败路径都不调用删文件；只在"用户确认删除本机"时删。

### 墓碑

- 拉取事务内发现墓碑：身份/账号一致才处理；纯云端镜像（无文件、无收藏、默认类别、不在播放列表、
  无本机改名、非剪辑）直接移出列表；其余写 `remote_marks`，**不碰行、不碰文件**。
- 批量弹窗：「云端已移除 N 项……本机文件还在」，主操作「保留本机文件」，关闭只是「稍后处理」。
- 保留：原 Room ID 变 `LIB_DETACHED`，清 `serverId/serverSeq`，保留文件、标题、收藏、类别、
  播放列表；自动上传与手动候选都将其排除。
- 未提供"同时删除这 N 项本机文件"的批量破坏入口；要删本机文件请逐项删除（见"自作主张"）。

### 标题

- 绑定行改名：`title/localTitleOverride` 显示新名，`confirmedTitle` 保留服务器已确认标题，
  `pendingTitle` 存草稿，`pendingTitleBaseSeq` 存第一次改名时的版本，`pendingTitleGen` 存世代。
- 同步顺序：重放删除 → 恢复匹配 → 拉取 → 标题 PATCH → 上传候选。
- PATCH 前先 `GET /v2/tracks/{id}` 核对：服务器版本已变则服务器赢；标题正好等于草稿则按成功收尾，
  不重发；墓碑清草稿并进待确认；网络/5xx 保留草稿下次重试；旧服务器裸 404 保留草稿、不回退旧接口。
- 备份：待推送草稿不覆盖 `confirmedTitle`，`title_override` 不写草稿；恢复行不写任何
  `pendingTitle*`，因此恢复不会生成 PATCH。

## 测试与构建

| 项 | 命令 | 结果 |
|---|---|---|
| 服务端 | `.venv/bin/python -m pytest -q server/tests tools/tests` | **408 passed / 50 skipped** |
| Android free | `:app:testFreeDebugUnitTest` | **325 passed / 0 failed** |
| Android full | `:app:testFullDebugUnitTest` | **506 passed / 0 failed** |
| Lint | `:app:lintFreeDebug :app:lintFullDebug` | **0 errors / 26 warnings**（两种风味） |
| 扫描 | `scan_leaks --all`、`scan_forbidden legacy`、`scan_forbidden heavy`（源码 + 两个 release APK） | 通过 |
| APK | `:app:assembleFullRelease` | `com.getmedia.app.full` 0.1.11 (12)，证书 SHA-256 `8a0920…691b`，SHA-256 `07fcbbbcbc9f33a385c6d9a6fe0edc696763276048d747b4b8df6daa2108996f` |

APK 归档在 `local-artifacts/getmedia-apk/stage5-0.1.11-20261120/`（不入库）。
**未安装手机、未部署服务端；真机验收要等服务端小补丁部署后另行批准。** 真机上的删除只用测试条目，
并逐项经用户批准；手机 1 未触碰。

## 隐私说明 v3 文本与阶段 5 实际行为的出入（供收尾判断，本次未改 v3）

1. **删除并非无条件双向**。v3 §1.7 写"你在这台设备上删除一个已经同步过的条目，服务器上的对应条目
   会被标记为已删除"。阶段 5 的勾选项默认勾选但可取消；取消后只删本机，云端条目保留（可能以
   "云端未下载"再次出现）。文本没有写"可取消/仅本机"这一支。
2. **归档行不会写墓碑**。v3 没有区分服务器归档；阶段 5 对 `archive` 行只删本机，云端归档
   （含文件）保留。删除确认里已单独提示，但隐私文本没写这一例外。
3. **v3 说曲库同步"不拿文件信息做任何判断"**。阶段 5 为隐藏归档行的云端移除选项，会读服务器
   返回的 `storage`（`has_file` 只解析、未参与判断）。这是对"同步忽略文件信息"的直接例外；
   若审查者认为口径必须一致，可改为只依赖服务端 409 兜底，不在客户端读 `storage`。
4. **墓碑设备端不是自动删**。v3 说其他设备"会同步到这次删除"，后文也写了可选择保留本机文件；
   但阶段 5 的实际默认是保留，且批量确认前不会改动任何行。文本没有明确"默认保留、需确认"。
5. **待推送标题不进备份**。v3 备份条目写"你为条目改过的名称"；阶段 5 对尚未被服务器确认的
   待推送草稿，备份使用 `confirmedTitle`（不写草稿）。这是有意的防误推，但文本没有区分
   "已确认的改名"与"待推送的改名"。
6. **本地队列不涉及服务器**。v3 说关闭同步后"不再上传、也不再拉取"，阶段 5 确实停止所有请求，
   但待重放删除/待推送标题仍留在本机，重新开启后继续。文本没有提本机队列的保留。

## 自作主张（请审查者批）

1. **墓碑弹窗没有做"同时删除这 N 项本机文件"的批量按钮**。计划写的是"若保留该入口"；我选择只给
   「保留本机文件」与「稍后处理」，把删本机文件留给逐项删除，减少一次误删一批的风险。
2. **纯云端镜像墓碑自动移出列表**（无文件、无收藏、默认类别、不在播放列表、无本机改名、非剪辑）。
   这类行没有可丢的用户数据；有文件或有整理痕迹的一律进批量确认。计划允许"无文件的云端项移出
   可下载队列"，这里落成了直接删行。
3. **`LIB_DETACHED` 同时清 `accountKey`**（计划只明确清 `serverId/serverSeq`）。这样换账号、
   重新登录都不会把已脱钩行再算回原账号的候选；代价是以后若要做"重新传上去"需要重新指定账号。
4. **为删除确认能提前识别归档，`tracks` 增加了 `serverStorage` 列**（Room v4）。未知时仍给勾选项，
   由服务端 409 兜底；已知归档则隐藏勾选项。
5. **为把"已确认标题"与草稿分开，增加了 `confirmedTitle` 列**；`title` 仍是界面显示值。
6. **删除队列增加 `SENDING` 状态**。请求发出前先落库，崩溃/超时后下一轮按远端核对，不会把
   "可能已发出"误当成"未发出"而取消。
7. **拉取时服务器版本更新会采用服务器标题并清空本机 `localTitleOverride`**（没有待推送草稿的行）。
   这是"服务器为准"的直接落地；阶段 5 之前遗留的本机改名在服务器再次变化时会被服务器标题覆盖。
8. **旧网页接口不改**：`DELETE /v2/tracks/{id}` 仍能给归档行写墓碑（网页库维护用）；手机专用路径
   单独保护归档。计划只要求保护手机入口，这里没有扩大旧接口行为。
9. **标题 PATCH 只处理标题**，不允许手机顺便改 `uploader/pinned`；后者继续走网页原接口。

## 没做什么

- 没有服务端部署、没有手机安装、没有生产数据写入、没有用真实账号调用新接口。
- 没有真机验证：删除确认、归档保护、墓碑批量确认、先删后加、标题 PATCH、恢复后不产生 PATCH。
- 没有做墓碑清理策略（设计已定不加）；没有做"同时删除一批本机文件"的批量入口。
- 没有改隐私说明 v3；上述出入留给审查者决定是否出 v4。
