package com.getmedia.app.player

import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.CancellationException

/** ID protects renamed entries; hash and source identify the same audio across imports. */
internal fun newQueueTracks(existing: List<TrackEntity>, incoming: List<TrackEntity>): List<TrackEntity> {
    val ids = existing.map { it.id }.toMutableSet()
    val hashes = existing.mapNotNull { it.audioSha256?.trim()?.takeIf(String::isNotEmpty)?.lowercase() }.toMutableSet()
    val sources = existing.filter { it.videoId.isNotBlank() }.map { it.platform to it.videoId }.toMutableSet()
    return incoming.filter { track ->
        val hash = track.audioSha256?.trim()?.takeIf(String::isNotEmpty)?.lowercase()
        val source = (track.platform to track.videoId).takeIf { track.videoId.isNotBlank() }
        val fresh = track.id !in ids && (hash == null || hash !in hashes) && (source == null || source !in sources)
        ids.add(track.id)
        if (hash != null) hashes.add(hash)
        if (source != null) sources.add(source)
        fresh
    }
}

internal fun queueRemovalIndices(ids: List<Long>, deletedIds: Set<Long>): List<Int> =
    ids.indices.filter { ids[it] in deletedIds }.reversed()

/**
 * 播放列表预载：逐条解析本地文件，解析不出来的**跳过**，绝不因为一条坏行拖死整批。
 *
 * 0.1.13 之前 `appendLocked` 在这里直接 `getOrThrow()`。恢复出来的空壳行（
 * `RESTORED`、没有文件）一旦进了默认列表，点**任意**一首播放都会失败，
 * 提示还是"本机音频文件已丢失，请重新提取"——点的那一首文件其实是好的。
 *
 * 取消必须继续抛：那不是"这条播不了"，是整次播放被取消。
 */
internal suspend fun seedQueueItems(
    tracks: List<TrackEntity>,
    resolve: suspend (TrackEntity) -> String?,
): List<Pair<TrackEntity, String>> = tracks.mapNotNull { track ->
    val path = try {
        resolve(track)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // 缺文件、账号变了、云端行还没下载 —— 都只是"这条现在放不了"。
        null
    }
    path?.takeIf { it.isNotBlank() }?.let { track to it }
}
