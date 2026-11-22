package com.getmedia.app.data.library

import com.getmedia.app.data.cloud.RepairOptions
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity

/**
 * 「这一条缺文件时该走哪条路」（阶段 6 的分派表）。
 *
 * 纯函数、不碰网络也不碰文件系统——[hasFile] 由调用方传进来（生产看 `localPath` 是否真的存在，
 * 单测直接给答案），这样分派规则能被钉死而不需要搭 Room 或磁盘。
 */
internal enum class DownloadRoute {
    /** 有本机文件，或者这一行本来就不归同步管：没有可做的下载。 */
    NONE,

    /** 服务器有身份、B 站：先直取服务器已存文件，没有就让服务器按 target 提取。 */
    SERVER_TARGET,

    /** 服务器有身份、非 B 站（抖音等）：**本机提取**，核对身份后按原 id 回填。 */
    SERVER_LOCAL,

    /**
     * 服务器已归档（`archive`，长期保存）、平台不是 B 站：**直接取服务器文件**。
     *
     * 这一档是本机导入的作品（`platform=local`）走的路：它们在服务器上只有文件、没有链接，
     * 按 [SERVER_LOCAL] 去本机提取必然因为"缺少原始链接"失败。归档文件本身就是服务器资产，
     * 直取还省一次重新提取。
     */
    SERVER_ARCHIVE,

    /** 清单恢复出来的空壳（没有服务器身份、没有文件）：本机提取 + 身份核对 + 按原 id 回填。 */
    SHELL_LOCAL,
}

internal object RepairRules {

    /** B 站以外一律走本机提取：服务器的 target 提取对抖音没有语义，也更容易被风控。 */
    private fun prefersServer(platform: String) = platform.equals("bilibili", ignoreCase = true)

    fun route(track: TrackEntity, hasFile: Boolean): DownloadRoute {
        if (hasFile) return DownloadRoute.NONE
        if (track.serverId != null) {
            if (prefersServer(track.platform)) return DownloadRoute.SERVER_TARGET
            // 非 B 站但服务器已归档：直接取服务器上的文件。本机导入/本机提取的作品没有
            // 原始链接（`sourceUrl` 为空），让手机再去提取一遍只会得到"缺少原始链接"。
            if (track.serverStorage == "archive") return DownloadRoute.SERVER_ARCHIVE
            return DownloadRoute.SERVER_LOCAL
        }
        // 回填到空壳要求 `localPath` 本来就是空的（`fillRestoredFile` 会核对这一点）；
        // `localPath` 指向的文件被系统清掉的行只给"移除"。
        val shell = track.localPath == null && SyncState.of(track.syncState) == SyncState.RESTORED
        return if (shell && track.sourceUrl.isNotBlank() && track.restoredKey() != null) {
            DownloadRoute.SHELL_LOCAL
        } else {
            DownloadRoute.NONE
        }
    }

    /**
     * 空壳行：没有服务器身份、`syncState = RESTORED`、而且这台手机上没有它的文件
     * （`localPath` 为空，或者指向的文件已经不在了）。
     * 判据只看本机列，不需要联网——云端有没有删除记录、有没有匹配项，这里都不知道也不猜。
     */
    fun isEmptyShell(track: TrackEntity, hasFile: Boolean): Boolean =
        !hasFile && track.serverId == null && SyncState.of(track.syncState) == SyncState.RESTORED

    fun options(track: TrackEntity, hasFile: Boolean): RepairOptions {
        val route = route(track, hasFile)
        if (route != DownloadRoute.NONE) {
            return if (route == DownloadRoute.SHELL_LOCAL) {
                RepairOptions(canDownload = true, canRemoveShell = true)
            } else {
                RepairOptions(canDownload = true)
            }
        }
        if (!isEmptyShell(track, hasFile)) return RepairOptions.NONE
        // 空壳但取不回来：没有来源链接，或者作品/分集/类型的信息不足以核对身份。
        // 只给"移除"，并把原因说出来，不给一个点了必然失败的按钮。
        val note = when {
            track.sourceUrl.isBlank() -> "这条没有可用的来源链接，取不回来"
            track.restoredKey() == null -> "这条缺少作品、分集或媒体类型，无法核对身份"
            else -> null
        }
        return RepairOptions(canRemoveShell = true, note = note)
    }
}
