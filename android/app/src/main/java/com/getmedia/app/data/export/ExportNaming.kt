package com.getmedia.app.data.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 导出文件名与目录名的**纯函数**规则（见 `docs/export-plan.md` §4）。
 *
 * 这里刻意不碰文件系统：目标目录里有哪些重名、available bytes 是多少，都由调用方查好后传进来，
 * 所以整套规则可以在 JVM 单测里穷举边界。
 *
 * 三条前提：
 * 1. 目标可能是 exFAT / SMB / 云盘，按最严格的字符与长度限制来（不是按 Linux 文件系统）。
 * 2. 不覆盖用户已有文件，重名只加序号；序号用尽宁可让该项失败。
 * 3. 文件名只用于落盘，日志与诊断包不记录路径（见方案 §8）。
 */
object ExportNaming {

    /** 基础名上限：字符数（按码点算，一个 emoji 记 1）。 */
    const val MAX_NAME_CHARS = 80

    /** 基础名上限：UTF-8 字节数，给扩展名、序号、博主名留余量。 */
    const val MAX_NAME_BYTES = 200

    const val MAX_FOLDER_CHARS = 60
    const val MAX_FOLDER_BYTES = 180
    const val MAX_UPLOADER_CHARS = 30
    const val MAX_UPLOADER_BYTES = 120

    /** 重名序号上限；到 999 还冲突就记该项失败，不做随机名。 */
    const val MAX_INDEX = 999

    const val DEFAULT_EXT = "m4a"
    const val FALLBACK_NAME = "未命名"
    const val UNKNOWN_UPLOADER = "未知博主"

    /** 子文件夹的两个分支（`按类型`）。 */
    const val TYPE_AUDIO = "音频"
    const val TYPE_VIDEO = "视频"

    /** Windows / SMB 非法字符，一律替换成单个空格。 */
    private const val ILLEGAL_CHARS = "\\/:*?\"<>|"

    /** Windows 保留名（不区分大小写），撞上就加下划线前缀。 */
    private val RESERVED_NAMES: Set<String> = buildSet {
        add("CON"); add("PRN"); add("AUX"); add("NUL")
        for (i in 1..9) { add("COM$i"); add("LPT$i") }
    }

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    private val WHITESPACE = Regex("\\s+")

    /**
     * 清洗一段名字：替换非法字符与控制字符 → 折叠空白 → 去结尾的点/空格 → 保留名加前缀 → 截断。
     *
     * @param fallback 清洗后为空时的替代值（文件用「未命名」，目录也用同一个，避免空目录名）。
     */
    fun sanitize(
        raw: String?,
        maxChars: Int = MAX_NAME_CHARS,
        maxBytes: Int = MAX_NAME_BYTES,
        fallback: String = FALLBACK_NAME,
    ): String {
        val replaced = buildString(raw?.length ?: 0) {
            raw.orEmpty().forEach { c ->
                // U+0000–U+001F 是控制字符，Windows/SMB 上会直接失败
                append(if (c in ILLEGAL_CHARS || c.code < 0x20) ' ' else c)
            }
        }
        val collapsed = WHITESPACE.replace(replaced, " ").trim()
        val trimmed = collapsed.trimEnd('.', ' ')
        if (trimmed.isEmpty()) return fallback
        val reserved = if (trimmed.uppercase(Locale.ROOT) in RESERVED_NAMES) "_$trimmed" else trimmed
        return truncate(reserved, maxChars, maxBytes).ifEmpty { fallback }
    }

    /**
     * 按码点截断，同时满足字符数与 UTF-8 字节数两个上限。
     * 不会切半个 emoji（代理对整对保留或整对丢弃）。
     */
    fun truncate(text: String, maxChars: Int, maxBytes: Int): String {
        if (text.length <= maxChars && text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val sb = StringBuilder()
        var chars = 0
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val cpChars = String(Character.toChars(cp))
            val cpBytes = cpChars.toByteArray(Charsets.UTF_8).size
            if (chars + 1 > maxChars || bytes + cpBytes > maxBytes) break
            sb.append(cpChars)
            chars += 1
            bytes += cpBytes
            i += Character.charCount(cp)
        }
        return sb.toString().trimEnd('.', ' ')
    }

