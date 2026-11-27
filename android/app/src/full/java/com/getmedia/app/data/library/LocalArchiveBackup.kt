package com.getmedia.app.data.library

import androidx.room.withTransaction
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.ArchiveState
import com.getmedia.app.data.local.TrackEntity
import java.io.File

/**
 * 把本机导入的文件（`platform = local`）备份到服务器归档池。
 *
 * 为什么只有这一类要走这条路：B 站/抖音的条目有原始链接，换手机时重新提取就行，
 * 服务器只放 1 天的缓存；**本机导入的文件没有任何地方能重新拉出来**，所以必须真的传上去，
 * 否则换机就是永久丢失。
 *
 * 三条纪律：
 *
 * 1. **只传不删**。传完本机那份原样保留，播放永远读本地文件——服务器是备份站，不是流媒体源。
 * 2. **失败分两类**。网络/限流/5xx 才按 2/5/15/60/300 秒退避重试，一轮 5 次都失败就冷却
 *    （10 分钟起、逐轮翻倍、最长 6 小时）；文件超大、云端空间不足、请求被拒这类重试一万次
 *    也不会成功，直接标成"无法备份"并停下，别每次切前台都白跑一遍。
 * 3. **先有记录再有文件**。上传前必须先有 `serverId`（元数据已同步），否则没有可挂的条目。
 */
