package com.getmedia.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 导出命名规则（`docs/export-plan.md` §4、§9.1 第 1–7、10 条）。
 */
class ExportNamingTest {

    private fun track(
        title: String,
        uploader: String? = null,
        addedAtMs: Long = 0L,
        format: String = "m4a",
        mediaType: String = "AUDIO",
        override: String? = null,
        id: Long = 1L,
    ) = ExportTrack(
        id = id,
        title = title,
        localTitleOverride = override,
        uploader = uploader,
        addedAtMs = addedAtMs,
        audioFormat = format,
        mediaType = mediaType,
    )

    /** 1. 非法字符换成空格，结尾的点与空格去掉。 */
    @Test
    fun illegalCharactersBecomeSpacesAndTrailingDotIsDropped() {
        val cleaned = ExportNaming.sanitize("a/b:c*?\"<>|.m4a")
        assertFalse("不该残留非法字符: $cleaned", cleaned.any { it in "\\/:*?\"<>|" })
        assertEquals("a b c .m4a", cleaned)

        assertEquals("名字", ExportNaming.sanitize("名字..."))
        assertEquals("名字", ExportNaming.sanitize("名字. . "))
        // 路径分隔符若被漏掉，等于把文件写到别的目录，这里单独盯一次
        assertFalse(ExportNaming.sanitize("a/b").contains('/'))
    }

    /** 2. 控制字符与连续空白都折叠成一个空格。 */
    @Test
    fun controlCharactersAndRunsOfWhitespaceCollapse() {
        assertEquals("a b", ExportNaming.sanitize("a\u0001  b"))
        assertEquals("a b c", ExportNaming.sanitize("a\t\n b\r c"))
        assertEquals("A B", ExportNaming.sanitize(" A   B "))
    }

    /** 3. 首尾空格与空名。 */
    @Test
    fun blankNameFallsBack() {
        assertEquals("未命名", ExportNaming.sanitize("  "))
        assertEquals("未命名", ExportNaming.sanitize(""))
        assertEquals("未命名", ExportNaming.sanitize(null))
        assertEquals("未命名", ExportNaming.sanitize("\u0000\u0001"))
        assertEquals("未命名.m4a", ExportNaming.fileName(track(title = "   ")))
    }

    /** 4. 长度截断：字符 ≤80、字节 ≤200，扩展名与日期后缀都保住。 */
    @Test
    fun longNameIsTruncatedByBothCharAndByteLimits() {
        val name = ExportNaming.fileName(track(title = "汉".repeat(200)))
        assertTrue("扩展名丢了: $name", name.endsWith(".m4a"))
        val stem = name.removeSuffix(".m4a")
        assertTrue("字符数超限: ${stem.length}", stem.codePointCount(0, stem.length) <= ExportNaming.MAX_NAME_CHARS)
        assertTrue("字节数超限: ${stem.toByteArray(Charsets.UTF_8).size}",
            stem.toByteArray(Charsets.UTF_8).size <= ExportNaming.MAX_NAME_BYTES)

        // 200 个汉字 = 600 字节，字节上限先到（200/3 = 66 字），不是 80
        assertEquals(66, stem.codePointCount(0, stem.length))

        // emoji 不能被切成半个代理对
        val emoji = ExportNaming.sanitize("🙂".repeat(150))
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= ExportNaming.MAX_NAME_BYTES)
        assertFalse(emoji.last().isHighSurrogate())

