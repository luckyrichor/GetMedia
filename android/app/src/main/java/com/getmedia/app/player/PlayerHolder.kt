package com.getmedia.app.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.repo.DiagnosticsStore
import com.getmedia.app.data.repo.PlaylistRepository
import com.getmedia.app.data.repo.TrackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 连接到 [PlaybackService] 的控制器。
 *
 * 整个 App 共用一个 —— 多个控制器会各自持有播放状态，界面之间就对不上了。
 */
object PlayerHolder {

    enum class Mode(val label: String, val description: String) {
        LOOP("列表循环", "按列表顺序播放，结束后从头继续"),
        SINGLE("单曲循环", "重复播放当前音频"),
        ORDERED("顺序播放", "按列表顺序播放，最后一首结束后停止"),
        SHUFFLE("随机播放", "随机播放列表中的音频")
    }

    /**
     * 队列里的一项。
     *
     * [playable] 为 false 表示这一条**在列表里有位置、本机没有文件**（"云端未下载"的占位行）。
     * 它照样进队列：这样队列条数与列表一致，用户看到的"正在播放"和列表对得上；
     * 播放器转场到它时 [skipMissing] 会直接跳到下一首可播的。
     *
     * 为什么不干脆不放进去：Media3 的每一项都必须有可解析的 URI，队列里没有它，
     * 队列就会比列表少几条 —— 那正是"列表 9 首、正在播放 8 首"的来源。
     */
    data class QueueItem(val track: TrackEntity, val path: String, val playable: Boolean = true)
    private val queueState = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue = queueState.asStateFlow()
    private val sourcePlaylistIdState = MutableStateFlow<Long?>(null)
    val sourcePlaylistId = sourcePlaylistIdState.asStateFlow()
    private val indexState = MutableStateFlow(0)
    val currentIndex = indexState.asStateFlow()
    private val playingState = MutableStateFlow(false)
    val isPlaying = playingState.asStateFlow()
    private val modeState = MutableStateFlow(Mode.ORDERED)
    val mode = modeState.asStateFlow()
    private val timerDeadlineState = MutableStateFlow<Long?>(null)
    val timerDeadline = timerDeadlineState.asStateFlow()
    private val waitingForTrackEndState = MutableStateFlow(false)
    val waitingForTrackEnd = waitingForTrackEndState.asStateFlow()
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private val appendMutex = Mutex()
    private var followJob: Job? = null
    private var followPlaylistId: Long? = null

    @Volatile
    private var placeholderCache: String? = null

    @Volatile
    private var controller: MediaController? = null

