package com.getmedia.app.data.repo

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.getmedia.app.player.PlayerHolder
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.localextract.DirectExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.io.File

class TrackRepository(private val context: Context) {

    companion object {
        internal val fileMutex = Mutex()
    }

    private val db = AppDatabase.get(context)
    private val trackDao = db.trackDao()

    private val audioDir: File
        get() = File(context.filesDir, "audio").apply { mkdirs() }

    // ---------- 读 ----------

    fun filtered(q: String, platform: String?, favoritesOnly: Boolean) = trackDao.filtered(q, platform, favoritesOnly)

    fun observeAll(): Flow<List<TrackEntity>> = trackDao.observeAll()
    fun search(q: String): Flow<List<TrackEntity>> = trackDao.search(q)
    fun byPlatform(p: String): Flow<List<TrackEntity>> = trackDao.byPlatform(p)
    fun favorites(): Flow<List<TrackEntity>> = trackDao.favorites()

    fun allWithTimeRange(q: String, startMs: Long?, endMs: Long?) =
        trackDao.allWithTimeRange(q, startMs, endMs)

    fun inCategory(categoryId: Long) = trackDao.inCategory(categoryId)

    suspend fun allNow(): List<TrackEntity> = trackDao.allNow()

    /**
     * 保存列表的手动顺序。
     *
     * 只替换「可见项」在原全量顺序里占的位置，其余记录原地不动 —— 这样在搜索结果或
     * 类别里拖动，不会把没显示的音频挤到别处。最后整库重编号 1..N，
     * 排序规则就变成纯粹的 sortIndex。
     */
    suspend fun reorderLibrary(visibleIds: List<Long>, newVisibleOrder: List<Long>) {
        if (visibleIds.isEmpty() || visibleIds.size != newVisibleOrder.size) return
        if (visibleIds.toSet() != newVisibleOrder.toSet()) return
        val visible = visibleIds.toSet()
        val full = trackDao.allNow().sortedWith(
            compareBy<TrackEntity> { it.sortIndex }
                .thenByDescending { it.addedAtMs }
                .thenByDescending { it.id },
        ).map { it.id }.toMutableList()
        val slots = full.indices.filter { full[it] in visible }
        if (slots.size != newVisibleOrder.size) return
        slots.forEachIndexed { i, slot -> full[slot] = newVisibleOrder[i] }
        db.withTransaction {
            full.forEachIndexed { i, id -> trackDao.setSortIndex(id, i + 1L) }
        }
    }

    suspend fun byId(id: Long): TrackEntity? = trackDao.byId(id)

    suspend fun rename(id: Long, title: String) {
        require(title.isNotBlank()) { "音频名称不能为空" }
        trackDao.rename(id, title.trim(), System.currentTimeMillis())
        withContext(Dispatchers.Main) {
            com.getmedia.app.player.PlayerHolder.rename(context, id, title.trim())
        }
    }

    suspend fun remove(id: Long) { removeAll(setOf(id)) }

