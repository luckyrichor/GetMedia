package com.getmedia.app.data.backup

import com.getmedia.app.account.ApiException
import java.io.IOException

/**
 * 备份的决策规则。抽成纯函数是为了能在 JVM 单测里把边界钉死——
 * 真正的"什么时候传"由 [BackupManager] 调它，不在这里碰数据库或网络。
 */
object BackupRules {
    /** 与服务端限频同一个窗口：10 分钟。 */
    const val WINDOW_MS = 10 * 60_000L

    /** 关键写操作后的防抖：改完立刻退出也不丢，等一下再算。 */
    const val DEBOUNCE_MS = 30_000L

    /** 进入前台先等一会儿：冷启动时库和会话都还没稳。 */
    const val FOREGROUND_DELAY_MS = 5_000L

    /** 前台停留时的检查周期，与限频窗口一致。 */
    const val TICK_MS = 10 * 60_000L

    /**
     * 该不该继续往下走。五个触发点（进前台/退后台/前台周期/写操作防抖/用户开启）
     * 都在**排协程之前**问这一句：关掉或被服务端停用时，一个网络请求都不发。
     */
    fun shouldRun(state: BackupPrefs.State): Boolean = state.enabled && state.blocked == null

    /**
     * 该不该发这次上传。
     *
     * - 关闭、被服务端停用 → 不发；
     * - 指纹没变 → 不发（`force` 是用户点"立即备份"，允许重传一份相同的）；
     * - 距上次成功不足 10 分钟 → 不发，本地先拦一道，正常路径不会撞 429。
     */
    fun shouldUpload(state: BackupPrefs.State, hash: String, nowMs: Long, force: Boolean = false): Boolean {
        if (!shouldRun(state)) return false
        if (force) return true
        if (hash == state.lastHash) return false
        return nowMs - state.lastAtMs >= WINDOW_MS
    }
}

/** 一次上传的结果。失败分支按"用户接下来该看到什么"分类，而不是按 HTTP 状态码堆砌。 */
sealed interface BackupOutcome {
    data class Done(val id: String, val pruned: List<String>) : BackupOutcome

    /** 429：静默，等下一个窗口。 */
    data object RateLimited : BackupOutcome

    /** 413：设置页常驻「备份已停用：库太大」。 */
    data object TooLarge : BackupOutcome

    /** 422：常驻「备份已停用」，并把 detail 写进诊断记录。 */
    data class Invalid(val detail: String) : BackupOutcome

    /** 404：服务端还没有这套接口，静默跳过（设计 §5.0 ②）。 */
    data object Unsupported : BackupOutcome

    /** 401：会话失效，交给账号模块处理，备份这边不弹错。 */
    data object Unauthorized : BackupOutcome

    /** 网络或其他：留着 dirty，下个窗口再试。 */
    data class Failed(val message: String) : BackupOutcome

    companion object {
        fun of(error: Throwable): BackupOutcome = when (error) {
            is ApiException -> when (error.status) {
                413 -> TooLarge
                422 -> Invalid(error.message)
                404 -> Unsupported
                401 -> Unauthorized
                429 -> RateLimited
                else -> Failed(error.message)
            }
            is IOException -> Failed("网络不可用")
            else -> Failed("服务暂时不可用")
        }
    }
}
