package com.getmedia.app.data.cloud

import android.content.Context
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.library.LibraryMark
import com.getmedia.app.data.library.LibrarySyncRunner
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.repo.TrackRepository
import com.getmedia.app.data.serverextract.ServerMediaApi
import com.getmedia.app.data.serverextract.serverExtractClient
import kotlinx.coroutines.flow.StateFlow
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

    override val uploadAsk: StateFlow<CloudUploadAsk?> get() = LibrarySyncRunner.uploadAsk

    override fun awaitingDownload(track: TrackEntity): Boolean =
        track.syncState == LibraryMark.CLOUD_ONLY

    override suspend fun download(context: Context, track: TrackEntity): File {
        val source = track.sourceUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("这一条缺少原始链接，请重新提取")
        val repo = TrackRepository(context.applicationContext)
        val saved = serverExtractClient(repo, ServerMediaApi(), context.cacheDir)
            .extract(
                input = source,
                kind = MediaKind.of(track.mediaType),
                accountKey = SessionStore.current.value?.loginName,
            )
        return saved.localPath?.let(::File)
            ?: throw IllegalStateException("下载完成但没有落成本地文件")
    }

    override fun onForeground(context: Context) = LibrarySyncRunner.onForeground(context)

    override fun onAccountChanged(context: Context) = LibrarySyncRunner.onAccountChanged(context)

    override fun acceptUpload(context: Context) = LibrarySyncRunner.accept(context)

    override fun declineUpload(context: Context) = LibrarySyncRunner.decline(context)
}
