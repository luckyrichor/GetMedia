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
}
