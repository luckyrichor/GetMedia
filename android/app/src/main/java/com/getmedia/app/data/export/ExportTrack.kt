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
    /**
     * 同步状态原值（`LOCAL_ONLY` / `RESTORED` / …）。只用来判断"这一条能不能补下载"，
     * 导出链路不据此做别的决定；免费版的行都是 `LOCAL_ONLY`。
     *
     * 为什么不在这里直接算 `RepairOptions`：判定要读只在 full 出现的同步状态常量，
     * 而 [ExportTrack] 是纯数据（JVM 单测直接构造）。真正的闸门在下载入口里，这里只管显示。
     */
    val syncState: String? = null,
    /** 来源链接。空串表示这一条从来没有链接（本机导入），补不回来。 */
    val sourceUrl: String = "",
    /** 平台原值；`local` 是本机导入。 */
    val platform: String = "",
    /** 作品号；空串表示身份不足以回填。 */
    val videoId: String = "",
) {
    /** 云端有、本机没下过：导出失败原因要说"尚未下载"，不能让人去重提取一遍。 */
    val awaitingDownload: Boolean
        get() = localPath.isNullOrBlank() && serverId != null

    /**
     * 缺文件、但能通过统一下载入口补回来（阶段 6）：云端行，或**带来源链接且身份齐全**的空壳。
     *
     * 0.1.19 修的一处不一致：以前只要求 `syncState == "RESTORED"`，于是本机导入的音频
     * （`platform=local`、`sourceUrl` 为空）也被算成"可补下载"，导出页给出「先下载这 N 项」，
     * 点下去每一条都必然报「这条没有可用的来源链接」。[RepairRules] 那边一直判得对（只给移除），
     * 两处口径不一致，这里按它的口径对齐。
     *
     * 判定用的三个字段都在 `main` 里，不引用 full 源码集的 `restoredKey()`。
     */
    val refillable: Boolean
        get() = localPath.isNullOrBlank() && (
            serverId != null ||
                (syncState == "RESTORED" && sourceUrl.isNotBlank() &&
                    platform.isNotBlank() && videoId.isNotBlank())
            )

    /**
     * 「本机导入、文件已不在本机」的空壳：没有任何来源链接可补，只能重新导入或移除。
     *
     * 与"本机文件真的丢了"分开显示：前者去原设备拿文件就能回来，后者连文件都没有过。
     */
    val restoredLocalImport: Boolean
        get() = localPath.isNullOrBlank() && serverId == null &&
            syncState == "RESTORED" && platform.equals("local", ignoreCase = true)

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
            syncState = entity.syncState,
            sourceUrl = entity.sourceUrl.orEmpty(),
            platform = entity.platform,
            videoId = entity.videoId,
        )
    }
}
