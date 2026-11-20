package com.getmedia.app.data.library

import com.getmedia.app.data.cloud.CloudUploadAsk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class LibraryUploadPrompt {
    private val pending = MutableStateFlow<CloudUploadAsk?>(null)
    private val dismissedAccounts = mutableSetOf<String>()
    val state = pending.asStateFlow()

    @Synchronized
    fun request(account: String, count: Int, manual: Boolean) {
        if (count <= 0 || (!manual && account in dismissedAccounts)) return
        pending.update {
            if (!manual && it?.manual == true && it.account == account) it
            else CloudUploadAsk(account, count, manual)
        }
    }

    fun clearAutomatic() = pending.update { it?.takeIf { ask -> ask.manual } }

    fun clear() { pending.value = null }

    @Synchronized
    fun dismiss(ask: CloudUploadAsk) {
        if (pending.compareAndSet(ask, null)) dismissedAccounts.add(ask.account)
    }

    fun decide(ask: CloudUploadAsk, prefs: LibrarySyncPrefs, decision: SyncUploadDecision): Boolean {
        if (!pending.compareAndSet(ask, null)) return false
        prefs.setDecision(ask.account, decision)
        return true
    }
}
