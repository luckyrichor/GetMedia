package com.getmedia.app.account

import org.json.JSONArray
import org.json.JSONObject

/** 服务端返回的错误：`detail` 是面向用户的中文文案，`code` 是稳定机器码。 */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val retryAfterSec: Int? = null,
    val devices: List<DeviceInfo> = emptyList(),
) : Exception(message)

data class DeviceInfo(
    val id: String, val channel: String, val device: String, val appVersion: String,
    val createdAt: Long, val lastUsedAt: Long, val current: Boolean,
) {
    companion object {
        fun parse(o: JSONObject) = DeviceInfo(
            id = o.getString("id"), channel = o.optString("channel"), device = o.optString("device"),
            appVersion = o.optString("app_version"), createdAt = o.optLong("created_at"),
            lastUsedAt = o.optLong("last_used_at"), current = o.optBoolean("current"),
        )
        fun parseList(a: JSONArray?): List<DeviceInfo> =
            if (a == null) emptyList() else (0 until a.length()).map { parse(a.getJSONObject(it)) }
    }
}

data class SessionInfo(val token: String, val expiresAt: Long, val deviceId: String?)

data class AccountInfo(val id: String, val loginName: String, val tier: String)

/** 注册 / 登录成功后的结果。`recoveryCode` 只在注册和找回时有值，且只显示这一次。 */
data class AuthResult(
    val session: SessionInfo, val account: AccountInfo,
    val consentRequired: Boolean, val recoveryConfirmed: Boolean, val recoveryCode: String?,
)

data class MeInfo(
    val id: String, val loginName: String, val tier: String,
    val consentRequired: Boolean, val recoveryConfirmed: Boolean, val status: String,
)

data class LatestRelease(val version: String, val notes: String, val apk: String, val sha256: String)

data class ServerConfig(
    val registrationOpen: Boolean, val inviteRequired: Boolean, val maintenance: String?,
    val minVersion: String, val latest: LatestRelease?,
)

data class LegalDocument(val version: Int, val effectiveDate: String, val sha256: String, val text: String)

data class LegalBundle(val userAgreement: LegalDocument, val privacyPolicy: LegalDocument)

data class ClientInfo(val appVersion: String, val os: String, val device: String)

object AccountJson {
    fun error(status: Int, body: String?, retryAfter: String?): ApiException {
        val o = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
        val code = o?.optString("code").orEmpty().ifBlank { "http_$status" }
        // 服务端 detail 已是固定中文文案；解析不出来时给通用文案，不回显任何原始响应。
        val detail = o?.optString("detail").orEmpty().ifBlank { defaultMessage(status) }
        return ApiException(status, code, detail, retryAfter?.trim()?.toIntOrNull(), DeviceInfo.parseList(o?.optJSONArray("devices")))
    }

    fun defaultMessage(status: Int): String = when (status) {
        401 -> "登录已失效，请重新登录"
        403 -> "当前账号无权执行此操作"
        404 -> "内容不存在"
        413 -> "内容太大"
        429 -> "操作太频繁，请稍后再试"
        503 -> "服务维护中，请稍后再试"
        else -> "服务暂时不可用，请稍后再试"
    }

    fun session(o: JSONObject) = SessionInfo(o.getString("token"), o.optLong("expires_at"), o.optString("device_id").ifBlank { null })

    fun account(o: JSONObject) = AccountInfo(o.getString("id"), o.getString("login_name"), o.optString("tier", "free"))

    fun auth(o: JSONObject) = AuthResult(
        session = session(o.getJSONObject("session")),
        account = account(o.getJSONObject("account")),
        consentRequired = o.optBoolean("consent_required", false),
        recoveryConfirmed = o.optBoolean("recovery_confirmed", false),
        recoveryCode = o.optString("recovery_code").ifBlank { null },
    )

    fun me(o: JSONObject) = MeInfo(
        o.getString("id"), o.getString("login_name"), o.optString("tier", "free"),
        o.optBoolean("consent_required"), o.optBoolean("recovery_confirmed"), o.optString("status"),
    )

    fun config(o: JSONObject): ServerConfig {
        val latest = o.optJSONObject("latest")?.let {
            LatestRelease(it.optString("version"), it.optString("notes"), it.optString("apk"), it.optString("sha256"))
        }
        return ServerConfig(
            o.optBoolean("registration_open"), o.optBoolean("invite_required"),
            o.optString("maintenance").ifBlank { null }.takeUnless { o.isNull("maintenance") },
            o.optString("min_version"), latest,
        )
    }

    fun legal(o: JSONObject): LegalBundle {
        fun doc(d: JSONObject) = LegalDocument(d.getInt("version"), d.optString("effective_date"), d.getString("sha256"), d.getString("text"))
        val docs = o.getJSONObject("documents")
        return LegalBundle(doc(docs.getJSONObject("user_agreement")), doc(docs.getJSONObject("privacy_policy")))
    }
}
