package com.getmedia.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
        // 主操作必须看得出来：填充底色的胶囊 + 加粗，和右侧的纯文字「稍后处理」一眼能分。
        // 之前两个都是 TextButton，源码里 confirm 在右、视觉上却完全对等，用户不知道该点哪个（0.1.14）。
        confirmButton = { CloudRemovedKeepButton(onKeep) },
        dismissButton = { CloudRemovedSnoozeButton(onDismiss) },
    )
}

/**
 * 主按钮：「保留本机文件」。填充底色 + 半粗，是弹窗里唯一的强调项。
 *
 * 单独抽出来是为了能在 JVM 测试里截图比对填充占比——`AlertDialog` 是独立窗口，
 * Robolectric 下 `captureToImage` 抓不到它（forceRedraw 超时），只能把按钮本身放进普通布局里测。
 */
@Composable
internal fun CloudRemovedKeepButton(onKeep: () -> Unit) {
    Button(
        onClick = onKeep,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text("保留本机文件", fontWeight = FontWeight.SemiBold)
    }
}

/** 次要按钮：「稍后处理」。纯文字、不加粗，视觉上刻意比主按钮轻。 */
@Composable
internal fun CloudRemovedSnoozeButton(onDismiss: () -> Unit) {
    TextButton(onClick = onDismiss) {
        Text("稍后处理", fontWeight = FontWeight.Normal)
    }
}
