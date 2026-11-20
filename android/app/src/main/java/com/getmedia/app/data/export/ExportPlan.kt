package com.getmedia.app.data.export

import java.io.FileNotFoundException
import java.io.IOException
import java.time.ZoneId
import java.util.Locale

/**
 * 导出计划的**纯函数**部分：分类计数、大小估算、空间三态判定、MIME 映射、失败归类。
 *
 * 与 [ExportNaming] 的分工：这里负责"导哪些、要多大、能不能开始、失败怎么说"，
 * 命名与目录层级全在 [ExportNaming]。两者都不碰文件系统，便于穷举单测。
 */
object ExportPlan {

    /** 需求侧留的余量（%）：文件名元数据、provider 的簇对齐等，2% 足够且不至于误拦。 */
    const val MARGIN_PERCENT = 2L

    /** 音频格式白名单（只用于 MIME 判定，不影响落盘扩展名）。 */
    private val AUDIO_MIME = mapOf(
        "m4a" to "audio/mp4",
        "mp3" to "audio/mpeg",
        "aac" to "audio/aac",
        "flac" to "audio/flac",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
    )

    private val VIDEO_MIME = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
    )

    /** 分类计数：确认页要分别报数（"音频 9 项 · 视频 3 项"）。 */
    data class Counts(val audio: Int, val video: Int) {
        val total: Int get() = audio + video
        val isEmpty: Boolean get() = total == 0
    }

    /** 空间判定的三种结果：云盘 provider 常返回 -1，不能用二值判断。 */
    enum class SpaceVerdict { ENOUGH, INSUFFICIENT, UNKNOWN }

    /** 一项待写出的文件：相对目录层级 + 文件名。 */
    data class Item(val track: ExportTrack, val dirs: List<String>, val fileName: String) {
        /** 用于展示与摘要的相对路径（不带所选根目录）。 */
        val relativePath: String get() = (dirs + fileName).joinToString("/")
    }

    /**
     * 按 id 去重，保留首次出现的顺序。跨来源合并（多选 + 播放列表 + 类别）时用；
     * 同一作品在库里只有一条记录，这里只防同一次选择里重复进来。
     */
    fun dedupe(tracks: List<ExportTrack>): List<ExportTrack> {
        val seen = HashSet<Long>(tracks.size * 2)
        return tracks.filter { seen.add(it.id) }
    }

    /** 只按 `mediaType` 二分，不猜格式。 */
    fun classify(tracks: List<ExportTrack>): Counts {
        var audio = 0
        var video = 0
        tracks.forEach { if (it.isVideo) video++ else audio++ }
        return Counts(audio, video)
    }

    /** 需求侧字节数：`audioBytes` 为空按 0 计（写入阶段以真实文件长度为准）。 */
    fun totalBytes(tracks: List<ExportTrack>): Long {
        var sum = 0L
        tracks.forEach { item ->
            val bytes = item.audioBytes ?: 0L
            if (bytes > 0) sum += bytes
        }
        return sum
    }

    /** 加上余量后的需求字节数（向上取整）。 */
    fun requiredBytes(tracks: List<ExportTrack>): Long = withMargin(totalBytes(tracks))

    fun withMargin(bytes: Long): Long =
        if (bytes <= 0L) 0L else (bytes * (100 + MARGIN_PERCENT) + 99) / 100

    /**
     * 空间判定。`available <= 0` 视为"provider 给不出"（云盘常见），走 [SpaceVerdict.UNKNOWN]。
     *
     * UNKNOWN **不拦**：确认页只显示"无法确认剩余空间，导出时若空间不足会自动中止"，
     * 不禁用"开始导出"，也不再弹二次确认（`docs/export-plan.md` §5.3）。
     * 真的写到一半空间不足时由 [ExportWriter.Outcome.noSpace] 让整批中止。
     */
    fun spaceCheck(required: Long, available: Long): SpaceVerdict = when {
        available <= 0L -> SpaceVerdict.UNKNOWN
        available < required -> SpaceVerdict.INSUFFICIENT
        else -> SpaceVerdict.ENOUGH
    }

    /** 扩展名 → MIME。白名单外一律 `application/octet-stream`（不转码，能不能播取决于目标设备）。 */
    fun mimeOf(extension: String, mediaType: String? = null): String {
        val ext = extension.trim().removePrefix(".").lowercase(Locale.ROOT)
        val isVideo = mediaType == "VIDEO"
        if (ext == "webm") return if (isVideo) "video/webm" else "audio/webm"
        VIDEO_MIME[ext]?.let { return it }
        AUDIO_MIME[ext]?.let { return it }
        return "application/octet-stream"
    }

    /** 本机文件缺失（快照里 `localPath` 为 null，或开始写时文件已被删）。 */
    const val REASON_MISSING_LOCAL = "本机文件缺失，需要重新提取"

    /**
     * 云端行还没下载到本机（`localPath` 为 null 但 `serverId` 有值）。
     *
     * 与 [REASON_MISSING_LOCAL] 分开：这条不是丢了，是还没拿下来，去下载就能导；
     * 说成"需要重新提取"会让人把服务器上已有的一条重提取一遍，白等一遍还多一份文件。
     */
    const val REASON_NOT_DOWNLOADED = "尚未下载到本机，请先下载"

    /**
     * 失败归类：只返回**固定文案**，绝不拼接异常消息或路径——异常消息里常带用户目录名。
     * 返回值直接用于摘要页与诊断计数。
     */
    fun failureReason(error: Throwable): String = when {
        error is SecurityException -> "目录授权已失效，请重新选择"
        error is FileNotFoundException -> "目标目录不可用，请重新选择"
        isNoSpace(error) -> "目标空间不足"
        error is IOException -> "写入失败"
        else -> "导出失败"
    }

    /**
     * ENOSPC 在不同 provider 上的表现不同：有的给 errno 字符串，有的只给英文描述。
     * 公开给 [ExportWriter] 判"这一项是空间不足"——那类失败要中止整批，不能逐项重试。
     */
    fun isNoSpace(error: Throwable): Boolean {
        val text = error.message.orEmpty().lowercase(Locale.ROOT)
        return text.contains("enospc") || text.contains("no space left") || text.contains("空间不足")
    }

    /**
     * 生成待写清单：分类与命名都确定，同名只在**同一份清单内**去重。
     * 与目标目录里已有文件的重名要查目录，落在写入阶段——[ExportWriter.write] 取
     * `childNames(dir)` 后交给 [ExportNaming.uniqueName] 加序号，这里不查、也不预留序号。
     */
    fun items(
        tracks: List<ExportTrack>,
        folderName: String? = null,
        byType: Boolean = false,
        byUploader: Boolean = false,
        withUploader: Boolean = false,
        withDate: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Item> = dedupe(tracks).map { track ->
        Item(
            track = track,
            dirs = ExportNaming.relativeDirs(track, folderName, byType, byUploader),
            fileName = ExportNaming.fileName(track, withUploader, withDate, zone),
        )
    }
}
