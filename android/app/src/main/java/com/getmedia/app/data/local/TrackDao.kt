package com.getmedia.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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

    @Update
    suspend fun updateExisting(track: TrackEntity): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TrackEntity>)

    /**
     * 只补缺失：主键已存在就整行丢弃，**绝不覆盖**。
     *
     * 同步写回必须走这个，不能走 [upsertAll]：`REPLACE` 会先删后插，把本地收藏、类别、
     * 重命名、`localPath` 一起抹成新值。返回插入成功的 rowid，已存在的不在里面。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<TrackEntity>): List<Long>

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM playlist_items WHERE trackId IN (:ids)")
    suspend fun removePlaylistReferences(ids: List<Long>)

    @Query("UPDATE tracks SET localPath = :path WHERE id = :id")
    suspend fun setLocalPath(id: Long, path: String?)

    /**
     * 磁盘上已经有文件、库字段还没跟上时，把字段补齐到与磁盘一致（0.1.16）。
     *
     * 只改 [replaceStates] 里列出的状态值：待推送草稿、已脱钩这类状态不能被 "SYNCED" 覆盖。
     * 两个状态字面量由调用方传进来——它们只允许出现在 full 源码集，不进 `main`。
     */
    @Query(
        "UPDATE tracks SET localPath = :path, audioBytes = :bytes, " +
            "syncState = CASE WHEN syncState IN (:replaceStates) THEN :state ELSE syncState END, " +
            "updatedAtMs = :now WHERE id = :id",
    )
    suspend fun markFilePresent(
        id: Long, path: String, bytes: Long, state: String,
        replaceStates: List<String>, now: Long,
    ): Int

    @Query("UPDATE tracks SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    // ---------- 整理信息推送（v5 起） ----------

    /** 本机改过、还没推上去的收藏/类别；只挑已绑定服务器 id 的行（没绑的推了也没处放）。 */
    @Query("SELECT * FROM tracks WHERE metaDirty = 1 AND serverId IS NOT NULL AND accountKey = :account ORDER BY id")
    suspend fun metaDirty(account: String): List<TrackEntity>

    @Query("UPDATE tracks SET favorite = :fav, metaDirty = 1 WHERE id = :id")
    suspend fun setFavoriteDirty(id: Long, fav: Boolean)

    @Query("UPDATE tracks SET localCategoryId = :categoryId, metaDirty = 1, updatedAtMs = :now WHERE id = :id")
    suspend fun moveDirty(id: Long, categoryId: Long, now: Long)

    /** 推送成功：清脏位，同时把服务器回来的一行记下来（版本号要跟上，否则下一轮又白推）。 */
    @Query("UPDATE tracks SET metaDirty = 0, serverSeq = :seq, serverUpdatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun markMetaClean(id: Long, seq: Long, updatedAtMs: Long)

    @Query("UPDATE tracks SET metaDirty = 0 WHERE id = :id")
    suspend fun clearMetaDirty(id: Long)

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
