package com.getmedia.app.data.library

import androidx.room.withTransaction
import com.getmedia.app.data.backup.MetadataSnapshot
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.TrackRemovalEntity
import com.getmedia.app.data.local.TrackEntity
import java.io.File

/**
 * 重放"本机删除 → 云端曲库"的待办队列（P0-2 阶段 5）。
 *
 * 四条不能破的纪律：
 *
 * 1. **任何分支都不删本机文件**。删除行与文件是用户在本机确认删除时做的；
 *    重放只改云端，成功后最多把"用户随后又添加回来的那条"脱钩，绝不碰它的文件。
 * 2. **条件写**：每条都带 `If-Match`（发起删除时看到的 `expectedSeq`）。版本变了就
 *    进 `WAITING`，不自动改用旧的无条件接口，也不拿新版本偷偷重试。
 * 3. **先删后加**：发送前重读本机；同键已经有"删除之后重新添加"的文件，就取消这次删除。
 *    请求已经发出、结果未知时，下一轮通过服务器状态确认；确认删除已生效，则把新条目
 *    脱钩（保留文件、不再自动上传）。
 * 4. **世代**：只清掉发起时那一代记录。迟到的旧响应不能把用户后来的新操作清掉。
 *
 * 队列持久化在 Room（`track_removals`），重启后继续；离线/网络失败保留原状态。
 */
