package com.getmedia.app.data.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本机文件备份到云端的进度（界面唯一的来源）。
 *
 * 为什么需要它：本机导入的音频/视频没有原始链接，换机时唯一的取回途径就是服务器上那份归档。
 * 导入后会自动传，但以前**界面上一个字都没有**——用户看到的是"导入完了"，不知道它在传、
 * 传到哪、还是根本没开始，只能干等或者反复重开应用。这里把 [LocalArchiveBackup] 上传时
 * 已经能拿到的字节数变成一条可见的进度。
 *
 * 与 [DownloadProgress] 分开而不是共用一个：下载是用户点的、随时可能和后台自动上传撞在一起，
 * 一个槽位会让两条进度互相覆盖。两边的"同一时刻只有一条"各自成立（上传在同步轮次里串行）。
 *
 * 三条纪律同 [DownloadProgress]：不持有 Context、不猜总长度（文件长度是已知的，所以这里
 * 基本总有 total）、结束时一定 [finish]。
 */
object UploadProgress {

    /**
     * 正在上传的那一条。
     *
     * [done] / [total] 是字节数（文件本身的长度，不含 multipart 的头与边界）。
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

    fun start(trackId: Long, title: String, total: Long? = null) {
        _state.value = Active(trackId = trackId, title = title, total = total)
    }

    /**
     * 收到一段已写出的字节。**只认当前这一条**：上一条的收尾回调晚到、或者批处理换了下一条，
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
