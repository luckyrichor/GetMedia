package com.getmedia.app.data.library

import android.content.Context
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.backup.withPage
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.localextract.DirectExtractor
import com.getmedia.app.data.repo.TrackRepository
import com.getmedia.app.data.serverextract.ServerMediaApi
import com.getmedia.app.data.serverextract.serverExtractClient
import java.io.File

/**
 * 唯一的「下载到本机」实现（阶段 6）。条目菜单、恢复条目页、导出确认页的「先下载这 N 项」、
 * 批量重新下载都走 [download]，差异只在 [RepairRules] 的分派：
 *
 * 1. 本机已经有文件 → 直接返回，**不发任何请求**；
 * 2. B 站云端行 → 服务器已存文件直取，没有就让服务器按 target 提取；
 * 3. 抖音等非 B 站云端行 → 本机提取（复用现有授权通道，不碰任何 Cookie 内容）；
 * 4. 恢复空壳 → 本机提取 + 身份核对 + 按原 id 回填。
 *
 * 三条硬规矩：
 * - **下载前没有任何隐式网络动作**：不预先拉清单、不预先做云端匹配、不触发同步；
 * - **身份不一致一律拒绝**：作品 / 分集 / 媒体类型任一项对不上就抛错，行保持不变；
 * - **永远不新增行**：落库只走 `fillExistingFile` / `fillRestoredFile`（按原主键 UPDATE）。
 */
internal class DownloadCenter(
    private val app: Context,
    private val repo: TrackRepository,
    private val api: ServerMediaApi,
    private val prefs: LibrarySyncPrefs,
    private val session: SessionStore.Session,
    private val sameSession: () -> Boolean,
) {

    suspend fun download(track: TrackEntity): File {
        // ① 本机已有 → 直接给文件。这一步只读磁盘，不碰网络。
        repo.download(track).getOrNull()?.let { return it }
        val row = repo.byId(track.id) ?: error("这条已被移除")
        return when (RepairRules.route(row, hasFile = false)) {
            DownloadRoute.NONE -> throw IllegalStateException(
                RepairRules.options(row, hasFile = false).note ?: "这条没有可下载的内容",
            )
            DownloadRoute.SERVER_TARGET -> refillFromServer(row)
            DownloadRoute.SERVER_LOCAL -> extractInto(row, asShell = false)
            DownloadRoute.SHELL_LOCAL -> extractInto(row, asShell = true)
        }
    }

    /** B 站云端行：服务器已存文件直取；缓存被清掉了才让服务器带 target 重新提取。 */
    private suspend fun refillFromServer(row: TrackEntity): File {
        val saved = serverExtractClient(repo, api, app.cacheDir).refillOriginal(row) { original, detail, file ->
            repo.fillExistingFile(
                original, file.file, file.bytes, file.sha256, detail.updatedSeq,
                detail.updatedAtSec?.times(1000), sameSession,
            )
        }
        return saved.localPath?.let(::File) ?: throw IllegalStateException("下载完成但没有落成本地文件")
    }

    /**
     * 本机提取后按原 id 回填。两条路只差"回填到哪种行"：
     * - [asShell] = true：清单恢复出来的空壳（`RESTORED`、无服务器身份）→ `fillRestoredFile`；
     * - [asShell] = false：服务器有身份的抖音行 → `fillExistingFile`（保留 serverId/seq）。
     *
     * 分 P 的页码必须补进链接：服务器写的 `sourceUrl` 不带 `?p=`，漏了就会把 P2 下成 P1。
     */
    private suspend fun extractInto(row: TrackEntity, asShell: Boolean): File {
        val expected = row.restoredKey() ?: throw IllegalStateException("这条缺少作品、分集或媒体类型，无法核对身份")
        val kind = runCatching { MediaKind.valueOf(row.mediaType) }.getOrDefault(MediaKind.AUDIO)
        val url = withPage(row.sourceUrl, row.serverPage)
        val staged = File.createTempFile(
            "repair-", if (kind == MediaKind.VIDEO) ".mp4" else ".audio", app.cacheDir,
        )
        try {
            val result = DirectExtractor(app).extractTo(url, kind, staged)
            check(result.restoredKey() == expected) {
                "这次取到的作品、分集或媒体类型与这条不符，已保留原样"
            }
            val bytes = result.audioBytes
            val hash = result.audioSha256
            check(
                result.localPath == staged.absolutePath && bytes != null && bytes > 0 &&
                    staged.length() == bytes && !hash.isNullOrBlank() && result.audioFormat.isNotBlank(),
            ) { "这次取到的文件不完整，已保留原样" }

            val saved = if (asShell) {
                check(prefs.requireManualUpload(session.loginName, row.id)) { "无法保存此项的手动上传设置" }
                repo.fillRestoredFile(row, staged, bytes, hash, result.audioFormat, session.loginName, sameSession)
            } else {
                repo.fillExistingFile(row, staged, bytes, hash, row.serverSeq, row.serverUpdatedAtMs, sameSession)
            }
            return saved.localPath?.let(::File)
                ?: throw IllegalStateException("下载完成但没有落成本地文件")
        } finally {
            staged.delete()
        }
    }
}
