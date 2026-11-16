package com.getmedia.app.ui

import androidx.compose.runtime.Composable

/**
 * 完整版能力接缝。
 *
 * `main` 只依赖这个接口，具体实现在风味源码集里：
 * - `free`：只弹"暂未开放"，不 import 任何重型功能的类；
 * - `full`：打开真实的博主批量页面。
 *
 * 这样做是为了让免费版产物里**不存在**重型功能的代码（反编译可验证），
 * 而不是"有代码但把入口藏起来"。
 */
interface OwnerGate {
    /** 当前风味是否具备博主批量能力。 */
    val batchExtract: Boolean

    @Composable
    fun BatchExtract(onBack: () -> Unit)
}
