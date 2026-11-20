package com.getmedia.app.ui.screen

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.getmedia.app.data.backup.BackupManager
import com.getmedia.app.data.backup.resetRestoreDecisionForPreview
import com.getmedia.app.ui.components.RowDivider
import com.getmedia.app.ui.components.SettingRow

/**
 * 「诊断与反馈」里的重置入口——**只存在于预览构建**。
 *
 * 正式包的 debug/release 源码集里有同名函数但只 `= Unit`，所以正式包 APK 里既没有这行字，
 * 也没有这个弹窗的任何文案（可用 `strings` 在 APK 上扫证）。理由：它是复验"登录后弹恢复
 * 决定框"用的后门，普通用户用不上，留在正式包里只会被误点。
 */
@Composable
internal fun RestoreResetSection(account: String?) {
    var asking by remember { mutableStateOf(false) }
    val context = LocalContext.current

    SettingRow(
        Icons.Rounded.Refresh,
        "重置云端恢复决策",
        subtitle = "下次登录或回到前台会重新询问",
        showChevron = true,
        onClick = { asking = true },
    )
    RowDivider()

    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            title = { Text("重置云端恢复决策？") },
            text = {
                Text(
                    "只清掉本设备上「${account ?: "当前账号"} 的云端备份要不要恢复」这条记录：" +
                        "云端备份、曲库和文件都不动。之后下次登录或回到前台会重新问一次。",
                )
            },
            confirmButton = {
                AreaButton(
                    onClick = {
                        asking = false
                        if (account != null) {
                            BackupManager.resetRestoreDecisionForPreview(context, account)
                            Toast.makeText(context, "已重置，下次登录或回到前台会重新询问", Toast.LENGTH_SHORT).show()
                        }
                    },
                    tone = ActionTone.CONFIRM,
                ) { Text("重置") }
            },
            dismissButton = { AreaButton(onClick = { asking = false }) { Text("取消") } },
        )
    }
}
