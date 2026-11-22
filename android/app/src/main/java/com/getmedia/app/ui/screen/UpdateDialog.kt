package com.getmedia.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.getmedia.app.update.UpdatePrompt

/**
 * 「有新版本」提示（免费版与完整版共用）。
 *
 * - 普通版本：可以"稍后"，也可以"不再提示这个版本"；
 * - 服务端把 `min_version` 抬到当前之上时（`mandatory`），不提供"稍后"，只能去下载——
 *   这类版本通常带有服务端不兼容的改动，留着旧版只会一直报错。
 *
 * 更新说明原样展示服务端给的文本（最多几行），不做二次加工，避免措辞与 Release 不一致。
 */
@Composable
fun UpdateDialog(
    prompt: UpdatePrompt,
    onDownload: () -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!prompt.mandatory) onLater() },
        title = { Text("GetMedia ${prompt.version} 可以更新了") },
        text = {
            Column {
                if (prompt.notes.isBlank()) {
                    Text("有新版本可用，建议更新后再继续使用。")
                } else {
                    Text(prompt.notes.take(1200))
                }
                if (prompt.mandatory) {
                    Text(
                        "这个版本需要更新后才能继续使用。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("去下载") } },
        dismissButton = {
            if (!prompt.mandatory) {
                Column {
                    TextButton(onClick = onLater) { Text("稍后") }
                    TextButton(onClick = onIgnore) {
                        Text("不再提示 ${prompt.version}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
    )
}
