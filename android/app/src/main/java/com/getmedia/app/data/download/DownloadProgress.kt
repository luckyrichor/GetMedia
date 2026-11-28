package com.getmedia.app.data.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 远端文件的下载进度（界面唯一的进度来源）。
 *
 * 为什么要有它：一次「下载到本机」可能传几百 MB、要几分钟。这期间界面原来只会在结束时
 * 冒一句"已下载到本机"，中途既没有进度也没有"失败了"的信号——用户点完按钮，列表还标着
 * 「云端未下载」，只能反复点。这里把 [ServerDownload] 已经报上来的字节数变成一条可见的
 * 进度，界面订阅 [state] 画进度条即可，不必各自去猜。
 *
 * 只记"当前这一条"：下载是串行的（`CloudSyncHook.download` 里有锁），批量补下载也是一条
 * 接一条，所以同一时刻只可能有一个活跃项；换成列表只会让界面更难读。
 *
 * 三条纪律：
 * - **不持有 Context**：这是纯数据，放进程里就够了，Activity 重建不影响它；
 * - **不猜总长度**：服务器没给 `Content-Length` / `Content-Range` 时 [Active.total] 为 null，
 *   界面画不确定进度条——编一个百分比反而更让人糊涂；
 * - **结束时一定清空**：调用方在 `finally` 里 [finish]，否则界面会永远挂着一条 100% 的条。
 */
object DownloadProgress {

    /**
     * 正在下载的那一条。
     *
     * [done] / [total] 是字节数。`total` 可能比 `done` 小（服务器声明有误）或为 null，
     * 所以 [fraction] 自己钳在 0..1，界面不需要再判。
     */
    data class Active(
        val trackId: Long,
        val title: String,
        val done: Long = 0,
        val total: Long? = null,
    ) {
        /** 0..1；总长度未知时返回 null（界面画不确定进度）。 */
        val fraction: Float?
            get() = total?.takeIf { it > 0 }?.let { (done.toDouble() / it).toFloat().coerceIn(0f, 1f) }
    }

    private val _state = MutableStateFlow<Active?>(null)
    val state: StateFlow<Active?> = _state.asStateFlow()

    fun start(trackId: Long, title: String) {
        _state.value = Active(trackId = trackId, title = title)
    }

    /**
     * 收到一段字节。**只认当前这一条**：锁换了人、或者上一条的收尾回调晚到，
     * 都不能把新一条的进度改乱。
     */
    fun update(trackId: Long, done: Long, total: Long?) {
        val current = _state.value ?: return
        if (current.trackId != trackId) return
        _state.value = current.copy(done = done, total = total ?: current.total)
    }

    fun finish() {
        _state.value = null
    }
}
