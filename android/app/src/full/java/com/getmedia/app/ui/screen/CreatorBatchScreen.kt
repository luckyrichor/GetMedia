package com.getmedia.app.ui.screen

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.creator.BatchState
import com.getmedia.app.data.creator.CreatorApi
import com.getmedia.app.data.creator.CreatorBatch
import com.getmedia.app.data.creator.CreatorException
import com.getmedia.app.data.creator.CreatorPlatform
import com.getmedia.app.data.creator.CreatorPreview
import com.getmedia.app.data.creator.BatchEntry
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.repo.DiagnosticsStore
import com.getmedia.app.data.repo.ErrorText
import com.getmedia.app.data.repo.PendingNotice
import com.getmedia.app.data.repo.TrackRepository
import com.getmedia.app.ui.components.Footnote
import com.getmedia.app.ui.components.NoticeHost
import com.getmedia.app.ui.components.InlineBanner
import com.getmedia.app.ui.components.SectionCard
import com.getmedia.app.ui.components.SubPageScaffold
import com.getmedia.app.ui.components.uiAccentContainer
import com.getmedia.app.ui.components.uiHairline
import com.getmedia.app.ui.components.uiMuted
import com.getmedia.app.ui.components.uiSuccess
import com.getmedia.app.ui.formatDuration
import kotlinx.coroutines.launch

/**
 * 博主批量提取。
 *
 * 交互分三步：输入 → 预览窗口 → 在本机逐条提取。列表由服务端抓（需要服务端出口
 * 和平台 cookie），媒体仍由手机的 DirectExtractor 下载并入库，不经过服务器存储。
 */
class CreatorBatchViewModel(
    private val repo: TrackRepository,
    private val api: CreatorApi,
    private val version: String,
) : ViewModel() {

    var input by mutableStateOf("")
    var platform by mutableStateOf<CreatorPlatform?>(null)
        private set
    var kind by mutableStateOf(MediaKind.AUDIO)
    var startText by mutableStateOf("1")
    var countText by mutableStateOf(CreatorApi.DEFAULT_COUNT.toString())

    var loading by mutableStateOf(false)
        private set
    var preview by mutableStateOf<CreatorPreview?>(null)
        private set
    var batch by mutableStateOf(CreatorBatch())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var summary by mutableStateOf<String?>(null)
        private set

    var running by mutableStateOf(false)
        private set
    var currentTitle by mutableStateOf("")
        private set
    var batchTotal by mutableStateOf(0)
        private set
    var batchIndex by mutableStateOf(0)
        private set

    internal val notification = PendingNotice<String>()
    private var cancelRequested = false
    private var startedAt = 0L

    private fun record(message: String) = DiagnosticsStore.record(startedAt, message)

    fun selectPlatform(value: CreatorPlatform?) {
        platform = value
        if (error != null) error = null
    }

    fun pasteFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().trim()
        if (text.isNotEmpty()) input = text
    }

    /** 预览：参数先在本地夹取，真正的上限仍由服务端判断。 */
    fun load() {
        if (loading) return
        val text = input.trim()
        if (text.isEmpty()) {
            error = "请先粘贴博主主页链接，或输入抖音号 / B站 UID"
            return
        }
        if (text.all { it.isDigit() } && platform == null) {
            error = "纯数字需要先选择平台：B 站 UID 和抖音号看起来一样，猜错会查错人"
            return
        }
        val start = (startText.toIntOrNull() ?: 1).coerceIn(1, CreatorApi.MAX_START)
        val count = (countText.toIntOrNull() ?: CreatorApi.DEFAULT_COUNT).coerceIn(1, CreatorApi.MAX_COUNT)
        startText = start.toString()
        countText = count.toString()

        viewModelScope.launch {
            loading = true
            error = null
            summary = null
            try {
                val result = api.preview(text, platform, start, count)
                preview = result
                batch = CreatorBatch(result.items.map { BatchEntry(item = it, selected = true) })
            } catch (e: CreatorException) {
                preview = null
                batch = CreatorBatch()
                error = e.message
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // 解析异常、地址非法等：给一句能看懂的提示，不让协程崩掉整个页面
                preview = null
                batch = CreatorBatch()
                error = "读取作品列表失败，请稍后重试"
            } finally {
                loading = false
            }
        }
    }

    fun toggle(position: Int) {
        if (running) return
        batch = batch.toggle(position)
    }

    fun selectAll(selected: Boolean) {
        if (running) return
        batch = batch.selectAll(selected)
    }

    fun start() {
        if (running || batch.pendingSelected == 0) return
        val profile = preview?.profile
        viewModelScope.launch {
            running = true
            cancelRequested = false
            error = null
            summary = null
            batchTotal = batch.pendingSelected
            batchIndex = 0
            startedAt = SystemClock.elapsedRealtime()
            DiagnosticsStore.beginLocal(version)
            record("博主批量：${profile?.name.orEmpty()} · ${platform?.label ?: profile?.platform?.label.orEmpty()}")

            while (true) {
                if (cancelRequested) {
                    batch = batch.cancel()
                    break
                }
                val (next, item) = batch.claimNext()
                batch = next
                if (item == null) break

                batchIndex += 1
                currentTitle = item.title
                record("---- ${item.title} ${item.sourceUrl}")
                val result = repo.extract(item.sourceUrl, kind) { line -> record(line) }
                result
                    .onSuccess { batch = batch.markDone(item.position) }
                    .onFailure { failure ->
                        val message = ErrorText.humanMessage(failure)
                        batch = batch.markFailed(item.position, message)
                        record("提取失败：$message")
                    }
            }

            currentTitle = ""
            running = false
            cancelRequested = false
            val text = batch.summary()
            summary = text
            DiagnosticsStore.finish(batch.failedCount == 0)
            notification.post(text)
        }
    }

    fun stop() {
        if (!running) return
        cancelRequested = true
        batch = batch.cancel()
    }

    fun retryFailed() {
        if (running) return
        batch = batch.retryFailed()
        start()
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CreatorBatchViewModel(
                TrackRepository(context),
                CreatorApi(),
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知版本",
            ) as T
    }
}

