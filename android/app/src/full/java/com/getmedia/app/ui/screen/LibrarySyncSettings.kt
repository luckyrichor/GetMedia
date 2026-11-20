package com.getmedia.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
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
    LaunchedEffect(context, account) {
        val db = AppDatabase.get(context)
        val uploader = LibraryUploader(db, LibrarySyncClient(), account)
        db.trackDao().observeAll().collect { rows ->
            count = withContext(Dispatchers.IO) { rows.count(uploader::eligible) }
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
