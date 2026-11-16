package com.getmedia.app.ui

import androidx.compose.runtime.Composable
import com.getmedia.app.ui.screen.CreatorBatchScreen

/** 完整版：博主批量接真实实现（`data/creator` 下的类也只在 full 源码集里）。 */
val ownerGate: OwnerGate = object : OwnerGate {
    override val batchExtract = true

    @Composable
    override fun BatchExtract(onBack: () -> Unit) {
        CreatorBatchScreen(onBack = onBack)
    }
}
