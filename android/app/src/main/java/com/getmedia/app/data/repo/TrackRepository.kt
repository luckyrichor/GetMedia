package com.getmedia.app.data.repo

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.backup.BackupManager
import com.getmedia.app.data.cloud.cloudSync
import com.getmedia.app.player.PlayerHolder
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.SyncState
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

class TrackRepository(
    private val context: Context,
    private val db: AppDatabase = AppDatabase.get(context),
) {

    companion object {
        internal val fileMutex = Mutex()
    }

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
        BackupManager.noteWrite()
    }

    suspend fun byId(id: Long): TrackEntity? = trackDao.byId(id)

    /**
     * 磁盘上已经有文件、库字段还没跟上时，把字段补成一致（0.1.16，不新增行）。
     *
     * 为什么需要：列表上的「云端未下载」读库字段，条目菜单读磁盘。两者不一致时会出现
     * "菜单里已经没有『下载到本机』，列表还标着云端未下载"。下载入口在"磁盘已有文件"
     * 的早返回分支上调这里，把差异当场抹平，而不是等下一次同步。
     *
     * @param state 这一行该归一成的同步状态（有服务器身份给 `SYNCED`，否则 `LOCAL_ONLY`）
     * @param replaceStates 只替换这些状态值；别的状态（待推送草稿、已脱钩）原样保留
     */
    suspend fun markFilePresent(
        id: Long,
        file: File,
        state: String,
        replaceStates: Collection<String>,
    ): TrackEntity? = withContext(Dispatchers.IO) {
        val bytes = file.length()
        if (bytes <= 0L || !file.isFile) return@withContext null
        trackDao.markFilePresent(
            id, file.absolutePath, bytes, state, replaceStates.toList(), System.currentTimeMillis(),
        )
        BackupManager.noteWrite()
        trackDao.byId(id)
    }

    suspend fun rename(id: Long, title: String) {
        require(title.isNotBlank()) { "音频名称不能为空" }
        val clean = title.trim()
        val track = trackDao.byId(id)
        val now = System.currentTimeMillis()
        // 已绑定服务器 ID 的行：full 只写待推送草稿；其它行走原来的本机改名（free 永远走这里）
        val handled = track != null && withContext(Dispatchers.IO) {
            db.withTransaction { cloudSync.onTitleEdited(db, track, clean, now) }
        }
        if (!handled) trackDao.rename(id, clean, now)
        withContext(Dispatchers.Main) {
            com.getmedia.app.player.PlayerHolder.rename(context, id, clean)
        }
        BackupManager.noteWrite()
    }

    suspend fun remove(id: Long, cloud: Boolean = false) =
        removeAll(setOf(id), if (cloud) setOf(id) else emptySet())

    /**
     * Delete metadata and every playlist reference in one transaction, then reconcile playback.
     *
     * `cloudIds` 是本机确认要"同时从云端曲库移除"的条目。队列写入与删行在同一事务里：
     * 不会出现行已经删了、删除意图却没记下来的情况。归档行即使被传进来也会被服务端拒绝，
     * 客户端只按已知 storage 提前跳过。
     */
    suspend fun removeAll(ids: Set<Long>, cloudIds: Set<Long> = emptySet()): Int {
        if (ids.isEmpty()) return 0
        return withContext(NonCancellable) {
            val removed = withContext(Dispatchers.IO) {
                fileMutex.withLock {
                    db.withTransaction {
                        val tracks = ids.mapNotNull { trackDao.byId(it) }
                        val account = SessionStore.current.value?.loginName
                        if (account != null && cloudIds.isNotEmpty()) {
                            cloudSync.queueRemovals(db, account, tracks, cloudIds, System.currentTimeMillis())
                        }
                        // Bound query size so selecting a large library cannot exceed SQLite bind limits.
                        tracks.map { it.id }.chunked(500).forEach {
                            trackDao.removePlaylistReferences(it)
                            trackDao.deleteByIds(it)
                        }
                        tracks
                    }.also { removed -> removed.forEach { it.localPath?.let { path -> File(path).delete() }; CoverCache.forget(context, it.id) } }
                }
            }
            withContext(Dispatchers.Main) { PlayerHolder.removeTracks(context, removed.map { it.id }.toSet()) }
            BackupManager.noteWrite()
            removed.size
        }
    }

    /**
     * 移除一条「恢复空壳」（阶段 6）：清单恢复出来、没有服务器身份、本机也没有文件的行。
     *
     * 与普通删除的区别有两个：
     * - **不写云端删除队列**（`cloudIds` 留空）：云端本来就没有这一条（或已经是墓碑），再删一次是多余请求；
     * - **不碰播放队列**：空壳没有文件，本来就进不了队列（入队必须先取到文件），
     *   所以这里不需要 `PlayerHolder` 的那次主线程调度，避免为了删一行把主线程拉进来。
     *
     * 有本机文件的行一律拒绝：那种情况要走普通删除，那里才有"文件也删掉吗"的确认。
     */
    suspend fun removeShell(id: Long): Int = withContext(Dispatchers.IO) {
        val row = trackDao.byId(id) ?: return@withContext 0
        require(row.localPath == null || !File(row.localPath).exists()) {
            "这条有本机文件，请用普通删除"
        }
        db.withTransaction {
            trackDao.removePlaylistReferences(listOf(id))
            trackDao.deleteByIds(listOf(id))
        }
        CoverCache.forget(context, id)
        BackupManager.noteWrite()
        1
    }

    suspend fun setFavorite(id: Long, fav: Boolean) {
        trackDao.setFavorite(id, fav)
        BackupManager.noteWrite()
    }

    // ---------- 提取 ----------

    /** 分享文本由手机直接提取，保存到本地库；不经过任何服务器。 */
    suspend fun extract(text: String, kind: MediaKind = MediaKind.AUDIO, onDiagnostic: (String) -> Unit = {}): Result<TrackEntity> = runCatching {
        withContext(Dispatchers.IO) {
            val extractor = DirectExtractor(context) { line ->
                withContext(Dispatchers.Main) { onDiagnostic(line) }
            }
            val entity = if (cloudSync.supportsManagedRestores) {
                val staged = File.createTempFile("extract-", ".part", context.cacheDir)
                try {
                    adoptStaged(extractor.extractTo(text, kind, staged, verifyIdentity = false), staged)
                } finally {
                    staged.delete()
                }
            } else fileMutex.withLock {
                val extracted = extractor.extract(text, kind)
                trackDao.upsertLocal(extracted)
                BackupManager.noteWrite()
                extracted
            }
            withContext(Dispatchers.Main) { onDiagnostic("已加入本地媒体库") }
            entity
        }
    }

    // ---------- 下载 ----------

    /**
     * 把「已经在本地落好、但还没入库」的暂存文件纳入媒体库。
     *
     * full 版的服务器提取用它：服务器下载完的文件先写成 `.part`，校验长度后再由这里
     * 搬到最终路径、算 sha256、写一行。`id` 由调用方按 [com.getmedia.app.data.local.LocalIds]
     * 算好，因此同一作品和本机提取共用一行：收藏、分类、重命名、入库时间都保留。
     *
     * 只有本方法成功返回，库里才会出现这一行——文件与记录始终一一对应。
     */
    suspend fun adoptStaged(entity: TrackEntity, staged: File): TrackEntity = withContext(Dispatchers.IO) {
        require(staged.isFile && staged.length() > 0) { "下载结果为空" }
        fileMutex.withLock {
            val video = MediaKind.of(entity.mediaType) == MediaKind.VIDEO
            val dir = File(context.filesDir, if (video) "video" else "audio").apply { mkdirs() }
            val dest = File(dir, if (video) "${entity.id}.mp4" else "${entity.id}.audio")
            val (saved, previousPath) = db.withTransaction {
                val existing = trackDao.byId(entity.id)
                check(existing == null || !cloudSync.protectsRecovery(existing, SessionStore.current.value)) {
                    "曲库已有此项，请从原条目或「恢复条目」下载"
                }
                if (dest.exists() && !dest.delete()) error("无法覆盖本地文件")
                if (!staged.renameTo(dest)) {
                    staged.copyTo(dest, overwrite = true)
                    staged.delete()
                }
                val now = System.currentTimeMillis()
                val record = entity.copy(
                    localPath = dest.absolutePath,
                    audioBytes = dest.length(),
                    audioSha256 = sha256(dest),
                    favorite = existing?.favorite ?: false,
                    localCategoryId = existing?.localCategoryId ?: entity.localCategoryId,
                    localTitleOverride = existing?.localTitleOverride,
                    title = existing?.localTitleOverride ?: entity.title,
                    addedAtMs = existing?.addedAtMs?.takeIf { it > 0 } ?: now,
                    sortIndex = existing?.sortIndex ?: entity.sortIndex,
                    updatedAtMs = now,
                )
                trackDao.upsertAll(listOf(record))
                record to existing?.localPath
            }
            BackupManager.noteWrite()
            // 覆盖到同一行时，旧路径（例如本机剪辑副本）留着就是孤儿文件
            previousPath?.takeIf { it != dest.absolutePath }?.let { File(it).delete() }
            saved
        }
    }

    /** 已校验的文件只补到原行；UPDATE 保留引用原主键的播放列表条目。 */
    suspend fun fillExistingFile(
        original: TrackEntity,
        staged: File,
        bytes: Long,
        hash: String,
        serverSeq: Long?,
        serverUpdatedAtMs: Long?,
        sameSession: () -> Boolean,
    ): TrackEntity = fillOriginal(original, staged, bytes, hash, serverSeq, serverUpdatedAtMs, null, null, sameSession)

    internal suspend fun fillRestoredFile(
        original: TrackEntity,
        staged: File,
        bytes: Long,
        hash: String,
        format: String,
        account: String,
        sameSession: () -> Boolean,
    ): TrackEntity = fillOriginal(original, staged, bytes, hash, null, null, account, format, sameSession)

    private suspend fun fillOriginal(
        original: TrackEntity,
        staged: File,
        bytes: Long,
        hash: String,
        serverSeq: Long?,
        serverUpdatedAtMs: Long?,
        recoveredBy: String?,
        format: String?,
        sameSession: () -> Boolean,
    ): TrackEntity = withContext(Dispatchers.IO + NonCancellable) {
        fileMutex.withLock {
            var dest: File? = null
            val saved = try {
                db.withTransaction {
                    val existing = trackDao.byId(original.id)
                    check(sameSession() && existing != null && existing.serverId == original.serverId &&
                        existing.accountKey == original.accountKey && existing.platform == original.platform &&
                        existing.videoId == original.videoId && existing.serverPage == original.serverPage &&
                        existing.mediaType == original.mediaType) { "账号或原条目已变化，请重新打开" }
                    if (recoveredBy != null) {
                        check(existing.syncState == SyncState.RESTORED.name && existing.serverId == null &&
                            existing.localPath == null && existing.sourceUrl == original.sourceUrl) {
                            "恢复条目已变化，请重新核对"
                        }
                    }
                    if (existing.localPath?.let { File(it).isFile && File(it).length() > 0 } == true) {
                        return@withTransaction existing
                    }
                    check(existing.syncState == original.syncState) { "原条目已变化，请重新打开" }
                    val video = MediaKind.of(existing.mediaType) == MediaKind.VIDEO
                    val dir = File(context.filesDir, if (video) "video" else "audio").apply { mkdirs() }
                    val file = File.createTempFile("refill-${existing.id}-", if (video) ".mp4" else ".audio", dir)
                    dest = file
                    staged.copyTo(file, overwrite = true)
                    check(file.length() == bytes && bytes > 0 && sha256(file) == hash) { "本机文件校验失败" }
                    check(sameSession()) { "账号已变化，请重新打开" }
                    val updated = existing.copy(
                        localPath = file.absolutePath, audioBytes = bytes, audioSha256 = hash,
                        audioFormat = format ?: if (video) "mp4" else "m4a",
                        accountKey = recoveredBy ?: existing.accountKey,
                        syncState = if (recoveredBy == null) SyncState.SYNCED.name else SyncState.LOCAL_ONLY.name,
                        serverSeq = serverSeq,
                        serverUpdatedAtMs = serverUpdatedAtMs, updatedAtMs = System.currentTimeMillis(),
                    )
                    check(trackDao.updateExisting(updated) == 1) { "原条目已变化，请重新打开" }
                    updated
                }
            } catch (e: Exception) {
                dest?.delete()
                throw e
            }
            staged.delete()
            if (dest != null) BackupManager.noteWrite()
            saved
        }
    }

    fun localFile(id: Long): File = File(audioDir, "$id.audio")

    /**
     * 返回曲目的本地文件。GetMedia 免费版没有服务器下载：文件只来自本机提取或导入，
     * 文件缺失时只能重新提取。保留 [Result] 形式，调用方统一处理失败。
     *
     * 两种情况必须分开说（P0-2 阶段 3）：`localPath == null` **且**这行有服务器身份
     * （`serverId` 非空，即"云端有、本机没下过"）→ 是"还没下载"，不是"文件丢了"。
     * 说成"已丢失，请重新提取"会让人去重提取一遍，白做；真正的丢失是下面那条：
     * 有 `localPath` 但文件不在了。
     *
     * 0.1.13 又拆出第三种：从云端清单恢复出来的空壳（`RESTORED`、没有 `serverId`、
     * 也没有 `localPath`）。它本来就没有过文件，说"已丢失"同样会误导。
     */
    suspend fun download(track: TrackEntity): Result<File> = runCatching {
        withContext(Dispatchers.IO) { fileMutex.withLock {
            val current = trackDao.byId(track.id) ?: error("音频已被移除")
            val file = current.localPath?.let(::File)
            if (file != null && file.exists() && file.length() > 0) return@withLock file
            if (current.locallyEdited) error("本地剪辑文件已丢失，请重新剪辑")
            if (current.localPath == null && current.serverId != null) error("尚未下载到本机，请先下载")
            // 恢复出来的空壳（清单回来了、文件从没下过）以前也落到最后一条"已丢失"，
            // 让人以为文件被系统清了。它本来就没有过文件，说法要分开（0.1.13）。
            if (current.localPath == null && SyncState.of(current.syncState) == SyncState.RESTORED) {
                error("这条是从云端清单恢复的，本机还没有文件")
            }
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
                BackupManager.noteWrite()
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
                    BackupManager.noteWrite()
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