    suspend fun get(context: Context): MediaController {
        controller?.let { if (it.isConnected) return it }
        return connect(context).also { connected ->
            controller = connected
            connected.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // 占位项（本机没文件）报错是预期内的：跳过它，别把它记成"播放错误"。
                    if (skipMissing(connected, afterError = true)) return
                    com.getmedia.app.data.repo.DiagnosticsStore.event("音频播放", "播放器错误：${error.errorCodeName}", false)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playingState.value = isPlaying
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val previousIndex = indexState.value
                    if (waitingForTrackEndState.value &&
                        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)) {
                        waitingForTrackEndState.value = false
                        connected.pause()
                        if (previousIndex in queueState.value.indices) connected.seekTo(previousIndex, 0)
                        timerScope.launch { applyMode(context) }
                    }
                    indexState.value = connected.currentMediaItemIndex.coerceAtLeast(0)
                    skipMissing(connected)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && waitingForTrackEndState.value) {
                        connected.pause()
                        waitingForTrackEndState.value = false
                        timerScope.launch { applyMode(context) }
                    }
                }
            })
        }
    }

    private suspend fun connect(context: Context): MediaController =
        suspendCancellableCoroutine { cont ->
            val token = SessionToken(
                context.applicationContext,
                ComponentName(context.applicationContext, PlaybackService::class.java),
            )
            val future = MediaController.Builder(context.applicationContext, token).buildAsync()
            future.addListener(
                { cont.resume(future.get()) },
                MoreExecutors.directExecutor(),
            )
        }

    suspend fun play(context: Context, track: TrackEntity, localPath: String) {
        playAll(context, listOf(QueueItem(track, localPath)))
    }

    private fun mediaItem(item: QueueItem) = MediaItem.Builder()
        .setUri(item.path)
        .setMediaId(item.track.id.toString())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.track.title)
            .setArtist(item.track.uploader ?: item.track.platform).build())
        .build()

    /**
     * 占位音源：0.2 秒静音 WAV，写在 cache 里。
     *
     * 队列里"本机没文件"的那一条也要有可解析的 URI —— 否则 Media3 会在转场时直接报错，
     * 报错后播放器进入 IDLE，得手动 prepare 才能继续，用户听到的是"卡住"。
     * 给一段静音就永远不会出错，转场到它时 [skipMissing] 立刻跳到下一首，实际听不到声音。
     */
    fun placeholder(context: Context): String {
        placeholderCache?.let { if (File(it).isFile) return it }
        val file = File(context.applicationContext.cacheDir, "queue-placeholder.wav")
        if (!file.isFile || file.length() <= 44L) {
            val sampleRate = 8000
            val frames = sampleRate / 5
            val dataSize = frames * 2
            val buffer = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
            buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            buffer.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            buffer.put("data".toByteArray()).putInt(dataSize)
            runCatching { file.writeBytes(buffer.array()) }
        }
        return file.absolutePath.also { placeholderCache = it }
    }

    private fun localFile(track: TrackEntity): String? =
        track.localPath?.takeIf { File(it).isFile && File(it).length() > 0 }

    private fun queueItem(context: Context, track: TrackEntity): QueueItem {
        val path = localFile(track)
        return if (path != null) QueueItem(track, path)
        else QueueItem(track, placeholder(context), playable = false)
    }

    @Volatile
    private var skipping = false

    /**
     * 当前项本机没有文件 → 跳到下一个可播的。返回 true 表示"已接管这次错误/转场"。
     *
     * 全部都没有文件时暂停（而不是继续 seek），否则会在队列里无限打转。
     */
    private fun skipMissing(player: MediaController, afterError: Boolean = false): Boolean {
        if (skipping) return false
        val items = queueState.value
        val index = player.currentMediaItemIndex
        val item = items.getOrNull(index) ?: return false
        if (item.playable) return false
        val target = nextPlayableIndex(items.map { it.playable }, index)
        skipping = true
        try {
            if (target == null) {
                player.pause()
                com.getmedia.app.data.repo.DiagnosticsStore.event("播放", "队列里没有已下载的音频，已暂停", false)
            } else {
                player.seekTo(target, 0)
                indexState.value = target
                if (afterError) {
                    // 出错后播放器可能已经进入 IDLE，必须重新 prepare 才播得动。
                    player.prepare()
                    player.play()
                } else if (player.playWhenReady) player.play()
            }
        } finally {
            skipping = false
        }
        return true
    }

    suspend fun playAll(context: Context, items: List<QueueItem>, playlistId: Long? = null) = appendMutex.withLock {
        val existingIds = TrackRepository(context).allNow().map { it.id }.toSet()
        val ids = newQueueTracks(emptyList(), items.map { it.track }).map { it.id }.toSet()
        val unique = items.distinctBy { it.track.id }
            .filter { it.track.id in ids && it.track.id in existingIds }
            .map { item ->
                // 调用方给的路径可能已经失效（下载被清理、文件被移动）：那就按"本机没文件"处理。
                if (item.playable && (item.path.isBlank() || !File(item.path).isFile)) queueItem(context, item.track) else item
            }
        if (unique.isEmpty()) return@withLock
        val lists = PlaylistRepository(context)
        val sourceId = playlistId ?: lists.ensureDefault()
        if (playlistId == null) lists.replaceTracks(sourceId, unique.map { it.track.id })
        // 起点落在第一个有文件的条目上：占位项不开播，否则会先响 0.2 秒静音再跳。
        val start = nextPlayableIndex(unique.map { it.playable }, -1) ?: 0
        sourcePlaylistIdState.value = sourceId
        queueState.value = unique
        indexState.value = start
        get(context).run {
            setMediaItems(unique.map(::mediaItem), start, 0L)
            prepare()
            play()
        }
        applyMode(context)
    }

    suspend fun append(context: Context, items: List<QueueItem>): Int = appendMutex.withLock {
        appendLocked(context, items)
    }

    private suspend fun appendLocked(context: Context, items: List<QueueItem>): Int {
        if (items.isEmpty()) return 0
        val repository = TrackRepository(context)
        check(items.all { repository.byId(it.track.id) != null }) { "音频已移除或账号已切换" }
        val player = get(context)
        val lists = PlaylistRepository(context)
        val sourceId = lists.resolve(sourcePlaylistIdState.value)
        if (queueState.value.isEmpty()) {
            // 列表里的每一条都进队列：缺文件的用占位音源（播到时跳过）。
            // 以前这里会**同步下载**所有缺文件的条目，点一首歌要等整张列表下完才出声。
            val members = lists.tracks(sourceId).distinctBy { it.id }
            sourcePlaylistIdState.value = sourceId
            if (members.isEmpty()) {
                player.clearMediaItems()
            } else {
                val restored = members.map { queueItem(context, it) }
                val start = nextPlayableIndex(restored.map { it.playable }, -1) ?: 0
                queueState.value = restored
                indexState.value = start
                player.setMediaItems(restored.map(::mediaItem), start, 0L)
                player.prepare()
            }
        }
        val acceptedIds = newQueueTracks(queueState.value.map { it.track }, items.map { it.track }).map { it.id }.toSet()
        val additions = items.distinctBy { it.track.id }
            .filter { it.track.id in acceptedIds }
            .map { item ->
                if (item.playable && (item.path.isBlank() || !File(item.path).isFile)) queueItem(context, item.track) else item
            }
        if (additions.isEmpty()) return 0
        lists.addTracks(sourceId, additions.map { it.track.id })
        // Recheck after database suspension so rapid repeated taps cannot enqueue duplicates.
        val finalIds = newQueueTracks(queueState.value.map { it.track }, additions.map { it.track }).map { it.id }.toSet()
        val finalItems = additions.filter { it.track.id in finalIds }
        if (finalItems.isEmpty()) return 0
        val wasEmpty = queueState.value.isEmpty()
        sourcePlaylistIdState.value = sourceId
        if (wasEmpty) {
            // Media3 1.3.1 addMediaItems after an empty timeline can retain INDEX_UNSET (-1).
            // Explicitly start the first item at zero; append preserves an existing queue's position.
            player.setMediaItems(finalItems.map(::mediaItem), 0, 0L)
            player.prepare()
            indexState.value = 0
        } else player.addMediaItems(finalItems.map(::mediaItem))
        queueState.value = queueState.value + finalItems
        return finalItems.size
    }

    /** Play a library selection inside the current list, keeping all its existing entries. */
    suspend fun playSelection(context: Context, items: List<QueueItem>) {
        if (items.isEmpty()) return
        append(context, items)
        val selected = items.first().track
        val position = queueState.value.indexOfFirst { item ->
            newQueueTracks(listOf(item.track), listOf(selected)).isEmpty()
        }
        if (position >= 0) jump(context, position)
    }

    suspend fun jump(context: Context, index: Int) {
        if (index !in queueState.value.indices) return
        val player = get(context)
        player.seekTo(index, 0)
        player.play()
        // seekTo 可能同步回调转场：如果落点是"本机没文件"的条目，[skipMissing] 已经把它
        // 跳到下一首并设好下标。这里再写死 index 会把那个结果覆盖掉 —— 界面标题停在
        // 一首没在播的歌上，进度却是下一首的。
        if (!skipMissing(player)) indexState.value = player.currentMediaItemIndex.coerceAtLeast(0)
    }

    /**
     * 手动切下一首，遵循当前的播放方式：
     * 随机 → 随机挑一首别的；单曲循环 → 也换歌（用户主动点了「下一首」就是要换）；
     * 列表循环 / 顺序播放 → 顺次下一首，到末尾回到第一首。
     */
    suspend fun next(context: Context) {
        val list = queueState.value
        if (list.isEmpty()) return
        if (list.size == 1) { jump(context, 0); return }
        val current = indexState.value.coerceIn(0, list.lastIndex)
        val target = if (modeState.value == Mode.SHUFFLE) {
            list.indices.filter { it != current }.random()
        } else {
            (current + 1) % list.size
        }
        jump(context, target)
    }

    /** Move existing Media3 items: the current song, position and paused/playing state survive. */
    suspend fun reorder(context: Context, playlistId: Long?, trackIds: List<Long>): Boolean = appendMutex.withLock {
        if (playlistId == null || playlistId != sourcePlaylistIdState.value) return@withLock false
        val original = queueState.value
        val moves = queueReorderMoves(original.map { it.track.id }, trackIds) ?: return@withLock false
        val player = get(context)
        if (player.mediaItemCount != original.size || original.indices.any {
            player.getMediaItemAt(it).mediaId != original[it].track.id.toString()
        }) return@withLock false
        // Once the transaction starts, finish applying it even if the dialog is dismissed.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            if (!PlaylistRepository(context).reorder(playlistId, trackIds)) return@withContext false
            moves.forEach { (from, to) -> player.moveMediaItem(from, to) }
            // A concurrent rename may have refreshed metadata while Room was saving.
            val byId = queueState.value.associateBy { it.track.id }
            queueState.value = trackIds.map { byId.getValue(it) }
            indexState.value = player.currentMediaItemIndex.coerceAtLeast(0)
            true
        }
    }

    suspend fun remove(context: Context, index: Int) = appendMutex.withLock {
        if (index !in queueState.value.indices) return@withLock
        val removed = queueState.value[index]
        sourcePlaylistIdState.value?.let { PlaylistRepository(context).removeTrack(it, removed.track.id) }
        get(context).removeMediaItem(index)
        queueState.value = queueState.value.toMutableList().also { it.removeAt(index) }
        indexState.value = get(context).currentMediaItemIndex.coerceAtLeast(0)
    }

    /** Deleting a saved list removes references, never library audio files. */
    suspend fun deletePlaylist(context: Context, id: Long) = appendMutex.withLock {
        val lists = PlaylistRepository(context)
        val current = lists.resolve(sourcePlaylistIdState.value)
        lists.delete(id)
        if (current == id) {
            get(context).run { stop(); clearMediaItems() }
            queueState.value = emptyList()
            indexState.value = 0
            playingState.value = false
            sourcePlaylistIdState.value = lists.ensureDefault()
            cancelTimer()
        }
    }

    /** Library deletion removes every occurrence without restarting surviving audio. */
    suspend fun removeTracks(context: Context, deletedIds: Set<Long>) = appendMutex.withLock {
        val remaining = queueState.value.toMutableList()
        val indices = queueRemovalIndices(remaining.map { it.track.id }, deletedIds)
        if (indices.isEmpty()) return@withLock
        val player = get(context)
        indices.forEach { index ->
            player.removeMediaItem(index)
            remaining.removeAt(index)
        }
        queueState.value = remaining
        indexState.value = player.currentMediaItemIndex.coerceAtLeast(0)
        if (remaining.isEmpty()) {
            player.pause()
            playingState.value = false
            indexState.value = 0
            cancelTimer()
        }
    }

    /** 更新队列及系统媒体信息；仅替换元数据，保留音源和播放进度。 */
    suspend fun rename(context: Context, id: Long, title: String) {
        val oldQueue = queueState.value
        if (oldQueue.none { it.track.id == id }) return
        val updated = oldQueue.map { item ->
            if (item.track.id == id) item.copy(track = item.track.copy(title = title)) else item
        }
        val player = get(context)
        updated.forEachIndexed { index, item ->
            if (item.track.id == id && index < player.mediaItemCount) {
                val original = player.getMediaItemAt(index)
                player.replaceMediaItem(index, original.buildUpon().setMediaMetadata(
                    original.mediaMetadata.buildUpon().setTitle(title).build()
                ).build())
            }
        }
        queueState.value = updated
    }

    suspend fun edited(context: Context, track: TrackEntity) = appendMutex.withLock {
        val old = queueState.value
        if (old.none { it.track.id == track.id }) return@withLock
        val player = get(context)
        val current = player.currentMediaItemIndex
        val updated = old.map { if (it.track.id == track.id) QueueItem(track, track.localPath!!) else it }
        if (old.getOrNull(current)?.track?.id == track.id) player.pause()
        updated.forEachIndexed { index, item ->
            if (item.track.id == track.id) player.replaceMediaItem(index, mediaItem(item))
        }
        if (old.getOrNull(current)?.track?.id == track.id) player.seekTo(current, 0)
        queueState.value = updated
    }

    private fun hasLiveQueue(): Boolean = queueState.value.isNotEmpty() &&
        controller?.let { it.isConnected && it.mediaItemCount > 0 } == true

    /** Cold start: recover cached, currently visible references, without network or autoplay. */
    suspend fun restore(context: Context) {
        if (hasLiveQueue()) return
        appendMutex.withLock {
            if (hasLiveQueue()) return@withLock
            val memory = PlaybackMemory(context)
            val saved = memory.read()
            val lists = PlaylistRepository(context)
            val source = saved?.playlistId ?: lists.defaultId() ?: return@withLock
            val restored = lists.tracks(source).distinctBy { it.id }.map { queueItem(context, it) }
            if (restored.isEmpty()) {
                memory.save(null)
                queueState.value = emptyList()
                indexState.value = 0
                playingState.value = false
                controller?.takeIf { it.isConnected }?.run { stop(); clearMediaItems() }
                cancelTimer()
                return@withLock
            }
            val savedTrack = restored.firstOrNull { it.track.id == saved?.trackId }?.track
            val duration = savedTrack?.durationSec?.takeIf { it.isFinite() && it > 0 }?.times(1000)?.toLong()
            val point = saved?.resumePoint(restored.map { it.track.id }, duration) ?: ResumePoint(0, 0)
            // 恢复点如果正好是"本机没文件"的条目，就近落到下一个可播的，避免一进 App 就静音。
            val index = if (restored.getOrNull(point.index)?.playable == false) {
                nextPlayableIndex(restored.map { it.playable }, point.index) ?: point.index
            } else point.index
            val player = get(context)
            sourcePlaylistIdState.value = source
            modeState.value = Mode.entries.firstOrNull { it.name == saved?.mode } ?: Mode.ORDERED
            queueState.value = restored
            indexState.value = index
            playingState.value = false
            player.playWhenReady = false
            player.setMediaItems(restored.map(::mediaItem), index, point.positionMs)
            player.prepare()
            applyMode(context)
        }
    }

    /**
     * 让队列跟随某个播放列表：另一台设备加/删/调序后，这里自动对齐。
     *
     * 队列本来就是"列表的播放视图"（队列里增删、拖动排序都会写回列表），
     * 所以跟随不是新概念，只是补上了"外部变化"这一路。对齐时当前这首不中断：
     * 位置、进度、播放/暂停状态都保留；当前曲被移出列表才顺延到原位置的下一首。
     */
    suspend fun follow(context: Context, playlistId: Long) {
        if (followPlaylistId == playlistId && followJob?.isActive == true) return
        followJob?.cancel()
        followPlaylistId = playlistId
        followJob = timerScope.launch {
            PlaylistRepository(context).items(playlistId).collect { entries ->
                try {
                    align(context, entries.map { it.trackId })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // 一轮对齐失败（数据库正在切换账号等）不该让跟随停下来，下一轮会再来。
                }
            }
        }
    }

    fun stopFollowing() {
        followJob?.cancel()
        followJob = null
        followPlaylistId = null
    }

    /** 下载完成后调用：把队列里"没有文件"的条目换成真实路径，播到时就不用跳过了。 */
    suspend fun refreshFiles(context: Context) = appendMutex.withLock {
        val items = queueState.value
        if (items.none { !it.playable }) return@withLock
        val repo = TrackRepository(context)
        val updated = items.map { item ->
            if (item.playable) item else {
                val track = repo.byId(item.track.id) ?: item.track
                localFile(track)?.let { QueueItem(track, it) } ?: item.copy(track = track)
            }
        }
        if (sameQueue(items, updated)) return@withLock
        applyQueue(context, updated)
    }

    private suspend fun align(context: Context, trackIds: List<Long>) = appendMutex.withLock {
        if (trackIds.isEmpty()) return@withLock
        val repo = TrackRepository(context)
        val target = trackIds.mapNotNull { repo.byId(it) }
        if (target.isEmpty()) return@withLock
        val current = queueState.value
        // 已有条目原样复用（尤其是正在播的那一首），顺序按列表重排。
        val aligned = target.map { track ->
            val old = current.firstOrNull { it.track.id == track.id }
            if (old != null && old.playable && File(old.path).isFile) old else queueItem(context, track)
        }
        if (sameQueue(current, aligned)) return@withLock
        applyQueue(context, aligned)
    }

    private fun sameQueue(a: List<QueueItem>, b: List<QueueItem>): Boolean =
        a.size == b.size && a.indices.all {
            a[it].track.id == b[it].track.id && a[it].playable == b[it].playable && a[it].path == b[it].path
        }

    private suspend fun applyQueue(context: Context, items: List<QueueItem>) {
        val player = get(context)
        val old = queueState.value
        val currentId = old.getOrNull(indexState.value)?.track?.id
        val wasPlaying = player.playWhenReady || player.isPlaying
        val kept = currentId?.let { id -> items.indexOfFirst { it.track.id == id } }?.takeIf { it >= 0 }
        val newIndex = kept ?: indexState.value.coerceIn(0, items.lastIndex)
        // 当前曲还在才保留进度；它被移出列表了就从头开始，别跳到半截。
        val position = if (kept != null && player.playbackState != Player.STATE_IDLE) player.currentPosition else 0L
        queueState.value = items
        indexState.value = newIndex
        player.setMediaItems(items.map(::mediaItem), newIndex, position)
        player.prepare()
        if (wasPlaying) player.play() else player.pause()
        skipMissing(player)
        applyMode(context)
    }

    fun savePlayback(context: Context, synchronous: Boolean = false) {
        controller?.takeIf { it.isConnected }?.let { player ->
            playbackSnapshot(player)?.let { PlaybackMemory(context).save(it, synchronous) }
        }
    }

    suspend fun toggle(context: Context) {
        get(context).run { if (isPlaying) pause() else play() }
    }

    suspend fun setMode(context: Context, value: Mode) {
        modeState.value = value
        applyMode(context)
    }

    private suspend fun applyMode(context: Context) {
        get(context).run {
            shuffleModeEnabled = modeState.value == Mode.SHUFFLE
            repeatMode = playbackRepeatMode(modeState.value, waitingForTrackEndState.value)
        }
    }

    fun setTimer(context: Context, minutes: Long, finishTrack: Boolean) {
        timerJob?.cancel()
        val deadline = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
        timerDeadlineState.value = deadline
        waitingForTrackEndState.value = false
        timerJob = timerScope.launch {
            applyMode(context)
            delay(minutes * 60_000L)
            if (timerDeadlineState.value == deadline) {
                timerDeadlineState.value = null
                if (finishTrack && get(context).isPlaying) {
                    waitingForTrackEndState.value = true
                    applyMode(context)
                } else get(context).pause()
            }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerDeadlineState.value = null
        waitingForTrackEndState.value = false
        controller?.let { it.repeatMode = playbackRepeatMode(modeState.value, false) }
    }

    fun release() {
        controller?.release()
        controller = null
    }
}