        // 入库日期后缀：按传入时区，格式 yyyy-MM-dd
        val dated = ExportNaming.fileName(
            track(title = "标题", addedAtMs = 1_700_000_000_000L),
            withDate = true,
            zone = ZoneOffset.ofHours(8),
        )
        assertEquals("标题 (2023-11-15).m4a", dated)
        // 未知入库时间不写日期，也不留空括号
        assertEquals("标题.m4a", ExportNaming.fileName(track(title = "标题"), withDate = true))
    }

    /** 5. Windows 保留名加下划线前缀（大小写不敏感）。 */
    @Test
    fun reservedNamesGetUnderscorePrefix() {
        assertEquals("_CON.m4a", ExportNaming.fileName(track(title = "CON")))
        assertEquals("_con.m4a", ExportNaming.fileName(track(title = "con")))
        assertEquals("_LPT9.m4a", ExportNaming.fileName(track(title = "LPT9")))
        assertEquals("_NUL.m4a", ExportNaming.fileName(track(title = "NUL")))
        // 只是以保留名开头的正常名字不该被改
        assertEquals("CONCERT.m4a", ExportNaming.fileName(track(title = "CONCERT")))
    }

    /** 6. 重名加序号，已有序号会被跳过。 */
    @Test
    fun duplicateNamesGetIncrementingIndex() {
        assertEquals("x (2).m4a", ExportNaming.uniqueName("x.m4a", listOf("x.m4a")))
        assertEquals("x (3).m4a", ExportNaming.uniqueName("x.m4a", listOf("x.m4a", "x (2).m4a")))
        assertEquals("x (2).m4a", ExportNaming.uniqueName("x.m4a", listOf("x.m4a", "x (3).m4a")))
        assertEquals("x.m4a", ExportNaming.uniqueName("x.m4a", emptyList()))

        // 序号用尽 → null，调用方记该项失败（不覆盖、不随机改名）
        val full = (2..ExportNaming.MAX_INDEX).map { "x ($it).m4a" }
        assertNull(ExportNaming.uniqueName("x.m4a", listOf("x.m4a") + full))
        assertEquals("x (999).m4a", ExportNaming.uniqueName("x.m4a", listOf("x.m4a") + (2..998).map { "x ($it).m4a" }))
    }

    /** 7. 大小写不敏感去重（SMB / 云盘 / Windows 目标上 Song 与 song 是同一个文件）。 */
    @Test
    fun duplicateCheckIsCaseInsensitive() {
        assertEquals("song (2).m4a", ExportNaming.uniqueName("song.m4a", listOf("Song.m4a")))
        assertEquals("SONG (2).m4a", ExportNaming.uniqueName("SONG.m4a", listOf("song.M4A")))
        assertEquals("x (3).m4a", ExportNaming.uniqueName("x.m4a", listOf("X.M4A", "x (2).m4a")))
    }

    /** 10. 子文件夹路径：按类型 + 按博主，层级固定。 */
    @Test
    fun subFolderPathIsFixedTwoOrThreeLevels() {
        val audio = track(title = "标题", uploader = "碧诗")
        val video = track(title = "标题", uploader = "碧诗", mediaType = "VIDEO")
        val unknown = track(title = "标题", uploader = null)

        assertEquals(listOf("音频", "碧诗"), ExportNaming.relativeDirs(audio, byType = true, byUploader = true))
        assertEquals(listOf("视频", "碧诗"), ExportNaming.relativeDirs(video, byType = true, byUploader = true))
        assertEquals(listOf("音频", "未知博主"), ExportNaming.relativeDirs(unknown, byType = true, byUploader = true))

        // 关闭时什么都不加；只开一项时只有一段
        assertEquals(emptyList<String>(), ExportNaming.relativeDirs(audio))
        assertEquals(listOf("视频"), ExportNaming.relativeDirs(video, byType = true))
        assertEquals(listOf("碧诗"), ExportNaming.relativeDirs(audio, byUploader = true))

        // 文件夹名在最前，且同样过清洗与 60 字符上限
        assertEquals(
            listOf("2026-11 导出", "音频", "碧诗"),
            ExportNaming.relativeDirs(audio, folderName = "2026-11 导出", byType = true, byUploader = true),
        )
        val longFolder = ExportNaming.relativeDirs(audio, folderName = "汉".repeat(100)).single()
        assertTrue(longFolder.length <= ExportNaming.MAX_FOLDER_CHARS)
        // 空白文件夹名视为"直接放根目录"，不生成「未命名」目录
        assertEquals(emptyList<String>(), ExportNaming.relativeDirs(audio, folderName = "   "))

        val item = ExportPlan.items(
            listOf(audio),
            folderName = "2026-11 导出",
            byType = true,
            byUploader = true,
        ).single()
        assertEquals("2026-11 导出/音频/碧诗/标题.m4a", item.relativePath)
    }

    /** 扩展名：空的补 m4a，白名单外的原样保留（不转码），并滤掉危险字符。 */
    @Test
    fun extensionKeepsUnknownFormatsAndFallsBackWhenEmpty() {
        assertEquals("m4a", ExportNaming.extension(null))
        assertEquals("m4a", ExportNaming.extension(""))
        assertEquals("m4a", ExportNaming.extension("  "))
        assertEquals("webm", ExportNaming.extension("WEBM"))
        assertEquals("mkv", ExportNaming.extension(".MKV"))
        assertEquals("m4a", ExportNaming.extension("../../etc/passwd"))
        assertEquals("m4a", ExportNaming.extension("非常长的假扩展名超过八个字"))
    }
}
