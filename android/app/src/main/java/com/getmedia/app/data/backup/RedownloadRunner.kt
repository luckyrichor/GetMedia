package com.getmedia.app.data.backup

import androidx.room.withTransaction
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.repo.DiagnosticsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 恢复后的"全部重新下载"（设计 §5.6）。
 *
 * 批量提取没有显式 sleep，限速来自每条几十秒的真实耗时；重新下载可能秒级命中或秒级失败，
 * 少了这个天然间隔就是对着平台刷请求。所以这里补两件事：
 * - **条间隔 3 秒 ±1 秒**，只在同一轮内，跨轮不累计；
 * - **连续失败 3 条熔断**，暂停整轮并保留进度，等用户决定 [继续] 还是 [停止]。
 *
 * 并发固定为 1；单条失败不影响后续，末尾汇总成功/失败。`sourceUrl` 为空（本地导入的作品）
 * 直接跳过、标"需重新导入"，不计入失败也不触发熔断。
 */
class RedownloadRunner(
    private val db: AppDatabase,
    /** 注入是为了单测：生产就是 [com.getmedia.app.data.localextract.DirectExtractor.extract]。 */
    private val extract: suspend (String, MediaKind) -> TrackEntity,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) {
    data class State(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val ok: Int = 0,
        val failed: Int = 0,
        /** 需要用户重新导入（没有来源链接），既不算成功也不算失败。 */
        val needsImport: Int = 0,
        val current: String? = null,
        /** 非空表示已暂停：熔断原因或用户手动停止。 */
        val paused: String? = null,
        val finished: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var queue: MutableList<Long> = mutableListOf()

    /** 3 秒 ±1 秒：抖动是为了避免多台设备在同一秒一起打过去。 */
    fun intervalMs(): Long = INTERVAL_MS + random.nextLong(-JITTER_MS, JITTER_MS + 1)

    fun start(trackIds: List<Long>) {
        if (job?.isActive == true) return
        queue = trackIds.toMutableList()
        _state.value = State(running = true, total = queue.size)
        job = scope.launch { run() }
    }

    fun resume() {
        if (job?.isActive == true || queue.isEmpty()) return
        _state.value = _state.value.copy(running = true, paused = null)
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel(); job = null
        if (_state.value.running) {
            _state.value = _state.value.copy(running = false, paused = "已停止")
        }
    }

    private suspend fun run() {
        var consecutive = 0
        while (queue.isNotEmpty()) {
            val id = queue.first()
            val track = db.trackDao().byId(id)
            if (track == null) { queue.removeAt(0); continue }

            // 已经有文件（上一轮成功过）→ 直接跳过，不占一次请求也不用等间隔
            if (track.localPath != null) {
                queue.removeAt(0)
                _state.value = _state.value.copy(done = _state.value.done + 1)
                continue
            }
            if (track.sourceUrl.isBlank()) {
                queue.removeAt(0)
                _state.value = _state.value.copy(
                    done = _state.value.done + 1,
                    needsImport = _state.value.needsImport + 1,
                )
                continue
            }

            _state.value = _state.value.copy(current = track.title)
            val result = try {
                extract(withPage(track.sourceUrl, track.serverPage), runCatching { MediaKind.valueOf(track.mediaType) }.getOrDefault(MediaKind.AUDIO))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                DiagnosticsStore.event("重新下载", "${track.title}：${error.message ?: "失败"}", false)
                null
            }

            if (result != null) {
                // 强制用恢复出来的 id 写回：URL 里的页码若与快照不一致，也不会多出一行。
                // syncState 与 serverPage 也照抄原行：重新下载只是把文件补回来，这条从来不是
                // "本机新增"；页码是这行的身份（P2 不能因为下载器不认识 ?p=2 就变回 P1）。
                db.trackDao().upsertLocal(
                    result.copy(id = track.id, syncState = track.syncState, serverPage = track.serverPage)
                )
                queue.removeAt(0)
                consecutive = 0
                _state.value = _state.value.copy(
                    done = _state.value.done + 1,
                    ok = _state.value.ok + 1,
                )
            } else {
                queue.removeAt(0)
                consecutive++
                _state.value = _state.value.copy(
                    done = _state.value.done + 1,
                    failed = _state.value.failed + 1,
                )
                if (consecutive >= BREAKER) {
                    _state.value = _state.value.copy(
                        running = false,
                        current = null,
                        paused = "已连续 $BREAKER 条失败，可能是链接失效或平台限流，已暂停",
                    )
                    DiagnosticsStore.event("重新下载", "连续 $BREAKER 条失败，已暂停", false)
                    return
                }
            }

            if (queue.isNotEmpty()) sleep(intervalMs())
        }
        _state.value = _state.value.copy(running = false, current = null, finished = true)
    }

    /** 恢复后统计"待下载"用：本地没有文件、且能从链接重新取的那些。 */
    suspend fun pendingIds(): List<Long> = db.withTransaction {
        db.trackDao().allNow().filter { it.localPath == null && it.sourceUrl.isNotBlank() }.map { it.id }
    }

    companion object {
        const val INTERVAL_MS = 3_000L
        const val JITTER_MS = 1_000L
        const val BREAKER = 3
    }
}

private val PAGE_PARAM = Regex("[?&]p=\\d+")

/**
 * 把分 P 页码补回链接。
 *
 * 手机直提取写的 `sourceUrl` 一定带 `?p=N`，但服务器提取写的只有
 * `https://www.bilibili.com/video/<bvid>`（`server/getmedia/extractor.py`），页码只存在
 * `serverPage` 列里。恢复出来的行如果照着这个链接去取，P2 会下成 P1 的音轨写进 P2 那一行。
 *
 * 链接里已经有 `p=` 时以链接为准——用户看得到的链接不该被改。
 */
internal fun withPage(url: String, page: Int): String {
    if (page <= 1 || !url.contains("bilibili.com", ignoreCase = true)) return url
    if (PAGE_PARAM.containsMatchIn(url)) return url
    return url + if ('?' in url) "&p=$page" else "?p=$page"
}
