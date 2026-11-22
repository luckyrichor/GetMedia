package com.getmedia.app.update

import android.content.Context
import com.getmedia.app.BuildConfig
import com.getmedia.app.account.AccountService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 一次"有新版本"的提示。`mandatory` 由服务端 `min_version` 决定。 */
data class UpdatePrompt(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val sha256: String,
    val mandatory: Boolean,
)

/**
 * 新版本检查（免费版与完整版共用）。
 *
 * 复用既有的公开接口 `GET /v2/config`：它本来就返回 `min_version` 与 `latest`
 * （版本、更新说明、APK 直链、sha256），免费通道也在白名单里，所以不需要为这件事
 * 新开一个接口，也不需要登录。
 *
 * 三条自我约束：
 * 1. **进前台才查**，且 6 小时最多一次——不做后台轮询，不额外耗电；
 * 2. 同一个版本只发一次系统通知；用户点"稍后"后本次进程内不再弹，
 *    但下次进前台仍会看到（避免"提示过一次就永远不再提"）；
 * 3. 任何失败都静默：版本检查不该让开 App 变慢或报错。
 */
object UpdateChecker {

    private const val PREFS = "app-update"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val KEY_LAST_CHECK = "last-check-at"
    private const val KEY_IGNORED = "ignored-version"
    private const val KEY_NOTIFIED = "notified-version"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _prompt = MutableStateFlow<UpdatePrompt?>(null)
    val prompt: StateFlow<UpdatePrompt?> = _prompt.asStateFlow()

    private var checking = false

    /** 本次进程里被"稍后"关掉的版本；下次启动会重新提示。 */
    @Volatile
    private var snoozedVersion: String? = null

    fun onForeground(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        check(app, prefs, now)
    }

    /** 用户主动检查（设置页用）。忽略 6 小时节流。 */
    fun checkNow(context: Context) {
        val app = context.applicationContext
        check(app, app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), System.currentTimeMillis())
    }

    private fun check(app: Context, prefs: android.content.SharedPreferences, now: Long) {
        synchronized(this) {
            if (checking) return
            checking = true
        }
        scope.launch {
            try {
                val config = AccountService.api.config()
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                val latest = config.latest ?: return@launch
                // 免费版只认 free 链接，拿不到就本次不提示（见 UpdateTarget）
                val link = UpdateTarget.pick(latest, BuildConfig.FLAVOR == "free") ?: return@launch
                if (!AppVersion.isNewer(latest.version, BuildConfig.VERSION_NAME)) return@launch
                if (prefs.getString(KEY_IGNORED, null) == latest.version) return@launch
                if (snoozedVersion == latest.version) return@launch
                val mandatory = AppVersion.isNewer(config.minVersion, BuildConfig.VERSION_NAME)
                _prompt.value = UpdatePrompt(
                    version = latest.version,
                    notes = latest.notes,
                    apkUrl = link.apk,
                    sha256 = link.sha256,
                    mandatory = mandatory,
                )
                if (prefs.getString(KEY_NOTIFIED, null) != latest.version) {
                    UpdateNotifications.notify(app, latest.version, latest.notes, link.apk)
                    prefs.edit().putString(KEY_NOTIFIED, latest.version).apply()
                }
            } catch (_: Exception) {
                // 静默：网络、解析、未初始化都按"这次没查到"处理
            } finally {
                checking = false
            }
        }
    }

    /** 关掉提示，本次进程内不再为这个版本弹窗。 */
    fun snooze() {
        snoozedVersion = _prompt.value?.version
        _prompt.value = null
    }

    /** 不再提示这个版本（直到更新的版本出现）。 */
    fun ignoreCurrent(context: Context) {
        val version = _prompt.value?.version ?: return
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_IGNORED, version).apply()
        _prompt.value = null
    }
}
