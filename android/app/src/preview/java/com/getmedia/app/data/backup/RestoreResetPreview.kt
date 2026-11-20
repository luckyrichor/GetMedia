package com.getmedia.app.data.backup

import android.content.Context
import com.getmedia.app.data.repo.DiagnosticsStore

/**
 * 把某个账号的"云端备份要不要恢复"清回**未决定**——只存在于预览构建（`src/preview`）。
 *
 * 为什么放在预览源码集而不是 `main`：这是诊断/复验用的后门，正式包不该有它的入口，
 * 也不该在 APK 里留下它的字符串。`main` 里只保留界面上那个可空的位置（由
 * `RestoreResetSection` 的 debug/release 空实现占住），正式包产物里两者都没有。
 *
 * 只动本设备的这条偏好：云端备份、曲库、媒体文件一个字节都不碰，也不主动发请求——
 * 走 [BackupManager.onForeground] 的既有路径，由下一次前台检查自己去问，免得一点就抢在
 * 用户前面打请求。
 */
fun BackupManager.resetRestoreDecisionForPreview(context: Context, account: String) {
    BackupPrefs(context).update(account) { it.copy(restoreDecision = BackupPrefs.RESTORE_UNKNOWN) }
    // 清掉内存里的结论与「稍后」标记，否则 resolveRestoreDecision 会拿缓存直接返回。
    showRestoreDecision()
    onForeground()
    DiagnosticsStore.event("数据备份", "已重置恢复决策：下次登录或回到前台会重新询问", true)
}
