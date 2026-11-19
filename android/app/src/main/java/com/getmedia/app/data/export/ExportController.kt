package com.getmedia.app.data.export

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 一次导出的"谁在跑、跑到哪"——**唯一持有者**，页面只是观察者。
 *
 * 为什么必须放在单例而不是页面里：导出由 [ExportService] 在前台服务里跑，用户随时可以关掉
 * 确认页去刷别的；页面一销毁就把状态带走，会让通知与进度条对不上，重试也接不回来。
 *
 * 状态用 Compose 的 `mutableStateOf`：写文件在 IO 线程，更新进度在服务的主线程协程，
 * 两边都被快照系统收敛到同一份可观察值上。
 *
 * 生命周期：`start` 建会话 → 服务 `takeRequest` 取走 → 跑完置 Finished/Interrupted；
 * 用户点"完成"调 [clear] 收摊。进程被杀即结束（本期不做队列持久化，见设计 §7）。
 */
object ExportController {

    /**
     * 一次请求。重试时 [only] 非空，其余字段沿用上一轮（同一份 items、同一个目录 URI）。
     */
    data class Request(
        val items: List<ExportPlan.Item>,
        val tree: Uri,
        val only: Set<Long>? = null,
    )

    /** 当前会话；null 表示还没有跑过或已被清理。 */
    var session: ExportSession? by mutableStateOf(null)
        private set

    /** 供界面观察的进度/结果；null 表示没有可显示的导出。 */
    var state: ExportSession.State? by mutableStateOf(null)
        private set

    private var pending: Request? = null
    private var last: Request? = null
    private var watcher: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 正在跑（或服务已接单还没起跑）。 */
    val running: Boolean
        get() = state is ExportSession.State.Running

    /**
     * 开始一次新导出。返回 false 表示已有一个在跑，调用方不要重复启动服务。
     *
     * 目录授权必须已经持久化（[SafGrants.isPersisted]）：服务里拿不到一次性授权，
     * 跑到一半才发现没有写权限比不让开始更糟。
     */
    fun start(context: Context, items: List<ExportPlan.Item>, tree: Uri): Boolean {
        if (running) return false
        val request = Request(items, tree)
        pending = request
        last = request
        session = null
        state = null
        ContextCompat.startForegroundService(context, ExportService.intent(context))
        return true
    }

    /**
     * 重试未完成项（失败的 + 没轮到的）。复用同一个会话，摘要里的"成功 N"不会被重来一遍冲掉。
     */
    fun retry(context: Context): Boolean {
        if (running) return false
        val base = last ?: return false
        val current = session ?: return false
        val ids = current.retryIds
        if (ids.isEmpty()) return false
        pending = base.copy(only = ids)
        ContextCompat.startForegroundService(context, ExportService.intent(context))
        return true
    }

    /** 通知里的「取消」、页面上的「取消导出」。当前项写到一半会被叫停并删掉半成品。 */
    fun cancel() = session?.cancel()

    /** Android 15 `onTimeout` / 5 小时自保护的统一入口。 */
    fun interrupt(reason: ExportSession.Reason) = session?.interrupt(reason)

    /** 摘要页点"完成"：清掉状态，下次打开确认页从干净的开始。 */
    fun clear() {
        if (running) return
        reset()
    }

    private fun reset() {
        watcher?.cancel()
        watcher = null
        session = null
        state = null
        last = null
        pending = null
    }

    // ── 以下是 ExportService 用的内部接口 ───────────────────────────────

    /** 服务接单：把请求取走（取走即清空，避免重复启动跑两遍）。 */
    internal fun takeRequest(): Request? = pending.also { pending = null }

    /**
     * 服务建会话。重试时若上一个会话还在且 items 是同一份，直接复用——
     * [ExportSession.run] 的 `only` 分支靠会话里已有的结果累加，新建一个就丢了上一轮。
     */
    internal fun attach(context: Context, request: Request): ExportSession {
        val existing = session
        val target = if (request.only != null && existing != null && existing.items === request.items) {
            existing
        } else {
            ExportSession(request.items, ExportWriter(SafExportTarget(context.contentResolver, request.tree)))
        }
        session = target
        if (existing !== target) {
            watcher?.cancel()
            watcher = scope.launch { target.state.collect { state = it } }
        }
        return target
    }

    /**
     * 单测用：绕过 SAF 与会话创建，直接装一个现成会话进来。
     *
     * 服务侧的 `onTimeout` 与通知取消只依赖"当前会话"，不需要真的写文件，
     * 装上假目标的会话就能验证这两条入口。
     *
     * @param displayState 界面要显示的进度。界面读的是 [state]（由服务的 watcher 推上来），
     *   测试里没有服务，就把它直接摆成 Running，省得为了"看到进度"去起一个真线程。
     */
    internal fun installForTest(target: ExportSession, displayState: ExportSession.State? = null) {
        watcher?.cancel()
        watcher = null
        session = target
        state = displayState ?: target.state.value
        pending = null
        last = null
    }

    /** 单测用：界面可能停在 Running 上（[installForTest] 摆进去的），清场时不该被 `running` 拦住。 */
    internal fun forceClearForTest() = reset()
}
