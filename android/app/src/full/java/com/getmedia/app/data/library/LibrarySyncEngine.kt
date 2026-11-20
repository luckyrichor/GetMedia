package com.getmedia.app.data.library

import androidx.room.withTransaction
import com.getmedia.app.data.backup.MetadataSnapshot
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LibraryCursorEntity
import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.RemoteMarkEntity
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity

/**
 * 从服务器把曲库条目拉下来（P0-2 阶段 2）。
 *
 * 四条铁律，每一条都有对应用例：
 *
 * 1. **只新建**：只在本地没有这个键时插入一行 [LibraryMark.CLOUD_ONLY]；
 *    已有的行（`RESTORED` / `LOCAL_ONLY` / `SYNCED`）**一个字段都不动**，连 `updated_seq`
 *    比较都不做——恢复关联由 [RestoredMatcher] 先处理，其余合并是阶段 5 的事。
 * 2. **只发 GET**：本类拿不到任何写通道，[LibrarySyncTransport] 里只有 `pull`。
 * 3. **不碰备份**：写库直接走 DAO，不经过 `TrackRepository`，因此不会触发 `BackupManager.noteWrite()`
 *    的防抖上传；而且这些行的"无整理痕迹"特征会让它们根本进不了快照（`MetadataSnapshot.keepInSnapshot`）。
 * 4. **水位只在整页全部应用成功后推进**：[applyPage] 里写行与写水位在同一个事务，
 *    中途抛异常整页回滚，水位留在上一页末尾，重跑幂等。
 */
