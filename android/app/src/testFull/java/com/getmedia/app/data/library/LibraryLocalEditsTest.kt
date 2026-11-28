package com.getmedia.app.data.library

import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 删除入队的判定（阶段 5 + 0.1.12 的归档修正）。
 *
 * 关键点：归档行**不给**"同时从云端移除"的选项；存储类型未知时仍给选项，由服务端的
 * `archive_protected` 兜底（本机删除照常完成，行随后被标成 archive）。
 */
class LibraryLocalEditsTest {

    private val account = "boss"

    private fun bound(storage: String?) = TrackEntity(
        id = -7, platform = "bilibili", videoId = "BV1", title = "这一条",
        uploader = "UP", durationSec = 10.0, audioBytes = 32, audioFormat = "m4a",
        sourceUrl = "https://www.bilibili.com/video/BV1", localPath = "/files/-7.audio",
        syncState = SyncState.SYNCED.name, serverId = 5, serverSeq = 9,
        accountKey = account, serverStorage = storage,
    )

    @Test fun archiveRowNeverOffersCloudRemoval() {
        val track = bound("archive")
        assertTrue(LibraryLocalEdits.isKnownArchive(track))
        assertFalse(LibraryLocalEdits.canQueueCloudDelete(track, account))
    }

    @Test fun unknownStorageStillOffersCloudRemovalAndLetsServerDecide() {
        val track = bound(null)
        assertFalse("存储类型不知道时不能假装它是归档", LibraryLocalEdits.isKnownArchive(track))
        assertTrue("未知也要给勾选项，服务端用 archive_protected 兜底",
            LibraryLocalEdits.canQueueCloudDelete(track, account))
    }

    /**
     * 本机导入的归档行**可以**连云端一起删。
     *
     * 这条以前是坏的：判定最后一句是 `restoredKey() != null`，而 `restoredIdentity` 只认
     * bilibili / douyin，`local` 落到 `else -> null`，于是本机导入的条目一条都进不了删除
     * 队列——手机上删了，云端行与归档文件原地不动，另一台手机还能看到、还能下回来。
     */
    @Test fun localImportedArchiveRowCanBeRemovedFromCloud() {
        val track = imported()
        assertFalse("本机导入的那份是用户自己的备份，不是受保护的平台资产",
            LibraryLocalEdits.isKnownArchive(track))
        assertTrue(LibraryLocalEdits.canQueueCloudDelete(track, account))
    }

    /** 平台条目的归档行仍然不给"同时从云端移除"，别被上一个用例带松。 */
    @Test fun platformArchiveRowIsStillProtected() {
        assertFalse(LibraryLocalEdits.canQueueCloudDelete(bound("archive"), account))
    }

    /** 没有稳定身份的（videoId 为空）不猜：宁可只删本机，也不要拿空键写队列。 */
    @Test fun localRowWithoutStableIdStaysLocalOnly() {
        assertFalse(LibraryLocalEdits.canQueueCloudDelete(imported().copy(videoId = ""), account))
    }

    /** 类型认不出来的（占位行）同样不进队列：队列键里带类型，猜错会串到别的条目上。 */
    @Test fun localRowWithoutKnownTypeStaysLocalOnly() {
        assertFalse(LibraryLocalEdits.canQueueCloudDelete(imported().copy(mediaType = ""), account))
    }

    /** 换账号不许拿别人的队列去删。 */
    @Test fun otherAccountLocalRowIsIgnored() {
        assertFalse(LibraryLocalEdits.canQueueCloudDelete(imported(), "someone-else"))
    }

    private fun imported() = TrackEntity(
        id = -9, platform = "local", videoId = "loc_6f1c", title = "自己录的一段",
        uploader = null, durationSec = 12.0, audioBytes = 4096, audioFormat = "mp3",
        sourceUrl = "", localPath = "/files/-9.audio", mediaType = "audio",
        syncState = SyncState.SYNCED.name, serverId = 82, serverSeq = 23,
        accountKey = account, serverStorage = "archive",
    )
}
