package com.getmedia.app.ui.screen

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getmedia.app.data.export.ExportNaming
import com.getmedia.app.data.export.ExportPlan
import com.getmedia.app.data.export.ExportController
import com.getmedia.app.data.export.ExportPrefs
import com.getmedia.app.data.export.ExportSession
import com.getmedia.app.data.export.ExportTrack
import com.getmedia.app.data.export.SafExportTarget
import com.getmedia.app.data.export.SafGrants
import com.getmedia.app.ui.components.uiAccentContainer
import com.getmedia.app.ui.components.uiMuted
import com.getmedia.app.ui.formatSize
import com.getmedia.app.ui.ownerGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导出确认页（节点 B）+ 进度与摘要（节点 C）。
 *
 * 执行体是 [ExportService]：本文件只收集参数、把目录交给 [ExportController]，然后观察它推过来的状态。
 * 因此页面被关掉不影响导出——进度还在通知里，重进这个页面能接回同一份会话。
 *
 * 三条硬约束都落在这个文件里：
 * 1. 目录必须持久化授权成功才开始（一次性授权进不了后台服务）；
 * 2. 空间**查不到**时只提示"无法确认剩余空间"，不拦；只有算出"明确不足"才禁用开始；
 * 3. 云端尚未下载与本机文件缺失分开提示，云端提示由完整版提供。
 */
