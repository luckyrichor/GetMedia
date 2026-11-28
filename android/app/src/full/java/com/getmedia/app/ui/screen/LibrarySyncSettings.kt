package com.getmedia.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.cloud.cloudSync
import com.getmedia.app.data.library.LibraryMark
import com.getmedia.app.data.library.LibrarySyncClient
import com.getmedia.app.data.library.LibrarySyncGate
import com.getmedia.app.data.library.LibrarySyncPrefs
import com.getmedia.app.data.library.LibrarySyncRunner
import com.getmedia.app.data.library.LibraryUploader
import com.getmedia.app.data.library.RestoreMatchIssue
import com.getmedia.app.data.library.needsRestoreMatch
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.ArchiveState
import com.getmedia.app.data.repo.ErrorText
import com.getmedia.app.data.repo.TrackRepository
import com.getmedia.app.ui.components.RowDivider
import com.getmedia.app.ui.components.SectionCard
import com.getmedia.app.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LibrarySyncSettings() {
    val session by SessionStore.current.collectAsState()
    val account = session?.loginName ?: return
    if (!LibrarySyncGate.isOwner(session)) {
        // 完整版到期（等级还是 full、只是过了有效期）：入口留在原地只给一句说明。
        // 直接整段消失会让人以为"功能没了"，而实际只是该续期了。
        if (LibrarySyncGate.expired(session)) {
            SectionTitle("曲库同步")
            SectionCard {
                Text(
                    "已到期，续期后可继续同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = com.getmedia.app.ui.components.uiMuted(),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        return
    }
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { LibrarySyncPrefs(context) }
    var enabled by remember(account) { mutableStateOf(prefs.enabled(account)) }
    var count by remember(account) { mutableStateOf(0) }
    var drafts by remember(account) { mutableStateOf(0) }
    var queued by remember(account) { mutableStateOf(0) }
    var waiting by remember(account) { mutableStateOf(0) }
    var summary by remember(account) { mutableStateOf<String?>(null) }
    var hasRestored by remember(account) { mutableStateOf(false) }
    var archiveIssues by remember(account) { mutableStateOf<List<ArchiveIssue>>(emptyList()) }
    var confirmLegacy by remember(session?.token) { mutableStateOf(false) }
    var includeLegacy by remember(session?.token) { mutableStateOf(false) }
    val restoreStatus by LibrarySyncRunner.restoreStatus.collectAsState()
    LaunchedEffect(context, account) {
        val db = AppDatabase.get(context)
        val uploader = LibraryUploader(db, LibrarySyncClient(), account)
        db.trackDao().observeAll().collect { rows ->
            count = withContext(Dispatchers.IO) { rows.count(uploader::eligible) }
            drafts = rows.count { it.accountKey == account && it.pendingTitle != null }
            hasRestored = rows.any { it.needsRestoreMatch() && (it.accountKey == null || it.accountKey == account) }
            // 备份失败 / 云端备份被清理的条目：原因以前只写进 `tracks.archive_note`，界面上
            // 一个字都没有，用户看到的是"同步好了"却不知道换机取不回这个文件。
            archiveIssues = rows.asSequence()
                .filter { it.accountKey == account }
                .mapNotNull { row ->
                    val state = ArchiveState.of(row.archiveState)
                    if (state != ArchiveState.UNABLE && state != ArchiveState.EVICTED) return@mapNotNull null
                    ArchiveIssue(
                        title = row.localTitleOverride ?: row.title,
                        note = row.archiveNote?.takeIf { it.isNotBlank() } ?: when (state) {
                            ArchiveState.UNABLE -> "没能备份到云端，重试也没用"
                            ArchiveState.EVICTED -> "云端那份已被清理，手机上的文件还在"
                            else -> ""
                        },
                    )
                }
                .toList()
        }
    }
    // 排队中的云端删除：开关关着时用户最需要看到它，所以单独观察、不依赖同步轮次
    LaunchedEffect(context, account) {
        AppDatabase.get(context).libraryAuxDao().observeRemovals(account).collect { list ->
            queued = list.count { it.state != LibraryMark.REMOVAL_WAITING }
            waiting = list.count { it.state == LibraryMark.REMOVAL_WAITING }
        }
    }
    LaunchedEffect(context, account) {
        AppDatabase.get(context).libraryAuxDao().observeCursor(account).collect { summary = it?.note }
    }

    LibrarySyncControls(
        enabled = enabled,
        count = count,
        allowed = LibrarySyncGate.maySync(session),
        queued = queued,
        waiting = waiting,
        drafts = drafts,
        summary = summary,
        onToggle = {
            LibrarySyncRunner.setEnabled(context, account, it)
            enabled = prefs.enabled(account)
        },
        onRequest = { LibrarySyncRunner.requestUpload(account, count) },
    )
    if (archiveIssues.isNotEmpty()) ArchiveIssueSection(archiveIssues)

    val status = restoreStatus?.takeIf { it.account == account && LibrarySyncGate.maySync(session) }
    val scope = rememberCoroutineScope()
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var shellToRemove by remember { mutableStateOf<Long?>(null) }
    fun downloadOne(id: Long) {
        actionMessage = null
        scope.launch {
            val row = TrackRepository(context).byId(id)
            actionMessage = if (row == null) {
                "这一条已被移除"
            } else {
                runCatching { cloudSync.download(context, row) }
                    .fold({ "已下载到原条目" }, { "没取到：${ErrorText.humanMessage(it)}" })
            }
        }
    }
    if (hasRestored || status?.message != null) {
        RestoredControls(
            status = status,
            canCheck = hasRestored && LibrarySyncGate.maySync(session),
            onCheck = { LibrarySyncRunner.checkRestored(context, includeLegacy) },
            onConfirmAccount = { confirmLegacy = true },
            // 阶段 6：这里也走统一下载入口。以前是 redownloadRestored，它会先拉一遍云端清单
            // 做预检——下载前不该有这种隐式网络动作，身份核对在回填时照样做。
            onRedownload = ::downloadOne,
            onRemoveShell = { shellToRemove = it },
            actionMessage = actionMessage,
        )
        shellToRemove?.let { id ->
            AlertDialog(
                onDismissRequest = { shellToRemove = null },
                title = { Text("移除这条？") },
                text = {
                    Text(
                        "云端有这项的删除记录，这台手机也没有它的文件。移除只影响这台手机，" +
                            "云端记录不动，也不会删任何文件。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        shellToRemove = null
                        scope.launch {
                            actionMessage = runCatching { TrackRepository(context).removeShell(id) }
                                .fold({ "已移除这条" }, { "移除失败：${it.message ?: "请稍后再试"}" })
                        }
                    }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { shellToRemove = null }) { Text("取消") } },
            )
        }
    }
    if (confirmLegacy && LibrarySyncGate.maySync(session)) {
        AlertDialog(
            onDismissRequest = { confirmLegacy = false },
            title = { Text("用当前账号核对旧备份恢复项？") },
            text = { Text("这些条目没有账号记录。确认后会用当前账号的云端曲库寻找匹配；唯一匹配的条目将关联当前账号。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLegacy = false
                    includeLegacy = true
                    LibrarySyncRunner.checkRestored(context, includeLegacy = true)
                }) { Text("用当前账号核对") }
            },
            dismissButton = { TextButton(onClick = { confirmLegacy = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun RestoredControls(
    status: LibrarySyncRunner.RestoreStatus?,
    canCheck: Boolean,
    onCheck: () -> Unit,
    onConfirmAccount: () -> Unit,
    onRedownload: (Long) -> Unit,
    onRemoveShell: ((Long) -> Unit)? = null,
    actionMessage: String? = null,
) {
    SectionTitle("恢复条目")
    SectionCard {
        val enabled = status?.working != true
        if (canCheck) TextButton(onClick = onCheck, enabled = enabled) { Text("核对云端匹配") }
        if (status?.working == true) Text("正在核对或重新下载…", Modifier.padding(horizontal = 14.dp))
        status?.message?.let { Text(it, Modifier.padding(14.dp)) }
        actionMessage?.let { Text(it, Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) }
        status?.notices?.forEach { notice ->
            RowDivider(startIndent = 14.dp)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(notice.title)
                Text(notice.issue.message)
                when (notice.issue) {
                    RestoreMatchIssue.LEGACY_ACCOUNT ->
                        TextButton(onClick = onConfirmAccount, enabled = enabled) { Text("确认使用当前账号") }
                    RestoreMatchIssue.UNMATCHED ->
                        TextButton(onClick = { onRedownload(notice.id) }, enabled = enabled) { Text("重新下载") }
                    // 阶段 6：云端有删除记录的恢复空壳，只给"移除这条"——它没有文件可取，
                    // 也不该出现在"重新下载"那里。确认与副作用由调用方负责。
                    RestoreMatchIssue.TOMBSTONE -> if (onRemoveShell != null) {
                        TextButton(onClick = { onRemoveShell(notice.id) }, enabled = enabled) {
                            Text("移除这条", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
internal fun LibrarySyncControls(
    enabled: Boolean,
    count: Int,
    allowed: Boolean = true,
    queued: Int = 0,
    waiting: Int = 0,
    drafts: Int = 0,
    summary: String? = null,
    onToggle: (Boolean) -> Unit,
    onRequest: () -> Unit,
) {
    SectionTitle("曲库同步")
    SectionCard {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("自动同步")
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        // 待处理项常显：开关关着时最需要看见它，否则用户会以为"删了就是删了"
        if (queued > 0) SyncStatusLine("${queued} 项云端删除排队中")
        if (waiting > 0) SyncStatusLine("${waiting} 项云端删除未完成，需处理")
        if (drafts > 0) SyncStatusLine("${drafts} 项改名待推送")
        if (!enabled) SyncStatusLine("同步已关闭，开启后继续")
        summary?.takeIf { it.isNotBlank() }?.let { SyncStatusLine(it) }
        RowDivider(startIndent = 14.dp)
        TextButton(
            onClick = onRequest,
            enabled = allowed && count > 0,
            modifier = Modifier.padding(start = 4.dp),
        ) {
            Text("上传本机新增（${count} 条）")
        }
    }
}

@Composable
private fun SyncStatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = com.getmedia.app.ui.components.uiMuted(),
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
    )
}

/** 「本机文件备份」区块的一行：哪个条目、为什么没备份上。 */
internal data class ArchiveIssue(val title: String, val note: String)

/**
 * 备份失败的清单。
 *
 * 为什么要有这一块：`platform = local` 的条目没有原始链接，云端归档是换机后唯一的取回途径。
 * 备份失败时以前只往 `tracks.archive_note` 写一句话、往同步摘要里加一句"可到条目详情看原因"，
 * 而条目详情根本没有这一项——用户看到的是"同步过"，实际换手机就取不回来了。
 *
 * 只列 `UNABLE` / `EVICTED` 两种终态：`PENDING` 还在自己重试，`DONE` 不用管，
 * 把它们也列出来只会让人以为出了问题。
 */
@Composable
internal fun ArchiveIssueSection(issues: List<ArchiveIssue>) {
    SectionTitle("本机文件备份")
    SectionCard {
        issues.forEachIndexed { index, issue ->
            if (index > 0) RowDivider(startIndent = 14.dp)
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(issue.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    issue.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = com.getmedia.app.ui.components.uiMuted(),
                )
            }
        }
        RowDivider(startIndent = 14.dp)
        Text(
            "这些文件目前只在当前手机上，换机或重装后取不回来。",
            style = MaterialTheme.typography.bodySmall,
            color = com.getmedia.app.ui.components.uiMuted(),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
