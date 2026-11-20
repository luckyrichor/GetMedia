package com.getmedia.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.library.LibrarySyncClient
import com.getmedia.app.data.library.LibrarySyncGate
import com.getmedia.app.data.library.LibrarySyncPrefs
import com.getmedia.app.data.library.LibrarySyncRunner
import com.getmedia.app.data.library.LibraryUploader
import com.getmedia.app.data.library.RestoreMatchIssue
import com.getmedia.app.data.library.needsRestoreMatch
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.ui.components.RowDivider
import com.getmedia.app.ui.components.SectionCard
import com.getmedia.app.ui.components.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LibrarySyncSettings() {
    val session by SessionStore.current.collectAsState()
    val account = session?.loginName ?: return
    if (!LibrarySyncGate.isOwner(session)) return
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { LibrarySyncPrefs(context) }
    var enabled by remember(account) { mutableStateOf(prefs.enabled(account)) }
    var count by remember(account) { mutableStateOf(0) }
    var hasRestored by remember(account) { mutableStateOf(false) }
    var confirmLegacy by remember(session?.token) { mutableStateOf(false) }
    var includeLegacy by remember(session?.token) { mutableStateOf(false) }
    val restoreStatus by LibrarySyncRunner.restoreStatus.collectAsState()
    LaunchedEffect(context, account) {
        val db = AppDatabase.get(context)
        val uploader = LibraryUploader(db, LibrarySyncClient(), account)
        db.trackDao().observeAll().collect { rows ->
            count = withContext(Dispatchers.IO) { rows.count(uploader::eligible) }
            hasRestored = rows.any { it.needsRestoreMatch() && (it.accountKey == null || it.accountKey == account) }
        }
    }

    LibrarySyncControls(
        enabled = enabled,
        count = count,
        allowed = LibrarySyncGate.maySync(session),
        onToggle = {
            LibrarySyncRunner.setEnabled(context, account, it)
            enabled = prefs.enabled(account)
        },
        onRequest = { LibrarySyncRunner.requestUpload(account, count) },
    )

    val status = restoreStatus?.takeIf { it.account == account && LibrarySyncGate.maySync(session) }
    if (hasRestored || status?.message != null) {
        RestoredControls(
            status = status,
            canCheck = hasRestored && LibrarySyncGate.maySync(session),
            onCheck = { LibrarySyncRunner.checkRestored(context, includeLegacy) },
            onConfirmAccount = { confirmLegacy = true },
            onRedownload = { LibrarySyncRunner.redownloadRestored(context, it, includeLegacy) },
        )
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
) {
    SectionTitle("恢复条目")
    SectionCard {
        val enabled = status?.working != true
        if (canCheck) TextButton(onClick = onCheck, enabled = enabled) { Text("核对云端匹配") }
        if (status?.working == true) Text("正在核对或重新下载…", Modifier.padding(horizontal = 14.dp))
        status?.message?.let { Text(it, Modifier.padding(14.dp)) }
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
