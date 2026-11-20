package com.getmedia.app.data.repo

/**
 * 把异常翻成能直接显示的中文，不暴露地址、令牌或异常原文里的敏感内容。
 *
 * 0.1.16 起做两件事：
 *
 * 1. **顺着 cause 链找根因**。下载链路会把底层异常层层包起来（例如
 *    `IllegalStateException("所有下载地址均失败：…", UnknownHostException)`），
 *    只看最外层的类型和人话就会漏掉，用户又看到 `Unable to resolve host …`。
 * 2. **关键字兜底**。OkHttp 与 Android 有些网络错误没有专门的异常类型，
 *    只能从 message 里认出"域名解析不了""连接被中断"这类说法。
 *
 * 这里只产出给用户看的一句话；原始异常类型与细节由调用方写进诊断日志
 * （[DiagnosticsStore]），两边分工明确：**人话给人，原文给日志**。
 */
object ErrorText {

    fun humanMessage(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(5).toList()
        chain.firstNotNullOfOrNull(::byType)?.let { return it }
        chain.firstNotNullOfOrNull { byText(it.message) }?.let { return it }
        return t.message?.takeIf { it.isNotBlank() && !looksTechnical(it) } ?: "操作失败，请稍后再试"
    }

    private fun byType(t: Throwable): String? = when (t) {
        is java.net.UnknownHostException -> NETWORK_DOWN
        is java.net.ConnectException -> "无法连接服务器，请稍后再试"
        is java.net.SocketTimeoutException -> "连接超时，请稍后再试"
        is java.net.SocketException -> "网络连接中断，请稍后再试"
        is java.io.EOFException -> "下载中断，请重试"
        is javax.net.ssl.SSLException -> "安全连接失败，请稍后再试"
        else -> null
    }

    /** 没有对应异常类型时的兜底：认得出就给同一句人话。 */
    private fun byText(message: String?): String? {
        val text = message?.lowercase() ?: return null
        return when {
            "unable to resolve host" in text -> NETWORK_DOWN
            "no address associated with hostname" in text -> NETWORK_DOWN
            "network is unreachable" in text || "no route to host" in text -> NETWORK_DOWN
            "failed to connect" in text || "connection refused" in text -> "无法连接服务器，请稍后再试"
            "software caused connection abort" in text -> "网络连接中断，请稍后再试"
            "timeout" in text || "timed out" in text -> "连接超时，请稍后再试"
            else -> null
        }
    }

    /**
     * 长得像异常名或英文堆栈的，不当成给用户看的话。
     * 判断标准简单：一句纯英文/ASCII 的 message 不给用户看，中文 + 英文混排的正常放行。
     */
    private fun looksTechnical(message: String): Boolean =
        message.none { it.code > 0x2E80 } && message.any { it.code in 'a'.code..'z'.code || it.code in 'A'.code..'Z'.code }

    const val NETWORK_DOWN = "网络不可用，稍后再试"
}
