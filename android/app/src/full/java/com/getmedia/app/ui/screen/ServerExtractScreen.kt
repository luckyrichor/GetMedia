package com.getmedia.app.ui.screen

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.localextract.LinkScanner
import com.getmedia.app.data.repo.DiagnosticsStore
import com.getmedia.app.data.repo.ErrorText
import com.getmedia.app.data.repo.PendingNotice
import com.getmedia.app.data.repo.TrackRepository
import com.getmedia.app.data.serverextract.ServerExtractClient
import com.getmedia.app.data.serverextract.ServerMediaException
import com.getmedia.app.data.serverextract.serverExtractClient
import com.getmedia.app.ui.components.SectionCard
import com.getmedia.app.ui.components.RowDivider
import com.getmedia.app.ui.components.uiAccentContainer
import com.getmedia.app.ui.components.uiCardColor
import com.getmedia.app.ui.components.uiHairline
import com.getmedia.app.ui.components.uiMuted
import com.getmedia.app.ui.components.uiSuccess
import com.getmedia.app.ui.components.uiSuccessContainer
import com.getmedia.app.ui.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 队列里一条链接的界面状态。 */
enum class ServerTaskStatus { WAITING, RUNNING, SUCCESS, FAILED }

data class ServerTask(
    val url: String,
    val label: String,
    val status: ServerTaskStatus = ServerTaskStatus.WAITING,
    val title: String? = null,
    /** 失败文案（已中文化）；`null` 表示还没结果。 */
    val message: String? = null,
    /** 服务器原文，只在展开的失败卡片里作小字展示。 */
    val detail: String? = null,
)

/**
 * 把 [ServerExtractClient] 收成一层可替换的函数。
 *
 * 队列逻辑（串行、单条失败不影响后面、重试只重跑一条）因此可以脱机单测，
 * 不必真的连服务器，也不必碰 Room。
 */
fun interface ServerExtractor {
    suspend fun extract(
        input: String,
        kind: MediaKind,
        onProgress: (ServerExtractClient.Progress) -> Unit,
    ): TrackEntity
}

/**
 * 诊断记录的接缝。
 *
 * 生产实现写 [DiagnosticsStore]（和手机直提取共用同一份页脚记录）；单测用 [None]，
 * 这样队列逻辑不必碰 `android.os.SystemClock`，可以在普通 JVM 单测里跑。
 */
interface ServerDiagnostics {
    fun begin()
    fun record(message: String)
    fun finish(success: Boolean)

    companion object {
        fun live(version: String): ServerDiagnostics = object : ServerDiagnostics {
            private var startedAt = 0L
            override fun begin() {
                startedAt = android.os.SystemClock.elapsedRealtime()
                DiagnosticsStore.beginServer(version)
            }

            override fun record(message: String) = DiagnosticsStore.record(startedAt, message)
            override fun finish(success: Boolean) = DiagnosticsStore.finish(success)
        }

        /** 单测用：不写任何记录。 */
        val None: ServerDiagnostics = object : ServerDiagnostics {
            override fun begin() = Unit
            override fun record(message: String) = Unit
            override fun finish(success: Boolean) = Unit
        }
    }
}

/**
 * 服务器提取页的编排：**多条一条一条来**（设计 §8）。
 *
 * 进度用 [MutableStateFlow]：下载回调来自 IO 线程，走 Flow 赋值比从后台线程写 Compose 快照状态稳妥。
 */
