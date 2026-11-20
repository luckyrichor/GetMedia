package com.getmedia.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 「其中 N 项在本机找不到文件」整块。
 *
 * 阶段 6 起，能补下载的那批（云端行、恢复空壳）多一个「先下载这 N 项」：
 * 点它走**统一下载入口**按行状态分派，下载完列表自然刷新，不必重进这个页面。
 * 补不回来的（本机文件真的丢了）只有「跳过」——那句文案由调用方给，见 `ExportPlan`。
 *
 * @param busy 正在下载：按钮禁用，避免重复点。
 * @param message 上一次的结果（含失败原因），如实显示，不写成"已丢失"。
 */
@Composable
internal fun ExportUnavailableNotice(
    missingCount: Int,
    reason: String,
    onSkip: () -> Unit,
    onDownload: (() -> Unit)? = null,
    busy: Boolean = false,
    message: String? = null,
) {
    if (missingCount <= 0) return
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "其中 $missingCount 项$reason",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        if (!message.isNullOrBlank()) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            if (busy) {
                Text(
                    "正在下载…",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            if (onDownload != null) {
                AreaButton(onClick = onDownload, enabled = !busy) { Text("先下载这 $missingCount 项") }
                Spacer(Modifier.width(8.dp))
            }
            AreaButton(onClick = onSkip) { Text("跳过这 $missingCount 项") }
        }
    }
}
