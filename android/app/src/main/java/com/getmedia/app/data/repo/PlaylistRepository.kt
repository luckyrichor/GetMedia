package com.getmedia.app.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.backup.BackupManager
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.PlaylistRemovalEntity
import com.getmedia.app.data.cloud.cloudSync
import com.getmedia.app.player.newQueueTracks

class PlaylistRepository(context: Context) {
    private val db = AppDatabase.get(context)
    private val dao = db.playlistDao()
    private val categoryRepo = CategoryRepository(context)
    private val appContext = context.applicationContext

    fun observeAll() = dao.observeAll()
    fun items(id: Long) = dao.items(id)

    /** 当前登录账号；没登录就没有跨设备身份，队列写了也没人重放。 */
    private fun account(): String? = SessionStore.current.value?.loginName

    /** 本地改动都走这一处：标脏 + 让同步器尽快推一轮（内部有 1.5 秒防抖与三道闸）。 */
    private fun touched() {
        BackupManager.noteWrite()
        cloudSync.onLocalChange(appContext)
    }

    suspend fun create(name: String): Long = db.withTransaction {
        dao.insert(PlaylistEntity(name = playlistName(name, dao.names()), dirty = true))
    }.also { touched() }

    suspend fun ensureDefault(): Long = db.withTransaction {
        dao.firstId() ?: dao.insert(
            PlaylistEntity(name = playlistName("", dao.names()), isDefault = true, dirty = true),
        )
    }

    suspend fun resolve(id: Long?): Long = id?.takeIf { dao.byId(it) != null } ?: ensureDefault()

    suspend fun defaultId(): Long? = dao.firstId()

    suspend fun tracks(id: Long) = dao.itemsNow(id).mapNotNull { db.trackDao().byId(it.trackId) }

    suspend fun replaceTracks(id: Long, trackIds: List<Long>) = db.withTransaction {
        dao.clear(id)
        addTracks(id, trackIds)
        dao.markDirty(id)
    }.also { touched() }

    /** Update references in one transaction; do not recreate them or accept a stale membership. */
    suspend fun reorder(id: Long, trackIds: List<Long>): Boolean = db.withTransaction {
        if (dao.byId(id) == null) return@withTransaction false
        val current = dao.itemsNow(id).map { it.trackId }
        if (com.getmedia.app.player.queueReorderMoves(current, trackIds) == null) return@withTransaction false
        trackIds.forEachIndexed { index, trackId -> dao.setOrder(id, trackId, index) }
        dao.markDirty(id)
        true
    }.also { if (it) touched() }

    suspend fun removeTrack(id: Long, trackId: Long) {
        dao.removeItem(id, trackId)
        dao.markDirty(id)
        touched()
    }

    /**
     * 改名。**旧名字要进删除队列**：列表的跨设备身份就是名称，不改名的话云端会留下
     * 一个旧名列表，另一台设备上就凭空多出一个空列表。
     */
    suspend fun rename(id: Long, name: String) {
        require(name.isNotBlank()) { "列表名称不能为空" }
        val current = dao.byId(id) ?: error("播放列表不存在")
        val clean = name.trim()
        dao.rename(id, clean)
        dao.markDirty(id)
        val account = account()
        if (account != null && current.name != clean) {
            dao.addRemoval(PlaylistRemovalEntity(account, current.name))
        }
        touched()
    }

    suspend fun delete(id: Long) = db.withTransaction {
        val removing = dao.byId(id)
        val wasDefault = removing?.isDefault == true
        dao.clear(id)
        dao.delete(id)
        val account = account()
        if (account != null && removing != null) {
            dao.addRemoval(PlaylistRemovalEntity(account, removing.name))
        }
        if (wasDefault) {
            val replacement = dao.oldestId() ?: dao.insert(
                PlaylistEntity(name = playlistName("", dao.names()), isDefault = true, dirty = true),
            )
            dao.markDefault(replacement)
        }
    }.also { touched() }

    suspend fun addTracks(playlistId: Long, trackIds: List<Long>) = db.withTransaction {
        require(dao.byId(playlistId) != null) { "播放列表不存在" }
        val tracks = db.trackDao().allNow().associateBy { it.id }
        val existing = dao.itemsNow(playlistId).mapNotNull { tracks[it.trackId] }
        val additions = newQueueTracks(existing, trackIds.mapNotNull { tracks[it] })
        val start = dao.lastOrder(playlistId) + 1
        dao.add(additions.mapIndexed { index, track ->
            PlaylistItemEntity(playlistId, track.id, orderIndex = start + index)
        })
        dao.markDirty(playlistId)
    }.also { touched() }

    suspend fun addCategory(playlistId: Long, categoryId: Long) =
        addTracks(playlistId, categoryRepo.recursiveTracks(categoryId).map { it.id })
}