class ServerExtractViewModel(
    private val extractor: ServerExtractor,
    private val diag: ServerDiagnostics = ServerDiagnostics.None,
) : ViewModel() {

    var input by mutableStateOf("")
    var kind by mutableStateOf(MediaKind.AUDIO)
    var busy by mutableStateOf(false)
        private set

    /** 当前这批链接；每次提交整批替换，重试只改其中一条。 */
    var tasks by mutableStateOf<List<ServerTask>>(emptyList())
        private set

    /** 输入框有内容但识别不出链接时的提示。 */
    var hint by mutableStateOf<String?>(null)
        private set
    var summary by mutableStateOf<String?>(null)
        private set

    /** 正在跑的那条的四段进度；`null` 表示当前没有下载。 */
    val progress = MutableStateFlow<ServerExtractClient.Progress?>(null)

    internal val notification = PendingNotice<String>()

    val links: List<LinkScanner.Found> get() = LinkScanner.scan(input)

    fun submit() {
        if (busy) return
        val found = LinkScanner.scan(input.trim())
        if (found.isEmpty()) {
            hint = "没有识别到链接，请粘贴作品链接或分享文本"
            return
        }
        hint = null
        summary = null
        progress.value = null
        tasks = found.map { ServerTask(it.url, it.label) }
        start(tasks.indices.toList())
    }

    /** 只重跑失败的那一条，不动其它已完成的结果。 */
    fun retry(index: Int) {
        if (busy || tasks.getOrNull(index)?.status != ServerTaskStatus.FAILED) return
        summary = null
        start(listOf(index))
    }

    fun clear() {
        if (busy) return
        input = ""
        hint = null
        summary = null
        progress.value = null
        tasks = emptyList()
    }

    fun pasteFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotBlank()) input = text
    }

    private fun start(indices: List<Int>) {
        viewModelScope.launch {
            busy = true
            diag.begin()
            diag.record("服务器提取：本轮 ${indices.size} 条")
            for (i in indices) {
                val task = tasks.getOrNull(i) ?: continue
                update(i) {
                    it.copy(status = ServerTaskStatus.RUNNING, message = null, detail = null, title = null)
                }
                setProgress(ServerExtractClient.Progress(ServerExtractClient.Stage.SUBMITTED))
                diag.record("---- ${task.label} ${task.url}")
                val entity = try {
                    extractor.extract(task.url, kind) { p -> setProgress(p) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val message = if (e is ServerMediaException) e.message else ErrorText.humanMessage(e)
                    update(i) {
                        it.copy(
                            status = ServerTaskStatus.FAILED,
                            message = message,
                            detail = (e as? ServerMediaException)?.detail?.takeIf { d -> d.isNotBlank() },
                        )
                    }
                    diag.record("提取失败：$message")
                    setProgress(null)
                    continue
                }
                update(i) { it.copy(status = ServerTaskStatus.SUCCESS, title = entity.title) }
                // 四段进度停在"已入库"：客户端也会上报 SAVED，这里兜一次，界面不会闪回上一段。
                setProgress(
                    ServerExtractClient.Progress(
                        ServerExtractClient.Stage.SAVED,
                        entity.audioBytes ?: 0,
                        entity.audioBytes,
                    ),
                )
                diag.record("已入库：${entity.title}")
            }
            val done = tasks.count { it.status == ServerTaskStatus.SUCCESS }
            val failed = tasks.count { it.status == ServerTaskStatus.FAILED }
            summary = when {
                tasks.size == 1 && done == 1 -> "已加入媒体库"
                failed == 0 -> "全部完成：成功 $done 条"
                done == 0 -> "全部失败：$failed 条，原因见下方"
                else -> "完成：成功 $done 条，失败 $failed 条"
            }
            notification.post(summary!!)
            diag.finish(failed == 0)
            busy = false
        }
    }

    private fun update(index: Int, block: (ServerTask) -> ServerTask) {
        tasks = tasks.mapIndexed { i, task -> if (i == index) block(task) else task }
    }

    /** 同一时刻只有一条在跑，所以一条 Flow 就够：它就是"当前这条"的进度。 */
    private fun setProgress(value: ServerExtractClient.Progress?) {
        progress.value = value
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val client = serverExtractClient(
                repository = TrackRepository(context),
                cacheDir = context.cacheDir,
            )
            val extractor = ServerExtractor { input, kind, onProgress ->
                client.extract(
                    input = input,
                    kind = kind,
                    accountKey = SessionStore.current.value?.loginName,
                    onProgress = onProgress,
                )
            }
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知版本"
            return ServerExtractViewModel(extractor, ServerDiagnostics.live(version)) as T
        }
    }
}

/**
 * 服务器提取面板（P0-1）。放在提取页的滚动容器里，所以**不要**用 LazyColumn：
 * 链接数量由用户粘贴决定，数量很小，普通 Column 足够，也不会和外层滚动打架。
 */
