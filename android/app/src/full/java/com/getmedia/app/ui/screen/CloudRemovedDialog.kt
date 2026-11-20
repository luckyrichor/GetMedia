package com.getmedia.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getmedia.app.data.cloud.CloudRemovedAsk

/**
 * 「云端已移除 N 项，本机文件还在」的批量确认（P0-2 阶段 5）。
 *
 * 同步只发现墓碑，**从不删本机文件**；这里默认的主操作也是保留。
 * 关闭/返回只是"稍后处理"，墓碑记录仍留着，下次同步再问。
 * 本机文件要删只能逐项走普通的删除确认，不在这个弹窗里做批量破坏。
 */
@Composable
internal fun CloudRemovedDialog(ask: CloudRemovedAsk, onKeep: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("云端已移除 ${ask.count} 项") },
        text = {
            Column {
                Text("这些条目已在网页端删除，这台手机上的文件还在。你可以保留文件继续播放。")
                Column(
                    Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                        .padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ask.titles.forEach { title ->
                        Text(
                            "· $title",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "保留后这些条目会标为「本机独有」，不会再自动上传。要删除本机文件，请逐项删除。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onKeep) { Text("保留本机文件") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后处理") } },
    )
}
