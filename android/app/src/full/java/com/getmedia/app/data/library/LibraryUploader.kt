package com.getmedia.app.data.library

import androidx.room.withTransaction
import com.getmedia.app.data.backup.MetadataSnapshot
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.ArchiveState
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.delay
import java.io.File

/**
 * 把"本机新增"逐条推到服务器（P0-2 阶段 3）。
 *
 * 准入只有一条（`SyncState.mayUploadToLibrary`：`LOCAL_ONLY` **且**本地文件在），
 * 另外三类行会被显式挡掉，因为它们各有各的归宿：
 *
 * - `CLOUD_ONLY`：服务器上本来就有，传上去没有意义；
 * - `LIB_DETACHED`：用户选了"保留本机文件、和云端脱钩"，**不自动回传**（否则删了又长回来）；
 * - `LIB_PENDING_UP`：只改了标题，走阶段 5 的 PATCH，不走新建。
 *
 * 写回纪律：**只能 `existing.copy(...)`**。`upsertAll` 是 `REPLACE`，整行覆盖；拿候选时的旧快照
 * 直接写回，会把期间发生的收藏、分类、重命名一起抹掉，所以每次写回前按 id 重读一次。
 */
class LibraryUploader(
    private val db: AppDatabase,
    private val transport: LibraryUploadTransport,
    private val account: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val allowCandidate: (Long) -> Boolean = { true },
) {

    /** 一轮上传的结果。`stopped` 非空表示整批停手（下次重跑，幂等）。 */
    data class Summary(
        val uploaded: Int = 0,
        val adopted: Int = 0,
        val conflicted: Int = 0,
        val failed: Int = 0,
        val stopped: LibrarySyncProblem? = null,
        val stopReason: String = "",
    ) {
        val attempted: Int get() = uploaded + conflicted + failed
        val isEmpty: Boolean get() = attempted == 0
    }

    /** 该账号下"只在这台手机上"的条目；顺序按入库时间，先传老的（可预期、便于排查）。 */
    suspend fun candidates(): List<TrackEntity> =
        db.trackDao().allNow()
            .filter { eligible(it) }
            .sortedBy { it.addedAtMs }

    /**
     * 串行上传。并发 2 在 60/分的限流下收益很小，而串行让"整批停"的语义干净：
     * 停在哪一条、下一条是谁都不用额外记。
     */
    suspend fun upload(): Summary {
        val list = candidates()
        if (list.isEmpty()) return Summary()

        var uploaded = 0
        var adopted = 0
        var conflicted = 0
        var failed = 0
        var stopped: LibrarySyncProblem? = null
        var stopReason = ""

        for (track in list) {
            val created = try {
                createWithBackoff(track)
            } catch (e: LibrarySyncException) {
                when (e.problem) {
                    // 云端已删：不删本地文件、不回传，攒着给阶段 5 的批量确认
                    LibrarySyncProblem.TRACK_DELETED -> { conflicted++; continue }
                    // 4xx 业务错（400/404/422）：这一条本身有问题，跳过它，别拖住其余
                    LibrarySyncProblem.INPUT -> { failed++; continue }
                    // 其余（429/5xx/网络/会话失效）都是"现在不适合继续"，整批停，保留 LOCAL_ONLY
                    else -> {
                        stopped = e.problem
                        stopReason = reasonOf(e.problem)
                        break
                    }
                }
            }
            writeBack(track.id, created)
            if (created.created) uploaded++ else adopted++
        }

        return Summary(uploaded, adopted, conflicted, failed, stopped, stopReason)
    }

    /** 429 按 `Retry-After` 退避**一次**；再撞就交给调用方整批停。 */
    private suspend fun createWithBackoff(track: TrackEntity): CreatedTrack = try {
        transport.create(entryOf(track))
    } catch (e: LibrarySyncException) {
        if (e.problem != LibrarySyncProblem.RATE_LIMITED) throw e
        sleep((e.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_S).coerceIn(1, MAX_RETRY_AFTER_S) * 1000)
        transport.create(entryOf(track))
    }

    private suspend fun writeBack(id: Long, created: CreatedTrack) = db.withTransaction {
        val current = db.trackDao().byId(id)?.takeIf(::eligible) ?: return@withTransaction
        val server = created.track
        db.trackDao().updateExisting(
            current.copy(
                serverId = server.id,
                serverSeq = server.updatedSeq,
                serverPage = server.page,
                serverUpdatedAtMs = (server.updatedAtSec * 1000).takeIf { it > 0 },
                syncState = SyncState.SYNCED.name,
                accountKey = account,
                serverStorage = server.storage.ifBlank { "metadata" },
                // 服务器已有同键（created=false）：以服务器为准改标题，但本机改过名的不动
                title = if (created.created || current.localTitleOverride != null) current.title else server.title,
                confirmedTitle = server.title,
                // 建库接口只收 8 个字段，不带收藏与类别；这一行若有本机整理信息，
                // 标成待推送，让同一轮的整理信息推送把它补上去（没有整理信息就不用多打一次 PATCH）。
                metaDirty = current.favorite || current.localCategoryId != MetadataSnapshot.DEFAULT_CATEGORY_ID,
                // 本机导入的条目：元数据一落地就有地方挂文件了，排进备份队列
                // （真正的上传由 [LocalArchiveBackup] 在同一轮里接着做）。
                archiveState = if (current.platform.equals("local", ignoreCase = true) &&
                    ArchiveState.of(current.archiveState) == ArchiveState.NONE
                ) ArchiveState.PENDING.name else current.archiveState,
                updatedAtMs = nowMs(),
            ),
        )
        Unit
    }

    internal fun eligible(t: TrackEntity): Boolean {
        if (t.serverId != null || !allowCandidate(t.id)) return false
        when (t.syncState) {
            LibraryMark.CLOUD_ONLY, LibraryMark.PENDING_UP, LibraryMark.DETACHED -> return false
        }
        val file = t.localPath?.let(::File)
        val hasFile = file != null && file.isFile && file.length() > 0
        if (!SyncState.mayUploadToLibrary(SyncState.of(t.syncState), hasFile)) return false
        // 别的账号的行：不推。null 是本机新增（还没归属），可以推。
        if (t.accountKey != null && t.accountKey != account) return false
        // 没有原始链接 / 键不全的行传上去也是个查不回来的空壳。
        // **本机导入（local）例外**：它本来就没有作品页链接，文件就是唯一来源，
        // 服务器上那份归档才是换机时的取回途径。
        if (t.sourceUrl.isNullOrBlank() && !t.platform.equals("local", ignoreCase = true)) return false
        if (t.platform.isBlank() || MetadataSnapshot.videoIdOf(t).isBlank()) return false
        return true
    }

    private fun entryOf(t: TrackEntity): UploadEntry {
        val identity = t.restoredKey()
        return UploadEntry(
            platform = identity?.platform ?: t.platform,
            videoId = identity?.videoId ?: MetadataSnapshot.videoIdOf(t),
            page = identity?.page ?: MetadataSnapshot.pageOf(t),
            mediaType = if (MediaKind.of(t.mediaType) == MediaKind.VIDEO) "video" else "audio",
            title = (t.localTitleOverride ?: t.title).trim().take(MAX_TITLE),
            uploader = t.uploader?.trim()?.take(MAX_UPLOADER)?.ifBlank { null },
            durationSec = t.durationSec?.takeIf { it > 0 },
            sourceUrl = t.sourceUrl.orEmpty().trim().take(MAX_URL),
        )
    }

    private fun reasonOf(problem: LibrarySyncProblem): String = when (problem) {
        LibrarySyncProblem.RATE_LIMITED -> "同步太频繁，稍后重试"
        LibrarySyncProblem.NETWORK -> "网络不可用，稍后重试"
        LibrarySyncProblem.LOGIN_REQUIRED -> "登录已失效，请重新登录"
        LibrarySyncProblem.NOT_AVAILABLE -> "当前账号无法同步，或服务器还没开这个功能"
        LibrarySyncProblem.LIBRARY_FULL -> "云端曲库已满，请先在网页端清理"
        LibrarySyncProblem.SERVER -> "服务器暂时不可用，稍后重试"
        else -> "上传未完成，稍后重试"
    }

    private companion object {
        const val DEFAULT_RETRY_AFTER_S = 5L
        const val MAX_RETRY_AFTER_S = 30L
        /** 与服务端 `TrackCreateBody` 的上限对齐，省得因为超长被 422 挡下。 */
        const val MAX_TITLE = 500
        const val MAX_UPLOADER = 100
        const val MAX_URL = 2048
    }
}
