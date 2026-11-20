package com.getmedia.app.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 会话令牌用 Android Keystore 里不可导出的 AES 密钥加密后存文件；密码永远不落盘。
 * 解密失败（密钥丢失、文件损坏）一律当作未登录，不崩溃。
 */
object SessionStore {
    /**
     * `consentRequired` / `recoveryConfirmed` 记在本地，这样离线启动也能直接进主界面；
     * `pendingRecoveryCode` 是刚注册（或找回）拿到、还没确认保存的恢复码——服务端只显示一次，
     * 所以在用户确认前加密留在本机，App 被杀掉再打开也还能看到。
     */
    data class Session(
        val loginName: String, val token: String, val expiresAt: Long, val deviceId: String?,
        val consentRequired: Boolean = false, val recoveryConfirmed: Boolean = true,
        val pendingRecoveryCode: String? = null,
        /**
         * 账号档位（`free` / `owner` …），登录与每次 `me()` 时刷新。
         *
         * 存本地是为了让曲库同步的两道闸能在**客户端**判定：没同意最新隐私说明、或不是
         * owner 档，就一个请求都不发（不靠服务端 403 兜底，403 只当兜底）。
         * `null` = 还不知道（旧版本升级上来的会话、或还没成功对过一次 `me()`），按"不许同步"处理。
         */
        val tier: String? = null,
        /**
         * 该账号**已同意**的隐私说明版本。只在 `consentRequired == false` 时才可信
         * （那时服务端的"当前版本"就等于他同意过的那一版）；同意过期或从没对过 `me()` 时为 null。
         *
         * 曲库同步的闸门要用它做本地硬判定：低于应用要求的版本，一个请求都不发
         * （见 `LibrarySyncGate.MIN_PRIVACY_FOR_SYNC`）。存本地是为了不依赖服务端"够不够新"。
         */
        val consentedPrivacyVersion: Int? = null,
    )

    private const val ALIAS = "getmedia.account.session"
    private const val FILE = "account-session.enc"
    private val state = MutableStateFlow<Session?>(null)
    val current: StateFlow<Session?> = state.asStateFlow()
    private lateinit var file: File

    fun initialize(context: Context) {
        file = File(context.applicationContext.filesDir, FILE)
        state.value = runCatching {
            if (!file.exists()) return@runCatching null
            val o = JSONObject(String(decrypt(file.readBytes()), Charsets.UTF_8))
            Session(
                o.getString("login_name"), o.getString("token"), o.optLong("expires_at"), o.optString("device_id").ifBlank { null },
                o.optBoolean("consent_required"), o.optBoolean("recovery_confirmed", true),
                o.optString("pending_recovery").ifBlank { null },
                o.optString("tier").ifBlank { null },
                o.optInt("consent_pp", 0).takeIf { it > 0 },
            )
        }.getOrNull()
    }

    fun token(): String? = state.value?.token

    @Synchronized
    fun save(session: Session) {
        val data = JSONObject().put("login_name", session.loginName).put("token", session.token)
            .put("expires_at", session.expiresAt).put("device_id", session.deviceId ?: "")
            .put("consent_required", session.consentRequired).put("recovery_confirmed", session.recoveryConfirmed)
            .put("pending_recovery", session.pendingRecoveryCode ?: "").put("tier", session.tier ?: "")
            .put("consent_pp", session.consentedPrivacyVersion ?: 0).toString().toByteArray()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(encrypt(data))
        check(temp.renameTo(file)) { "无法保存登录状态" }
        state.value = session
    }

    /** 在现有会话上改几个字段（同意状态、恢复码确认等）；没有会话时什么也不做。 */
    @Synchronized
    fun update(change: (Session) -> Session) {
        val now = state.value ?: return
        save(change(now))
    }

    @Synchronized
    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
        state.value = null
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(bytes)
    }

    private fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
}
