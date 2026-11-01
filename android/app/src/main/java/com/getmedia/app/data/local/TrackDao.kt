package com.getmedia.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' AND (:startMs IS NULL OR addedAtMs >= :startMs) AND (:endMs IS NULL OR addedAtMs <= :endMs) ORDER BY sortIndex ASC, addedAtMs DESC, id DESC")
    fun allWithTimeRange(q: String, startMs: Long?, endMs: Long?): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE localCategoryId = :categoryId ORDER BY sortIndex ASC, addedAtMs DESC, id DESC")
    fun inCategory(categoryId: Long): Flow<List<TrackEntity>>

    @Query("UPDATE tracks SET sortIndex = :index WHERE id = :id")
    suspend fun setSortIndex(id: Long, index: Long)

    @Query("SELECT * FROM tracks")
    suspend fun allNow(): List<TrackEntity>

    @Query("UPDATE tracks SET title = :title, localTitleOverride = :title, updatedAtMs = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE tracks SET localCategoryId = :categoryId, updatedAtMs = :now WHERE id = :id")
    suspend fun move(id: Long, categoryId: Long, now: Long)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM tracks WHERE (:platform IS NULL OR platform = :platform) AND (:favoritesOnly = 0 OR favorite = 1) AND (title LIKE '%' || :q || '%' OR uploader LIKE '%' || :q || '%') ORDER BY addedAtMs DESC, id DESC")
    fun filtered(q: String, platform: String?, favoritesOnly: Boolean): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY addedAtMs DESC, id DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR uploader LIKE '%' || :q || '%' ORDER BY addedAtMs DESC, id DESC")
    fun search(q: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE platform = :platform ORDER BY addedAtMs DESC, id DESC")
    fun byPlatform(platform: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE favorite = 1 ORDER BY addedAtMs DESC, id DESC")
    fun favorites(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM playlist_items WHERE trackId IN (:ids)")
    suspend fun removePlaylistReferences(ids: List<Long>)

    @Query("UPDATE tracks SET localPath = :path WHERE id = :id")
    suspend fun setLocalPath(id: Long, path: String?)

    @Query("UPDATE tracks SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    /** 本机提取/导入入库：保留已有的收藏、分类、重命名与入库时间。 */
    @Transaction
    suspend fun upsertLocal(track: TrackEntity) {
        val existing = byId(track.id)
        upsertAll(listOf(track.copy(favorite = existing?.favorite ?: false,
            localCategoryId = existing?.localCategoryId ?: track.localCategoryId,
            title = existing?.localTitleOverride ?: track.title,
            localTitleOverride = existing?.localTitleOverride,
            addedAtMs = existing?.addedAtMs?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            sortIndex = existing?.sortIndex ?: track.sortIndex,
            updatedAtMs = System.currentTimeMillis())))
    }
}
