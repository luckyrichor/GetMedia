package com.getmedia.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getmedia.app.data.backup.BackupItem
import com.getmedia.app.data.backup.BackupManager
import com.getmedia.app.data.backup.RedownloadRunner
import com.getmedia.app.data.backup.RestoreService
import com.getmedia.app.ui.components.uiMuted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「从云端备份恢复」的对话框链：选一份 → 恢复 → 结果 → 全部重新下载。
 *
 * 恢复只有一种语义（设计 §5.5）：只补缺失、不覆盖已有。所以界面上不提供"覆盖"选项，
 * 也不问"要合并还是替换"——用户没有什么可选的，少一个问题就少一次误解。
 */
@Composable
internal fun RestoreFlow(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<BackupItem>>(emptyList()) }
    var selected by remember { mutableStateOf<BackupItem?>(null) }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RestoreService.Result?>(null) }
    var redownloading by remember { mutableStateOf(false) }

    fun load() {
        loading = true; error = null
        scope.launch {
            BackupManager.listCloud()
                // 只有一份且不是空备份时才默认选中；空备份选了也没东西可恢复。
                .onSuccess { items = it; if (it.size == 1 && it.first().tracks > 0) selected = it.first() }
                .onFailure { error = it.message ?: "读取失败，请稍后再试" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    when {
        redownloading -> RedownloadDialog(onClose = { redownloading = false; onClose() })

        result != null -> {
            val r = result!!
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("恢复完成") },
                text = {
                    Column {
                        Text("本地新增 ${r.addedTracks} 首，跳过 ${r.skippedTracks} 首（本地已有）。")
                        if (r.addedCategories > 0 || r.addedPlaylists > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "同时补回 ${r.addedCategories} 个类别、${r.addedPlaylists} 个播放列表。",
                                fontSize = 13.sp, color = uiMuted(),
                            )
                        }
                        if (r.addedTracks > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "新增的条目还没有本地文件，先标为「待下载」；点「全部重新下载」会逐条重新提取。",
                                fontSize = 13.sp, color = uiMuted(),
                            )
                        }
                    }
                },
                confirmButton = {
                    if (r.addedTracks > 0) {
                        TextButton(onClick = {
                            scope.launch {
                                val ids = BackupManager.pendingDownloadIds()
                                BackupManager.startRedownload(ids)
                                redownloading = true
                            }
                        }) { Text("全部重新下载") }
                    } else {
                        TextButton(onClick = onClose) { Text("好") }
                    }
                },
                dismissButton = if (r.addedTracks > 0) {
                    { TextButton(onClick = onClose) { Text("稍后") } }
                } else null,
            )
        }

        working -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在恢复") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("  正在下载并校验备份…", fontSize = 14.sp)
                }
            },
            confirmButton = {},
        )

        loading -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("从云端备份恢复") },
            text = { Text("正在读取云端备份…", fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = onClose) { Text("取消") } },
        )

        error != null -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("读取失败") },
            text = { Text(error!!, fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = { load() }) { Text("重试") } },
            dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
        )

        items.isEmpty() -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("云端还没有备份") },
            text = { Text("这台账号下没有可恢复的备份。先让「自动备份」跑一次，或点「立即备份」。", fontSize = 14.sp) },
            confirmButton = { TextButton(onClick = onClose) { Text("知道了") } },
        )

        else -> BackupPickerDialog(
            items = items,
            selected = selected,
            onSelect = { selected = it },
            onDismiss = onClose,
            onConfirm = {
                val target = selected ?: return@BackupPickerDialog
                if (target.tracks <= 0) return@BackupPickerDialog
                working = true
                scope.launch {
                    BackupManager.restoreNow(target.id)
                        .onSuccess { result = it }
                        .onFailure { error = it.message ?: "恢复失败，请稍后再试" }
                    working = false
                }
            },
        )
    }
}

/** 选一份备份。抽出来单独一个函数，渲染回归测试才好直接拍它。 */
@Composable
internal fun BackupPickerDialog(
    items: List<BackupItem>,
    selected: BackupItem?,
    onSelect: (BackupItem) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择要恢复的备份") },
        text = { BackupPickerContent(items, selected, onSelect) },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = onConfirm) { Text("恢复") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 弹窗正文。与 Dialog 分开，渲染回归测试才能直接把这一段画出来（Dialog 在另一个 window 里拍不到）。 */
@Composable
internal fun BackupPickerContent(
    items: List<BackupItem>,
    selected: BackupItem?,
    onSelect: (BackupItem) -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        items.forEach { item ->
            // 0 首的备份是"空清单"：恢复它不会补回任何条目，却容易被当成"有一份备份"。
            // 标出来并禁选，避免用户把它当成正常备份点下去。
            val empty = item.tracks <= 0
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = !empty) { onSelect(item) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected?.id == item.id,
                    onClick = { onSelect(item) },
                    enabled = !empty,
                )
                Column(Modifier.padding(start = 4.dp)) {
                    Text(
                        if (empty) "空备份 · ${backupTime(item.createdAt)}"
                        else "${item.tracks} 首 · ${backupTime(item.createdAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (empty) uiMuted() else MaterialTheme.colorScheme.onSurface,
                    )
                    if (item.device.isNotBlank()) {
                        Text(item.device, fontSize = 12.sp, color = uiMuted())
                    }
                    if (empty) {
                        Text("上传时本地没有条目，恢复不会带回内容", fontSize = 12.sp, color = uiMuted())
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (items.isNotEmpty() && items.all { it.tracks <= 0 }) {
                "云端只有空备份，没有可恢复的内容。"
            } else {
                "恢复只补本地缺少的条目，已有的标题、类别、收藏和播放列表不会被动到。"
            },
            fontSize = 12.sp, color = uiMuted(), lineHeight = 17.sp,
        )
    }
}

/** 重新下载的进度：N/M、当前标题、成功/失败计数，熔断时给 [继续] 与 [停止]。 */
@Composable
private fun RedownloadDialog(onClose: () -> Unit) {
    val flow = remember { BackupManager.redownloadState() ?: MutableStateFlow(RedownloadRunner.State()) }
    val state by flow.collectAsState()
    AlertDialog(
        onDismissRequest = { if (!state.running) onClose() },
        title = { Text(if (state.running) "正在重新下载" else "已暂停") },
        text = {
            Column {
                Text("${state.done}/${state.total}　成功 ${state.ok} · 失败 ${state.failed}", fontSize = 14.sp)
                state.current?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 13.sp, color = uiMuted())
                }
                if (state.needsImport > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text("${state.needsImport} 条没有来源链接，需要重新导入（不计为失败）。", fontSize = 12.sp, color = uiMuted())
                }
                state.paused?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                if (state.finished) {
                    Spacer(Modifier.height(8.dp))
                    Text("本轮结束：成功 ${state.ok} 条，失败 ${state.failed} 条。", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            when {
                state.running -> TextButton(onClick = { BackupManager.stopRedownload() }) { Text("停止") }
                state.finished -> TextButton(onClick = onClose) { Text("关闭") }
                else -> TextButton(onClick = { BackupManager.resumeRedownload() }) { Text("继续") }
            }
        },
        dismissButton = if (state.running) {
            { TextButton(onClick = { BackupManager.stopRedownload(); onClose() }) { Text("关闭") } }
        } else {
            { TextButton(onClick = onClose) { Text("关闭") } }
        },
    )
}

private val restoreTimeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/** 服务端 `created_at` 是秒；兼容一下已经是毫秒的情况。 */
private fun backupTime(value: Long): String {
    val ms = if (value in 1..9_999_999_999L) value * 1000 else value
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(restoreTimeFormat)
}
