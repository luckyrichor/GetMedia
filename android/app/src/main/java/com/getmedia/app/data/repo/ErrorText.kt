package com.getmedia.app.data.repo

/** 把异常翻成能直接显示的中文，不暴露地址、令牌或异常原文里的敏感内容。 */
object ErrorText {
    fun humanMessage(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "请求超时，请检查网络或稍后重试"
        is java.net.UnknownHostException -> "无法连接网络，请检查网络后重试"
        is java.net.ConnectException -> "无法连接，请检查网络后重试"
        is java.net.SocketException -> "网络连接被中断，请重试"
        is java.io.EOFException -> "下载中断，请重试"
        is javax.net.ssl.SSLException -> "安全连接失败，请检查网络或稍后重试"
        else -> t.message ?: "未知错误"
    }
}
