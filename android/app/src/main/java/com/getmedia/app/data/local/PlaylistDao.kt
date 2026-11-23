package com.getmedia.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Query("SELECT id FROM playlists WHERE isDefault = 1 LIMIT 1")
    suspend fun firstId(): Long?

    @Query("SELECT id FROM playlists ORDER BY createdAt, id LIMIT 1")
    suspend fun oldestId(): Long?

    @Query("UPDATE playlists SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun markDefault(id: Long)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY orderIndex")
    suspend fun itemsNow(id: Long): List<PlaylistItemEntity>

    /** 全量读取（备份快照用）：一次取完，避免按列表逐条查询。 */
    @Query("SELECT * FROM playlists ORDER BY createdAt, id")
    suspend fun allNow(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_items")
    suspend fun itemsAll(): List<PlaylistItemEntity>

    @Query("UPDATE playlist_items SET orderIndex = :position WHERE playlistId = :id AND trackId = :trackId")
    suspend fun setOrder(id: Long, trackId: Long, position: Int)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id AND trackId = :trackId")
    suspend fun removeItem(id: Long, trackId: Long)

    @Query("SELECT name FROM playlists")
    suspend fun names(): List<String>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY orderIndex")
    fun items(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun lastOrder(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clear(playlistId: Long)

    @Query("DELETE FROM playlist_items WHERE trackId = :trackId")
    suspend fun removeTrack(trackId: Long)

    // ---------- 跨设备同步（v5 起） ----------

    /** 本机改过、还没推上去的列表。 */
    @Query("SELECT * FROM playlists WHERE dirty = 1 ORDER BY id")
    suspend fun dirtyNow(): List<PlaylistEntity>

    @Query("UPDATE playlists SET dirty = 1 WHERE id = :id")
    suspend fun markDirty(id: Long)

    /**
     * 这些条目出现在哪些列表里。
     *
     * **必须在删成员之前查**：删完就查不到这条属于哪些列表了，那些列表也就不会被标脏。
     */
    @Query("SELECT DISTINCT playlistId FROM playlist_items WHERE trackId IN (:trackIds)")
    suspend fun idsContaining(trackIds: List<Long>): List<Long>

    /** 一次把多个列表标成"有本机改动"，避免逐条 update。 */
    @Query("UPDATE playlists SET dirty = 1 WHERE id IN (:ids)")
    suspend fun markDirtyAll(ids: List<Long>)

    @Query("UPDATE playlists SET dirty = 0 WHERE id = :id")
    suspend fun markClean(id: Long)

    /** 推送成功：清脏位并记下对齐时间。 */
    @Query("UPDATE playlists SET dirty = 0, syncedAt = :now WHERE id = :id")
    suspend fun markSynced(id: Long, now: Long)

    /** 拉取时按名称找本地列表：名称就是跨设备身份。 */
    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_removals WHERE account = :account ORDER BY requestedAt")
    suspend fun removals(account: String): List<PlaylistRemovalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addRemoval(removal: PlaylistRemovalEntity)

    @Query("DELETE FROM playlist_removals WHERE account = :account AND name = :name")
    suspend fun dropRemoval(account: String, name: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id AND trackId NOT IN (:keep)")
    suspend fun keepOnly(id: Long, keep: List<Long>)
}
