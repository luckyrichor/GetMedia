package com.getmedia.app.data.export

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 批量导出的前台服务：**真正跑批的地方**。确认页只负责收集参数并把目录交过来。
 *
 * 为什么要前台服务：导出可能几十分钟（几十个视频），页面随时会被回收。
 * 服务 + [ExportController] 把会话与状态钉在应用进程里，页面销毁不影响进度，
 * 通知里随时能取消。
 *
 * 三条收尾路径（通知取消 / Android 15 `onTimeout` / 服务内 5 小时自保护）最终都落在
 * [ExportSession] 的同一个 [ExportSession.State.Interrupted] 上：已完成项保留，当前项的半成品删掉，
 * 剩下没跑的在 `retryIds` 里。区别只在 [ExportSession.Reason] 的文案。
 *
 * 关于 **PARTIAL_WAKE_LOCK**（结论：要用，且必须带超时）：
 * 前台服务只影响进程优先级与回收，**不阻止 CPU 进入 suspend**；SAF 的写是同步 IO，
 * 屏幕一灭又没有别的持锁者时，写循环会停在某个缓冲块上等下一次唤醒，用户看到的是进度条不动。
 * 所以写文件期间持一把 `PARTIAL_WAKE_LOCK`，超时取"5 小时自保护 + 1 分钟余量"——
 * 即使收尾路径全部失效，这把锁也会自己到期，不会留下一个永久持锁的进程。
 * 释放放在 `finally`（正常完成、取消、中断、异常都会走到），`onDestroy` 再兜一次。
 */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ExportNotifications.ensureChannel(this)
    }

    /**
     * 启动前台服务。`FOREGROUND_SERVICE_TYPE_DATA_SYNC` 是 API 29 的常量，
     * 但它是编译期内联的 int，低版本上走到这里也不会去读那个字段，所以只对 lint 说明一下。
     */
    @SuppressLint("InlinedApi")
    private fun startAsForeground(session: ExportSession) {
        val first = session.state.value as? ExportSession.State.Running
            ?: ExportSession.State.Running(session.items.size, 0, 0, "")
        ServiceCompat.startForeground(
            this,
            ExportNotifications.NOTIFY_ID,
            ExportNotifications.progress(this, first),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知里的「取消」：把停止信号交给会话，当前项写到一半会被叫停并删掉半成品
        if (intent?.action == ACTION_CANCEL) {
            ExportController.cancel()
            return START_NOT_STICKY
        }

        val request = ExportController.takeRequest()
        if (request == null) {
            // 没有待跑请求（重复启动、或进程被回收后系统重投）：不留一个空的通知
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val session = ExportController.attach(this, request)
        startAsForeground(session)
        acquireWakeLock()

        job = scope.launch {
            val watcher = launch { watch(session) }
            try {
                session.run(request.only, deadlineMs = System.currentTimeMillis() + MAX_RUN_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 写文件的异常已在 ExportWriter 里归类成单项失败；能走到这里说明状态机自己出了问题，
                // 按"中止"收尾比让服务静默死掉更好——用户至少能看到可重试。
                session.interrupt(ExportSession.Reason.ABORTED)
            } finally {
                watcher.cancel()
                settle(session.state.value)
                releaseWakeLock()
                ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15 起 `dataSync` 前台服务有 6 小时/24 小时限额，到点系统回调这里。
     * 不处理的话系统会直接抛 `ForegroundServiceDidNotStopInTimeException` 崩掉进程。
     *
     * 状态先置成 [ExportSession.Reason.SYSTEM_TIMEOUT] 再 `stopSelf`：跑批协程看到停止信号后
     * 会在下一个 64 KiB 检查点退出，当前项的临时文件由 [ExportWriter] 的 cleanup 删掉。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ExportController.interrupt(ExportSession.Reason.SYSTEM_TIMEOUT)
        settle(ExportController.state)
        stopSelf(startId)
    }

    override fun onDestroy() {
        job?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * 进度通知节流 500 ms，终态不节流。
     *
     * 每项都刷新会让通知栏狂闪，而且 provider 慢的时候一秒能刷几十次。
     */
    private suspend fun watch(session: ExportSession) {
        session.state.collect { state ->
            val manager = getSystemService(NotificationManager::class.java) ?: return@collect
            if (state is ExportSession.State.Running) {
                val now = SystemClock.elapsedRealtime()
                if (lastNotifyAt != 0L && now - lastNotifyAt < NOTIFY_INTERVAL_MS) return@collect
                lastNotifyAt = now
                manager.notify(ExportNotifications.NOTIFY_ID, ExportNotifications.progress(this, state))
            } else {
                manager.notify(ExportNotifications.NOTIFY_ID, ExportNotifications.settle(this, state))
            }
        }
    }

    private fun settle(state: ExportSession.State?) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(ExportNotifications.NOTIFY_ID, ExportNotifications.settle(this, state)) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(MAX_RUN_MS + WAKE_GRACE_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    companion object {
        const val ACTION_CANCEL = "com.getmedia.app.action.EXPORT_CANCEL"

        /** 服务内自保护：5 小时主动收尾。系统限额是 6 小时，留 1 小时给收尾与重试。 */
        const val MAX_RUN_MS = 5L * 60 * 60 * 1000

        /** 唤醒锁的余量：比自保护多 1 分钟，任何收尾路径都失效时它也会自己到期。 */
        private const val WAKE_GRACE_MS = 60_000L

        private const val NOTIFY_INTERVAL_MS = 500L
        private const val WAKE_LOCK_TAG = "getmedia:export"

        fun intent(context: Context): Intent = Intent(context, ExportService::class.java)
    }
}
