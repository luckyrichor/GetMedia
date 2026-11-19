package com.getmedia.app.data.backup

import androidx.room.withTransaction
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.CategoryEntity
import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity

/**
 * 把一份云端快照合并进本地库（设计 §5.5 ③）。
 *
 * 唯一的规则：**只补缺失，不覆盖已有**。
 * 判定键是 `localId(platform, key)`——不是快照里的行号；键相同就整行跳过，
 * 标题、收藏、类别、列表归属、`localPath` 全部保持本地现值，快照再旧也改不动。
 * 因此"库非空也能放心恢复"：最坏情况是什么都没补。
 */
class RestoreService(private val db: AppDatabase) {

    data class Result(
        val addedTracks: Int,
        val skippedTracks: Int,
        val addedCategories: Int,
        val addedPlaylists: Int,
        val addedItems: Int,
    ) {
        val totalTracks: Int get() = addedTracks + skippedTracks
    }

    suspend fun restore(snapshot: ParsedSnapshot, nowMs: Long = System.currentTimeMillis()): Result =
        db.withTransaction {
            val categoryMap = mergeCategories(snapshot.categories, nowMs)
            val playlistMap = mergePlaylists(snapshot.playlists)
            val existingIds = db.trackDao().allNow().mapTo(HashSet()) { it.id }
            val existingItems = db.playlistDao().itemsAll().mapTo(HashSet()) { it.playlistId to it.trackId }

            val toInsert = ArrayList<TrackEntity>(snapshot.tracks.size)
            val newItems = ArrayList<PlaylistItemEntity>()
            var skipped = 0

            for (t in snapshot.tracks) {
                val id = LocalIds.localId(t.platform, t.videoId, t.page)
                if (!existingIds.add(id)) {
                    skipped++
                    continue
                }
                toInsert += TrackEntity(
                    id = id,
                    platform = t.platform,
                    // 与 DirectExtractor 同一套键：B 站 P2 存成 BV1xx-p2，重新下载能落回同一行
                    videoId = LocalIds.key(t.platform, t.videoId, t.page),
                    title = t.title,
                    uploader = t.uploader,
                    durationSec = t.durationSec,
                    audioBytes = t.bytes,
                    audioFormat = t.format,
                    sourceUrl = t.sourceUrl,
                    // 文件没跟着来：先标"待下载"（判定就是 localPath == null）
                    localPath = null,
                    favorite = t.favorite,
                    localCategoryId = t.categoryId?.let { categoryMap[it] } ?: 1L,
                    updatedAtMs = nowMs,
                    localTitleOverride = t.titleOverride,
                    addedAtMs = t.addedAtMs,
                    sortIndex = t.sortIndex,
                    // 快照里的类型不可信：认不出来就当音频，别让一条脏数据把整个事务打回
                    mediaType = runCatching { MediaKind.valueOf(t.mediaType) }.getOrDefault(MediaKind.AUDIO).name,
                    serverPage = t.page,
                    // 六列同步字段的默认值，一条都不能省：
                    // serverId / serverSeq / serverUpdatedAtMs / accountKey = null（服务器身份未知），
                    // syncState = RESTORED（P0-2 别当新条目上传，否则会在服务器上造出重复）。
                    // 这条约定由 RestoreSyncDefaultsTest 钉住。
                    syncState = SyncState.RESTORED.name,
                )
                for (ref in t.playlists) {
                    val playlistId = playlistMap[ref.playlistId] ?: continue
                    if (existingItems.add(playlistId to id)) {
                        newItems += PlaylistItemEntity(playlistId, id, nowMs, ref.orderIndex)
                    }
                }
            }

            // 一次写一批：REPLACE 语义在这里不会被触发到覆盖分支，因为上面已经按键跳过了
            if (toInsert.isNotEmpty()) db.trackDao().upsertAll(toInsert)
            if (newItems.isNotEmpty()) db.playlistDao().add(newItems)

            Result(
                addedTracks = toInsert.size,
                skippedTracks = skipped,
                addedCategories = categoryMap.added,
                addedPlaylists = playlistMap.added,
                addedItems = newItems.size,
            )
        }

    /** 快照 id → 本地 id。默认类别（id=1，"未分类"）直接复用本地那条。 */
    private suspend fun mergeCategories(snapshot: List<BackupCategory>, nowMs: Long): CategoryMap {
        val local = db.categoryDao().allNow()
        val byName = local.associateBy { it.name }.toMutableMap()
        val map = HashMap<Long, Long>()
        var added = 0
        for (c in snapshot) {
            if (c.id == 1L) { map[1L] = 1L; continue }
            val hit = byName[c.name]
            if (hit != null) { map[c.id] = hit.id; continue }
            val id = db.categoryDao().insert(
                CategoryEntity(
                    name = c.name, parentId = null, kind = c.kind,
                    createdAt = c.createdAt.takeIf { it > 0 } ?: nowMs,
                )
            )
            byName[c.name] = CategoryEntity(id, c.name, null, c.kind, c.createdAt)
            map[c.id] = id
            added++
        }
        // 父引用只能第二遍补：子类别可能先于父类别出现在快照里
        for (c in snapshot) {
            val child = map[c.id] ?: continue
            val parent = c.parentId?.let { map[it] } ?: continue
            val current = db.categoryDao().byId(child) ?: continue
            if (current.parentId == null) db.categoryDao().update(current.copy(parentId = parent))
        }
        return CategoryMap(map, added)
    }

    /** 按 name 查重复用：恢复两次不该出现两个"默认"。 */
    private suspend fun mergePlaylists(snapshot: List<BackupPlaylist>): PlaylistMap {
        val local = db.playlistDao().allNow()
        val byName = local.associateBy { it.name }.toMutableMap()
        var added = 0
        val map = HashMap<Long, Long>()
        for (p in snapshot) {
            val hit = byName[p.name]
            if (hit != null) { map[p.id] = hit.id; continue }
            val id = db.playlistDao().insert(PlaylistEntity(name = p.name, createdAt = p.createdAt))
            byName[p.name] = PlaylistEntity(id, p.name, p.createdAt)
            map[p.id] = id
            added++
        }
        return PlaylistMap(map, added)
    }

    private class CategoryMap(val ids: Map<Long, Long>, val added: Int) {
        operator fun get(snapshotId: Long): Long? = ids[snapshotId]
    }

    private class PlaylistMap(val ids: Map<Long, Long>, val added: Int) {
        operator fun get(snapshotId: Long): Long? = ids[snapshotId]
    }
}