    /** Delete metadata and every playlist reference in one transaction, then reconcile playback. */
    suspend fun removeAll(ids: Set<Long>): Int {
        if (ids.isEmpty()) return 0
        return withContext(NonCancellable) {
            val removed = withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    db.withTransaction {
                        val tracks = ids.mapNotNull { trackDao.byId(it) }
                        // Bound query size so selecting a large library cannot exceed SQLite bind limits.
                        tracks.map { it.id }.chunked(500).forEach {
                            trackDao.removePlaylistReferences(it)
                            trackDao.deleteByIds(it)
                        }
                        tracks
                    }.also { removed -> removed.forEach { it.localPath?.let { path -> File(path).delete() } } }
                }
            }
            withContext(Dispatchers.Main) { PlayerHolder.removeTracks(context, removed.map { it.id }.toSet()) }
            removed.size
        }
    }

    suspend fun setFavorite(id: Long, fav: Boolean) = trackDao.setFavorite(id, fav)

    // ---------- 提取 ----------

    /** 分享文本由手机直接提取，保存到本地库；不经过任何服务器。 */
    suspend fun extract(text: String, onDiagnostic: (String) -> Unit = {}): Result<TrackEntity> = runCatching {
        withContext(Dispatchers.IO) {
            val entity = fileMutex.withLock {
                val extracted = DirectExtractor(context) { line ->
                    withContext(Dispatchers.Main) { onDiagnostic(line) }
                }.extract(text)
                trackDao.upsertLocal(extracted)
                extracted
            }
            withContext(Dispatchers.Main) { onDiagnostic("已加入本地媒体库") }
            entity
        }
    }

    // ---------- 下载 ----------

    fun localFile(id: Long): File = File(audioDir, "$id.audio")

    /**
     * 返回曲目的本地文件。GetMedia 免费版没有服务器下载：文件只来自本机提取或导入，
     * 文件缺失时只能重新提取。保留 [Result] 形式，调用方统一处理失败。
     */
    suspend fun download(track: TrackEntity): Result<File> = runCatching {
        withContext(Dispatchers.IO) { fileMutex.withLock {
            val current = trackDao.byId(track.id) ?: error("音频已被移除")
            val file = current.localPath?.let(::File)
            if (file != null && file.exists() && file.length() > 0) return@withLock file
            if (current.locallyEdited) error("本地剪辑文件已丢失，请重新剪辑")
            error("本机音频文件已丢失，请重新提取")
        } }
    }

    /** A stable snapshot prevents background server downloads from changing an open edit. */
    suspend fun editSource(id: Long): File = withContext(Dispatchers.IO) {
        val track = trackDao.byId(id) ?: error("音频已被删除")
        download(track).getOrThrow()
        fileMutex.withLock {
            val current = trackDao.byId(id) ?: error("音频已被删除")
            val source = current.localPath?.let(::File)?.takeIf { it.exists() } ?: error("音频已更新，请重新打开剪辑")
            val snapshot = File.createTempFile("edit-source-", ".audio", context.cacheDir)
            try { source.copyTo(snapshot, overwrite = true); snapshot }
            catch (e: Exception) { snapshot.delete(); throw e }
        }
    }

    /** Commit a complete export under a unique path before touching the library record. */
    suspend fun saveEdit(id: Long, output: File, title: String, overwrite: Boolean): TrackEntity = withContext(Dispatchers.IO) {
        require(title.isNotBlank()) { "名称不能为空" }
        withContext(NonCancellable) {
            var previousPath: String? = null
            val saved = fileMutex.withLock {
                val original = trackDao.byId(id) ?: error("音频已被删除")
                val newId = if (overwrite) id else generateSequence { -java.util.concurrent.ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE) }
                    .first { trackDao.byId(it) == null }
                val target = File(audioDir, "$newId-edit-${java.util.UUID.randomUUID()}.m4a")
                val duration = com.getmedia.app.audioedit.AudioEditor.duration(output)
                require(duration > 0 && output.length() > 0) { "剪辑结果无效" }
                val digest = MessageDigest.getInstance("SHA-256")
                output.inputStream().use { input -> val buffer = ByteArray(65536); while (true) {
                    val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
                } }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                try { output.copyTo(target) } catch (e: Exception) { target.delete(); throw e }
                if (overwrite) previousPath = original.localPath
                val now = System.currentTimeMillis()
                val record = original.copy(id = newId, title = title.trim(), localTitleOverride = title.trim(),
                    videoId = if (overwrite) original.videoId else "edit-${java.util.UUID.randomUUID()}",
                    localPath = target.absolutePath, locallyEdited = true,
                    sourceTrackId = original.sourceTrackId ?: original.id,
                    durationSec = duration / 1000.0, audioBytes = target.length(), audioFormat = "m4a", audioSha256 = hash,
                    updatedAtMs = now, addedAtMs = if (overwrite) original.addedAtMs else now,
                    favorite = if (overwrite) original.favorite else false)
                try { trackDao.upsertAll(listOf(record)) } catch (e: Exception) { target.delete(); throw e }
                record
            }
            withContext(Dispatchers.Main) { PlayerHolder.edited(context, saved) }
            previousPath?.takeIf { it != saved.localPath }?.let { File(it).delete() }
            saved
        }
    }

    // ---------- 导入本地音频 ----------

    /**
     * 把系统文件选择器选中的音频复制进应用私有目录并入库。
     *
     * 场景：重装/换机后把之前导出的音频搬回来，或把手里的本地文件加进库。
     * id 由文件内容的 sha256 派生（负数），同一个文件重复导入不会产生副本。
     */
    suspend fun importAudio(uris: List<Uri>, onProgress: (String) -> Unit = {}): Result<Int> = runCatching {
        withContext(Dispatchers.IO) {
            var added = 0
            uris.forEachIndexed { index, uri ->
                val name = displayName(uri)?.takeIf { it.isNotBlank() } ?: "未命名音频"
                val title = titleOf(name)
                onProgress("正在导入 ${index + 1}/${uris.size}：$title")
                val staged = File.createTempFile("import-", ".part", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        staged.outputStream().use { out -> input.copyTo(out) }
                    } ?: error("无法读取：$title")
                    if (staged.length() <= 0L) error("文件为空：$title")
                    val hash = sha256(staged)
                    val id = importId(hash)
                    val dest = File(audioDir, "$id.audio")
                    if (dest.exists() && dest.length() == staged.length()) {
                        onProgress("已在库中：$title")
                        return@forEachIndexed
                    }
                    fileMutex.withLock {
                        if (dest.exists() && !dest.delete()) error("无法覆盖：$title")
                        if (!staged.renameTo(dest)) {
                            staged.copyTo(dest, overwrite = true)
                            staged.delete()
                        }
                        trackDao.upsertLocal(TrackEntity(
                            id = id,
                            platform = "local",
                            videoId = title,
                            title = title,
                            uploader = null,
                            durationSec = durationOf(dest),
                            audioBytes = dest.length(),
                            audioFormat = name.substringAfterLast('.', "m4a").lowercase(),
                            sourceUrl = "",
                            audioSha256 = hash,
                            localPath = dest.absolutePath,
                        ))
                    }
                    added++
                } finally {
                    staged.delete()
                }
            }
            onProgress("导入完成：$added 首")
            added
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun titleOf(name: String): String =
        name.substringAfterLast('/').substringBeforeLast('.').ifBlank { name }.trim()

    private fun durationOf(file: File): Double? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toDoubleOrNull()?.div(1000.0)
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun importId(hash: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest("import:$hash".toByteArray())
        val positive = java.nio.ByteBuffer.wrap(bytes).long and Long.MAX_VALUE
        return -(positive.takeIf { it > 0 } ?: 1L)
    }
}