class LibrarySyncEngine(
    private val db: AppDatabase,
    private val transport: LibrarySyncTransport,
    private val pageSize: Int = DEFAULT_PAGE,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    data class Result(
        val added: Int,
        val skipped: Int,
        val tombstones: Int,
        val pages: Int,
        val cursor: Long,
        /** 本轮新发现的墓碑（本机还有文件或整理痕迹），等用户批量确认。 */
        val marks: Int = 0,
        /** 无文件也无整理痕迹的纯云端行，已直接移出列表（不涉及任何文件）。 */
        val removedRows: Int = 0,
    )

    /**
     * 拉完为止（水位为 0 时就是全量翻页）。
     *
     * 不在这里吞异常：调用方按 [LibrarySyncProblem] 决定是静默停手（`NOT_AVAILABLE`）、
     * 退避（`RATE_LIMITED`）还是提示（`LOGIN_REQUIRED`）。
     */
    suspend fun pull(account: String): Result {
        require(account.isNotBlank()) { "账号不能为空" }
        var cursor = db.libraryAuxDao().cursor(account)?.lastPos ?: 0L
        // 一次性回填：旧版本只在"新建云端行"时写 serverStorage，恢复匹配与老绑定行可能一直是空。
        // 增量水位不会再把那些行送回来，所以只要发现"有 serverId 但没 storage"，就退到全量拉一次。
        // 拉取幂等；全量之后仍是空的（服务器没给这个字段）就写 "unknown" 占位，避免每轮都全量。
        var backfill = false
        if (cursor > 0L && db.trackDao().allNow().any {
                it.accountKey == account && it.serverId != null && it.serverStorage == null
            }
        ) {
            cursor = 0L
            backfill = true
        }
        var added = 0
        var skipped = 0
        var tombstones = 0
        var pages = 0
        var marks = 0
        var removedRows = 0
        while (true) {
            val page = transport.pull(cursor, pageSize)
            pages++
            val applied = applyPage(account, page.items, cursor)
            added += applied.added
            skipped += applied.skipped
            tombstones += applied.tombstones
            marks += applied.marks
            removedRows += applied.removedRows
            cursor = applied.cursor
            // 空页但 has_more=true 是服务端的边界抖动，直接停，别打空请求
            if (!page.hasMore || page.items.isEmpty()) break
        }
        if (backfill) {
            // 服务器没给 storage 的绑定行写占位值：`isKnownArchive` 只认 "archive"，
            // 所以它仍然会被当作"未知"，删除确认照常给勾选项，由服务端兜底。
            db.trackDao().allNow()
                .filter { it.accountKey == account && it.serverId != null && it.serverStorage == null }
                .forEach { db.trackDao().updateExisting(it.copy(serverStorage = "unknown")) }
        }
        return Result(added = added, skipped = skipped, tombstones = tombstones, pages = pages,
            cursor = cursor, marks = marks, removedRows = removedRows)
    }

    private data class Applied(val added: Int, val skipped: Int, val tombstones: Int, val cursor: Long,
                               val marks: Int = 0, val removedRows: Int = 0)

    /** 一页一个事务：写行与写水位要么一起生效，要么一起不生效。 */
    private suspend fun applyPage(account: String, items: List<CloudTrack>, since: Long): Applied =
        db.withTransaction {
            if (items.isEmpty()) return@withTransaction Applied(0, 0, 0, since)

            // 整表读一次建索引：同步只补缺失，靠 id 与 (platform, videoId, page) 双保险判重。
            // 双保险不是多余的——历史行可能用旧规则算过 id，键相同但 id 不同，只查 id 会插出重复。
            val rows = db.trackDao().allNow()
            val knownIds = rows.mapTo(HashSet()) { it.id }
            val knownKeys = rows.mapTo(HashSet()) { keyOf(it) }
            val normalized = rows.mapNotNullTo(HashSet()) { it.restoredKey() }

            var added = 0
            var skipped = 0
            var tombstones = 0
            var marks = 0
            var removedRows = 0
            val fresh = ArrayList<TrackEntity>(items.size)
            val now = nowMs()
            val aux = db.libraryAuxDao()

            // 本机刚删、还没重放成功的键：拉取不能把它们又建回来（先删后加竞态的读侧保险）
            val queuedKeys = aux.removals(account).mapTo(HashSet()) {
                Triple(it.platform.lowercase(), it.videoKey, account)
            }
            val byServerId = rows.mapNotNull { row -> row.serverId?.let { it to row } }.toMap()

            for (item in items) {
                val key = Triple(item.platform.lowercase(), LocalIds.key(item.platform, item.videoId, item.page), account)
                val serverKey = Triple(item.platform.lowercase(), item.videoId, item.page)
                if (item.deleted) {
                    tombstones++
                    // 自己刚发起的删除：墓碑已生效，清掉待重放记录即可，不必再弹确认
                    if (key in queuedKeys) {
                        aux.dropRemoval(item.platform, key.second, account)
                        queuedKeys.remove(key)
                    }
                    val row = byServerId[item.id] ?: continue
                    if (row.accountKey != account) continue
                    if (row.syncState == LibraryMark.DETACHED) continue
                    // 身份必须一致；不一致就只报告，不碰这一行
                    if (row.serverId != item.id || row.restoredKey() != item.restoredKey()) continue
                    // 先清掉待推送草稿：墓碑上再 PATCH 只会 409，草稿留着也没意义
                    if (row.pendingTitle != null) {
                        db.trackDao().updateExisting(
                            row.copy(pendingTitle = null, pendingTitleBaseSeq = null, pendingTitleGen = null),
                        )
                    }
                    val itemsInPlaylist = db.playlistDao().itemsAll().any { it.trackId == row.id }
                    val noTrace = row.localPath.isNullOrBlank() && !row.favorite &&
                        row.localCategoryId == MetadataSnapshot.DEFAULT_CATEGORY_ID &&
                        row.localTitleOverride == null && !itemsInPlaylist && !row.locallyEdited
                    if (noTrace) {
                        // 纯云端镜像：没有文件、没有任何用户整理信息，直接移出列表
                        db.trackDao().removePlaylistReferences(listOf(row.id))
                        db.trackDao().delete(row.id)
                        removedRows++
                    } else {
                        // 有文件或整理痕迹：只记下来，绝不自动删文件；等用户批量确认
                        aux.addMark(
                            RemoteMarkEntity(
                                account = account, remoteId = item.id, platform = item.platform,
                                videoKey = key.second,
                                title = row.localTitleOverride ?: row.title, seq = item.updatedSeq, seenAt = now,
                            ),
                        )
                        marks++
                    }
                    continue
                }

                val id = LocalIds.localId(item.platform, item.videoId, item.page)
                val identity = item.restoredKey()
                val existing = byServerId[item.id]?.takeIf {
                    it.accountKey == account && it.restoredKey() == identity
                }
                if (existing != null) {
                    // 已有的绑定行：只更新服务器权威字段，绝不碰收藏/类别/排序/文件。
                    // 除了版本前进，**存储类型变了也要写**：归档行可能版本没动（例如恢复匹配时
                    // 才第一次看到 storage），只比 updated_seq 会让本机永远不知道它是 archive。
                    val storage = item.storage.ifBlank { existing.serverStorage }
                    if (item.updatedSeq > existing.serverSeq.orZero() || storage != existing.serverStorage) {
                        db.trackDao().updateExisting(mergeServer(existing, item, now))
                    }
                    skipped++
                    continue
                }
                if (key in queuedKeys || id in knownIds || serverKey in knownKeys || identity in normalized) {
                    skipped++
                    continue
                }

                knownIds += id
                knownKeys += serverKey
                if (identity != null) normalized += identity
                fresh += entity(item, id, account, now)
                added++
            }

            // IGNORE 语义：万一判重漏了，也只会丢这条新行，绝不会覆盖已有行（REPLACE 会）
            if (fresh.isNotEmpty()) db.trackDao().insertIgnore(fresh)

            val next = (items.maxOfOrNull { it.updatedSeq } ?: since).coerceAtLeast(since)
            aux.putCursor(LibraryCursorEntity(account = account, lastPos = next, lastAt = now))
            Applied(added = added, skipped = skipped, tombstones = tombstones, cursor = next,
                marks = marks, removedRows = removedRows)
        }

    private fun Long?.orZero(): Long = this ?: 0L

    /**
     * 把服务器的新版本合进已有绑定行。**只改服务器权威字段**：
     * 收藏、类别、手动顺序、本地文件、入库时间、播放列表不动（`existing.copy` 的纪律）。
     *
     * 标题的三条规则（从强到弱）：
     *
     * 1. **有待推送草稿**：拉取一律不动标题、不动草稿，也不改 `syncState`。草稿只能由
     *    [LibraryTitlePush] 收尾——它先 `GET` 核对，只有版本冲突（含 412）时才采用服务器标题
     *    并计入 `conflicts`，由调用方给出"已改用云端标题"的明确提示。
     *    以前这里"服务器版本比基线新就清草稿"的做法会让用户改完名、还没推送就被悄悄抹掉。
     * 2. **阶段 4 留下的本机改名**（`localTitleOverride` 有值、没有草稿）：保留本机标题，
     *    只把服务器的实际标题记到 `confirmedTitle`，作为以后冲突判断的基准。
     * 3. 其余情况：服务器为准。
     */
    private fun mergeServer(existing: TrackEntity, item: CloudTrack, now: Long): TrackEntity {
        var state = existing.syncState
        var title = existing.title
        var confirmed = existing.confirmedTitle
        var override = existing.localTitleOverride
        if (existing.pendingTitle != null) {
            // 规则 1：草稿受保护，连 title/confirmed 都不动
        } else if (override != null) {
            // 规则 2：本机改名优先，服务器标题只作基准
            confirmed = item.title
        } else {
            title = item.title
            confirmed = item.title
            state = SyncState.SYNCED.name
        }
        return existing.copy(
            title = title,
            confirmedTitle = confirmed,
            localTitleOverride = override,
            pendingTitle = existing.pendingTitle,
            pendingTitleBaseSeq = existing.pendingTitleBaseSeq,
            pendingTitleGen = existing.pendingTitleGen,
            uploader = item.uploader,
            durationSec = item.durationSec,
            sourceUrl = item.sourceUrl.ifBlank { existing.sourceUrl },
            serverSeq = item.updatedSeq,
            serverPage = item.page,
            serverUpdatedAtMs = (item.updatedAtSec * 1000).takeIf { it > 0 },
            serverStorage = item.storage.ifBlank { existing.serverStorage },
            syncState = state,
            updatedAtMs = now,
        )
    }

    /** 键与 [MetadataSnapshot] 算快照时用的完全一致，三源回退的规则只有那一份实现。 */
    private fun keyOf(t: TrackEntity): Triple<String, String, Int> =
        Triple(t.platform.lowercase(), MetadataSnapshot.videoIdOf(t), MetadataSnapshot.pageOf(t))

    private fun entity(item: CloudTrack, id: Long, account: String, now: Long) = TrackEntity(
        id = id,
        platform = item.platform,
        // 与直提取/服务器提取/恢复同一套键形态：B 站 P2 存成 BV1xx-p2，重新下载才落回同一行
        videoId = LocalIds.key(item.platform, item.videoId, item.page),
        title = item.title,
        uploader = item.uploader,
        durationSec = item.durationSec,
        // 云端只有元数据：没有文件、没有字节数、没有格式。格式留空字符串而不是编一个
        // "m4a"——列表要显示的就是"未知/云端"，编出来的值会让"重新下载后格式不对"变成悬案。
        audioBytes = null,
        audioFormat = "",
        sourceUrl = item.sourceUrl,
        localPath = null,
        favorite = false,
        localCategoryId = MetadataSnapshot.DEFAULT_CATEGORY_ID,
        updatedAtMs = now,
        addedAtMs = (item.updatedAtSec * 1000).takeIf { it > 0 } ?: now,
        sortIndex = 0,
        mediaType = runCatching { MediaKind.valueOf(item.mediaType.uppercase()) }
            .getOrDefault(MediaKind.AUDIO).name,
        serverId = item.id,
        serverSeq = item.updatedSeq,
        serverPage = item.page,
        serverUpdatedAtMs = (item.updatedAtSec * 1000).takeIf { it > 0 },
        syncState = LibraryMark.CLOUD_ONLY,
        accountKey = account,
        serverStorage = item.storage.ifBlank { null },
        confirmedTitle = item.title,
    )

    companion object {
        /** 与服务器 `limit` 默认值一致；一页 200 行在 2000 条规模下是 10 次请求。 */
        const val DEFAULT_PAGE = 200
    }
}