    /**
     * 扩展名：空 → `m4a`；有值就原样保留（不转码，`webm`/`mkv` 该怎么落就怎么落），
     * 只滤掉非字母数字字符，防止拼出带路径分隔符的名字。
     */
    fun extension(format: String?): String {
        val cleaned = format.orEmpty().trim().removePrefix(".").lowercase(Locale.ROOT)
        val safe = cleaned.filter { it.isLetterOrDigit() }
        // 真实扩展名不会超过 8 个字符；滤完还剩一长串说明原值不像扩展名（比如混进了路径），回落默认值
        return if (safe.length in 1..8) safe else DEFAULT_EXT
    }

    /** 入库日期后缀（`yyyy-MM-dd`）；`addedAtMs <= 0` 视为未知，不写。 */
    fun dateSuffix(addedAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String? =
        if (addedAtMs <= 0L) null
        else DATE_FORMAT.format(Instant.ofEpochMilli(addedAtMs).atZone(zone))

    /**
     * 组合文件名：`基础名 + 博主 + 入库日期 + 扩展名`（顺序固定，见方案 §4.2）。
     *
     * @param withUploader 文件名带博主名（默认关闭）
     * @param withDate 文件名带**入库日期**（默认关闭；界面文案也必须写"入库日期"）
     */
    fun fileName(
        track: ExportTrack,
        withUploader: Boolean = false,
        withDate: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val sb = StringBuilder(sanitize(track.displayTitle))
        if (withUploader) {
            val uploader = track.uploader?.takeIf { it.isNotBlank() }
            if (uploader != null) {
                sb.append(" - ")
                    .append(sanitize(uploader, MAX_UPLOADER_CHARS, MAX_UPLOADER_BYTES, UNKNOWN_UPLOADER))
            }
        }
        if (withDate) {
            dateSuffix(track.addedAtMs, zone)?.let { sb.append(" (").append(it).append(')') }
        }
        return sb.append('.').append(extension(track.audioFormat)).toString()
    }

    /**
     * 重名处理：与目标目录里已有的名字（**大小写不敏感**）比较，冲突就加 ` (2)`、` (3)`…
     *
     * @return 可用的文件名；序号到 [MAX_INDEX] 仍冲突则返回 null（调用方记该项失败：重名过多）。
     */
    fun uniqueName(desired: String, existingNames: Collection<String>): String? {
        val taken = HashSet<String>(existingNames.size * 2)
        existingNames.forEach { taken.add(it.lowercase(Locale.ROOT)) }
        if (desired.lowercase(Locale.ROOT) !in taken) return desired

        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        for (index in 2..MAX_INDEX) {
            val candidate = "$stem ($index)$ext"
            if (candidate.lowercase(Locale.ROOT) !in taken) return candidate
        }
        return null
    }

    /**
     * 相对目录层级（**不含**所选根目录）：`[文件夹名?] + [类型?] + [博主?]`，最多 3 段。
     *
     * 固定层级、不按类别无限嵌套——类别是可改名的软结构，导出目录要稳定。
     */
    fun relativeDirs(
        track: ExportTrack,
        folderName: String? = null,
        byType: Boolean = false,
        byUploader: Boolean = false,
    ): List<String> {
        val dirs = ArrayList<String>(3)
        folderName?.takeIf { it.isNotBlank() }?.let {
            dirs += sanitize(it, MAX_FOLDER_CHARS, MAX_FOLDER_BYTES)
        }
        if (byType) dirs += if (track.isVideo) TYPE_VIDEO else TYPE_AUDIO
        if (byUploader) {
            dirs += sanitize(
                track.uploader?.takeIf { it.isNotBlank() } ?: UNKNOWN_UPLOADER,
                MAX_UPLOADER_CHARS,
                MAX_UPLOADER_BYTES,
                UNKNOWN_UPLOADER,
            )
        }
        return dirs
    }
}
