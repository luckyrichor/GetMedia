package com.getmedia.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getmedia.app.ui.components.uiAccentContainer
import com.getmedia.app.ui.components.uiCardColor
import com.getmedia.app.ui.components.uiMuted
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 登录后的一次性决策：云端有备份，先恢复还是以本机为准（见 `BackupManager` 的决策闸）。
 *
 * 为什么不做成"静默比较条数"：本机少于云端不一定是"丢了数据"，用户也可能就是删到这么少。
 * 只在这个账号**还没做过决定**时问一次，选了就不再问；不选也能继续用 App 的其它功能，
 * 只是自动备份先停着（卡片上有常驻提示与入口）。
 *
 * 抽成 Card 而不是直接写 AlertDialog：弹窗在另一个 window 里，渲染测试拍不到，
 * 分开之后亮/暗两种模式的截图才是真组件画出来的。
 */
@Composable
internal fun RestoreDecisionSheet(
    cloudTracks: Int,
    cloudAtMs: Long,
    onRestore: () -> Unit,
    onKeepLocal: () -> Unit,
    onLater: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = uiCardColor(),
        // uiCardColor() 不在配色方案里，contentColorFor 会给 Unspecified，深色下正文会发黑。
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 18.dp,
    ) {
        Column(Modifier.padding(20.dp)) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(uiAccentContainer()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Cloud, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "云端已有曲库备份",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "这个账号在云端有一份 ${cloudTracks} 首的备份（${decisionTime(cloudAtMs)}）。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "「恢复」把本机缺少的条目补回来，已有的标题、类别和播放列表不会被改到。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = uiMuted(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "在你选之前，自动备份先暂停——本机的清单一旦传上去，可能把云端这份挤掉。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = uiMuted(),
            )
            Spacer(Modifier.height(16.dp))
            AreaButton(
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth(),
                tone = ActionTone.CONFIRM,
            ) {
                Text("恢复云端备份", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            AreaButton(
                onClick = onKeepLocal,
                modifier = Modifier.fillMaxWidth(),
                tone = ActionTone.NORMAL,
            ) {
                Text("用本机内容（不恢复）", fontSize = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
                Text("稍后再说", fontSize = 13.sp, color = uiMuted(), textAlign = TextAlign.Center)
            }
        }
    }
}

private val decisionTimeFormat = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun decisionTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(decisionTimeFormat)

/** 供「数据备份」卡片复用的横幅文案：说清"暂停了、为什么、去哪儿处理"。 */
internal fun restorePausedHint(tracks: Int): String =
    "云端有 $tracks 首的备份，还没决定要不要恢复；自动备份已暂停。"
