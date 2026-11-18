package com.getmedia.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.getmedia.app.ui.components.NotAvailableDialog

/**
 * 免费版：博主批量与服务器提取都只保留占位。
 *
 * 这里刻意不 import `data/creator`、`data/serverextract` 下的任何类，也不引用真实页面 ——
 * 免费版 APK 里不应该存在这些类，靠构建产物隔离而不是靠隐藏入口。
 */
val ownerGate: OwnerGate = object : OwnerGate {
    override val batchExtract = false
    override val serverExtract = false

    @Composable
    override fun BatchExtract(onBack: () -> Unit) {
        NotAvailableDialog(title = "博主批量提取", onDismiss = onBack)
    }

    /** 免费版不会渲染这一段（`serverExtract = false` 时提取页不出现开关），保持空实现。 */
    @Composable
    override fun ServerExtract(snackbar: SnackbarHostState) = Unit
}
