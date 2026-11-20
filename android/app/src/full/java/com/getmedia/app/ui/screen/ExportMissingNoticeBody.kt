package com.getmedia.app.ui.screen

import androidx.compose.runtime.Composable
import com.getmedia.app.data.export.ExportPlan

/** 云端尚未下载的说明只由完整版渲染。 */
@Composable
internal fun ExportMissingNoticeBody(missingCount: Int, onSkip: () -> Unit) =
    ExportUnavailableNotice(missingCount, ExportPlan.REASON_NOT_DOWNLOADED, onSkip)