class LibraryRemovalReplay(
    private val db: AppDatabase,
    private val transport: LibraryWriteTransport,
    private val account: String,
    private val valid: () -> Boolean = { true },
) {

    data class Summary(
        val replayed: Int = 0,
        val waiting: Int = 0,
        val cancelled: Int = 0,
        val stopped: LibrarySyncProblem? = null,
        val reason: String = "",
    )

    suspend fun replay(): Summary {
        val dao = db.libraryAuxDao()
        var replayed = 0
        var waiting = 0
        var cancelled = 0
        for (op in dao.removals(account)) {
            if (!valid()) return Summary(replayed, waiting, cancelled, LibrarySyncProblem.LOGIN_REQUIRED, "登录已失效")
            // 归档保护 / 版本冲突是"待用户处理"，不是自动重试项；用户再次删除会重置为 QUEUED
            if (op.state == LibraryMark.REMOVAL_WAITING) {
                waiting++
                continue
            }
            val remoteId = op.remoteId
            val expected = op.expectedSeq
            if (remoteId == null || remoteId <= 0 || expected == null || expected <= 0) {
                markWaiting(op, "缺少云端编号或版本，已暂停")
                waiting++
                continue
            }

            // 先删后加（请求尚未发出）：同键已有删除之后重新添加的文件 → 取消这次删除。
            // 已经是 SENDING 的不取消：请求可能已经到达服务器，必须按远端状态核对。
            if (op.state == LibraryMark.REMOVAL_QUEUED) {
                val readded = readdedLocally(op)
                if (readded != null) {
                    dao.dropRemovalForGeneration(op.platform, op.videoKey, op.account, op.generation)
                    cancelled++
                    continue
                }
            }
            // 记下"已发出"再发请求：崩溃恢复后才知道不能按"未发出"处理
            markState(op, LibraryMark.REMOVAL_SENDING)

            try {
                transport.delete(remoteId, expected)
                finish(op, deleted = true)
                replayed++
            } catch (e: LibrarySyncException) {
                when (e.problem) {
                    // 业务码 404 / 已知墓碑：这条已经不存在，删除目标已达成，结项
                    LibrarySyncProblem.TRACK_NOT_FOUND, LibrarySyncProblem.TRACK_DELETED -> {
                        finish(op, deleted = true)
                        replayed++
                    }
                    LibrarySyncProblem.VERSION_CONFLICT -> {
                        val detail = runCatching { transport.detail(remoteId) }.getOrNull()
                        if (detail?.deleted == true) {
                            finish(op, deleted = true)
                            replayed++
                        } else {
                            markWaiting(op, "云端条目已变化，已暂停删除")
                            waiting++
                        }
                    }
                    LibrarySyncProblem.ARCHIVE_PROTECTED -> {
                        markWaiting(op, "云端归档会保留，未从云端移除")
                        waiting++
                    }
                    LibrarySyncProblem.INPUT -> {
                        markWaiting(op, e.message.ifBlank { "请求未被接受，已暂停" })
                        waiting++
                    }
                    // 这些是"现在不适合继续"：保留队列，下次同步重试
                    LibrarySyncProblem.NETWORK, LibrarySyncProblem.RATE_LIMITED,
                    LibrarySyncProblem.SERVER, LibrarySyncProblem.NOT_AVAILABLE,
                    LibrarySyncProblem.LOGIN_REQUIRED -> {
                        return Summary(replayed, waiting, cancelled, e.problem, e.message.orEmpty())
                    }
                    else -> {
                        markWaiting(op, e.message.ifBlank { "删除未完成，已暂停" })
                        waiting++
                    }
                }
            }
        }
        return Summary(replayed, waiting, cancelled)
    }

    /** 删除成功后：若用户在本机重新添加了同键文件，把它脱钩，避免下一轮又自动传回云端。 */
    private suspend fun finish(op: TrackRemovalEntity, deleted: Boolean) = db.withTransaction {
        if (!deleted) return@withTransaction
        val dao = db.libraryAuxDao()
        // 只有发起时那一代还有效才收尾
        val current = dao.removal(op.account, op.platform, op.videoKey)
        if (current?.generation != op.generation) return@withTransaction
        val row = trackFor(op)
        if (row != null && row.serverId == null && readdedAfter(row, op) && fileExists(row)) {
            // 云端删除已经生效，这条新条目不能再自动上传；文件留着，成为"本机独有"
            db.trackDao().updateExisting(
                row.copy(
                    syncState = LibraryMark.DETACHED,
                    serverId = null,
                    serverSeq = null,
                    serverUpdatedAtMs = null,
                    accountKey = null,
                    pendingTitle = null,
                    pendingTitleBaseSeq = null,
                    pendingTitleGen = null,
                ),
            )
        }
        dao.dropRemovalForGeneration(op.platform, op.videoKey, op.account, op.generation)
    }

    private suspend fun markState(op: TrackRemovalEntity, state: String) {
        db.libraryAuxDao().markRemoval(
            op.platform, op.videoKey, op.account, op.generation, state, op.attempts, op.lastError,
        )
    }

    private suspend fun markWaiting(op: TrackRemovalEntity, reason: String) {
        db.libraryAuxDao().markRemoval(
            op.platform, op.videoKey, op.account, op.generation,
            LibraryMark.REMOVAL_WAITING, op.attempts + 1, reason,
        )
    }

    /** 同键本机行；只认当前账号（或尚未归属的重新添加行）。 */
    private suspend fun trackFor(op: TrackRemovalEntity): TrackEntity? =
        db.trackDao().allNow().firstOrNull {
            it.platform.equals(op.platform, ignoreCase = true) &&
                LocalIds.key(it.platform, MetadataSnapshot.videoIdOf(it),
                    MetadataSnapshot.pageOf(it)) == op.videoKey &&
                (it.accountKey == null || it.accountKey == account)
        }

    private suspend fun readdedLocally(op: TrackRemovalEntity): TrackEntity? {
        val row = trackFor(op) ?: return null
        if (row.serverId != null) return null          // 已经重新绑定，不是"删除后重新添加"
        return row.takeIf { readdedAfter(it, op) && fileExists(it) }
    }

    private fun readdedAfter(row: TrackEntity, op: TrackRemovalEntity): Boolean =
        row.addedAtMs > op.requestedAt

    private fun fileExists(row: TrackEntity): Boolean =
        row.localPath?.let { File(it).isFile && File(it).length() > 0 } == true
}
