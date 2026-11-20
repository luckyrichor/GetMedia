package com.getmedia.app.data.cloud

import android.content.Context
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.library.LibraryLocalEdits
import com.getmedia.app.data.library.LibraryMark
import com.getmedia.app.data.library.LibrarySyncRunner
import com.getmedia.app.data.library.LibrarySyncGate
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.repo.TrackRepository
import com.getmedia.app.data.serverextract.ServerMediaApi
import com.getmedia.app.data.serverextract.serverExtractClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 完整版：接真实同步器与服务器提取。
 *
 * 两件事在这里落地：
 *
 * - "云端未下载"的判定与下载入口。判定用 [LibraryMark.CLOUD_ONLY] 原值比较；下载走
 *   `data/serverextract` 的服务器提取（`ServerExtractClient`），落库走 `adoptStaged`，
 *   因为 id 由 `LocalIds` 派生，下载完仍然写回**同一行**，收藏、类别、重命名都保住。
 * - 触发点：前台与账号变化都转给 [LibrarySyncRunner]，闸门（隐私同意、档位）由它判。
 */
val cloudSync: CloudSyncHook = object : CloudSyncHook {
    private val downloadLock = Mutex()

    override val uploadAsk: StateFlow<CloudUploadAsk?> get() = LibrarySyncRunner.uploadAsk

    override val remoteRemovedAsk: StateFlow<CloudRemovedAsk?> get() = LibrarySyncRunner.remoteRemovedAsk

    override fun canQueueCloudDelete(track: TrackEntity, session: SessionStore.Session?): Boolean =
        session != null && LibrarySyncGate.maySync(session) &&
            LibraryLocalEdits.canQueueCloudDelete(track, session.loginName)

    override fun isCloudArchive(track: TrackEntity): Boolean = LibraryLocalEdits.isKnownArchive(track)

    override suspend fun queueRemovals(db: AppDatabase, account: String, tracks: List<TrackEntity>,
                                       cloudIds: Set<Long>, nowMs: Long): Int =
        LibraryLocalEdits.queue(db, account, tracks, cloudIds, nowMs)

    override suspend fun onTitleEdited(db: AppDatabase, track: TrackEntity, title: String, nowMs: Long): Boolean =
        LibraryLocalEdits.markTitleEdited(db, track, title, nowMs)

    override fun awaitingDownload(track: TrackEntity): Boolean =
        track.syncState == LibraryMark.CLOUD_ONLY ||
            (track.syncState == SyncState.SYNCED.name && track.serverId != null && track.localPath == null)

    override suspend fun download(context: Context, track: TrackEntity): File = downloadLock.withLock {
        val session = SessionStore.current.value
        check(session != null && track.accountKey == session.loginName && LibrarySyncGate.maySync(session)) {
            "请使用原账号并同意最新隐私说明后再下载"
        }
        fun sameSession(): Boolean = SessionStore.current.value.let {
            it?.token == session.token && it.loginName == session.loginName && LibrarySyncGate.maySync(it)
        }
        val repo = TrackRepository(context.applicationContext)
        repo.download(track).getOrNull()?.let { return@withLock it }
        val api = ServerMediaApi(token = { session.token.takeIf { sameSession() } })
        val saved = serverExtractClient(repo, api, context.cacheDir).refillOriginal(track) { original, detail, file ->
            repo.fillExistingFile(original, file.file, file.bytes, file.sha256, detail.updatedSeq,
                detail.updatedAtSec?.times(1000), ::sameSession)
        }
        saved.localPath?.let(::File)
            ?: throw IllegalStateException("下载完成但没有落成本地文件")
    }

    override fun managesRestores(session: SessionStore.Session?) = LibrarySyncGate.isOwner(session)

    override val supportsManagedRestores = true

    override fun protectsRecovery(track: TrackEntity, session: SessionStore.Session?) =
        (track.syncState == SyncState.RESTORED.name && (track.accountKey != null || managesRestores(session))) ||
            awaitingDownload(track)

    override fun onBackupRestored(context: Context) = LibrarySyncRunner.onBackupRestored(context)

    override fun onForeground(context: Context) = LibrarySyncRunner.onForeground(context)

    override fun onAccountChanged(context: Context) = LibrarySyncRunner.onAccountChanged(context)

    override fun dismissUpload(ask: CloudUploadAsk) = LibrarySyncRunner.dismiss(ask)

    override fun acceptUpload(context: Context, ask: CloudUploadAsk) = LibrarySyncRunner.accept(context, ask)

    override fun declineUpload(context: Context, ask: CloudUploadAsk) = LibrarySyncRunner.decline(context, ask)

    override fun keepRemoteRemoved(context: Context, ask: CloudRemovedAsk) =
        LibrarySyncRunner.keepRemoteRemoved(context, ask)

    override fun dismissRemoteRemoved(ask: CloudRemovedAsk) = LibrarySyncRunner.dismissRemoteRemoved(ask)
}