@Composable
fun ExportSheet(
    tracks: List<ExportTrack>,
    onDismiss: () -> Unit,
    /** 仅测试用：预置一个"已授权、已探测"的目录，跳过 SAF 交互，用来拍 320 dp 窄屏的版式。 */
    preview: ExportPreview? = null,
) {
    val context = LocalContext.current

    val rememberedTree = remember { preview?.tree ?: ExportPrefs.lastTree(context) }
    var tree by remember {
        mutableStateOf(preview?.tree ?: rememberedTree?.takeIf { SafGrants.isPersisted(context, it) })
    }
    var treeLost by remember { mutableStateOf(preview == null && rememberedTree != null && tree == null) }
    var folder by remember { mutableStateOf(ExportPrefs.lastFolder(context) ?: ExportPrefs.defaultFolder()) }
    var byType by remember { mutableStateOf(ExportPrefs.byType(context)) }
    var byUploader by remember { mutableStateOf(ExportPrefs.byUploader(context)) }
    var withUploader by remember { mutableStateOf(ExportPrefs.withUploader(context)) }
    var withDate by remember { mutableStateOf(ExportPrefs.withDate(context)) }
    var advanced by remember { mutableStateOf(false) }
    var useExisting by remember { mutableStateOf(false) }
    var skipIds by remember { mutableStateOf(emptySet<Long>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val folderFocus = remember { FocusRequester() }

    // 会话与状态住在 ExportController 里：重进这个页面能接回正在跑的导出
    val runState = ExportController.state

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (SafGrants.persist(context, uri)) {
            tree = uri
            treeLost = false
            useExisting = false
            notice = null
        } else {
            notice = "该位置不支持长期授权，请换一个目录"
        }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // 拒绝不影响导出：前台服务的进度只是看不见，功能照跑（节点 C）
    }

    // 选择集快照在进入本页时冻结；之后的库变化不影响 items
    val usable = remember(tracks, skipIds) { tracks.filter { it.id !in skipIds } }
    val sized = remember(usable) {
        usable.map { track ->
            if ((track.audioBytes ?: 0L) > 0L) track
            else track.copy(audioBytes = track.localPath?.let { File(it).length() }?.takeIf { it > 0L })
        }
    }
    val folderName = folder.trim().ifBlank { null }
    val items = remember(sized, folderName, byType, byUploader, withUploader, withDate) {
        ExportPlan.items(sized, folderName, byType, byUploader, withUploader, withDate)
    }
    val counts = remember(sized) { ExportPlan.classify(sized) }
    val required = remember(sized) { ExportPlan.requiredBytes(sized) }
    val (waitingForDownload, missingLocalFiles) = remember(usable) {
        usable.filter { track -> track.localPath.isNullOrBlank() || !File(track.localPath).isFile }
            .partition { it.awaitingDownload }
    }
    val sanitizedFolder = folderName?.let {
        ExportNaming.sanitize(it, ExportNaming.MAX_FOLDER_CHARS, ExportNaming.MAX_FOLDER_BYTES)
    }

    var rootNames by remember { mutableStateOf(preview?.rootNames ?: emptySet()) }
    var available by remember { mutableStateOf(preview?.available) }
    var leftovers by remember { mutableStateOf(preview?.leftovers ?: 0) }
    val dirPaths = remember(items) { items.map { it.dirs }.distinct() }

    LaunchedEffect(tree, dirPaths) {
        // 预览模式已经喂过探测结果，不再碰真实 provider
        if (preview != null) return@LaunchedEffect
        val uri = tree ?: return@LaunchedEffect
        available = null
        val probe = withContext(Dispatchers.IO) {
            runCatching {
                val target = SafExportTarget(context.contentResolver, uri)
                Probe(
                    names = target.childNames(target.ensureDir(emptyList())),
                    available = target.availableBytes(),
                    leftovers = target.countLeftovers(dirPaths),
                )
            }.getOrNull()
        }
        if (probe != null) {
            rootNames = probe.names
            available = probe.available
            leftovers = probe.leftovers
        }
    }

    // 空间三态：ENOUGH 放行、INSUFFICIENT 禁用开始、UNKNOWN（查不到）只提示不拦
    val verdict = available?.let { ExportPlan.spaceCheck(required, it) }
    val conflict = sanitizedFolder != null && rootNames.any { it.equals(sanitizedFolder, ignoreCase = true) }
    val canStart = tree != null && items.isNotEmpty() && verdict != ExportPlan.SpaceVerdict.INSUFFICIENT

    fun start() {
        val uri = tree ?: return
        if (preview == null && !SafGrants.isPersisted(context, uri)) {
            tree = null
            treeLost = true
            notice = "目录授权已失效，请重新选择"
            return
        }
        // 通知权限只在第一次点开始时问一次；拒绝不改流程（服务照跑，只是通知看不见）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ExportPrefs.notifyAsked(context)) {
            ExportPrefs.markNotifyAsked(context)
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ExportPrefs.remember(context, uri, folder.trim(), byType, byUploader, withUploader, withDate)
        if (!ExportController.start(context, items, uri)) {
            notice = "已有导出在进行中"
        }
    }

    fun retryFailed() {
        if (!ExportController.retry(context)) notice = "没有可重试的项"
    }

    val running = runState is ExportSession.State.Running
    val settled = runState is ExportSession.State.Finished || runState is ExportSession.State.Interrupted

    MusicSheet(
        title = when (runState) {
            is ExportSession.State.Running -> "正在导出"
            is ExportSession.State.Finished -> "导出完成"
            is ExportSession.State.Interrupted -> "导出被中断"
            else -> "导出"
        },
        subtitle = (runState as? ExportSession.State.Running)?.let { st ->
            buildString {
                append("${st.done}/${st.total}")
                if (st.failed > 0) append(" · 失败 ${st.failed}")
            }
        },
        // 关掉浮层不打断导出：服务和通知还在跑，重进这个页面能接回同一份会话
        onDismiss = onDismiss,
        confirmText = when {
            running -> "取消导出"
            settled -> "完成"
            else -> "开始导出"
        },
        confirmEnabled = when {
            running || settled -> true
            else -> canStart
        },
        onConfirm = {
            when {
                running -> ExportController.cancel()
                settled -> {
                    ExportController.clear()
                    onDismiss()
                }
                else -> start()
            }
        },
    ) {
        when (val st = runState) {
            is ExportSession.State.Running -> RunningBody(st)
            is ExportSession.State.Finished -> SummaryBody(
                results = st.results,
                interrupted = null,
                retryCount = ExportController.session?.retryIds?.size ?: 0,
                onRetry = ::retryFailed,
            )
            is ExportSession.State.Interrupted -> SummaryBody(
                results = st.results,
                interrupted = st,
                retryCount = ExportController.session?.retryIds?.size ?: 0,
                onRetry = ::retryFailed,
            )
            else -> {
                // ── 确认阶段 ──────────────────────────────────────────────
                Text(
                    buildList {
                        if (counts.audio > 0) add("音频 ${counts.audio} 项")
                        if (counts.video > 0) add("视频 ${counts.video} 项")
                        add("共 ${counts.total} 项" + if (required > 0) "，约 ${formatSize(required)}" else "")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )

                Spacer(Modifier.height(14.dp))
                Text("保存位置", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = uiAccentContainer(),
                    modifier = Modifier.fillMaxWidth().clickable { picker.launch(tree) },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Folder, null, Modifier.size(18.dp), tint = uiMuted())
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tree?.let { SafGrants.label(it) } ?: "未选择",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (tree == null) "选择" else "更换",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    if (treeLost) "原目录的授权已失效，请重新选择"
                    else "系统不允许直接选「Download」根目录，选它下面的子文件夹即可",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (treeLost) MaterialTheme.colorScheme.error else uiMuted(),
                    modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it; useExisting = false },
                    label = { Text("文件夹名（可留空）", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = uiAccentContainer(),
                        unfocusedContainerColor = uiAccentContainer(),
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(folderFocus),
                )

                if (conflict && !useExisting) {
                    Spacer(Modifier.height(8.dp))
                    WarnRow("该目录下已有同名文件夹") {
                        AreaButton(onClick = { useExisting = true }) { Text("使用已有") }
                        AreaButton(onClick = { folderFocus.requestFocus() }) { Text("改名") }
                    }
                }

                if (leftovers > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "上次导出留下 $leftovers 个临时文件（~getmedia-…part），可在目标目录里手动删除；本应用不会替你删",
                        style = MaterialTheme.typography.bodySmall,
                        color = uiMuted(),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { advanced = !advanced }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (if (advanced) "▾ " else "▸ ") + "高级",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (advanced) {
                    CheckRow("按类型分子文件夹（音频/、视频/）", byType) { byType = it }
                    CheckRow("按博主分子文件夹", byUploader) { byUploader = it }
                    CheckRow("文件名带博主名", withUploader) { withUploader = it }
                    CheckRow("文件名带入库日期", withDate) { withDate = it }
                }

                if (waitingForDownload.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    ownerGate.ExportMissingNotice(waitingForDownload.size) {
                        skipIds = skipIds + waitingForDownload.map { it.id }
                    }
                }
                if (missingLocalFiles.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    ExportUnavailableNotice(missingLocalFiles.size, ExportPlan.REASON_MISSING_LOCAL) {
                        skipIds = skipIds + missingLocalFiles.map { it.id }
                    }
                }

                when (verdict) {
                    ExportPlan.SpaceVerdict.INSUFFICIENT -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "目标可用空间不足（需 ${formatSize(required)}，可用 ${formatSize(available ?: 0L)}）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    ExportPlan.SpaceVerdict.UNKNOWN -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "无法确认剩余空间，导出时若空间不足会自动中止",
                            style = MaterialTheme.typography.bodySmall,
                            color = uiMuted(),
                        )
                    }
                    else -> Unit
                }

                if (items.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("没有可导出的条目", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                notice?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private data class Probe(val names: Set<String>, val available: Long, val leftovers: Int)

/**
 * 仅测试用：确认页的预置状态。
 *
 * JVM 里搭不出真实的 SAF provider（也没有持久化授权），但 320 dp 窄屏 + 200% 字体这种版式问题
 * 必须能自动化。传入它以后，页面不再探测真实目录，也不读本机偏好。
 */
data class ExportPreview(
    val tree: Uri,
    /** 目标可用字节；null 表示 provider 查不到（"无法确认剩余空间"那一态）。 */
    val available: Long? = null,
    val rootNames: Set<String> = emptySet(),
    val leftovers: Int = 0,
)

@Composable
private fun RunningBody(st: ExportSession.State.Running) {
    LinearProgressIndicator(
        progress = { if (st.total <= 0) 0f else st.done.toFloat() / st.total },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    Text(
        "正在写入：${st.current.ifBlank { "准备中" }}",
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    if (st.failed > 0) {
        Spacer(Modifier.height(6.dp))
        Text("失败 ${st.failed} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SummaryBody(
    results: List<ExportSession.Result>,
    interrupted: ExportSession.State.Interrupted?,
    retryCount: Int,
    onRetry: () -> Unit,
) {
    val done = results.count { it.status == ExportSession.Status.DONE }
    val failed = results.filter { it.status == ExportSession.Status.FAILED }
    val skipped = results.count { it.status == ExportSession.Status.SKIPPED }
    val rewritten = results.count { it.rewritten }

    Text(
        buildString {
            append("成功 $done · 失败 ${failed.size}")
            if (skipped > 0) append(" · 跳过 $skipped")
        },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    interrupted?.let {
        Spacer(Modifier.height(6.dp))
        Text(it.reason.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (it.notRun > 0) {
            Text("还有 ${it.notRun} 项未执行", style = MaterialTheme.typography.bodySmall, color = uiMuted())
        }
    }
    if (rewritten > 0) {
        Spacer(Modifier.height(6.dp))
        Text(
            "$rewritten 项目标不支持改名，已按最终文件名直接写入",
            style = MaterialTheme.typography.bodySmall,
            color = uiMuted(),
        )
    }
    if (failed.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("失败", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        failed.take(5).forEach { result ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    result.title,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(result.reason.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (failed.size > 5) {
            Text("等 ${failed.size} 项", style = MaterialTheme.typography.bodySmall, color = uiMuted())
        }
    }
    if (retryCount > 0) {
        Spacer(Modifier.height(12.dp))
        AreaButton(onClick = onRetry, tone = ActionTone.CONFIRM) { Text("重试未完成项（$retryCount）") }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun WarnRow(text: String, actions: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        actions()
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
