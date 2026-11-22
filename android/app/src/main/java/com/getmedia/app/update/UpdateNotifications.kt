package com.getmedia.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 新版本通知。
 *
 * 与导出通知分开一个渠道：用户可以在系统设置里单独关掉"版本提醒"，不影响导出进度。
 * 通知权限被拒（Android 13+）时这里静默失败——App 内的弹窗仍然会显示，提醒不会丢。
 */
object UpdateNotifications {

    const val CHANNEL_ID = "getmedia:update"
    const val NOTIFY_ID = 4301

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "版本更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "有新版本可以下载时提醒一次"
                setShowBadge(true)
            },
        )
    }

    fun notify(context: Context, version: String, notes: String, apkUrl: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val text = notes.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val open = if (apkUrl.isNotBlank()) downloadIntent(context, apkUrl) else null
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("GetMedia $version 可以更新了")
            .setContentText(text.ifBlank { "有新版本可用，点按下载" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.ifBlank { "有新版本可用，点按下载" }))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (open != null) builder.setContentIntent(open)
        runCatching { manager.notify(NOTIFY_ID, builder.build()) }
    }

    private fun downloadIntent(context: Context, apkUrl: String): PendingIntent? = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()
}
