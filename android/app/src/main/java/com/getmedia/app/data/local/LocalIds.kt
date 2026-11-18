package com.getmedia.app.data.local

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * 本地行 id 的唯一算法：`-(sha256("platform:key") 的低 63 位)`，必须为负数。
 *
 * 手机直提取与服务器提取共用它，所以同一作品不论从哪条路进来都落**同一行**，
 * 重复提取是幂等的（[TrackDao.upsertLocal] 会保留收藏、分类、重命名与入库时间）。
 *
 * 从 [com.getmedia.app.data.localextract.DirectExtractor] 抽出来单独放，
 * 是为了让 full 版的服务器提取能复算同一个 id，而不必复制一遍哈希逻辑。
 */
object LocalIds {

    /**
     * 算 id 用的键：**B 站多 P 必须带页码**，其余平台就是作品 id。
     *
     * B 站一个链接下可能有多个分 P，服务器把它们存成同一个 `video_id` 的若干行
     * （`page` 不同）。若键里不带页码，P2 就会算出和 P1 相同的 id，直接覆盖 P1。
     * 这条规则手机直提取（[com.getmedia.app.data.localextract.DirectLink]）与服务器提取
     * 共用一处，改动只改这里。
     */
    fun key(platform: String, videoId: String, page: Int): String =
        if (page > 1 && platform.equals("bilibili", ignoreCase = true)) "$videoId-p$page" else videoId

    fun localId(platform: String, key: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest("$platform:$key".toByteArray())
        val positive = ByteBuffer.wrap(bytes).long and Long.MAX_VALUE
        return -(positive.takeIf { it > 0 } ?: 1L)
    }

    /** 按 [key] 的规则先归一键再算 id；两条提取路径都该用这个重载。 */
    fun localId(platform: String, videoId: String, page: Int): Long =
        localId(platform, key(platform, videoId, page))
}
