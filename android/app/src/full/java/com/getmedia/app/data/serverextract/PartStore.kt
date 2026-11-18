package com.getmedia.app.data.serverextract

import java.io.File

/**
 * `.part` 的命名与清理（P0-1 设计 §5「下载中断」）。
 *
 * 文件名带版本标识：`server-<trackId>-<updatedSeq>-<bytes>.part`。服务器对同一条媒体重新提取、
 * 或缓存到期被 `prune_cache` 清掉后重下，`updated_seq`（`bytes` 也可能）都会变，文件名随之变化，
 * 旧文件自然不会被续传——否则会把"旧文件的后半段"接在"新文件的前半段"后面，
 * 长度恰好对得上，内容却是错的。
 *
 * 清理放在每次 extract 开始（拿到 track 之后，知道该留哪个名字的时候）：
 * - 服务器缓存 TTL 是 1 天，更久的断点已经续不上，直接删；
 * - 同一 trackId 但版本标识不同的旧 `.part` 一律删，避免越积越多。
 *
 * 只适用于"一条一条串行提取"（设计 §8）：同一 trackId 并发下载时，后开始的那条会把先开始的
 * 那个 `.part` 当成旧版本删掉。
 */
object PartStore {

    /** 与服务器 `cache` 存储的 TTL 对齐：超过这个时长的断点不再续传。 */
    const val TTL_MS = 24L * 60 * 60 * 1000

    private const val PREFIX = "server-"
    private const val SUFFIX = ".part"

    /** `bytes` 为空时按 0 写进名字，保证文件名始终是三段。 */
    fun name(trackId: Long, updatedSeq: Long, bytes: Long?): String =
        "$PREFIX$trackId-$updatedSeq-${bytes ?: 0}$SUFFIX"

    fun file(cacheDir: File, trackId: Long, updatedSeq: Long, bytes: Long?): File =
        File(cacheDir, name(trackId, updatedSeq, bytes))

    /**
     * 清掉过期与旧版本的 `.part`，[keep] 本身永远保留。返回被删掉的文件，便于日志与断言。
     *
     * [nowMs] 可注入，测试不必等 24 小时。
     */
    fun sweep(cacheDir: File, keep: File, nowMs: Long = System.currentTimeMillis()): List<File> {
        val keepTrack = trackIdOf(keep.name) ?: return emptyList()
        return cacheDir.listFiles().orEmpty()
            .filter { it.isFile && it.name != keep.name && isPartName(it.name) }
            .filter { expired(it, nowMs) || trackIdOf(it.name) == keepTrack }
            .filter { it.delete() }
    }

    private fun isPartName(name: String) = name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /** 从 `server-31-17-16.part` 取出 trackId；旧命名 `server-31.part` 也认。 */
    private fun trackIdOf(name: String): Long? = name
        .removePrefix(PREFIX)
        .removeSuffix(SUFFIX)
        .substringBefore('-')
        .toLongOrNull()

    private fun expired(file: File, nowMs: Long) = nowMs - file.lastModified() > TTL_MS
}
