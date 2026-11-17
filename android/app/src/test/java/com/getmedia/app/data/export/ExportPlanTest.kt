package com.getmedia.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 导出计划（`docs/export-plan.md` §3、§5.3、§9.1 第 8、9、11、12 条）。
 */
class ExportPlanTest {

    private var nextId = 1L

    private fun track(
        mediaType: String = "AUDIO",
        bytes: Long? = 1_000_000L,
        title: String = "标题",
        format: String = "m4a",
        localPath: String? = "/data/user/0/getmedia/files/a.audio",
        id: Long? = null,
    ) = ExportTrack(
        id = id ?: nextId++,
        title = title,
        audioFormat = format,
        mediaType = mediaType,
        audioBytes = bytes,
        localPath = localPath,
    )

    private fun mixed(audio: Int, video: Int): List<ExportTrack> =
        List(audio) { track(mediaType = "AUDIO") } + List(video) { track(mediaType = "VIDEO", format = "mp4") }

    /** 8. 混选分别计数。 */
    @Test
    fun mixedSelectionIsCountedByType() {
        val counts = ExportPlan.classify(mixed(9, 3))
        assertEquals(9, counts.audio)
        assertEquals(3, counts.video)
        assertEquals(12, counts.total)
        assertFalse(counts.isEmpty)

        assertTrue(ExportPlan.classify(emptyList()).isEmpty)
        // mediaType 里出现未知值时按音频算（库内只有两种，不猜格式）
        assertEquals(1, ExportPlan.classify(listOf(track(mediaType = "OTHER"))).audio)
    }

    /** 9. 空间三态：够 / 不够 / provider 给不出（-1、0）。 */
    @Test
    fun spaceCheckHasThreeStates() {
        assertEquals(ExportPlan.SpaceVerdict.ENOUGH, ExportPlan.spaceCheck(100L, 200L))
        assertEquals(ExportPlan.SpaceVerdict.ENOUGH, ExportPlan.spaceCheck(100L, 100L))
        assertEquals(ExportPlan.SpaceVerdict.INSUFFICIENT, ExportPlan.spaceCheck(486L * 1024 * 1024, 120L * 1024 * 1024))
        // 云盘 provider 常返回 -1；0 也视为"给不出"，不能当成"空间不足"直接拦死
        assertEquals(ExportPlan.SpaceVerdict.UNKNOWN, ExportPlan.spaceCheck(100L, -1L))
        assertEquals(ExportPlan.SpaceVerdict.UNKNOWN, ExportPlan.spaceCheck(100L, 0L))

        // 需求侧 2% 余量，向上取整；空列表不产生需求
        assertEquals(0L, ExportPlan.requiredBytes(emptyList()))
        assertEquals(1020L, ExportPlan.withMargin(1000L))
        assertEquals(2L, ExportPlan.withMargin(1L))
        assertEquals(3_000_000L, ExportPlan.totalBytes(mixed(2, 1)))
        assertEquals(3_060_000L, ExportPlan.requiredBytes(mixed(2, 1)))
        // audioBytes 为空按 0 计（写入阶段以真实文件长度为准）
        assertEquals(0L, ExportPlan.totalBytes(listOf(track(bytes = null))))
    }

    /** 11. MIME 映射：白名单外一律 octet-stream（不转码）。 */
    @Test
    fun mimeMapping() {
        assertEquals("audio/mp4", ExportPlan.mimeOf("m4a"))
        assertEquals("audio/mpeg", ExportPlan.mimeOf(".MP3"))
        assertEquals("video/mp4", ExportPlan.mimeOf("mp4"))
        assertEquals("video/x-matroska", ExportPlan.mimeOf("mkv"))
        assertEquals("application/octet-stream", ExportPlan.mimeOf("xyz"))
        assertEquals("application/octet-stream", ExportPlan.mimeOf(""))
        // webm 既可能是音频也可能是视频，按作品类型分
        assertEquals("video/webm", ExportPlan.mimeOf("webm", "VIDEO"))
        assertEquals("audio/webm", ExportPlan.mimeOf("webm", "AUDIO"))
    }

    /** 12. 失败归类：固定文案，不带异常消息、不带路径。 */
    @Test
    fun failureReasonsAreFixedTextsWithoutPaths() {
        assertEquals("目录授权已失效，请重新选择", ExportPlan.failureReason(SecurityException("no permission for /storage/emulated/0/x")))
        assertEquals("目标目录不可用，请重新选择", ExportPlan.failureReason(FileNotFoundException("/sdcard/Download/x.m4a")))
        assertEquals("目标空间不足", ExportPlan.failureReason(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals("目标空间不足", ExportPlan.failureReason(IOException("No space left on device")))
        assertEquals("写入失败", ExportPlan.failureReason(IOException("broken pipe")))
        assertEquals("导出失败", ExportPlan.failureReason(IllegalStateException("/sdcard/foo")))

        val all = listOf(
            SecurityException("/storage/emulated/0/Music/a.m4a"),
            FileNotFoundException("/sdcard/Download/b.m4a"),
            IOException("ENOSPC /data/local/tmp"),
            IOException("x"),
            RuntimeException("/home/user/x"),
        ).map { ExportPlan.failureReason(it) }

        all.forEach { reason ->
            assertFalse("文案里出现了路径分隔符: $reason", reason.contains('/') || reason.contains('\\'))
            assertFalse("文案里出现了文件名后缀: $reason", reason.contains(".m4a"))
            assertTrue(reason.isNotBlank())
        }
        // 本机文件缺失是另一条固定文案，走摘要时不与异常混用
        assertEquals("本机文件缺失，需要重新提取", ExportPlan.REASON_MISSING_LOCAL)
    }

    /** 选择集去重：同一 id 只保留首次出现，顺序不变。 */
    @Test
    fun duplicateIdsAreCollapsedKeepingOrder() {
        val a = track(title = "A", id = 7L)
        val b = track(title = "B", id = 8L)
        val dup = track(title = "A 再来一次", id = 7L)
        assertEquals(listOf(7L, 8L), ExportPlan.dedupe(listOf(a, b, dup)).map { it.id })
        // items 也走去重，避免同一作品被写两次
        assertEquals(2, ExportPlan.items(listOf(a, b, dup)).size)
    }

    /** items：命名与目录都定下来，重名留到写入阶段（那要查目标目录）。 */
    @Test
    fun itemsCarryDirsAndNames() {
        val one = track(title = "标题", format = "m4a")
        val item = ExportPlan.items(
            listOf(one),
            folderName = "导出",
            byType = true,
            withDate = false,
        ).single()
        assertEquals(listOf("导出", "音频"), item.dirs)
        assertEquals("标题.m4a", item.fileName)
        assertEquals("导出/音频/标题.m4a", item.relativePath)
    }
}
