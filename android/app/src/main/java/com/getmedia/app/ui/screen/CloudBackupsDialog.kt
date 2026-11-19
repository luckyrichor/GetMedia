package com.getmedia.app.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getmedia.app.data.backup.BackupItem
import com.getmedia.app.data.backup.BackupManager
import com.getmedia.app.ui.components.uiMuted
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「管理云端备份」：列出该账号的每一份备份，逐份删除。
 *
 * 为什么需要它：误传的空清单（见 [BackupManager.runCheck] 的空库拦截）只能用单份删除清掉，
 * 而原来的「删除云端备份」是整账号全删——为了删一份把另外两份有用的也删了，代价太大。
 */
@Composable
internal fun CloudBackupsDialog(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<BackupItem>>(emptyList()) }
    var deletingId by remember { mutableStateOf<String?>(null) }

    fun load() {
        loading = true; error = null
        scope.launch {
            BackupManager.listCloud()
                .onSuccess { items = it }
                .onFailure { error = it.message ?: "读取失败，请稍后再试" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("云端备份") },
        text = {
            CloudBackupsContent(
                items = items,
                loading = loading,
                error = error,
                deletingId = deletingId,
                onDelete = { item ->
                    deletingId = item.id
                    BackupManager.deleteOne(item.id) { result ->
                        deletingId = null
                        if (result.isFailure) {
                            error = result.exceptionOrNull()?.message ?: "删除失败，请稍后再试"
                        }
                        load()
                    }
                },
            )
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
}

/** 弹窗正文。抽出来是为了渲染测试能直接画它（Dialog 在另一个 window 里拍不到）。 */
@Composable
internal fun CloudBackupsContent(
    items: List<BackupItem>,
    loading: Boolean,
    error: String?,
    deletingId: String?,
    onDelete: (BackupItem) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        when {
            loading -> Text("正在读取云端备份…", fontSize = 14.sp)
            error != null -> Text(error, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
            items.isEmpty() -> Text("这个账号下还没有备份。", fontSize = 14.sp)
            else -> items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (item.tracks <= 0) "空备份 · ${cloudTime(item.createdAt)}"
                            else "${item.tracks} 首 · ${cloudTime(item.createdAt)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (item.tracks <= 0) uiMuted() else MaterialTheme.colorScheme.onSurface,
                        )
                        if (item.device.isNotBlank()) {
                            Text(item.device, fontSize = 12.sp, color = uiMuted())
                        }
                    }
                    TextButton(
                        onClick = { onDelete(item) },
                        enabled = deletingId == null,
                    ) {
                        Text(
                            if (deletingId == item.id) "删除中…" else "删除",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "每账号保留最近 3 份。删掉的那份不会自动补回来，下次改动后才会重新上传。",
            fontSize = 12.sp, color = uiMuted(), lineHeight = 17.sp,
        )
    }
}

private val cloudTimeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun cloudTime(sec: Long): String =
    Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).format(cloudTimeFormat)
