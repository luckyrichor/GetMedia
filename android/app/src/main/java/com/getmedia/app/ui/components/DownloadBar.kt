package com.getmedia.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getmedia.app.data.download.DownloadProgress
import com.getmedia.app.ui.formatSize

/**
 * 远端下载的进度条（没有下载时什么都不占）。
 *
 * 为什么放在底部导航上方：下载可以从条目菜单、播放列表面板、导出页、同步设置页发起，
 * 挂在某一个页面里就等于"只有从这一页发起才看得见"。放在 `Scaffold` 的 bottomBar 里，
 * 用户在哪个页签都能看到同一件事。
 *
 * 两行内容：第一行"正在下载 · 标题"加右侧的百分比与字节数，第二行是进度条本体。
 * 总长度未知（服务器没给 `Content-Length`，比如当场转封装的中转流）时画不确定进度条——
 * 这时候编一个百分比只会更让人糊涂。
 */
@Composable
fun DownloadBar() {
    val active by DownloadProgress.state.collectAsState()
    val item = active ?: return
    val fraction = item.fraction
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CloudDownload, contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "正在下载 · ${item.title}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                trailing(item.done, item.total, fraction),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                strokeCap = StrokeCap.Round,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

/** 右侧那行字：有总长度就给"45% · 68.2 / 162.4 MB"，没有就只说已下载多少。 */
private fun trailing(done: Long, total: Long?, fraction: Float?): String = when {
    total != null && total > 0 && fraction != null ->
        "${(fraction * 100).toInt()}% · ${formatSize(done)} / ${formatSize(total)}"
    done > 0 -> formatSize(done)
    else -> "准备中"
}

/**
 * 挂在**条目自己身上**的下载进度：列表里正在下载的那一行，在它内容区的下半部分多出一行
 * 「正在下载 45% · 68.2 / 162.4 MB」+ 一条细进度条。
 *
 * 为什么不做成全局挂在页面底部：底部那块是"正在播放"，同一时间只有一条队列状态；
 * 下载却是"这一条在传"，进度离开条目本身就要用户自己去对应（尤其批量补齐几项时）。
 * [trackId] 对不上就什么都不画，行高不变。
 */
@Composable
fun DownloadLine(trackId: Long, modifier: Modifier = Modifier) {
    val active by DownloadProgress.state.collectAsState()
    val item = active?.takeIf { it.trackId == trackId } ?: return
    val fraction = item.fraction
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CloudDownload, contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "正在下载",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                trailing(item.done, item.total, fraction),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                strokeCap = StrokeCap.Round,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp),
                strokeCap = StrokeCap.Round,
            )
        }
    }
}