class LocalArchiveBackup(
    private val db: AppDatabase,
    private val transport: LocalFileUploadTransport,
    private val account: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    data class Summary(
        val uploaded: Int = 0,
        val failed: Int = 0,
        val unable: Int = 0,
        val evicted: Int = 0,
        val stopped: LibrarySyncProblem? = null,
        val stopReason: String = "",
    )

    private val dao get() = db.trackDao()

    suspend fun run(): Summary {
        val evicted = markEvicted()
        var uploaded = 0
        var failed = 0
        var unable = 0
        var stopped: LibrarySyncProblem? = null
        var reason = ""

        val now = nowMs()
        val batch = dao.allNow().filter { pending(it, now) }.sortedBy { it.addedAtMs }
        for (track in batch) {
            val file = track.localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0 } ?: continue
            try {
                writeDone(track.id, transport.uploadFile(track.serverId!!, file))
                uploaded++
            } catch (e: LibrarySyncException) {
                when {
                    // 登录失效/接口不可用：这一批停手，等下次进前台再说（队列原样保留）
                    e.problem == LibrarySyncProblem.LOGIN_REQUIRED ||
                        e.problem == LibrarySyncProblem.NOT_AVAILABLE -> {
                        stopped = e.problem
                        reason = reasonOf(e)
                        break
                    }
                    permanent(e) -> { markUnable(track.id, reasonOf(e)); unable++ }
                    else -> { writeFailure(track.id, e); failed++ }
                }
            }
        }
        return Summary(uploaded, failed, unable, evicted, stopped, reason)
    }

    /**
     * 一条要不要现在传。**只认 `local`**：平台条目没有本机导入的原件，不该被卷进来。
     *
     * `accountKey` 必须对得上，否则会把上一个账号导入的文件传到这个账号下面。
     */
    internal fun pending(track: TrackEntity, now: Long): Boolean {
        if (!track.platform.equals("local", ignoreCase = true)) return false
        if (track.serverId == null || track.serverId <= 0) return false
        if (track.syncState == LibraryMark.DETACHED) return false
        if (track.accountKey != account) return false
        if (ArchiveState.of(track.archiveState) != ArchiveState.PENDING) return false
        if (track.archiveNextAt > now) return false
        val file = track.localPath?.let(::File) ?: return false
        return file.isFile && file.length() > 0
    }

    /** 重试也没用的那几类：请求被拒、条目没了、空间不够、文件本身有问题。 */
    private fun permanent(e: LibrarySyncException): Boolean = when (e.problem) {
        LibrarySyncProblem.INPUT,
        LibrarySyncProblem.TRACK_NOT_FOUND,
        LibrarySyncProblem.TRACK_DELETED,
        LibrarySyncProblem.LIBRARY_FULL,
        LibrarySyncProblem.ARCHIVE_PROTECTED,
        LibrarySyncProblem.VERSION_CONFLICT -> true
        // 409 里认不出来的多半是空间问题（quota_exceeded / storage_disabled）：重试也是白试。
        // 只认这两个码，别的 409/5xx 仍然按"以后还能成功"重试。
        else -> e.code == "quota_exceeded" || e.code == "storage_disabled"
    }

    /** 云端那份备份被淘汰掉了：手机上还在、服务器上已经没有了，必须让用户看见。 */
    private suspend fun markEvicted(): Int {
        var n = 0
        db.withTransaction {
            for (row in dao.allNow()) {
                if (!row.platform.equals("local", ignoreCase = true)) continue
                if (ArchiveState.of(row.archiveState) != ArchiveState.DONE) continue
                if (row.serverStorage == "archive") continue
                if (row.accountKey != account) continue
                dao.updateExisting(
                    row.copy(
                        archiveState = ArchiveState.EVICTED.name,
                        archiveNote = "云端备份已被清理，手机上的文件还在",
                    ),
                )
                n++
            }
        }
        return n
    }

    private suspend fun writeDone(id: Long, cloud: CloudTrack) = db.withTransaction {
        val row = dao.byId(id)?.takeIf { it.accountKey == account } ?: return@withTransaction
        dao.updateExisting(
            row.copy(
                archiveState = ArchiveState.DONE.name,
                archiveTries = 0,
                archiveNextAt = 0,
                archiveNote = null,
                // 服务器侧刚写完，存储类型与版本都以它回的为准
                serverStorage = cloud.storage.ifBlank { "archive" },
                serverSeq = cloud.updatedSeq.takeIf { it > 0 } ?: row.serverSeq,
                serverUpdatedAtMs = (cloud.updatedAtSec * 1000).takeIf { it > 0 } ?: row.serverUpdatedAtMs,
            ),
        )
    }

    private suspend fun writeFailure(id: Long, e: LibrarySyncException) = db.withTransaction {
        val row = dao.byId(id) ?: return@withTransaction
        val tries = row.archiveTries + 1
        val wait = waitAfter(tries, e.retryAfterSeconds)
        dao.updateExisting(
            row.copy(
                archiveState = ArchiveState.PENDING.name,
                archiveTries = tries,
                archiveNextAt = nowMs() + wait,
                archiveNote = if (tries % ROUND == 0) "暂时无法备份到云端，稍后自动重试" else "备份未完成，稍后重试",
            ),
        )
    }

    private suspend fun markUnable(id: Long, reason: String) = db.withTransaction {
        val row = dao.byId(id) ?: return@withTransaction
        dao.updateExisting(
            row.copy(
                archiveState = ArchiveState.UNABLE.name,
                archiveNextAt = 0,
                archiveNote = reason,
            ),
        )
    }

    private fun reasonOf(e: LibrarySyncException): String = when {
        e.code == "quota_exceeded" -> "云端空间不足，这个文件没能备份"
        e.code == "storage_disabled" -> "服务器没有开启长期存储"
        e.problem == LibrarySyncProblem.TRACK_DELETED -> "这条在云端已被删除"
        e.problem == LibrarySyncProblem.TRACK_NOT_FOUND -> "云端找不到这条记录"
        e.problem == LibrarySyncProblem.LIBRARY_FULL -> "云端曲库已满，请先在网页端清理"
        e.problem == LibrarySyncProblem.LOGIN_REQUIRED -> "登录已失效"
        e.problem == LibrarySyncProblem.NETWORK -> "网络不可用"
        e.problem == LibrarySyncProblem.SERVER -> "服务器暂时不可用"
        else -> e.message.ifBlank { "备份未完成" }
    }

    companion object {
        /** 一轮的重试次数：与用户定的一致（失败 5 次后进入冷却）。 */
        internal const val ROUND = 5

        /** 一轮之内的退避：2 / 5 / 15 / 60 / 300 秒。 */
        internal val BACKOFF_MS = longArrayOf(2_000, 5_000, 15_000, 60_000, 300_000)

        internal const val COOLDOWN_BASE_MS = 10 * 60 * 1000L
        internal const val COOLDOWN_MAX_MS = 6 * 60 * 60 * 1000L

        /**
         * 第 [tries] 次连续失败之后要等多久。
         *
         * 一轮内按固定表退避；一轮满（第 5、10、15… 次）后进入冷却并**逐轮翻倍**，
         * 最长 6 小时——否则一个 6 GB 的文件会每次切前台都重跑一遍，纯耗电耗流量。
         */
        internal fun waitAfter(tries: Int, retryAfterSeconds: Long? = null): Long {
            val base = if (tries % ROUND != 0) {
                BACKOFF_MS[(tries - 1).coerceIn(0, ROUND - 1) % ROUND]
            } else {
                val rounds = (tries / ROUND - 1).coerceIn(0, 10)
                (COOLDOWN_BASE_MS shl rounds).coerceAtMost(COOLDOWN_MAX_MS)
            }
            // 服务器明说了"多久之后再来"，就取更大的那个，别比它更早重试
            return maxOf(base, (retryAfterSeconds ?: 0L) * 1000)
        }
    }
}
