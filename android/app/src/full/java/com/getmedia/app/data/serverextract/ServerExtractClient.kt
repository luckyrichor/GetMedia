package com.getmedia.app.data.serverextract

import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.repo.TrackRepository
import kotlinx.coroutines.delay
import java.io.File

/** 轮询节奏与超时上限。抽成对象，好让单测钉住"退避到 4 秒封顶"和"音频 180s / 视频 600s"。 */
object PollPolicy {
    const val FIRST_DELAY_MS = 800L
    const val MAX_DELAY_MS = 4_000L
    const val AUDIO_TIMEOUT_MS = 180_000L
    const val VIDEO_TIMEOUT_MS = 600_000L

    /** 800 → 1200 → 1800 → 2700 → 4000（封顶），按次数递增，不随任务累积。 */
    fun delayFor(attempt: Int): Long {
        var d = FIRST_DELAY_MS
        repeat(attempt.coerceAtLeast(0)) { d = (d * 3 / 2).coerceAtMost(MAX_DELAY_MS) }
        return d
    }

    fun timeoutFor(kind: MediaKind): Long =
        if (kind == MediaKind.VIDEO) VIDEO_TIMEOUT_MS else AUDIO_TIMEOUT_MS
}

/**
 * 手机端的服务器提取流程（P0-1）：**提交 → 等 → 取 → 落库**，不含任何同步或合并逻辑。
 *
 * 只做一次一条，调用方负责"多条排队、一条一条来"（设计 §8）。
 * `sleep` / `clock` / `adopt` 都可注入，单测因此不必真等 800ms、跑满 180s，也不需要 Room。
 */
class ServerExtractClient(
    private val api: MediaGateway,
    private val adopt: suspend (TrackEntity, File) -> TrackEntity,
    private val cacheDir: File,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Stage { SUBMITTED, RESOLVING, DOWNLOADING, SAVED }

    data class Progress(val stage: Stage, val downloaded: Long = 0, val total: Long? = null)

    /**
     * 提一条作品。返回的实体已经带 `localPath` 并写进 Room——**失败一律不落库**。
     *
     * @param accountKey 登录名，写进 `tracks.accountKey`，P0-2 按账号隔离同步用；
     *                   拿不到就留 null。
     */
    suspend fun extract(
        input: String,
        kind: MediaKind,
        accountKey: String? = null,
        onProgress: (Progress) -> Unit = {},
    ): TrackEntity {
        // 配额不足 / storage 被关闭时改用 metadata 重来一次（不落盘、不能续传），设计 §3
        val done = try {
            submitAndWait(input, kind, Storage.CACHE, onProgress)
        } catch (e: ServerMediaException) {
            if (e.problem != ServerProblem.STORAGE_FULL) throw e
            submitAndWait(input, kind, Storage.METADATA, onProgress)
        }
        val trackId = done.trackId ?: throw ServerMediaException(
            ServerProblem.SERVER, "任务完成了但没有返回媒体编号")
        val track = api.track(trackId)
        // 名字里带 updatedSeq 与 bytes：服务器重新提取后这两个值会变，旧 `.part` 就不会被续传。
        val part = PartStore.file(cacheDir, track.id, track.updatedSeq, track.bytes)
        // 顺手清掉过期的、以及同一 trackId 下版本标识不同的旧 `.part`（服务器缓存 TTL 1 天）。
        PartStore.sweep(cacheDir, part)
        // 断流时 **不删** `.part`：里面的字节是真实前缀，下一次下载用 Range 接着要。
        // 只有 [ServerDownload] 判定"内容已确认不可用"时才由它自己删。
        val downloaded = ServerDownload.fetch(api, track.id, part, track.bytes) { got, total ->
            onProgress(Progress(Stage.DOWNLOADING, got, total))
        }
        val entity = entityOf(track, kind, downloaded.sha256, accountKey)
        val saved = adopt(entity, downloaded.file)
        onProgress(Progress(Stage.SAVED, saved.audioBytes ?: 0, saved.audioBytes))
        return saved
    }

    private suspend fun submitAndWait(
        input: String,
        kind: MediaKind,
        storage: String,
        onProgress: (Progress) -> Unit,
    ): ExtractJob {
        val job = api.startExtract(input, kind, storage)
        onProgress(Progress(Stage.SUBMITTED))
        return poll(job.id, kind) { onProgress(Progress(Stage.RESOLVING)) }
    }

    private suspend fun poll(jobId: Long, kind: MediaKind, onTick: () -> Unit): ExtractJob {
        val deadline = clock() + PollPolicy.timeoutFor(kind)
        var attempt = 0
        var rateLimited = 0
        while (true) {
            onTick()
            val job = try {
                api.job(jobId)
            } catch (e: ServerMediaException) {
                when (e.problem) {
                    ServerProblem.RATE_LIMITED -> {
                        if (++rateLimited > MAX_RATE_LIMITED) throw e
                        sleep(RATE_LIMIT_DELAY_MS)
                        continue
                    }
                    // 网络抖动：任务仍在服务器上，继续查，**不重发 extract**（否则会多建一个任务）
                    ServerProblem.NETWORK -> {
                        if (clock() >= deadline) throw timeout()
                        sleep(PollPolicy.delayFor(attempt++))
                        continue
                    }
                    else -> throw e
                }
            }
            when (job.status) {
                JobStatus.DONE -> return job
                // STORAGE_FULL 由 extract() 换 metadata 重来整条任务
                JobStatus.FAILED -> throw ServerMediaJson.failure(
                    job.errorCode.orEmpty(), job.errorDetail)
                else -> {
                    if (clock() >= deadline) throw timeout()
                    sleep(PollPolicy.delayFor(attempt++))
                }
            }
        }
    }

    private fun timeout() = ServerMediaException(
        ServerProblem.TIMEOUT,
        "服务器还在处理，可稍后在网页控制台查看任务结果",
        code = "poll_timeout",
    )

    private fun entityOf(
        track: ServerTrack,
        kind: MediaKind,
        sha256: String,
        accountKey: String?,
    ): TrackEntity {
        val video = kind == MediaKind.VIDEO
        return TrackEntity(
            // 键里带页码：B 站多 P 共用一个 video_id，不带页码 P2 会覆盖 P1
            id = LocalIds.localId(track.platform, track.videoId, track.page),
            platform = track.platform,
            videoId = track.videoId,
            title = track.title,
            uploader = track.uploader,
            durationSec = track.durationSec,
            coverUrl = track.coverUrl,
            audioBytes = track.bytes,
            // 服务端不返回容器后缀：音频按 m4a、视频按 mp4（播放器按内容嗅探，不依赖后缀）
            audioFormat = if (video) "mp4" else "m4a",
            sourceUrl = track.sourceUrl,
            audioSha256 = sha256,
            mediaType = if (video) MediaKind.VIDEO.name else MediaKind.AUDIO.name,
            serverId = track.id,
            serverSeq = track.updatedSeq,
            serverPage = track.page,
            serverUpdatedAtMs = track.updatedAtSec?.times(1000),
            syncState = SyncState.SYNCED.name,
            accountKey = accountKey,
        )
    }

    private companion object {
        const val MAX_RATE_LIMITED = 3
        const val RATE_LIMIT_DELAY_MS = 3_000L
    }
}

/** 生产环境的组装入口：真实接口 + 媒体库落库。 */
fun serverExtractClient(
    repository: TrackRepository,
    api: MediaGateway = ServerMediaApi(),
    cacheDir: File,
): ServerExtractClient = ServerExtractClient(
    api = api,
    adopt = repository::adoptStaged,
    cacheDir = cacheDir,
)
