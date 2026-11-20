package com.getmedia.app.data.library

import androidx.room.withTransaction
import com.getmedia.app.data.backup.MetadataSnapshot
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LibraryCursorEntity
import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.TrackEntity

/**
 * 从服务器把曲库条目拉下来（P0-2 阶段 2）。
 *
 * 四条铁律，每一条都有对应用例：
 *
 * 1. **只新建**：只在本地没有这个键时插入一行 [LibraryMark.CLOUD_ONLY]；
 *    已有的行（`RESTORED` / `LOCAL_ONLY` / `SYNCED`）**一个字段都不动**，连 `updated_seq`
 *    比较都不做——那是阶段 4 匹配与阶段 5 合并的事。
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
        var added = 0
        var skipped = 0
        var tombstones = 0
        var pages = 0
        while (true) {
            val page = transport.pull(cursor, pageSize)
            pages++
            val applied = applyPage(account, page.items, cursor)
            added += applied.added
            skipped += applied.skipped
            tombstones += applied.tombstones
            cursor = applied.cursor
            // 空页但 has_more=true 是服务端的边界抖动，直接停，别打空请求
            if (!page.hasMore || page.items.isEmpty()) break
        }
        return Result(added = added, skipped = skipped, tombstones = tombstones, pages = pages, cursor = cursor)
    }

    private data class Applied(val added: Int, val skipped: Int, val tombstones: Int, val cursor: Long)

    /** 一页一个事务：写行与写水位要么一起生效，要么一起不生效。 */
    private suspend fun applyPage(account: String, items: List<CloudTrack>, since: Long): Applied =
        db.withTransaction {
            if (items.isEmpty()) return@withTransaction Applied(0, 0, 0, since)

            // 整表读一次建索引：同步只补缺失，靠 id 与 (platform, videoId, page) 双保险判重。
            // 双保险不是多余的——历史行可能用旧规则算过 id，键相同但 id 不同，只查 id 会插出重复。
            val rows = db.trackDao().allNow()
            val knownIds = rows.mapTo(HashSet()) { it.id }
            val knownKeys = rows.mapTo(HashSet()) { keyOf(it) }

            var added = 0
            var skipped = 0
            var tombstones = 0
            val fresh = ArrayList<TrackEntity>(items.size)
            val now = nowMs()

            for (item in items) {
                // 阶段 2 遇到墓碑什么都不做：本地有文件的那份不能因为云端删除而消失，
                // 批量确认与"是否同时删本机文件"是阶段 5 的事（已定 3）。
                if (item.deleted) { tombstones++; continue }

                val id = LocalIds.localId(item.platform, item.videoId, item.page)
                val key = Triple(item.platform.lowercase(), item.videoId, item.page)
                if (id in knownIds || key in knownKeys) { skipped++; continue }

                knownIds += id
                knownKeys += key
                fresh += entity(item, id, account, now)
                added++
            }

            // IGNORE 语义：万一判重漏了，也只会丢这条新行，绝不会覆盖已有行（REPLACE 会）
            if (fresh.isNotEmpty()) db.trackDao().insertIgnore(fresh)

            val next = (items.maxOfOrNull { it.updatedSeq } ?: since).coerceAtLeast(since)
            db.libraryAuxDao().putCursor(LibraryCursorEntity(account = account, lastPos = next, lastAt = now))
            Applied(added = added, skipped = skipped, tombstones = tombstones, cursor = next)
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
    )

    companion object {
        /** 与服务器 `limit` 默认值一致；一页 200 行在 2000 条规模下是 10 次请求。 */
        const val DEFAULT_PAGE = 200
    }
}
