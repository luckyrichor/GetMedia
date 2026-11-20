package com.getmedia.app.data.library

import androidx.room.withTransaction
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity

internal enum class RestoreMatchIssue(val message: String) {
    LEGACY_ACCOUNT("备份未记录所属账号，请确认是否用当前账号核对"),
    IDENTITY_UNVERIFIED("作品、分集或媒体类型的信息不足或不一致，已保留原样"),
    INSUFFICIENT("缺少可核对的作品、分集、媒体类型或来源链接，已保留原样"),
    CONFLICT("作品、分集、媒体类型或关联记录存在冲突，已保留原样"),
    AMBIGUOUS("这一项在云端有多条可能匹配，已保留原样"),
    LOCAL_DUPLICATE("本机有多条恢复记录可能关联同一项，已保留原样"),
    TOMBSTONE("云端有这项的删除记录，已保留原样"),
    UNMATCHED("当前账号云端未找到匹配项，可以重新下载"),
}

internal data class RestoreMatchNotice(val id: Long, val title: String, val issue: RestoreMatchIssue)
internal data class RestoreMatchResult(val bound: Int = 0, val notices: List<RestoreMatchNotice> = emptyList())

internal fun TrackEntity.restoredKey() = restoredIdentity(platform, videoId, serverPage, sourceUrl, mediaType)
internal fun CloudTrack.restoredKey() = restoredIdentity(platform, videoId, page, sourceUrl, mediaType)
internal fun TrackEntity.needsRestoreMatch() =
    syncState == SyncState.RESTORED.name && serverId == null && localPath == null

internal class RestoredMatcher(
    private val db: AppDatabase,
    private val transport: LibrarySyncTransport,
    private val pageSize: Int = LibrarySyncEngine.DEFAULT_PAGE,
) {
    suspend fun match(account: String, includeLegacy: Boolean, valid: () -> Boolean): RestoreMatchResult {
        val dao = db.trackDao()
        val candidates = dao.allNow().filter { it.needsRestoreMatch() && it.belongsTo(account) }
        if (candidates.isEmpty()) return RestoreMatchResult()
        if (!includeLegacy && candidates.none { it.accountKey == account }) {
            return RestoreMatchResult(notices = candidates.map { it.notice(RestoreMatchIssue.LEGACY_ACCOUNT) })
        }
        val catalog = catalog(valid)
        return db.withTransaction {
            requireSession(valid)
            // Read inside the transaction so edits during paging and existing bindings are honored.
            val rows = dao.allNow()
            val waiting = rows.filter { it.needsRestoreMatch() && it.belongsTo(account) }
            val waitingKeys = waiting.groupingBy { it.restoredKey() }.eachCount()
            val occupied = rows.filter { it.belongsTo(account) }.mapNotNull { it.serverId }.toSet()
            val otherLocalKeys = rows.filter { it.belongsTo(account) && !it.needsRestoreMatch() }
                .mapNotNullTo(HashSet()) { it.restoredKey() }
            val keyed = catalog.groupBy { it.restoredKey() }
            val invalid = keyed[null].orEmpty().groupBy { it.platform.lowercase() to baseId(it.videoId) }
            val ids = catalog.groupBy { it.id }
            val notices = mutableListOf<RestoreMatchNotice>()
            var bound = 0
            for (row in waiting) {
                val key = row.restoredKey()
                val sameKey = keyed[key].orEmpty().takeIf { key != null }.orEmpty()
                val active = sameKey.filterNot { it.deleted }.sortedByDescending { it.updatedSeq }.distinctBy { it.id }
                val hit = active.singleOrNull()
                val issue = when {
                    row.accountKey == null && !includeLegacy -> RestoreMatchIssue.LEGACY_ACCOUNT
                    key == null -> RestoreMatchIssue.IDENTITY_UNVERIFIED
                    invalid[key.platform to key.videoId].orEmpty().isNotEmpty() -> RestoreMatchIssue.CONFLICT
                    waitingKeys.getValue(key) > 1 -> RestoreMatchIssue.LOCAL_DUPLICATE
                    active.size > 1 -> RestoreMatchIssue.AMBIGUOUS
                    sameKey.any { it.deleted } -> RestoreMatchIssue.TOMBSTONE
                    hit != null && (hit.id in occupied || ids.getValue(hit.id).any { it.restoredKey() != key }) ->
                        RestoreMatchIssue.CONFLICT
                    key in otherLocalKeys -> RestoreMatchIssue.LOCAL_DUPLICATE
                    hit == null && keyed.containsKey(key.copy(type = if (key.type == "audio") "video" else "audio")) ->
                        RestoreMatchIssue.CONFLICT
                    hit == null && row.sourceUrl.toHttpUrlOrNull() == null -> RestoreMatchIssue.INSUFFICIENT
                    hit == null -> RestoreMatchIssue.UNMATCHED
                    else -> null
                }
                if (issue != null) {
                    notices += row.notice(issue)
                    continue
                }
                checkNotNull(key)
                checkNotNull(hit)
                requireSession(valid)
                check(dao.updateExisting(row.copy(
                    serverId = hit.id,
                    serverSeq = hit.updatedSeq,
                    serverPage = key.page,
                    serverUpdatedAtMs = (hit.updatedAtSec * 1_000).takeIf { it > 0 },
                    accountKey = account,
                    syncState = SyncState.SYNCED.name,
                )) == 1)
                bound++
            }
            requireSession(valid)
            RestoreMatchResult(bound, notices)
        }
    }

    private suspend fun catalog(valid: () -> Boolean): List<CloudTrack> {
        val items = mutableListOf<CloudTrack>()
        var cursor = 0L
        while (true) {
            requireSession(valid)
            val page = transport.pull(cursor, pageSize)
            requireSession(valid)
            items += page.items
            if (!page.hasMore) return items
            val next = page.items.maxOfOrNull { it.updatedSeq }
            if (next == null || next <= cursor || page.nextSeq != next) {
                throw LibrarySyncException(LibrarySyncProblem.SERVER, "云端清单分页未完成")
            }
            cursor = next
        }
    }

    private fun requireSession(valid: () -> Boolean) {
        if (!valid()) throw LibrarySyncException(LibrarySyncProblem.LOGIN_REQUIRED, "账号或同步权限已变化")
    }

    private fun TrackEntity.belongsTo(account: String) = accountKey == null || accountKey == account
    private fun TrackEntity.notice(issue: RestoreMatchIssue) = RestoreMatchNotice(id, localTitleOverride ?: title, issue)
    private fun baseId(id: String) = id.substringBefore("-p")
}
