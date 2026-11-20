package com.getmedia.app.data.export

import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.TrackEntity

/**
 * 导出用的作品快照：**纯数据、不依赖 Android 与 Room**，方便在 JVM 单测里直接构造。
 *
 * 导出开始时把选择集拍成一份快照（[ExportTrack.of]），之后的库变化不影响本次导出；
 * 期间被删掉的作品，由写入阶段按"本机文件缺失"记为失败，而不是在快照里消失。
 *
 * 字段只取命名、分类、大小估算真正用得到的那几个，避免把整行实体带进导出链路。
 */
data class ExportTrack(
    val id: Long,
    val title: String,
    /** 本机重命名后的显示名；空串不算改名。 */
    val localTitleOverride: String? = null,
    val uploader: String? = null,
    /** 入库时间（毫秒）。0 表示未知，不写日期后缀。 */
    val addedAtMs: Long = 0L,
    /** 库内格式字段（`audioFormat`），视频作品这里也是容器名。 */
    val audioFormat: String = "m4a",
    val mediaType: String = MediaKind.AUDIO.name,
    val audioBytes: Long? = null,
    /** null 表示文件缺失（导出时记失败）。 */
    val localPath: String? = null,
    /**
     * 服务器上的条目号；null 表示这一条从来不在服务器上（本机提取/导入）。
     *
     * 用它区分两种"本机没文件"：有 `serverId` + 没 `localPath` = **还没下载**（去下载就行）；
     * 没有 `serverId` = 本机产物真的丢了（要重新提取）。免费版永远是 null。
     */
    val serverId: Long? = null,
) {
    /** 云端有、本机没下过：导出失败原因要说"尚未下载"，不能让人去重提取一遍。 */
    val awaitingDownload: Boolean
        get() = localPath.isNullOrBlank() && serverId != null

    /** 显示名：重命名优先，空串仍回落到原标题。 */
    val displayTitle: String
        get() = localTitleOverride?.takeIf { it.isNotBlank() } ?: title

    val isVideo: Boolean
        get() = mediaType == MediaKind.VIDEO.name

    companion object {
        fun of(entity: TrackEntity): ExportTrack = ExportTrack(
            id = entity.id,
            title = entity.title,
            localTitleOverride = entity.localTitleOverride,
            uploader = entity.uploader,
            addedAtMs = entity.addedAtMs,
            audioFormat = entity.audioFormat,
            mediaType = entity.mediaType,
            audioBytes = entity.audioBytes,
            localPath = entity.localPath,
            serverId = entity.serverId,
        )
    }
}
