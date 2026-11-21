package com.getmedia.app.data.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ExportTrack.refillable` 的口径测试。
 *
 * 背景（0.1.19）：以前只要 `syncState == "RESTORED"` 就算"可补下载"，于是本机导入的音频
 * （`platform=local`、`sourceUrl` 为空）也被塞进「先下载这 N 项」，点下去每一条都必然报
 * 「这条没有可用的来源链接」。这里的用例把四种行的口径钉死。
 */
class ExportTrackTest {

    private fun track(
        localPath: String? = null,
        serverId: Long? = null,
        syncState: String? = "RESTORED",
        sourceUrl: String = "",
        platform: String = "douyin",
        videoId: String = "7300000000000000000",
    ) = ExportTrack(
        id = 1L,
        title = "标题",
        mediaType = "AUDIO",
        localPath = localPath,
        serverId = serverId,
        syncState = syncState,
        sourceUrl = sourceUrl,
        platform = platform,
        videoId = videoId,
    )

    /** 1. 有文件：不需要下载。 */
    @Test
    fun filePresentIsNotRefillable() {
        val t = track(localPath = "/data/user/0/getmedia/files/a.audio", sourceUrl = "https://x/1")
        assertFalse(t.refillable)
    }

    /** 2. 云端行（有 serverId）缺文件：可下载，与链接无关。 */
    @Test
    fun cloudRowIsRefillable() {
        assertTrue(track(serverId = 42L, syncState = "BOUND").refillable)
    }

    /** 3. 恢复空壳带链接与身份：可下载。 */
    @Test
    fun restoredShellWithLinkIsRefillable() {
        val t = track(sourceUrl = "https://www.douyin.com/video/7300000000000000000")
        assertTrue(t.refillable)
        assertFalse(t.restoredLocalImport)
    }

    /** 4. 本机导入的空壳：不算可下载，且单列为"本机导入"。 */
    @Test
    fun localImportShellIsNotRefillable() {
        val t = track(platform = "local", videoId = "望", sourceUrl = "")
        assertFalse(t.refillable)
        assertTrue(t.restoredLocalImport)
    }

    /** 5. 空壳缺作品号：身份不足以回填，不算可下载（但也不是本机导入）。 */
    @Test
    fun shellWithoutIdentityIsNotRefillable() {
        val t = track(videoId = "")
        assertFalse(t.refillable)
        assertFalse(t.restoredLocalImport)
    }

    /** 6. 空壳缺链接：同样不可下载——这正是 30 条那次的直接原因。 */
    @Test
    fun shellWithoutLinkIsNotRefillable() {
        val t = track(platform = "bilibili")
        assertFalse(t.refillable)
        assertFalse(t.restoredLocalImport)
    }
}
