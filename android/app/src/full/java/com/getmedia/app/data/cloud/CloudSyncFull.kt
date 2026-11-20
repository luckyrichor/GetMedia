package com.getmedia.app.data.cloud

import android.content.Context
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.backup.BackupManager
import com.getmedia.app.data.library.DownloadCenter
import com.getmedia.app.data.library.LibraryLocalEdits
import com.getmedia.app.data.library.LibraryMark
import com.getmedia.app.data.library.LibrarySyncRunner
import com.getmedia.app.data.library.LibrarySyncGate
import com.getmedia.app.data.library.LibrarySyncPrefs
import com.getmedia.app.data.library.RepairRules
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.repo.TrackRepository
import com.getmedia.app.data.serverextract.ServerMediaApi
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

    /**
     * 统一下载入口（阶段 6）。分派规则见 [RepairRules] / [DownloadCenter]：
     * B 站云端行走服务器、抖音云端行与恢复空壳走本机提取，落库永远按原主键回填。
     */
    override suspend fun download(context: Context, track: TrackEntity): File = downloadLock.withLock {
        val session = SessionStore.current.value
        check(session != null && track.accountKey == session.loginName && LibrarySyncGate.maySync(session)) {
            "请使用原账号并同意最新隐私说明后再下载"
        }
        fun sameSession(): Boolean = SessionStore.current.value.let {
            it?.token == session.token && it.loginName == session.loginName && LibrarySyncGate.maySync(it)
        }
        val app = context.applicationContext
        val repo = TrackRepository(app)
        val api = ServerMediaApi(token = { session.token.takeIf { sameSession() } })
        DownloadCenter(
            app = app,
            repo = repo,
            api = api,
            prefs = LibrarySyncPrefs(app),
            session = session,
            sameSession = ::sameSession,
        ).download(track)
    }

    override fun repairOptions(track: TrackEntity): RepairOptions = RepairRules.options(
        track = track,
        // 菜单里的判断可以读磁盘：`localPath` 指向的文件可能已经被系统清理。
        hasFile = track.localPath?.let { File(it).let { f -> f.isFile && f.length() > 0 } } == true,
    )

    override fun resetRestoreDecision(context: Context, account: String) =
        BackupManager.resetRestoreDecision(account)

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
