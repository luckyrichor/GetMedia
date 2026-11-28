package com.getmedia.app.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getmedia.app.data.backup.BackupManager
import com.getmedia.app.data.backup.BackupPrefs
import com.getmedia.app.ui.components.SectionCard
import com.getmedia.app.ui.components.SectionTitle
import com.getmedia.app.ui.components.uiAccentContainer
import com.getmedia.app.ui.components.uiMuted
import androidx.compose.runtime.collectAsState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「我的 → 数据备份」（设计 §5.7）。
 *
 * 状态行是常驻的，不是一次性提示条：`blocked` 有值时重进设置页还在，
 * 直到本地体积回落或内容修正后自动消失——用户不会"看了一眼就再也找不到原因"。
 *
 * [injected] 只给渲染测试用（Robolectric 截图）；正式界面传 null，跟 [BackupManager.state] 走。
 */
@Composable
fun BackupSection(injected: BackupManager.UiState? = null) {
    val context = LocalContext.current
    val live by BackupManager.state.collectAsState()
    val state = injected ?: live
    var confirmDelete by remember { mutableStateOf(false) }
    var restoreOpen by remember { mutableStateOf(false) }
    var manageOpen by remember { mutableStateOf(false) }

    SectionTitle("数据备份")
    SectionCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(uiAccentContainer()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Cloud, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("曲库清单备份", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(
                    statusText(state),
                    fontSize = 12.sp,
                    color = statusColor(state),
                    lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.enabled,
                onCheckedChange = { BackupManager.setEnabled(it) },
                enabled = state.loggedIn && state.blocked == null,
            )
        }

        if (state.loggedIn) {
            Text(
                when {
                    !state.enabled -> "已关闭自动备份。云端已有的备份仍保留，可以在这里删除。"
                    state.blocked != null -> "自动备份已停止。清理到上限内或换一份能通过校验的数据后会自动恢复。"
                    state.restoreDecisionPending -> "决定之前不会上传，云端那份不会被动到。"
                    else -> "改动后自动备份，每账号保留最近 3 份；只传清单，不传媒体文件。"
                },
                fontSize = 12.sp,
                color = uiMuted(),
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
            // 决策没做之前不能只弹一次了事：卡片上常驻一条，点「去处理」再把弹窗叫回来。
            // 这就是"稍后"不会变成静默死锁的那条出口。
            if (state.restoreDecisionPending) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(uiAccentContainer())
                        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        restorePausedHint(state.restoreCloudTracks),
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextButton(onClick = { BackupManager.showRestoreDecision() }) {
                        Text("去处理", fontSize = 13.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AreaButton(
                    onClick = { BackupManager.backupNow() },
                    enabled = state.enabled && state.blocked == null && !state.busy,
                    modifier = Modifier.weight(1f),
                    tone = ActionTone.CONFIRM,
                ) {
                    Text(if (state.busy) "正在备份…" else "立即备份", fontSize = 13.sp)
                }
                AreaButton(
                    onClick = { restoreOpen = true },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                    tone = ActionTone.SYNC,
                ) {
                    Text("从云端备份恢复", fontSize = 13.sp)
                }
            }
            TextButton(
                onClick = { manageOpen = true },
                enabled = !state.busy,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Text("管理云端备份（可单份删除）", fontSize = 13.sp)
            }
            AreaButton(
                onClick = { confirmDelete = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                tone = ActionTone.DESTRUCTIVE,
            ) {
                Text("删除云端备份", fontSize = 13.sp)
            }
        } else {
            Text(
                "登录后可用备份：重装或换机时把曲库清单恢复回来。",
                fontSize = 12.sp,
                color = uiMuted(),
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
    }

    if (restoreOpen) RestoreFlow(onClose = { restoreOpen = false })

    if (manageOpen) CloudBackupsDialog(onClose = { manageOpen = false })

    // 空库点「立即备份」：先说清楚代价再传。服务器每账号只留 3 份，
    // 一份空清单挤掉的可能是唯一一份有内容的备份。
    if (state.emptyConfirmPending) {
        AlertDialog(
            onDismissRequest = { BackupManager.cancelEmptyConfirm() },
            title = { Text("本地曲库为空") },
            text = { Text(emptyBackupMessage(state.cloudCount)) },
            confirmButton = {
                TextButton(onClick = { BackupManager.confirmEmptyBackup() }) { Text("仍要上传") }
            },
            dismissButton = {
                TextButton(onClick = { BackupManager.cancelEmptyConfirm() }) { Text("取消") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除云端备份？") },
            text = { Text("会删掉这个账号在服务器上的全部备份（最多 3 份）。本机媒体库和文件不受影响；下次改动后若自动备份仍开启，会重新生成一份。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    BackupManager.deleteCloud { result ->
                        val message = result.fold(
                            onSuccess = { if (it > 0) "已删除云端备份" else "云端没有备份" },
                            onFailure = { "删除失败，请稍后重试" },
                        )
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

private fun statusText(state: BackupManager.UiState): String = when {
    !state.loggedIn -> "登录后可用备份"
    state.blocked == BackupPrefs.BLOCKED_TOO_LARGE -> "备份已停用：库太大（超过 512 KiB）"
    state.blocked == BackupPrefs.BLOCKED_INVALID -> "备份已停用：上次上传的内容不合规"
    // 决策没做：状态行也要说"为什么停着"，不能只在下面挂一条提示
    state.restoreDecisionPending -> "自动备份已暂停：等你决定是否恢复云端备份"
    state.unsupported -> "服务器暂不支持备份，稍后会自动再试"
    state.busy -> "正在备份…"
    // 限频不是失败：上一次成功的记录还在，这里只提示"等一会儿"，不覆盖上次时间
    state.throttleUntilMs > 0 -> "刚备份过，请 10 分钟后再试"
    state.dirty && state.lastAtMs > 0 -> "上次备份失败，将在下次打开时重试"
    state.lastAtMs > 0 -> "上次备份：${state.lastTracks} 首 · ${formatBackupTime(state.lastAtMs)}"
    else -> "还没备份过"
}

@Composable
private fun statusColor(state: BackupManager.UiState) = when {
    state.blocked != null -> MaterialTheme.colorScheme.error
    state.unsupported -> MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
    state.restoreDecisionPending -> MaterialTheme.colorScheme.tertiary
    state.throttleUntilMs > 0 -> uiMuted()
    state.dirty -> MaterialTheme.colorScheme.tertiary
    else -> uiMuted()
}

private val timeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun formatBackupTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFormat)

/**
 * 「本地曲库为空」时点「立即备份」的确认文案。
 *
 * `cloudCount` = 云端现有几份（`BackupManager.UiState.cloudCount`）：
 * - `-1` 没查到（离线/超时），只说通用规则，不编造份数；
 * - `0..2` 只说现有份数，不吓唬人（传完还没满 3 份，不会移除任何东西）；
 * - `>=3` 才会真的移除最久的一份（服务端 `metadata_backup.KEEP = 3`）。
 */
internal fun emptyBackupMessage(cloudCount: Int): String = when {
    cloudCount < 0 ->
        "云端最多保留 3 份备份。备份满时上传会移除最久的备份。"
    cloudCount >= 3 ->
        "云端现有 $cloudCount 份备份（上限 3 份）。上传会移除最久的备份。"
    else ->
        "云端现有 $cloudCount 份备份（上限 3 份）。"
}
