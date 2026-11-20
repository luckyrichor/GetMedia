package com.getmedia.app.data.library

import com.getmedia.app.data.cloud.CloudRemovedAsk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * "云端已移除 N 项"的批量确认，一次只摆一个。
 *
 * 用户点关闭只是"稍后处理"：本进程内不再重复弹，墓碑记录仍在；下次启动/换账号会重新问。
 * 用户选"保留本机文件"后由调用方清空。
 */
internal class LibraryRemovedPrompt {
    private val pending = MutableStateFlow<CloudRemovedAsk?>(null)
    private val dismissed = mutableSetOf<String>()

    val state = pending.asStateFlow()

    @Synchronized
    fun request(account: String, titles: List<String>, count: Int) {
        if (count <= 0 || account in dismissed) return
        pending.value = CloudRemovedAsk(account, count, titles.take(MAX_TITLES))
    }

    fun clear() = pending.update { null }

    /** 换账号/退出：弹窗与"稍后处理"的记忆都清掉。 */
    @Synchronized
    fun reset() {
        pending.value = null
        dismissed.clear()
    }

    @Synchronized
    fun clearFor(account: String) {
        pending.update { it?.takeIf { ask -> ask.account != account } }
    }

    @Synchronized
    fun dismiss(ask: CloudRemovedAsk) {
        if (pending.compareAndSet(ask, null)) dismissed += ask.account
    }

    private companion object {
        const val MAX_TITLES = 20
    }
}
