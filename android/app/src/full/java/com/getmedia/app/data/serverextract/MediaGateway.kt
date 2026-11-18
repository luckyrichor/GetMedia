package com.getmedia.app.data.serverextract

import com.getmedia.app.data.local.MediaKind
import okhttp3.Response

/**
 * 服务器提取所需的最小接口面，[ServerMediaApi] 是唯一实现。
 *
 * 单独抽出来只是为了让"提交 → 轮询 → 下载"的编排能被单测完整驱动：
 * 用假实现就能造出"任务先 queued 后 done""配额不足""超时"这些分支，
 * 不必等真实网络或真的睡 180 秒。
 */
interface MediaGateway {

    /** 建任务；`storage` 见 [Storage]。 */
    suspend fun startExtract(input: String, kind: MediaKind, storage: String = Storage.CACHE): ExtractJob

    suspend fun job(id: Long): ExtractJob

    suspend fun track(id: Long): ServerTrack

    /** 打开媒体流，调用方负责关闭。 */
    fun openMedia(trackId: Long, offset: Long = 0): Response
}
