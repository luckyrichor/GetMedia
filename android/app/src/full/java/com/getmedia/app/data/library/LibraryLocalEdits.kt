package com.getmedia.app.data.library

import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.local.TrackRemovalEntity

/**
 * 本机删除 / 改名的"入队"规则（P0-2 阶段 5）。
 *
 * 只有**真正绑定在当前账号上**的行才允许进队列：
 * - 正的服务器 ID 与已见版本（否则没有 If-Match 可用）；
 * - 账号匹配；
 * - 状态是 `SYNCED` 或 `PENDING_UP`（`RESTORED` / `DETACHED` / 纯本机都不算）；
 * - 不是本机剪辑派生行（`sourceTrackId` / `locallyEdited`）；
 * - 平台条目的 `archive` 行不入队（服务端也会拒绝，这里只是少发一次必然被拒的请求）；
 *   **本机导入（`local`）的归档行可以删**——那份文件就是这台手机传上去的备份，
 *   手机端删条目就该连云端备份一起删。
 *
 * 删除的入队调用发生在 [com.getmedia.app.data.repo.TrackRepository.removeAll] 的事务里：
 * 队列写入与删行、播放列表引用清理同生共死，不会出现"行删了但删除没记下来"。
 */
object LibraryLocalEdits {

    fun canQueueCloudDelete(track: TrackEntity, account: String?): Boolean {
        if (account.isNullOrBlank()) return false
        if (track.serverId == null || track.serverId <= 0) return false
        if (track.serverSeq == null || track.serverSeq <= 0) return false
        if (track.accountKey != account) return false
        if (track.syncState != SyncState.SYNCED.name && track.syncState != LibraryMark.PENDING_UP) return false
        if (track.serverStorage == "archive" && !isLocal(track)) return false
        if (track.sourceTrackId != null || track.locallyEdited) return false
        return track.restoredKey() != null
    }

    /** 归档行里只有"平台资产"受保护；本机导入的那份是用户自己的备份，可以删。 */
    fun isKnownArchive(track: TrackEntity): Boolean =
        track.serverStorage == "archive" && !isLocal(track)

    private fun isLocal(track: TrackEntity): Boolean = track.platform.equals("local", ignoreCase = true)

    /**
     * 入队；调用方必须已经在 Room 事务里。返回实际入队的条数。
     *
     * 同一个键重复删除用 REPLACE 覆盖成一代新操作；`generation` 取"当前时间"，保证比旧的大，
     * 迟到的旧响应只清得掉旧世代。
     */
    suspend fun queue(db: AppDatabase, account: String, tracks: List<TrackEntity>,
              cloudIds: Set<Long>, nowMs: Long): Int {
        var queued = 0
        tracks.filter { it.id in cloudIds && canQueueCloudDelete(it, account) }.forEach { track ->
            val identity = track.restoredKey() ?: return@forEach
            val videoKey = LocalIds.key(identity.platform, identity.videoId, identity.page)
            val previous = db.libraryAuxDao().removal(account, identity.platform, videoKey)
            val generation = maxOf(nowMs, (previous?.generation ?: 0L) + 1)
            db.libraryAuxDao().addRemoval(
                TrackRemovalEntity(
                    platform = identity.platform,
                    videoKey = videoKey,
                    account = account,
                    remoteId = track.serverId,
                    requestedAt = nowMs,
                    expectedSeq = track.serverSeq,
                    generation = generation,
                    state = LibraryMark.REMOVAL_QUEUED,
                ),
            )
            queued++
        }
        return queued
    }

    /**
     * 本机改名：绑定行只写草稿，不动已确认标题；返回 true 表示已由同步逻辑接管。
     *
     * - `pendingTitle` = 新标题（显示用）；
     * - `pendingTitleBaseSeq` = 第一次改名前看到的服务器版本（多次改名不重置）；
     * - `pendingTitleGen` = 本次操作世代；
     * - `confirmedTitle` = 上一次服务器确认的标题，草稿不会覆盖它。
     *
     * 纯本机 / `RESTORED` / `DETACHED` 行返回 false，调用方走原来的本机改名。
     */
    suspend fun markTitleEdited(db: AppDatabase, track: TrackEntity, title: String, nowMs: Long): Boolean {
        if (track.serverId == null || track.serverId <= 0) return false
        if (track.accountKey == null) return false
        if (track.serverSeq == null || track.serverSeq <= 0) return false
        if (track.syncState != SyncState.SYNCED.name && track.syncState != LibraryMark.PENDING_UP) return false
        val clean = title.trim()
        if (clean.isEmpty()) return false
        db.trackDao().updateExisting(
            track.copy(
                title = clean,
                localTitleOverride = clean,
                confirmedTitle = track.confirmedTitle ?: track.title,
                pendingTitle = clean,
                pendingTitleBaseSeq = track.pendingTitleBaseSeq ?: track.serverSeq,
                pendingTitleGen = nowMs,
                syncState = LibraryMark.PENDING_UP,
                updatedAtMs = nowMs,
            ),
        )
        return true
    }
}
