package com.getmedia.app.data.cloud

import android.content.Context
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 免费版：没有服务器曲库，曲库同步整体不存在。
 *
 * 这里不 import 任何 `data/library`、`data/serverextract` 的类，也不持有任何服务器接口路径——
 * 免费版的反编译产物里查不到这些标识符（`tools/scan_forbidden.py heavy` 会验）。
 */
val cloudSync: CloudSyncHook = object : CloudSyncHook {

    override val uploadAsk: StateFlow<CloudUploadAsk?> = MutableStateFlow(null)

    /** 免费版的行只可能是"本机提取/导入"或"从备份恢复"，都不属于服务器曲库。 */
    override fun awaitingDownload(track: TrackEntity): Boolean = false

    /** 不会走到这里（[awaitingDownload] 恒 false）；真被调用也只报一句本机事实。 */
    override suspend fun download(context: Context, track: TrackEntity): File =
        throw IllegalStateException("这一条没有可下载的文件")

    override fun onForeground(context: Context) = Unit

    override fun onAccountChanged(context: Context) = Unit

    override fun dismissUpload(ask: CloudUploadAsk) = Unit

    override fun acceptUpload(context: Context, ask: CloudUploadAsk) = Unit

    override fun declineUpload(context: Context, ask: CloudUploadAsk) = Unit
}
