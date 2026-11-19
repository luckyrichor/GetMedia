package com.getmedia.app.data.backup

import com.getmedia.app.BuildConfig
import com.getmedia.app.account.AccountApi
import com.getmedia.app.account.AccountJson
import com.getmedia.app.account.ApiException
import com.getmedia.app.account.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class BackupUpload(val id: String, val pruned: List<String>)

/**
 * 上传/删除这两个动作的抽象。生产只有 [BackupApi] 一个实现，
 * 抽出来是为了让"某个状态下绝不出网"能在单测里用一个记账的假实现断言。
 */
interface BackupTransport {
    suspend fun upload(body: ByteArray, appVersion: String, device: String): BackupUpload
    suspend fun deleteAll(): Int
}

/**
 * 曲库清单备份接口（设计 §3.2）。五条路由里客户端目前用两条：
 * 上传与"删除云端备份"；列表与恢复在恢复功能里接。
 *
 * 请求体是 gzip 原字节（不是 JSON 里套 base64）：省 1/3 体积，nginx 日志里也不会留下可读内容。
 */
class BackupApi(
    baseUrl: String = BuildConfig.SERVER_BASE,
    private val client: OkHttpClient = AccountApi.defaultClient(),
    private val token: () -> String? = SessionStore::token,
) : BackupTransport {
    private val base = baseUrl.trimEnd('/')
    private val gzip = "application/gzip".toMediaType()

    init { require(base.startsWith("https://") || AccountApi.ALLOW_TEST_HTTP) { "只允许 HTTPS" } }

    override suspend fun upload(body: ByteArray, appVersion: String, device: String): BackupUpload =
        withContext(Dispatchers.IO) {
            val request = authorized(
                Request.Builder()
                    .url((base + PATH).toHttpUrl())
                    .header("Accept", "application/json")
                    .header("X-GetMedia-App", header(appVersion, 32))
                    .header("X-GetMedia-Device", header(device, 64))
                    .post(body.toRequestBody(gzip)),
            )
            val o = JSONObject(execute(request))
            BackupUpload(
                id = o.getString("id"),
                pruned = o.optJSONArray("pruned")?.let { a -> (0 until a.length()).map(a::optString) }.orEmpty(),
            )
        }

    /** 删除该账号的全部云端备份，返回删掉的份数。 */
    override suspend fun deleteAll(): Int = withContext(Dispatchers.IO) {
        val request = authorized(Request.Builder().url((base + PATH).toHttpUrl()).delete())
        JSONObject(execute(request)).optInt("deleted")
    }

    private fun authorized(builder: Request.Builder): Request {
        val value = token() ?: throw ApiException(401, "unauthenticated", AccountJson.defaultMessage(401))
        return builder.header("X-GetMedia-Session", value).build()
    }

    /** 非 2xx 抛 [ApiException]（`message` 就是服务端的中文 detail），网络问题抛 IOException。 */
    private fun execute(request: Request): String = client.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (response.isSuccessful) text
        else throw AccountJson.error(response.code, text, response.header("Retry-After"))
    }

    /**
     * 请求头只能是 ASCII：机型名里偶尔有非 ASCII 字符（厂商自定义 ROM 改名），
     * 直接塞进去 OkHttp 会抛 IllegalArgumentException，这里先滤掉并截断。
     */
    private fun header(value: String, limit: Int): String =
        value.filter { it.code in 0x20..0x7e }.trim().take(limit)

    companion object {
        const val PATH = "/account/metadata-backups"
    }
}
