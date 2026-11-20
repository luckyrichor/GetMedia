package com.getmedia.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 两张辅助表的读写（P0-2 骨架）。
 *
 * 现在只有测试会调它：阶段 2 起同步器用 [cursor]/[putCursor] 记处理位置，阶段 5 用
 * [addRemoval]/[removals]/[dropRemoval] 重放本机删除。名字与列名保持中性，理由见实体的注释。
 */
@Dao
interface LibraryAuxDao {

    /** 某个账号还没重放掉的删除，按发生时间从早到晚。 */
    @Query("SELECT * FROM track_removals WHERE account = :account ORDER BY requestedAt ASC")
    suspend fun removals(account: String): List<TrackRemovalEntity>

    @Query("SELECT COUNT(*) FROM track_removals WHERE account = :account")
    suspend fun removalCount(account: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addRemoval(item: TrackRemovalEntity)

    /** 重放成功后清掉这一条；键相同就覆盖，重复入队不会变多行。 */
    @Query("DELETE FROM track_removals WHERE platform = :platform AND videoKey = :videoKey AND account = :account")
    suspend fun dropRemoval(platform: String, videoKey: String, account: String)

    /** 注销账号时清干净，避免拿已失效的身份去重放。 */
    @Query("DELETE FROM track_removals WHERE account = :account")
    suspend fun clearRemovals(account: String)

    @Query("SELECT * FROM library_cursor WHERE account = :account")
    suspend fun cursor(account: String): LibraryCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCursor(cursor: LibraryCursorEntity)
}
