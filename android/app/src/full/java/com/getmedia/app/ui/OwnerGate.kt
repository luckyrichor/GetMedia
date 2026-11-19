package com.getmedia.app.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.ui.screen.CreatorBatchScreen
import com.getmedia.app.ui.screen.ExportMissingNoticeBody
import com.getmedia.app.ui.screen.ServerExtractPanel

/** 完整版：博主批量与服务器提取都接真实实现（`data/creator`、`data/serverextract` 只在 full 源码集里）。 */
val ownerGate: OwnerGate = object : OwnerGate {
    override val batchExtract = true
    override val serverExtract = true

    @Composable
    override fun BatchExtract(onBack: () -> Unit) {
        CreatorBatchScreen(onBack = onBack)
    }

    @Composable
    override fun ServerExtract(
        snackbar: SnackbarHostState,
        onKindChange: (MediaKind) -> Unit,
        onUseLocal: (String) -> Unit,
        onGoLogin: () -> Unit,
    ) {
        ServerExtractPanel(
            snackbar = snackbar,
            onKindChange = onKindChange,
            onUseLocal = onUseLocal,
            onGoLogin = onGoLogin,
        )
    }

    /** 真实实现只存在于 full 源码集（`ExportMissingNoticeBody`），free 产物里查不到这个类名。 */
    @Composable
    override fun ExportMissingNotice(missingCount: Int, onSkip: () -> Unit) =
        ExportMissingNoticeBody(missingCount = missingCount, onSkip = onSkip)
}
