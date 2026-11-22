package com.getmedia.app.data.cloud

import android.content.Context
import com.getmedia.app.account.SessionStore
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
import com.getmedia.app.data.repo.DiagnosticsStore
import com.getmedia.app.data.repo.ErrorText
import com.getmedia.app.data.repo.TrackRepository
import com.getmedia.app.data.serverextract.ServerMediaApi
import kotlinx.coroutines.CancellationException
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

    /**
     * 列表标签的判定（0.1.16 起与菜单同口径）。
     *
     * 先看磁盘：`localPath` 指向的文件真实存在就不算"云端未下载"。之前只看库字段，
     * 而菜单（[repairOptions]）读磁盘，字段/磁盘一旦不一致就会出现
     * "菜单里没有『下载到本机』、列表还标着云端未下载"的自相矛盾。
     */
    override fun awaitingDownload(track: TrackEntity): Boolean {
        if (track.localPath?.let { File(it).let { f -> f.isFile && f.length() > 0 } } == true) return false
        return track.syncState == LibraryMark.CLOUD_ONLY ||
            (track.syncState == SyncState.SYNCED.name && track.serverId != null)
    }

    /**
     * 统一下载入口（阶段 6）。分派规则见 [RepairRules] / [DownloadCenter]：
     * B 站云端行走服务器、抖音云端行与恢复空壳走本机提取，落库永远按原主键回填。
     *
     * 失败一律换成能给人看的一句话抛出（[ErrorText]），原始异常只写进诊断日志，
     * 且只记"条目号 + 异常类型"，不记 message —— message 里可能带链接参数或凭据。
     */
    override suspend fun download(context: Context, track: TrackEntity): File = downloadLock.withLock {
        try {
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            DiagnosticsStore.event(
                "下载到本机",
                "#${track.id} ${error.javaClass.simpleName} · ${ErrorText.humanMessage(error)}",
                success = false,
            )
            throw DownloadFailed(ErrorText.humanMessage(error), error)
        }
    }

    override fun repairOptions(track: TrackEntity): RepairOptions = RepairRules.options(
        track = track,
        // 菜单里的判断可以读磁盘：`localPath` 指向的文件可能已经被系统清理。
        hasFile = track.localPath?.let { File(it).let { f -> f.isFile && f.length() > 0 } } == true,
    )

    override fun managesRestores(session: SessionStore.Session?) = LibrarySyncGate.isOwner(session)

    override val supportsManagedRestores = true

    override fun protectsRecovery(track: TrackEntity, session: SessionStore.Session?) =
        (track.syncState == SyncState.RESTORED.name && (track.accountKey != null || managesRestores(session))) ||
            awaitingDownload(track)

    override fun onBackupRestored(context: Context) = LibrarySyncRunner.onBackupRestored(context)

    override fun onForeground(context: Context) = LibrarySyncRunner.onForeground(context)

    override fun onLocalChange(context: Context) = LibrarySyncRunner.onLocalChange(context)

    override fun onBackground() = LibrarySyncRunner.onBackground()

    override fun onAccountChanged(context: Context) = LibrarySyncRunner.onAccountChanged(context)

    override fun dismissUpload(ask: CloudUploadAsk) = LibrarySyncRunner.dismiss(ask)

    override fun acceptUpload(context: Context, ask: CloudUploadAsk) = LibrarySyncRunner.accept(context, ask)

    override fun declineUpload(context: Context, ask: CloudUploadAsk) = LibrarySyncRunner.decline(context, ask)

    override fun keepRemoteRemoved(context: Context, ask: CloudRemovedAsk) =
        LibrarySyncRunner.keepRemoteRemoved(context, ask)

    override fun dismissRemoteRemoved(ask: CloudRemovedAsk) = LibrarySyncRunner.dismissRemoteRemoved(ask)
}

/**
 * 下载失败：[message] 已经是可以直接给人看的一句话（见 [ErrorText]），
 * 原始异常留在 [cause] 里只给诊断日志用，不往界面上抛。
 */
class DownloadFailed(message: String, cause: Throwable) : IllegalStateException(message, cause)
