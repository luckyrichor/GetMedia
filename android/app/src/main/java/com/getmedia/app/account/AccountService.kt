package com.getmedia.app.account

import android.content.Context
import android.os.Build
import com.getmedia.app.BuildConfig

/**
 * 账号相关的唯一入口：把接口调用和本地会话状态接在一起。
 * 服务器不可达时不影响本地功能——这里的任何失败都只影响注册/登录/反馈等需要联网的操作。
 */
object AccountService {
    lateinit var api: AccountApi
        private set
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        SessionStore.initialize(appContext)
        api = AccountApi(BuildConfig.SERVER_BASE, token = SessionStore::token)
    }

    fun clientInfo() = ClientInfo(BuildConfig.VERSION_NAME, "Android ${Build.VERSION.RELEASE}", Build.MODEL.orEmpty())

    /** 用于展示/同意的协议：优先服务端最新版，取不到用 APK 内置种子。 */
    suspend fun legal(): LegalBundle = runCatching { api.legal() }.getOrElse { LegalText.seed(appContext) }

    suspend fun register(loginName: String, password: String, invite: String?, consent: LegalBundle) {
        val r = api.register(loginName, password, invite, consent, clientInfo())
        // 注册即同意：服务端已记录同意；恢复码未确认前留在本机。
        SessionStore.save(SessionStore.Session(
            r.account.loginName, r.session.token, r.session.expiresAt, r.session.deviceId,
            consentRequired = false, recoveryConfirmed = false, pendingRecoveryCode = r.recoveryCode,
        ))
    }

    suspend fun login(loginName: String, password: String, evictDeviceId: String? = null) {
        val r = api.login(loginName, password, clientInfo(), evictDeviceId)
        SessionStore.save(SessionStore.Session(
            r.account.loginName, r.session.token, r.session.expiresAt, r.session.deviceId,
            consentRequired = r.consentRequired, recoveryConfirmed = r.recoveryConfirmed,
        ))
    }

    /** 找回：成功后所有设备登出，需要用新密码重新登录；返回新的恢复码（只显示一次）。 */
    suspend fun recover(loginName: String, recoveryCode: String, newPassword: String): String =
        api.recover(loginName, AccountRules.cleanRecoveryCode(recoveryCode), newPassword).also { SessionStore.clear() }

    suspend fun consent(consent: LegalBundle) {
        api.consent(consent)
        SessionStore.update { it.copy(consentRequired = false) }
    }

    suspend fun ackRecovery() {
        api.recoveryAck()
        SessionStore.update { it.copy(recoveryConfirmed = true, pendingRecoveryCode = null) }
    }

    /** 启动/回到前台时对一次账号状态；401 说明会话失效（被吊销、过期、账号注销），退回登录页。联网失败则保持现状。 */
    suspend fun refresh() {
        if (SessionStore.current.value == null) return
        try {
            val me = api.me()
            SessionStore.update { it.copy(consentRequired = me.consentRequired, recoveryConfirmed = me.recoveryConfirmed) }
        } catch (e: ApiException) {
            if (e.status == 401) SessionStore.clear()
        } catch (_: java.io.IOException) {
        }
    }

    /** 每天一次的使用心跳；服务端按设备去重，失败静默（不影响任何功能）。 */
    suspend fun heartbeat() {
        if (SessionStore.current.value == null) return
        runCatching { api.heartbeat(clientInfo()) }
    }

    /** 退出：先尽力通知服务器吊销，不管成败都清掉本机会话。本地媒体库不受影响。 */
    suspend fun logout() {
        runCatching { api.logout() }
        SessionStore.clear()
    }

    /** 调用需要登录的接口时统一处理会话失效。 */
    suspend fun <T> authed(block: suspend () -> T): T = try { block() } catch (e: ApiException) {
        if (e.status == 401 && e.code == "unauthenticated") SessionStore.clear()
        throw e
    }
}
