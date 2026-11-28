package com.getmedia.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getmedia.app.data.download.DownloadProgress
import com.getmedia.app.data.download.UploadProgress
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
            if (item.cancellable) {
                Spacer(Modifier.width(4.dp))
                Text(
                    "取消",
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { DownloadProgress.cancel(item.trackId) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
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

/** 右侧那行字：有总长度就给"45% · 68.2 / 162.4 MB"，没有就只说已传多少。 */
private fun trailing(done: Long, total: Long?, fraction: Float?): String = when {
    total != null && total > 0 && fraction != null ->
        "${(fraction * 100).toInt()}% · ${sizeText(done)} / ${formatSize(total)}"
    done > 0 -> sizeText(done)
    else -> "准备中"
}

/**
 * 已传字节数的文案。`formatSize` 把 0 当作"未知"返回 `-`（条目列表里需要这个语义），
 * 但进度行的第一帧必然是 0 字节——那里要的是"真的还一个字节都没传"，不是"不知道"。
 */
private fun sizeText(bytes: Long): String = if (bytes <= 0) "0 B" else formatSize(bytes)

/**
 * 挂在**条目自己身上**的传输进度：列表里正在传的那一行，在它内容区的下半部分多出一行
 * 「正在下载 45% · 68.2 / 162.4 MB」+ 一条细进度条。
 *
 * 为什么不做成全局挂在页面底部：底部那块是"正在播放"，同一时间只有一条队列状态；
 * 传输却是"这一条在传"，进度离开条目本身就要用户自己去对应（尤其批量补齐几项时）。
 * [trackId] 对不上就什么都不画，行高不变。
 *
 * 下载与上传共用这一套画法（[TransferLine]），只有图标、文案与颜色不同——两边分别是
 * [DownloadProgress] 与 [UploadProgress] 两个槽位，可以同时各有一条在跑。
 */
@Composable
fun DownloadLine(trackId: Long, modifier: Modifier = Modifier) {
    val active by DownloadProgress.state.collectAsState()
    val item = active?.takeIf { it.trackId == trackId } ?: return
    TransferLine(
        label = "正在下载",
        icon = Icons.Default.CloudDownload,
        done = item.done,
        total = item.total,
        fraction = item.fraction,
        modifier = modifier,
        cancel = if (item.cancellable) ({ DownloadProgress.cancel(trackId) }) else null,
    )
}

/**
 * 本机文件备份到云端的进度（导入音频/视频后自动上传）。
 *
 * 与下载那一行长得一样，是为了让"正在传"这件事在两处读起来是同一种东西；分开成两个
 * Composable 而不是加参数，是因为它们读的是两个互不相干的状态源。
 */
@Composable
fun UploadLine(trackId: Long, modifier: Modifier = Modifier) {
    val active by UploadProgress.state.collectAsState()
    val item = active?.takeIf { it.trackId == trackId } ?: return
    TransferLine(
        label = "正在上传",
        icon = Icons.Default.CloudUpload,
        done = item.done,
        total = item.total,
        fraction = item.fraction,
        modifier = modifier,
    )
}

/**
 * 一行传输进度：图标 + 文案 + 「45% · 68.2 / 162.4 MB」+ 细进度条。
 *
 * [cancel] 非空才画「取消」：发起方拿不到可取消的东西时，画一个点了没反应的按钮更糟。
 */
@Composable
private fun TransferLine(
    label: String,
    icon: ImageVector,
    done: Long,
    total: Long?,
    fraction: Float?,
    modifier: Modifier = Modifier,
    cancel: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                trailing(done, total, fraction),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (cancel != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "取消",
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = cancel)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
            }
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
