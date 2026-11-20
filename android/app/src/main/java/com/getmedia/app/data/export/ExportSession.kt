package com.getmedia.app.data.export

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一次导出的运行状态机（串行、可取消、可中断、可只重试失败项）。
 *
 * 不依赖 Android：节点 B 由确认页在应用内协程里跑，节点 C 由 `ExportService` 跑，同一份实现。
 * 进度放在 [StateFlow] 里，页面销毁不影响；进程被杀即结束（本期不做队列持久化）。
 */
class ExportSession(
    /** 冻结下来的待导出项：本次运行期间库变化不影响它。 */
    val items: List<ExportPlan.Item>,
    private val writer: ExportWriter,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 单调墙钟（毫秒）；单测注入假时钟，用来验证 5 小时时限。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    enum class Status { DONE, FAILED, SKIPPED }

    /**
     * 中断原因。文案固定在这里，通知（节点 C）与摘要页共用同一句，
     * 避免"系统中断"被写成"失败"让用户以为文件坏了。
     */
    enum class Reason(val text: String) {
        /** 用户点了取消。 */
        USER_CANCEL("已取消导出"),

        /** Android 15 的 dataSync 前台服务到点被系统回调 `onTimeout`。 */
        SYSTEM_TIMEOUT("导出被系统中断，可重试失败项"),

        /** 服务内自保护：单次导出跑满 5 小时主动收尾，不赌系统一定回调 `onTimeout`。 */
        TIME_LIMIT("导出时间过长，已主动收尾，可重试失败项"),

        /** 空间不足、目标目录授权失效等：继续跑只会制造同样的失败。 */
        ABORTED("导出已中止，可重试失败项"),
    }

    data class Result(
        val id: Long,
        val title: String,
        val fileName: String?,
        val status: Status,
        val reason: String? = null,
        /** 目标不支持改名，已按最终名直接写入。 */
        val rewritten: Boolean = false,
    )

    sealed interface State {
        data class Idle(val total: Int) : State

        data class Running(val total: Int, val done: Int, val failed: Int, val current: String) : State

        data class Finished(val results: List<Result>) : State

        /**
         * @property reason 取消 / 系统超时 / 中止，见 [Reason.text]。
         * @property notRun 还没轮到就被打断的项数——摘要里要单独提一句，不能让用户以为它们失败了。
         */
        data class Interrupted(
            val results: List<Result>,
            val reason: Reason,
            val notRun: Int,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle(items.size))
    val state: StateFlow<State> = _state.asStateFlow()

    private val results = LinkedHashMap<Long, Result>()

    @Volatile
    private var stopReason: Reason? = null

    private var doneCount = 0
    private var failedCount = 0

    /** 需要重试的 id：已失败的、被跳过的（空间不足中止的那批）、还没跑到的（被中断时）。 */
    val retryIds: Set<Long>
        get() {
            val out = LinkedHashSet<Long>()
            results.values.forEach { if (it.status != Status.DONE) out += it.id }
            items.forEach { if (it.track.id !in results) out += it.track.id }
            return out
        }

    /** 用户取消。当前这一项写到一半会被叫停并删掉半成品（[ExportTarget.write] 的 `stop` 检查点）。 */
    fun cancel() = stop(Reason.USER_CANCEL)

    /**
     * 外部中断。Android 15 上 `Service.onTimeout(startId, fgsType)` 里调这一个方法：
     * 立刻把状态置为 [State.Interrupted]，界面/通知读到的是"被系统中断，可重试失败项"，
     * 而不是一个永远停在 7/12 的进度条。
     */
    fun interrupt(reason: Reason) = stop(reason)

    private fun stop(reason: Reason) {
        if (stopReason != null) return
        stopReason = reason
        _state.value = interrupted(reason)
    }

    private fun interrupted(reason: Reason): State.Interrupted = State.Interrupted(
        results = results.values.toList(),
        reason = reason,
        notRun = items.count { it.track.id !in results },
    )

    /**
     * 串行跑一遍。[only] 为空表示全部；重试时传 [retryIds]，只跑失败与未跑的项，计数重算。
     *
     * 空间不足会中止整批（剩余项记 [Status.SKIPPED]）：provider 说没地方了，
     * 后面每一项都会以同一种方式失败，只会把摘要刷满同样的文案。
     *
     * @param deadlineMs 到达这个时刻就按 [Reason.TIME_LIMIT] 收尾（服务传"启动时刻 + 5 小时"）。
     *   检查点放在每个 64 KiB 块之间，所以正在写的大文件也会被及时叫停，不用等整份写完。
     */
    suspend fun run(only: Set<Long>? = null, deadlineMs: Long? = null) {
        stopReason = null
        // 重试时保留上一轮的结果：摘要要给"成功 9 · 失败 1"的全貌，而不是只剩重试的那一项
        if (only == null) results.clear()
        doneCount = 0
        failedCount = 0
        val pending = items.filter { only == null || it.track.id in only }
        val total = pending.size
        _state.value = State.Running(total, 0, 0, "")

        var skipReason: String? = null
        for (item in pending) {
            if (shouldStop(deadlineMs)) break
            val title = item.track.displayTitle
            val fileName = item.fileName
            _state.value = State.Running(total, doneCount, failedCount, fileName)

            val outcome =
                if (item.track.localPath.isNullOrBlank()) {
                    // 云端行还没下到本机 ≠ 本机文件丢了：前者去下载就能导，后者只能重提取
                    val reason = if (item.track.awaitingDownload) {
                        ExportPlan.REASON_NOT_DOWNLOADED
                    } else {
                        ExportPlan.REASON_MISSING_LOCAL
                    }
                    ExportWriter.Outcome(null, 0, reason)
                } else {
                    withContext(io) {
                        writer.write(item, File(item.track.localPath)) { shouldStop(deadlineMs) }
                    }
                }

            // 写到一半被叫停：半成品已删，这一项算"还没跑"，留给重试，不记失败
            if (outcome.cancelled) break

            results[item.track.id] = Result(
                id = item.track.id,
                title = title,
                fileName = outcome.fileName,
                status = if (outcome.ok) Status.DONE else Status.FAILED,
                reason = outcome.reason,
                rewritten = outcome.rewritten,
            )
            if (outcome.ok) doneCount++ else failedCount++

            if (outcome.noSpace || outcome.abort) {
                // 空间不足 / 授权失效：继续跑只会把摘要刷满同一句失败，直接中止整批
                stopReason = Reason.ABORTED
                skipReason = if (outcome.noSpace) "空间不足，未执行" else "已中止，未执行"
                break
            }
            if (stopReason != null) break
            _state.value = State.Running(total, doneCount, failedCount, fileName)
        }

        skipReason?.let { reason ->
            pending.filter { it.track.id !in results }.forEach { item ->
                results[item.track.id] = Result(item.track.id, item.track.displayTitle, null, Status.SKIPPED, reason)
            }
        }

        _state.value = stopReason?.let { interrupted(it) } ?: State.Finished(results.values.toList())
    }

    /** 汇总用计数（摘要页与诊断都只记这几个数，不记路径）。 */
    fun counts(): Map<Status, Int> = results.values.groupingBy { it.status }.eachCount()

    /**
     * 该不该停：取消/中断置的 [stopReason] 优先，其次是时限。
     *
     * 会在 IO 线程上被调用（写循环里），所以只碰 `@Volatile` 字段与线程安全的 `StateFlow`。
     */
    private fun shouldStop(deadlineMs: Long?): Boolean {
        if (stopReason != null) return true
        if (deadlineMs != null && clock() >= deadlineMs) {
            stop(Reason.TIME_LIMIT)
            return true
        }
        return false
    }
}
