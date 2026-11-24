package com.getmedia.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.player.PlayerHolder
import com.getmedia.app.data.repo.PlaylistRepository
import kotlinx.coroutines.launch

@Composable
fun QueueBar() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue by PlayerHolder.queue.collectAsState()
    val sourceId by PlayerHolder.sourcePlaylistId.collectAsState()
    val playlists by remember(context) { PlaylistRepository(context).observeAll() }.collectAsState(initial = emptyList())
    val listName = playlists.firstOrNull { it.id == sourceId }?.name ?: playlists.firstOrNull { it.isDefault }?.name ?: "播放列表"
    val index by PlayerHolder.currentIndex.collectAsState()
    val playing by PlayerHolder.isPlaying.collectAsState()
    val mode by PlayerHolder.mode.collectAsState()
    val timer = timerStatus()
    var showTimer by remember { mutableStateOf(false) }
    var showMode by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    // 当前曲目是视频时，在播放条上方展开画面；同一个 Media3 会话，不重新加载。
    var showVideo by remember { mutableStateOf(false) }
    val current = queue.getOrNull(index)?.track
    val isVideo = current?.mediaType == MediaKind.VIDEO.name
    val missingCount = queue.count { !it.playable }
    // 队列跟随来源列表：另一台设备加/删/调序后，正在播放的这一份最多一分钟内对齐。
    LaunchedEffect(sourceId) { sourceId?.let { PlayerHolder.follow(context, it) } }
    // 全局「显示画面」开关：关闭时即使选中视频也不出画面，只有声音。
    val videoDisplayEnabled by com.getmedia.app.data.repo.VideoDisplayPrefs.showVideo.collectAsState()
    LaunchedEffect(current?.id) { showVideo = isVideo }
    if (queue.isNotEmpty()) {
        Column {
            if (isVideo && showVideo && videoDisplayEnabled) VideoPlaybackPanel { showVideo = false }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().height(88.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp).clickable(enabled = isVideo && videoDisplayEnabled) { showVideo = true }) {
                    Text(queue.getOrNull(index)?.track?.title ?: queue.first().track.title,
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    key(queue.getOrNull(index)?.track?.id) {
                        PlaybackProgress(queue.getOrNull(index)?.track?.id ?: queue.first().track.id)
                    }
                }
                Column(Modifier.width(64.dp).height(56.dp).clickable { showMode = true }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(playbackModeIcon(mode), mode.label, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(mode.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                }
                IconButton(onClick = { scope.launch { PlayerHolder.next(context) } }) {
                    Icon(Icons.Default.SkipNext, "下一首")
                }
                FilledIconButton(onClick = { scope.launch { PlayerHolder.toggle(context) } }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "暂停" else "继续")
                }
                IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.List, "播放列表：$listName") }
            }

        }
    }
    if (showTimer) TimerDialog(onClose = { showTimer = false })
    if (showMode) PlaybackModeDialog(onClose = { showMode = false })
    if (showQueue) MusicSheet(
        title = listName,
        onDismiss = { showQueue = false },
        subtitle = when {
            timer != null -> "定时停止：$timer"
            missingCount > 0 -> "$missingCount 首本机未下载，播放时会跳过"
            else -> "长按拖动调整播放顺序"
        },
        badge = if (queue.size == 1) "1 首" else "${queue.size} 首",
        scroll = false,
        confirmText = "完成",
        onConfirm = { showQueue = false },
        header = if (timer != null) {
            {
                // 定时相关的两个 pill 单独一行；大字体/窄屏放不下时可以横向滑动，不会被裁掉。
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SheetPill("查看定时", Icons.Default.Timer, { showQueue = false; showTimer = true })
                    SheetPill("取消定时", Icons.Default.TimerOff, { PlayerHolder.cancelTimer() })
                }
            }
        } else null,
    ) {
        ReorderableQueue(
            queue = queue, currentId = queue.getOrNull(index)?.track?.id, playing = playing,
            onReorder = { ids -> PlayerHolder.reorder(context, sourceId, ids) },
            onPlay = { id -> scope.launch {
                val position = PlayerHolder.queue.value.indexOfFirst { it.track.id == id }
                if (position >= 0) {
                    if (position == PlayerHolder.currentIndex.value) PlayerHolder.toggle(context)
                    else PlayerHolder.jump(context, position)
                }
            } },
            onRemove = { id -> scope.launch {
                val position = PlayerHolder.queue.value.indexOfFirst { it.track.id == id }
                if (position >= 0) PlayerHolder.remove(context, position)
            } },
        )
    }
}