@Composable
fun ServerExtractPanel(snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val vm: ServerExtractViewModel = viewModel(factory = ServerExtractViewModel.Factory(context))
    val found = vm.links
    val progress by vm.progress.collectAsState()
    val runningIndex = vm.tasks.indexOfFirst { it.status == ServerTaskStatus.RUNNING }

    LaunchedEffect(Unit) {
        snapshotFlow { vm.notification.value }.collect { message ->
            if (message != null) {
                vm.notification.take()
                snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Short)
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(vm.kind == MediaKind.AUDIO, { vm.kind = MediaKind.AUDIO }, { Text("音频") }, enabled = !vm.busy)
            FilterChip(vm.kind == MediaKind.VIDEO, { vm.kind = MediaKind.VIDEO }, { Text("视频") }, enabled = !vm.busy)
        }
        Text(
            "链接交给服务器解析，文件仍落到手机本地库。手机直接解析不出来的链接走这里；服务器先落地一份临时副本，手机再下载，断流可续传。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = vm.input, onValueChange = { vm.input = it }, enabled = !vm.busy,
            placeholder = { Text("粘贴分享文本或链接，可一次粘贴多条", style = MaterialTheme.typography.bodyMedium) },
            minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = uiCardColor(),
                unfocusedContainerColor = uiAccentContainer(),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            AreaButton(onClick = { vm.pasteFromClipboard(context) }, enabled = !vm.busy, tone = ActionTone.SYNC) {
                Icon(Icons.Default.ContentPaste, null, Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("粘贴剪贴板")
            }
            AreaButton(onClick = { vm.clear() }, enabled = !vm.busy && vm.input.isNotBlank()) { Text("清空") }
        }
        if (found.size > 1) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(uiAccentContainer())
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Link, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(9.dp))
                Text(
                    "识别到 ${found.size} 个链接，会一条一条按顺序提取",
                    Modifier.weight(1f),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (vm.hint != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(vm.hint!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        Button(
            onClick = { vm.submit() },
            enabled = !vm.busy && vm.input.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    vm.busy && runningIndex >= 0 -> "正在提取 ${runningIndex + 1}/${vm.tasks.size}"
                    vm.busy -> "正在提取…"
                    found.size > 1 -> "服务器提取 ${found.size} 条"
                    else -> "服务器提取"
                },
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            "服务器只保留 1 天的临时副本，之后由它自己清理。提取中途可以离开这个页面，回来仍是同一个任务。",
            style = MaterialTheme.typography.bodySmall,
            color = uiMuted(),
            lineHeight = 18.sp,
        )

        // ---- 四段进度：只显示正在跑的那一条 ----
        if (progress != null) {
            ProgressCard(
                progress = progress!!,
                index = if (runningIndex >= 0) runningIndex else 0,
                total = vm.tasks.size,
            )
        }

        // ---- 队列：多条时看得到每一条的归宿 ----
        if (vm.tasks.isNotEmpty()) {
            SectionCard {
                vm.tasks.forEachIndexed { index, task ->
                    if (index > 0) RowDivider(startIndent = 14.dp)
                    ServerTaskRow(index, task, progress = progress.takeIf { task.status == ServerTaskStatus.RUNNING })
                }
            }
        }

        if (vm.summary != null) {
            val failed = vm.tasks.count { it.status == ServerTaskStatus.FAILED }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (failed == 0) uiSuccessContainer() else MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (failed == 0) Icons.Default.Check else Icons.Default.ErrorOutline,
                    null,
                    Modifier.size(17.dp),
                    tint = if (failed == 0) uiSuccess() else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    vm.summary!!,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (failed == 0) uiSuccess() else MaterialTheme.colorScheme.error,
                )
            }
        }

        // ---- 失败卡片：每条失败单独一张，能就地重试 ----
        val failures = vm.tasks.withIndex().filter { it.value.status == ServerTaskStatus.FAILED }
        if (failures.isNotEmpty()) {
            Text(
                "${failures.size} 条失败",
                Modifier.padding(start = 6.dp),
                fontSize = 12.sp,
                color = uiMuted(),
            )
            failures.forEach { (index, task) ->
                FailureCard(task = task, enabled = !vm.busy, onRetry = { vm.retry(index) })
            }
        }
    }
}