@Composable
fun CreatorBatchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: CreatorBatchViewModel = viewModel(factory = CreatorBatchViewModel.Factory(context))
    val snackbar = remember { SnackbarHostState() }
    val session by SessionStore.current.collectAsState()

    LaunchedEffect(Unit) {
        snapshotFlow { vm.notification.value }.collect { message ->
            if (message != null) {
                vm.notification.take()
                snackbar.showSnackbar(message, withDismissAction = true)
            }
        }
    }

    SubPageScaffold("博主批量", onBack) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (session == null) {
                    InlineBanner(
                        "博主批量需要先登录：列表由服务器抓取，用自己的账号查询。请到「我的」登录后再来。",
                        tone = MaterialTheme.colorScheme.error,
                        icon = Icons.Default.Person,
                    )
                } else {
                    InlineBanner(
                        "输入博主主页，先看作品列表，再挑要提取的条目。列表由服务器读取，音频/视频仍然只在手机上提取。",
                        icon = Icons.Default.Info,
                    )
                }

                // ---------- 输入 ----------
                SectionCard(padding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                vm.platform == null,
                                { vm.selectPlatform(null) },
                                { Text("自动识别") },
                                enabled = !vm.loading && !vm.running,
                            )
                            FilterChip(
                                vm.platform == CreatorPlatform.BILIBILI,
                                { vm.selectPlatform(CreatorPlatform.BILIBILI) },
                                { Text("B 站") },
                                enabled = !vm.loading && !vm.running,
                            )
                            FilterChip(
                                vm.platform == CreatorPlatform.DOUYIN,
                                { vm.selectPlatform(CreatorPlatform.DOUYIN) },
                                { Text("抖音") },
                                enabled = !vm.loading && !vm.running,
                            )
                        }
                        OutlinedTextField(
                            value = vm.input,
                            onValueChange = { vm.input = it },
                            enabled = !vm.loading && !vm.running,
                            placeholder = { Text("粘贴博主主页链接、分享文案，或输入抖音号 / B站 UID", style = MaterialTheme.typography.bodyMedium) },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = uiAccentContainer(),
                                unfocusedContainerColor = uiAccentContainer(),
                            ),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AreaButton(onClick = { vm.pasteFromClipboard(context) }, enabled = !vm.loading && !vm.running, tone = ActionTone.SYNC) {
                                Icon(Icons.Default.ContentPaste, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("粘贴剪贴板")
                            }
                            AreaButton(onClick = { vm.input = "" }, enabled = !vm.loading && !vm.running && vm.input.isNotBlank()) {
                                Text("清空")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(vm.kind == MediaKind.AUDIO, { vm.kind = MediaKind.AUDIO }, { Text("音频") }, enabled = !vm.running)
                            FilterChip(vm.kind == MediaKind.VIDEO, { vm.kind = MediaKind.VIDEO }, { Text("视频") }, enabled = !vm.running)
                            Spacer(Modifier.weight(1f))
                            Text("每条都按这个类型保存", fontSize = 11.sp, color = uiMuted())
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = vm.startText,
                                onValueChange = { vm.startText = it.filter(Char::isDigit).take(3) },
                                label = { Text("起点") },
                                singleLine = true,
                                enabled = !vm.loading && !vm.running,
                                modifier = Modifier.width(96.dp),
                            )
                            OutlinedTextField(
                                value = vm.countText,
                                onValueChange = { vm.countText = it.filter(Char::isDigit).take(2) },
                                label = { Text("数量") },
                                singleLine = true,
                                enabled = !vm.loading && !vm.running,
                                modifier = Modifier.width(96.dp),
                            )
                            Spacer(Modifier.weight(1f))
                            listOf(10, 20, 50).forEach { preset ->
                                AssistChip(
                                    onClick = { vm.countText = preset.toString() },
                                    label = { Text("$preset") },
                                    enabled = !vm.loading && !vm.running,
                                )
                            }
                        }
                        Footnote("一次最多 50 条，可分多次、调整起点继续；起点 1 是最新一条。")
                        Button(
                            onClick = { vm.load() },
                            enabled = !vm.loading && !vm.running && session != null && vm.input.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Icon(Icons.Default.Person, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (vm.loading) "正在读取作品列表…" else "预览作品列表")
                        }
                    }
                }

                if (vm.error != null) {
                    InlineBanner(vm.error!!, tone = MaterialTheme.colorScheme.error, icon = Icons.Default.ErrorOutline)
                }
                if (vm.loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                }

                val preview = vm.preview
                if (preview != null) {
                    ProfileCard(preview)
                    WindowSection(vm, preview)
                    BatchSection(vm)
                }
            }
            NoticeHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun ProfileCard(preview: CreatorPreview) {
    val profile = preview.profile
    SectionCard(padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (profile.avatarUrl != null) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(uiAccentContainer()),
                )
            } else {
                Box(Modifier.size(52.dp).clip(CircleShape).background(uiAccentContainer()), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, Modifier.size(24.dp), tint = uiMuted())
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        profile.platform.label,
                        profile.followerCount?.let { "粉丝 ${formatCount(it)}" },
                        profile.totalVideos?.let { "作品 ${formatCount(it)}" },
                    ).joinToString(" · "),
                    fontSize = 12.sp,
                    color = uiMuted(),
                )
            }
        }
        if (!profile.signature.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                profile.signature!!,
                fontSize = 12.sp,
                color = uiMuted(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WindowSection(vm: CreatorBatchViewModel, preview: CreatorPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "第 ${preview.start}–${preview.start + preview.items.size - 1} 条" +
                    (preview.total?.let { " / 共 ${formatCount(it)}" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            val allSelected = vm.batch.entries.isNotEmpty() && vm.batch.entries.all { it.selected }
            TextButton(onClick = { vm.selectAll(!allSelected) }, enabled = !vm.running) {
                Text(if (allSelected) "全不选" else "全选")
            }
        }
        SectionCard(padding = PaddingValues(0.dp)) {
            vm.batch.entries.forEachIndexed { index, entry ->
                if (index > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(uiHairline()))
                ItemRow(entry, enabled = !vm.running && !vm.loading) { vm.toggle(entry.item.position) }
            }
        }
        if (preview.hasMore) {
            Footnote("后面还有更多作品；把起点改成 ${preview.start + preview.items.size} 可以继续往后看。")
        }
        Button(
            onClick = { vm.start() },
            enabled = !vm.running && vm.batch.pendingSelected > 0,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(if (vm.kind == MediaKind.VIDEO) Icons.Default.Movie else Icons.Default.GraphicEq, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("提取选中的 ${vm.batch.pendingSelected} 条", style = MaterialTheme.typography.titleSmall)
        }
        Footnote("逐条用手机直连解析并保存到媒体库；某条失败不影响其它条，失败的可以单独重试。")
    }
}

@Composable
private fun ItemRow(entry: BatchEntry, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = entry.selected,
            onCheckedChange = { onToggle() },
            enabled = enabled && entry.state != BatchState.DONE,
        )
        if (entry.item.coverUrl != null) {
            AsyncImage(
                model = entry.item.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(uiAccentContainer()),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                entry.item.title,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (entry.state == BatchState.DONE) uiMuted() else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOfNotNull(entry.item.publishedDay, formatDuration(entry.item.durationSec)).joinToString(" · "),
                    fontSize = 11.sp,
                    color = uiMuted(),
                )
                val stateText = when (entry.state) {
                    BatchState.DONE -> "已入库"
                    BatchState.RUNNING -> "提取中…"
                    BatchState.FAILED -> "失败"
                    BatchState.CANCELLED -> "未开始"
                    BatchState.SKIPPED -> "跳过"
                    BatchState.PENDING -> null
                }
                if (stateText != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stateText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = when (entry.state) {
                            BatchState.DONE -> uiSuccess()
                            BatchState.FAILED -> MaterialTheme.colorScheme.error
                            else -> uiMuted()
                        },
                    )
                }
            }
            if (entry.state == BatchState.FAILED && !entry.error.isNullOrBlank()) {
                Text(
                    entry.error!!,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BatchSection(vm: CreatorBatchViewModel) {
    if (vm.running) {
        SectionCard(padding = PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "正在提取 ${vm.batchIndex}/${vm.batchTotal}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                LinearProgressIndicator(
                    progress = {
                        if (vm.batchTotal <= 0) 0f
                        else (vm.batch.doneCount + vm.batch.failedCount).toFloat() / vm.batchTotal
                    },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                )
                Text(
                    vm.currentTitle.ifBlank { "准备中…" },
                    fontSize = 12.sp,
                    color = uiMuted(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "成功 ${vm.batch.doneCount} · 失败 ${vm.batch.failedCount}",
                        fontSize = 12.sp,
                        color = uiMuted(),
                        modifier = Modifier.weight(1f),
                    )
                    AreaButton(onClick = { vm.stop() }, tone = ActionTone.DESTRUCTIVE) { Text("停止") }
                }
                Footnote("点停止后不会再开始新的条目，正在下载的那条可能先完成。")
            }
        }
        return
    }

    val summary = vm.summary ?: return
    SectionCard(padding = PaddingValues(14.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (vm.batch.failedCount == 0) Icons.Default.CheckCircle else Icons.Default.Info,
                    null,
                    Modifier.size(17.dp),
                    tint = if (vm.batch.failedCount == 0) uiSuccess() else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(summary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            if (vm.batch.failedCount > 0) {
                Text(
                    "失败的条目仍留在上面的列表里，可以只重试它们。",
                    fontSize = 12.sp,
                    color = uiMuted(),
                )
                AreaButton(onClick = { vm.retryFailed() }, tone = ActionTone.CONFIRM) {
                    Icon(Icons.Default.CloudDownload, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重试失败的 ${vm.batch.failedCount} 条")
                }
            }
        }
    }
}

private fun formatCount(value: Long): String = when {
    value >= 100_000_000 -> "%.1f 亿".format(value / 100_000_000.0)
    value >= 10_000 -> "%.1f 万".format(value / 10_000.0)
    else -> value.toString()
}
