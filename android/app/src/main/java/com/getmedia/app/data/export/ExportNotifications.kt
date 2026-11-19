package com.getmedia.app.data.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.getmedia.app.MainActivity

/**
 * 导出通知：进度、结果、以及"取消"。
 *
 * 三条约束：
 * 1. 取消走 `startService` 回到 [ExportService]，不新建广播接收器——服务本来就在，少一个组件少一处泄漏面；
 * 2. 进度通知**不带声音**，渠道用 `IMPORTANCE_LOW`，否则每 500 ms 刷新一次会响个不停；
 * 3. 通知权限被拒时这里的所有调用都静默失败（系统自己丢），不影响导出——调用方不用判断权限。
 */
object ExportNotifications {

    const val CHANNEL_ID = "getmedia:export"
    const val NOTIFY_ID = 4201

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "批量导出", NotificationManager.IMPORTANCE_LOW).apply {
                description = "导出进度与结果"
                setShowBadge(false)
            },
        )
    }

    fun progress(context: Context, state: ExportSession.State.Running): Notification =
        base(context)
            .setContentTitle("正在导出 ${state.done}/${state.total}")
            .setContentText(state.current.ifBlank { "准备中" })
            .setProgress(state.total, state.done, false)
            .setOngoing(true)
            .addAction(0, "取消", cancelIntent(context))
            .build()

    /** 终态：完成 / 取消 / 系统中断 / 中止。通知保留，让用户点回来看摘要。 */
    fun settle(context: Context, state: ExportSession.State?): Notification {
        val (title, text) = when (state) {
            is ExportSession.State.Finished -> {
                val done = state.results.count { it.status == ExportSession.Status.DONE }
                val failed = state.results.count { it.status == ExportSession.Status.FAILED }
                "导出完成" to if (failed > 0) "成功 $done · 失败 $failed" else "成功 $done 项"
            }
            is ExportSession.State.Interrupted ->
                "导出已停止" to state.reason.text
            else -> "导出结束" to "没有可导出的条目"
        }
        return base(context)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    private fun base(context: Context): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent(context))
            .setOnlyAlertOnce(true)
            .setSilent(true)

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(context: Context): PendingIntent = PendingIntent.getService(
        context,
        1,
        ExportService.intent(context).setAction(ExportService.ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
