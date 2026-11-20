package com.getmedia.app.ui.screen

import androidx.compose.runtime.Composable
import com.getmedia.app.data.export.ExportPlan

/**
 * 云端尚未下载的说明只由完整版渲染。
 *
 * 阶段 6：多给一个「先下载这 N 项」，走统一下载入口（[com.getmedia.app.data.cloud.cloudSync]），
 * 下载完这一块的计数会自己变小；失败原因由父级如实回传。
 */
@Composable
internal fun ExportMissingNoticeBody(
    missingCount: Int,
    busy: Boolean,
    message: String?,
    onDownload: () -> Unit,
    onSkip: () -> Unit,
) = ExportUnavailableNotice(
    missingCount = missingCount,
    reason = ExportPlan.REASON_NOT_DOWNLOADED,
    onSkip = onSkip,
    onDownload = onDownload,
    busy = busy,
    message = message,
)
