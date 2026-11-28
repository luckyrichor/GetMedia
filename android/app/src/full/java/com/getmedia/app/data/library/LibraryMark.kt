package com.getmedia.app.data.library

import com.getmedia.app.data.local.TrackEntity

/**
 * 曲库条目在同步流程里的三个额外状态值（P0-2）。
 *
 * 为什么不做成 `SyncState` 的枚举项：那个枚举在 `main`，加进去之后 free 的 dex 里就会出现
 * `CLOUD_ONLY` 这种字符串。这里只是字符串常量，`main` 一个字都不改；同步器按原文比较，
 * 老代码把不认识的值退化成 `LOCAL_ONLY` 也不会误上传——上传准入还要求有本地文件
 * （`SyncState.mayUploadToLibrary`）。
 *
 * 这三个值只会由 full 源码集里的同步器写入。
 */
object LibraryMark {

    /** 远端有、本机没有文件（列表标"云端未下载"）。 */
    const val CLOUD_ONLY = "CLOUD_ONLY"

    /** 本机改了标题，等推送；推成功回到 `SYNCED`。 */
    const val PENDING_UP = "LIB_PENDING_UP"

    /**
     * 远端删了、本机选择保留文件：已经和远端脱钩（`serverId` / `serverSeq` 清空）。
     * **不自动上传**；想再传上去只能从条目菜单手动发起。
     *
     * 值带 `LIB_` 前缀是扫描要求：裸的 `DETACHED` / `PENDING_UP` 在 Android 平台常量里就有
     * （`FLAG_TMP_DETACHED`、`MSG_HANDLE_PENDING_UPDATES`），free APK 里必然命中，等于永远扫不干净。
     */
    const val DETACHED = "LIB_DETACHED"

    /** 待重放的删除：还没发出去 / 可以自动重试。 */
    const val REMOVAL_QUEUED = "QUEUED"

    /** 请求已经发出但结果未知（进程崩溃/超时）：下一轮按服务器状态核对，不当作"未发出"取消。 */
    const val REMOVAL_SENDING = "SENDING"

    /** 待重放的删除：归档保护或版本冲突，等用户处理；不再自动重试。 */
    const val REMOVAL_WAITING = "WAITING"
}

/**
 * 播放列表成员对不上曲库行时补出来的**占位行**（`LibraryPlaylists.placeholder`）：
 * 有位置、没文件、没有云端身份，也还没见过云端标题。
 *
 * 判据只用本机列，不需要联网。三个条件缺一不可：
 * - `serverId == null`：真正的云端行一定先有 id；
 * - `CLOUD_ONLY`：播放列表补行时写的就是这个；
 * - `confirmedTitle == null`：一旦绑过云端行就会写上服务器标题，之后不再算占位。
 *
 * 为什么单独抽出来：占位行的 `mediaType` 只能是 `MediaKind.UNKNOWN_NAME`（云端播放列表
 * 只存三元组），所以**任何按类型相等来匹配的地方都必须对它放行**，否则视频占位行永远
 * 绑不上视频云端行，界面上就一直停在错的位置。
 */
internal fun TrackEntity.isPlaylistPlaceholder(): Boolean =
    serverId == null && syncState == LibraryMark.CLOUD_ONLY && confirmedTitle == null