/** 四段进度：已提交 → 解析中 → 下载中 → 已入库，下载段按字节推进。 */
@Composable
private fun ProgressCard(progress: ServerExtractClient.Progress, index: Int, total: Int) {
    SectionCard(padding = PaddingValues(14.dp)) {
        Text(
            if (total > 1) "第 ${index + 1}/$total 条 · ${stageLabel(progress.stage)}" else stageLabel(progress.stage),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        StageTrack(progress.stage)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { stageFraction(progress) },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
        )
        if (progress.stage == ServerExtractClient.Stage.DOWNLOADING) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatSize(progress.downloaded)} / ${progress.total?.let(::formatSize) ?: "大小未知"}",
                fontSize = 11.sp,
                color = uiMuted(),
            )
        }
    }
}

@Composable
private fun StageTrack(stage: ServerExtractClient.Stage) {
    val steps = listOf(
        ServerExtractClient.Stage.SUBMITTED to "已提交",
        ServerExtractClient.Stage.RESOLVING to "解析中",
        ServerExtractClient.Stage.DOWNLOADING to "下载中",
        ServerExtractClient.Stage.SAVED to "已入库",
    )
    val current = steps.indexOfFirst { it.first == stage }.coerceAtLeast(0)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        steps.forEachIndexed { index, step ->
            val done = index < current
            val active = index == current
            val tint = if (done || active) MaterialTheme.colorScheme.primary else uiMuted().copy(alpha = 0.55f)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(22.dp).clip(CircleShape)
                        .background(if (done || active) MaterialTheme.colorScheme.primary else uiHairline()),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(
                            Icons.Default.Check, null, Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(
                            "${index + 1}", fontSize = 11.sp,
                            color = if (active) MaterialTheme.colorScheme.onPrimary else uiMuted(),
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    step.second,
                    fontSize = 10.sp,
                    color = tint,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun stageFraction(progress: ServerExtractClient.Progress): Float = when (progress.stage) {
    ServerExtractClient.Stage.SUBMITTED -> 0.08f
    ServerExtractClient.Stage.RESOLVING -> 0.34f
    ServerExtractClient.Stage.DOWNLOADING -> {
        val total = progress.total ?: 0L
        val ratio = if (total > 0) (progress.downloaded.toFloat() / total).coerceIn(0f, 1f) else 0.1f
        0.34f + 0.56f * ratio
    }
    ServerExtractClient.Stage.SAVED -> 1f
}

private fun stageLabel(stage: ServerExtractClient.Stage): String = when (stage) {
    ServerExtractClient.Stage.SUBMITTED -> "已提交，排队中"
    ServerExtractClient.Stage.RESOLVING -> "服务器正在解析"
    ServerExtractClient.Stage.DOWNLOADING -> "正在下载到手机"
    ServerExtractClient.Stage.SAVED -> "已加入媒体库"
}

@Composable
private fun ServerTaskRow(index: Int, task: ServerTask, progress: ServerExtractClient.Progress?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", Modifier.width(22.dp), fontSize = 12.sp, color = uiMuted())
        Column(Modifier.weight(1f)) {
            Text(
                task.title ?: task.label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                task.url,
                fontSize = 11.sp,
                color = uiMuted(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        val (text, color) = when (task.status) {
            ServerTaskStatus.WAITING -> "等待" to uiMuted()
            ServerTaskStatus.RUNNING -> (progress?.let { stageLabel(it.stage) } ?: "进行中") to MaterialTheme.colorScheme.primary
            ServerTaskStatus.SUCCESS -> "已入库" to uiSuccess()
            ServerTaskStatus.FAILED -> "失败" to MaterialTheme.colorScheme.error
        }
        Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FailureCard(task: ServerTask, enabled: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.06f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Default.ErrorOutline, null, Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                task.message ?: "提取失败",
                Modifier.weight(1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
                lineHeight = 19.sp,
            )
        }
        if (task.detail != null) {
            Text(
                task.detail,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.78f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            task.url,
            fontSize = 11.sp,
            color = uiMuted(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = onRetry,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Download, null, Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("重试这条", fontSize = 12.sp)
            }
        }
    }
}
