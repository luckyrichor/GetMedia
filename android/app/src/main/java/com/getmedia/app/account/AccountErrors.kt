package com.getmedia.app.account

import java.io.IOException

/** 把任何异常变成可直接展示给用户的一句话；绝不回显原始异常文本、URL 或响应体。 */
object AccountErrors {
    fun userMessage(e: Throwable): String = when (e) {
        is ApiException -> {
            val wait = e.retryAfterSec?.takeIf { it in 1..86_400 }
            if (e.status == 429 && wait != null) "${e.message}（约 ${waitText(wait)}后再试）" else e.message
        }
        is IOException -> "网络不可用，请检查网络后重试"
        else -> "出了点问题，请稍后再试"
    }

    fun waitText(sec: Int): String = when {
        sec < 60 -> "${sec} 秒"
        sec < 3600 -> "${(sec + 59) / 60} 分钟"
        else -> "${(sec + 3599) / 3600} 小时"
    }

    /** 设备数已达上限时，服务端返回设备列表供选择踢出。 */
    fun deviceLimit(e: Throwable): List<DeviceInfo>? =
        (e as? ApiException)?.takeIf { it.code == "device_limit" && it.devices.isNotEmpty() }?.devices
}
