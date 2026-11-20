package com.getmedia.app.data.library

import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 6 的分派表：缺文件的一条该走哪条路、菜单里该出现什么。
 *
 * 全是纯函数断言——不碰网络、不碰磁盘、不建库。真机上的落库由各条路径自己核对身份，
 * 这里只把"什么情况给什么动作"钉死。
 */
class RepairRulesTest {

    private fun row(
        id: Long = 1,
        platform: String = "bilibili",
        videoId: String = "BV1x",
        sourceUrl: String = "https://www.bilibili.com/video/BV1x",
        localPath: String? = null,
        serverId: Long? = null,
        syncState: String = SyncState.LOCAL_ONLY.name,
        page: Int = 1,
    ) = TrackEntity(
        id = id, platform = platform, videoId = videoId, title = "第 $id 条",
        uploader = null, durationSec = 10.0, audioBytes = null, audioFormat = "m4a",
        sourceUrl = sourceUrl, localPath = localPath, serverId = serverId,
        syncState = syncState, serverPage = page, mediaType = MediaKind.AUDIO.name,
    )

    @Test fun localFilePresentGivesNoRepairAction() {
        val track = row(localPath = "/files/1.audio")
        assertEquals(DownloadRoute.NONE, RepairRules.route(track, hasFile = true))
        val options = RepairRules.options(track, hasFile = true)
        assertFalse(options.canDownload)
        assertFalse(options.canRemoveShell)
        assertNull(options.note)
    }

    @Test fun cloudBilibiliRowGoesToServer() {
        val track = row(serverId = 6, syncState = LibraryMark.CLOUD_ONLY)
        assertEquals(DownloadRoute.SERVER_TARGET, RepairRules.route(track, hasFile = false))
        val options = RepairRules.options(track, hasFile = false)
        assertTrue(options.canDownload)
        assertFalse("云端行不是空壳，不给移除", options.canRemoveShell)
    }

    @Test fun cloudDouyinRowExtractsOnDevice() {
        val track = row(platform = "douyin", videoId = "7", serverId = 9, syncState = LibraryMark.CLOUD_ONLY)
        assertEquals(DownloadRoute.SERVER_LOCAL, RepairRules.route(track, hasFile = false))
        assertTrue(RepairRules.options(track, hasFile = false).canDownload)
    }

    @Test fun restoredShellWithLinkCanBeFetchedOrRemoved() {
        val track = row(serverId = null, syncState = SyncState.RESTORED.name)
        assertEquals(DownloadRoute.SHELL_LOCAL, RepairRules.route(track, hasFile = false))
        val options = RepairRules.options(track, hasFile = false)
        assertTrue(options.canDownload)
        assertTrue(options.canRemoveShell)
    }

    @Test fun restoredShellWithoutLinkOnlyOffersRemove() {
        val track = row(serverId = null, syncState = SyncState.RESTORED.name, sourceUrl = "")
        assertEquals(DownloadRoute.NONE, RepairRules.route(track, hasFile = false))
        val options = RepairRules.options(track, hasFile = false)
        assertFalse(options.canDownload)
        assertTrue(options.canRemoveShell)
        assertTrue("原因要说清是缺来源链接，而不是文件丢了", options.note!!.contains("来源链接"))
    }

    /** 有 `localPath` 但文件已被系统清掉：不能按空壳回填（那条路要求 localPath 为空），只给移除。 */
    @Test fun restoredRowWhoseFileDisappearedOnlyOffersRemove() {
        val track = row(serverId = null, syncState = SyncState.RESTORED.name, localPath = "/files/gone.audio")
        assertEquals(DownloadRoute.NONE, RepairRules.route(track, hasFile = false))
        val options = RepairRules.options(track, hasFile = false)
        assertFalse(options.canDownload)
        assertTrue(options.canRemoveShell)
    }

    /** 本机提取出来的行文件丢了：既不是云端行也不是空壳，一个补救动作都不给（要重新提取）。 */
    @Test fun lostLocalExtractionHasNoRepairAction() {
        val track = row(serverId = null, syncState = SyncState.LOCAL_ONLY.name)
        assertEquals(DownloadRoute.NONE, RepairRules.route(track, hasFile = false))
        val options = RepairRules.options(track, hasFile = false)
        assertFalse(options.any)
    }

    /** 空壳但作品/分集/类型信息不足（`restoredKey` 为空）：不摆必然失败的下载按钮。 */
    @Test fun shellWithoutIdentityCannotBeFetched() {
        val track = row(serverId = null, syncState = SyncState.RESTORED.name, videoId = "")
        val options = RepairRules.options(track, hasFile = false)
        assertFalse(options.canDownload)
        assertTrue(options.canRemoveShell)
        assertTrue(options.note!!.contains("身份"))
    }
}
