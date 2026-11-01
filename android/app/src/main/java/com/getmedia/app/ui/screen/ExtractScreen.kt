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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
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
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.repo.ErrorText
import com.getmedia.app.data.repo.TrackRepository
import com.getmedia.app.data.repo.DiagnosticsStore
import com.getmedia.app.data.repo.PendingNotice
import com.getmedia.app.ui.formatDuration
import com.getmedia.app.ui.components.uiAccentContainer
import com.getmedia.app.data.localextract.LinkScanner
import com.getmedia.app.ui.components.uiCardColor
import com.getmedia.app.ui.components.uiHairline
import com.getmedia.app.ui.components.uiMuted
import com.getmedia.app.ui.components.uiSuccess
import com.getmedia.app.ui.components.uiSuccessContainer
import kotlinx.coroutines.launch

class ExtractViewModel(private val repo: TrackRepository, private val version: String) : ViewModel() {

    var input by mutableStateOf("")
    /** 要保存成音频还是视频；批量提取沿用同一个选择。 */
    var kind by mutableStateOf(MediaKind.AUDIO)
    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<TrackEntity?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    internal val notification = PendingNotice<String>()
    private var startedAt = 0L

    /** 当前输入里识别到的链接；只有 1 条时按单条提取处理。 */
    val links: List<LinkScanner.Found> get() = LinkScanner.scan(input)

    /** 批量进度：当前第几条 / 总条数，以及本轮每条的成败。 */
    var batchIndex by mutableStateOf(0)
        private set
    var batchTotal by mutableStateOf(0)
        private set
    var batchCurrent by mutableStateOf("")
        private set
    var batchDone by mutableStateOf<List<Pair<String, String?>>>(emptyList())
        private set

    /** 批量结束后的结果摘要，留在页面上，不用等一闪而过的提示条。 */
    var batchSummary by mutableStateOf<String?>(null)
        private set

    private fun record(message: String) {
        DiagnosticsStore.record(startedAt, message)
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        val found = LinkScanner.scan(text)
        if (found.size > 1) {
            submitBatch(found)
            return
        }
        viewModelScope.launch {
            busy = true
            error = null
            result = null
            batchIndex = 0
            batchTotal = 0
            batchDone = emptyList()
            batchSummary = null
            notification.take()
            startedAt = SystemClock.elapsedRealtime()
            DiagnosticsStore.beginLocal(version)
            repo.extract(text, kind, ::record)
                .onSuccess {
                    result = it
                    notification.post("${it.title} · ${formatDuration(it.durationSec)} · 已加入${if (it.mediaType == MediaKind.VIDEO.name) "视频" else "音频"}库")
                    DiagnosticsStore.finish(true)
                }
                .onFailure {
                    error = ErrorText.humanMessage(it)
                    notification.post(error!!)
                    record("提取失败：${it.javaClass.simpleName} · $error")
                    DiagnosticsStore.finish(false)
                }
            busy = false
        }
    }

    /**
     * 批量提取：逐条串行执行，单条失败不影响后面的条目。
     * 每条都单独记录诊断，失败原因保留在列表里给用户看。
     */
    private fun submitBatch(found: List<LinkScanner.Found>) {
        if (found.isEmpty()) return
        viewModelScope.launch {
            busy = true
            error = null
            result = null
            notification.take()
            batchTotal = found.size
            batchIndex = 0
            batchDone = emptyList()
            batchSummary = null
            startedAt = SystemClock.elapsedRealtime()
            DiagnosticsStore.beginLocal(version)
            record("批量提取：共 ${found.size} 个链接")
            var ok = 0
            val failures = mutableListOf<Pair<String, String?>>()
            found.forEachIndexed { i, link ->
                batchIndex = i + 1
                batchCurrent = "第 ${i + 1}/${found.size} 条 · ${link.label} ${link.key}"
                record("---- ${link.label} ${link.url}")
                val outcome = runCatching { repo.extract(link.url, kind, ::record) }
                val entity = outcome.getOrNull()?.getOrNull()
                if (entity != null) {
                    ok++
                } else {
                    val reason = outcome.getOrNull()?.exceptionOrNull()
                        ?: outcome.exceptionOrNull()
                    val message = ErrorText.humanMessage(reason ?: IllegalStateException("未知错误"))
                    failures.add(link.url to message)
                    record("提取失败：$message")
                }
            }
            batchDone = failures
            batchCurrent = ""
            val summary = if (failures.isEmpty()) "批量完成：$ok 条全部成功"
            else "批量完成：成功 $ok 条，失败 ${failures.size} 条"
            notification.post(summary)
            batchSummary = summary
            error = if (failures.isEmpty()) null else "$summary。失败的链接已保留在下方列表。"
            DiagnosticsStore.finish(failures.isEmpty())
            busy = false
        }
    }

