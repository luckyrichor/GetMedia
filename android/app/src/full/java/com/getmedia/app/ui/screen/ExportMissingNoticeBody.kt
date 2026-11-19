package com.getmedia.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 「其中 N 项本机没有文件」整块——**只存在于 full 源码集**。
 *
 * 免费版调用同一个接缝，但实现是空函数（`free/ui/OwnerGate.kt`），所以这句文案与这个类名
 * 在 free 的 APK 里查不到（扫描见 `docs/export-plan.md` §9.3）。
 *
 * 本期只给「跳过这 N 项」：补下载再导出要等 P0-2 的按需下载，先不摆一个按了必然失败的按钮。
 */
@Composable
internal fun ExportMissingNoticeBody(missingCount: Int, onSkip: () -> Unit) {
    if (missingCount <= 0) return
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "其中 $missingCount 项本机没有文件",
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        AreaButton(onClick = onSkip) { Text("跳过这 $missingCount 项") }
    }
}
