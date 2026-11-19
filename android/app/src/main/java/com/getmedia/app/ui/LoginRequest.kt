package com.getmedia.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "去登录"的跨页面请求。
 *
 * 服务器提取面板在提取页里，登录入口在"我的"页，面板不该知道底部导航怎么切。
 * 于是面板只把请求放在这里，由 `MainActivity` 消费：切到"我的"，再让 `MyScreen` 打开登录页。
 */
object LoginRequest {
    /** `true` 表示有一次待处理的"去登录"请求。 */
    var pending by mutableStateOf(false)
        private set

    fun request() {
        pending = true
    }

    fun consume() {
        pending = false
    }
}