    /**
     * 读剪贴板自动填入。
     *
     * 这是整个 App 最关键的体验点：用户刚从抖音复制完分享文案切过来，
     * 手动粘贴多一步就少一分用。
     */
    fun pasteFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotBlank() && looksLikeShareLink(text)) input = text
    }

    private fun looksLikeShareLink(text: String): Boolean =
        listOf("bilibili.com", "b23.tv", "douyin.com", "BV").any { text.contains(it) }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExtractViewModel(
                TrackRepository(context),
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知版本",
            ) as T
    }
}

@Composable
fun ExtractScreen(prefill: String? = null, onSharedConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val vm: ExtractViewModel = viewModel(factory = ExtractViewModel.Factory(context))
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(prefill) {
        if (!prefill.isNullOrBlank()) {
            vm.input = prefill
            onSharedConsumed()
            // 分享进来的只有一条就立刻提取；多条先列清单让用户确认，避免一次触发一串下载。
            if (vm.links.size <= 1) vm.submit()
        } else if (vm.input.isBlank()) vm.pasteFromClipboard(context)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { vm.notification.value }.collect { message ->
            if (message != null) {
                vm.notification.take()
                snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Short)
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (vm.kind == MediaKind.VIDEO) "提取视频" else "提取音频", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(vm.kind == MediaKind.AUDIO, { vm.kind = MediaKind.AUDIO }, { Text("音频") }, enabled = !vm.busy)
                FilterChip(vm.kind == MediaKind.VIDEO, { vm.kind = MediaKind.VIDEO }, { Text("视频") }, enabled = !vm.busy)
            }
            Text("粘贴视频链接或完整分享文本，自动识别 B站、抖音。多个链接可以一起粘贴。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                AreaButton(onClick = { vm.input = "" }, enabled = !vm.busy && vm.input.isNotBlank()) { Text("清空") }
            }
            // 识别结果：一条以上就展示清单，用户能确认到底抓到了哪些链接。
            val found = vm.links
            if (found.size > 1) {
                val bili = found.count { it.platform == "bilibili" }
                val douyin = found.size - bili
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(uiAccentContainer())
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Link, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "识别到 ${found.size} 个链接" +
                            listOfNotNull(
                                if (bili > 0) "B站 $bili" else null,
                                if (douyin > 0) "抖音 $douyin" else null,
                            ).joinToString(" · ", prefix = "（", postfix = "）"),
                        Modifier.weight(1f),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(uiCardColor())) {
                    found.forEachIndexed { index, link ->
                        if (index > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(uiHairline()))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                Modifier.width(22.dp),
                                fontSize = 12.sp,
                                color = uiMuted(),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(link.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    link.url,
                                    fontSize = 11.sp,
                                    color = uiMuted(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Button(
                onClick = { vm.submit() },
                enabled = !vm.busy && vm.input.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(if (found.size > 1) Icons.Default.Download else Icons.Default.GraphicEq, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        vm.busy && vm.batchTotal > 0 -> "正在批量提取 ${vm.batchIndex}/${vm.batchTotal}"
                        vm.busy -> "正在提取…"
                        found.size > 1 -> "批量提取 ${found.size} 条"
                        else -> "提取音频"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                if (found.size > 1) "批量提取会逐条用手机直连解析；某条失败不影响其它条。"
                else "用手机直连解析，不经过任何服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = uiMuted(),
                lineHeight = 18.sp,
            )
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                Text(
                    when {
                        vm.batchTotal > 0 -> vm.batchCurrent.ifBlank { "准备中…" }
                        else -> "手机正在解析并下载音频"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = uiMuted(),
                )
            }
            if (vm.batchSummary != null && !vm.busy) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(uiSuccessContainer())
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (vm.batchDone.isEmpty()) Icons.Default.CheckCircle else Icons.Default.Info,
                        null,
                        Modifier.size(17.dp),
                        tint = if (vm.batchDone.isEmpty()) uiSuccess() else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        vm.batchSummary!!,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (vm.batchDone.isEmpty()) uiSuccess() else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (vm.batchDone.isNotEmpty() && !vm.busy) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.07f))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("${vm.batchDone.size} 条失败", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    vm.batchDone.forEach { (url, reason) ->
                        Text(
                            "$url · ${reason ?: "未知原因"}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            ExtractionDiagnostics()
        }
    }
}

