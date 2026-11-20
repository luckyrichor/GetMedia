package com.getmedia.app.ui.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.getmedia.app.data.cloud.CloudUploadAsk

/**
 * 「有 N 条只在这台手机上，上传到账号 X？」（P0-2 阶段 3）。
 *
 * 只问一次：同意后自动同步，拒绝后不再问。文案里把"上传的是什么"说清楚——
 * 只有元数据（标题、博主、时长、原始链接），没有音频文件、没有封面、没有整理痕迹，
 * 与隐私说明 v3 的措辞对齐。
 */
@Composable
fun UploadAskDialog(ask: CloudUploadAsk, onUpload: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("上传本机新增") },
        text = {
            Text(
                "有 ${ask.count} 条只在这台手机上，上传到账号 ${ask.account}？\n\n" +
                    "只上传标题、博主、时长与原始链接，不上传音频文件和封面。上传后可以在网页控制台看到并管理。",
            )
        },
        confirmButton = { TextButton(onClick = onUpload) { Text("上传 ${ask.count} 条") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("暂不上传") } },
    )
}
