package com.getmedia.app.update

import android.content.Context
import android.content.SharedPreferences
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
 * 四条自我约束：
 * 1. **进前台才查**，同一版本内 6 小时最多一次——不做后台轮询，不额外耗电；
 *    节流与"刚升级就放行"的规则在 [UpdateGate] 里，可单测。
 * 2. 同一个版本只发一次系统通知；用户点"稍后"后本次进程内不再弹，
 *    但下次进前台仍会看到（避免"提示过一次就永远不再提"）；
 * 3. 任何失败都静默：版本检查不该让开 App 变慢或报错；
 * 4. 设置页的「检查更新」走 [checkNow]：不受节流限制、忽略"不再提示这个版本"，
 *    并把结果（正在查 / 已是最新 / 失败）通过 [manual] 显示出来——手动动作必须有回应。
 */
object UpdateChecker {

    private const val PREFS = "app-update"
    private const val KEY_LAST_CHECK = "last-check-at"
    private const val KEY_CHECKED_VERSION = "checked-version"
    private const val KEY_IGNORED = "ignored-version"
    private const val KEY_NOTIFIED = "notified-version"

    /** 用户手动检查的可见结果；自动检查从不写它，保持静默。 */
    sealed class ManualCheck {
        data object Checking : ManualCheck()
        data object UpToDate : ManualCheck()
        data object Failed : ManualCheck()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _prompt = MutableStateFlow<UpdatePrompt?>(null)
    val prompt: StateFlow<UpdatePrompt?> = _prompt.asStateFlow()

    private val _manual = MutableStateFlow<ManualCheck?>(null)
    val manual: StateFlow<ManualCheck?> = _manual.asStateFlow()

    private var checking = false

    /** 本次进程里被"稍后"关掉的版本；下次启动会重新提示。 */
    @Volatile
    private var snoozedVersion: String? = null

    fun onForeground(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val due = UpdateGate.shouldCheck(
            now = now,
            lastCheckAt = prefs.getLong(KEY_LAST_CHECK, 0L),
            checkedVersion = prefs.getString(KEY_CHECKED_VERSION, null),
            currentVersion = BuildConfig.VERSION_NAME,
        )
        if (!due) return
        check(app, prefs, now, manual = false)
    }

    /** 用户主动检查（设置页「检查更新」）。忽略节流与"不再提示这个版本"。 */
    fun checkNow(context: Context) {
        val app = context.applicationContext
        check(app, app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), System.currentTimeMillis(), manual = true)
    }

    private fun check(app: Context, prefs: SharedPreferences, now: Long, manual: Boolean) {
        synchronized(this) {
            if (checking) return
            checking = true
        }
        if (manual) _manual.value = ManualCheck.Checking
        scope.launch {
            try {
                val config = AccountService.api.config()
                // 只有查成功才推进计时器：网络失败时下次进前台还能再试。
                // 版本号一起记，用来判断"这次记录属于哪个版本"。
                prefs.edit()
                    .putLong(KEY_LAST_CHECK, now)
                    .putString(KEY_CHECKED_VERSION, BuildConfig.VERSION_NAME)
                    .apply()
                val latest = config.latest
                // 免费版只认 free 链接，拿不到就本次不提示（见 UpdateTarget）
                val link = latest?.let { UpdateTarget.pick(it, BuildConfig.FLAVOR == "free") }
                if (latest == null || link == null || !AppVersion.isNewer(latest.version, BuildConfig.VERSION_NAME)) {
                    if (manual) _manual.value = ManualCheck.UpToDate
                    return@launch
                }
                // 自动检查尊重用户的"稍后"与"不再提示"；手动检查是明确动作，一律照给。
                if (!manual) {
                    val ignored = prefs.getString(KEY_IGNORED, null) == latest.version
                    if (ignored || snoozedVersion == latest.version) return@launch
                }
                val mandatory = AppVersion.isNewer(config.minVersion, BuildConfig.VERSION_NAME)
                _prompt.value = UpdatePrompt(
                    version = latest.version,
                    notes = latest.notes,
                    apkUrl = link.apk,
                    sha256 = link.sha256,
                    mandatory = mandatory,
                )
                // 弹窗本身就是手动检查的结果，不再额外显示提示文字。
                _manual.value = null
                if (prefs.getString(KEY_NOTIFIED, null) != latest.version) {
                    UpdateNotifications.notify(app, latest.version, latest.notes, link.apk)
                    prefs.edit().putString(KEY_NOTIFIED, latest.version).apply()
                }
            } catch (_: Exception) {
                // 静默：网络、解析、未初始化都按"这次没查到"处理
                if (manual) _manual.value = ManualCheck.Failed
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

    /** 清掉手动检查的结果文字（设置页在几秒后调用）。 */
    fun clearManual() {
        _manual.value = null
    }
}
