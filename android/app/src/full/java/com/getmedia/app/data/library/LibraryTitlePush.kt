package com.getmedia.app.data.library

import androidx.room.withTransaction
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.RemoteMarkEntity
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity

/**
 * 把本机改的标题以条件 PATCH 推给服务器（P0-2 阶段 5）。
 *
 * 规则：
 * - **服务器为准**。本机草稿只带"改名前看到的版本"（`pendingTitleBaseSeq`）；
 *   服务器版本比基线新时，服务器赢：草稿作废、采用服务器标题与序号。
 * - 响应丢失后不盲目重发：先 `GET /v2/tracks/{id}` 核对；标题已是目标值就按成功收尾。
 * - 墓碑：清掉草稿并把墓碑记进待确认表；不 PATCH 已删除的行。
 * - 世代：只写回发起时那一代；迟到的旧响应不能覆盖后来的一次改名。
 * - 旧服务器没有专用路径（裸 404）→ 保留草稿、整批停，部署后自愈；**不回退到旧 PATCH**。
 * - 每次调用最多处理一个账号的一轮，失败是"停"不是"跳"，避免把 429/离线打成风暴。
 */
class LibraryTitlePush(
    private val db: AppDatabase,
    private val transport: LibraryWriteTransport,
    private val account: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val valid: () -> Boolean = { true },
) {

    data class Summary(
        val pushed: Int = 0,
        val adopted: Int = 0,
        val conflicts: Int = 0,
        val stopped: LibrarySyncProblem? = null,
        val reason: String = "",
    )

    suspend fun push(): Summary {
        var pushed = 0
        var adopted = 0
        var conflicts = 0
        val rows = db.trackDao().allNow()
            .filter { it.accountKey == account && it.pendingTitle != null }
            .sortedBy { it.pendingTitleGen ?: 0L }
        for (row in rows) {
            if (!valid()) return Summary(pushed, adopted, conflicts, LibrarySyncProblem.LOGIN_REQUIRED, "登录已失效")
            val serverId = row.serverId?.takeIf { it > 0 } ?: continue
            val title = row.pendingTitle ?: continue
            val base = row.pendingTitleBaseSeq ?: row.serverSeq ?: continue
            val gen = row.pendingTitleGen ?: 0L
            try {
                val detail = transport.detail(serverId)
                when {
                    detail.deleted -> { tombstoned(row, detail, gen); conflicts++; continue }
                    detail.updatedSeq != base && detail.title == title -> {
                        adopt(row, detail, gen); adopted++
                    }
                    detail.updatedSeq != base -> {
                        // 服务器在我们编辑之后又改了：服务器赢，草稿作废
                        adopt(row, detail, gen); conflicts++
                    }
                    else -> {
                        val patched = transport.patchTitle(serverId, title, base)
                        adopt(row, patched, gen); pushed++
                    }
                }
            } catch (e: LibrarySyncException) {
                when (e.problem) {
                    LibrarySyncProblem.VERSION_CONFLICT -> {
                        // 竞态：检查后、PATCH 前服务器又变了。重新核对一次，不拿新版本重试。
                        val detail = runCatching { transport.detail(serverId) }.getOrNull()
                        when {
                            detail == null -> return Summary(pushed, adopted, conflicts, LibrarySyncProblem.VERSION_CONFLICT, e.message.orEmpty())
                            detail.deleted -> { tombstoned(row, detail, gen); conflicts++ }
                            detail.title == title -> { adopt(row, detail, gen); adopted++ }
                            else -> { adopt(row, detail, gen); conflicts++ }
                        }
                    }
                    LibrarySyncProblem.TRACK_DELETED, LibrarySyncProblem.TRACK_NOT_FOUND -> {
                        tombstoned(row, null, gen); conflicts++
                    }
                    LibrarySyncProblem.ARCHIVE_PROTECTED, LibrarySyncProblem.INPUT -> {
                        // 服务端拒绝标题本身：保留草稿，等用户重新编辑（不自动改标题）
                        conflicts++
                        continue
                    }
                    LibrarySyncProblem.NETWORK, LibrarySyncProblem.RATE_LIMITED,
                    LibrarySyncProblem.SERVER, LibrarySyncProblem.NOT_AVAILABLE,
                    LibrarySyncProblem.LOGIN_REQUIRED, LibrarySyncProblem.LIBRARY_FULL,
                    LibrarySyncProblem.TOMBSTONE -> {
                        return Summary(pushed, adopted, conflicts, e.problem, e.message.orEmpty())
                    }
                }
            }
        }
        return Summary(pushed, adopted, conflicts)
    }

    /** 以服务器返回的条目为准收尾：清草稿、写 confirmedTitle、回到 SYNCED。 */
    private suspend fun adopt(row: TrackEntity, detail: CloudTrack, gen: Long) = db.withTransaction {
        val current = db.trackDao().byId(row.id) ?: return@withTransaction
        if (current.pendingTitleGen != gen) return@withTransaction   // 后来又改过，别覆盖新草稿
        db.trackDao().updateExisting(
            current.copy(
                title = detail.title,
                confirmedTitle = detail.title,
                localTitleOverride = null,
                pendingTitle = null,
                pendingTitleBaseSeq = null,
                pendingTitleGen = null,
                serverSeq = detail.updatedSeq,
                serverPage = detail.page,
                serverUpdatedAtMs = (detail.updatedAtSec * 1000).takeIf { it > 0 },
                serverStorage = detail.storage.ifBlank { current.serverStorage },
                syncState = SyncState.SYNCED.name,
                uploader = detail.uploader,
                durationSec = detail.durationSec,
                sourceUrl = detail.sourceUrl.ifBlank { current.sourceUrl },
                updatedAtMs = nowMs(),
            ),
        )
    }

    /** 云端墓碑：清草稿，记待确认（有文件/整理痕迹时），不删任何本机文件。 */
    private suspend fun tombstoned(row: TrackEntity, detail: CloudTrack?, gen: Long) = db.withTransaction {
        val current = db.trackDao().byId(row.id) ?: return@withTransaction
        if (current.pendingTitleGen != gen) return@withTransaction
        val seq = detail?.updatedSeq ?: current.serverSeq ?: 0L
        db.trackDao().updateExisting(
            current.copy(
                pendingTitle = null,
                pendingTitleBaseSeq = null,
                pendingTitleGen = null,
                syncState = if (current.syncState == LibraryMark.PENDING_UP) SyncState.SYNCED.name else current.syncState,
            ),
        )
        if (current.serverId != null) {
            db.libraryAuxDao().addMark(
                RemoteMarkEntity(
                    account = account, remoteId = current.serverId, platform = current.platform,
                    videoKey = com.getmedia.app.data.local.LocalIds.key(
                        current.platform,
                        com.getmedia.app.data.backup.MetadataSnapshot.videoIdOf(current),
                        com.getmedia.app.data.backup.MetadataSnapshot.pageOf(current),
                    ),
                    title = current.localTitleOverride ?: current.title,
                    seq = seq, seenAt = nowMs(),
                ),
            )
        }
    }
}
