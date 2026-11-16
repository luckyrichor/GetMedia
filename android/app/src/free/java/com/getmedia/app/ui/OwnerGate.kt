package com.getmedia.app.ui

import androidx.compose.runtime.Composable
import com.getmedia.app.ui.components.NotAvailableDialog

/**
 * 免费版：博主批量只保留占位。
 *
 * 这里刻意不 import `data/creator` 下的任何类或 `CreatorBatchScreen` —— 免费版 APK 里
 * 不应该存在这些类，靠构建产物隔离而不是靠隐藏入口。
 */
val ownerGate: OwnerGate = object : OwnerGate {
    override val batchExtract = false

    @Composable
    override fun BatchExtract(onBack: () -> Unit) {
        NotAvailableDialog(title = "博主批量提取", onDismiss = onBack)
    }
}
