package com.getmedia.app.data.serverextract

import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 编排层：用假网关跑通"提交 → 轮询 → 下载 → 落库"，以及配额退化、超时、任务消失。 */
class ServerExtractClientTest {

    private val media = "0123456789abcdef".toByteArray()

    /** 每个用例一个干净目录：失败残留 `.part` 的断言才不会互相干扰。 */
    private lateinit var dir: File

    @Before fun setUpDir() {
        dir = Files.createTempDirectory("gm-owner-extract").toFile()
    }

    @After fun clearDir() {
        dir.deleteRecursively()
    }

    private fun serverTrack(bytes: Long = media.size.toLong(), updatedSeq: Long = 17) = ServerTrack(
        id = 31, platform = "douyin", videoId = "7412", page = 2, title = "标题",
        uploader = "作者", durationSec = 9.5, coverUrl = "https://c/1.jpg",
        sourceUrl = "https://s/1", storage = Storage.CACHE, bytes = bytes,
        updatedSeq = updatedSeq, updatedAtSec = 1_700_000_000, hasFile = true,
    )

    /** 依次返回 [jobs] 里的状态，最后一个会一直重复；媒体流按 offset 切片。 */
    private inner class FakeGateway(private val jobs: List<ExtractJob>) : MediaGateway {
        val storages = mutableListOf<String>()
        var jobCalls = 0

        override suspend fun startExtract(input: String, kind: MediaKind, storage: String): ExtractJob {
            storages += storage
            return ExtractJob(1, JobStatus.QUEUED)
        }

        override suspend fun job(id: Long): ExtractJob {
            val job = jobs[minOf(jobCalls, jobs.size - 1)]
            jobCalls++
            return job
        }

        override suspend fun track(id: Long): ServerTrack = serverTrack()

        override fun openMedia(trackId: Long, offset: Long): Response {
            val start = offset.toInt()
            return Response.Builder()
                .request(Request.Builder().url("http://localhost/v2/media/$trackId").build())
                .protocol(Protocol.HTTP_1_1)
                .code(if (start > 0) 206 else 200)
                .message("OK")
                .apply {
                    if (start > 0) header("Content-Range", "bytes $start-${media.size - 1}/${media.size}")
                }
                .body(media.copyOfRange(start, media.size).toResponseBody())
                .build()
        }
    }

    private fun client(
        gateway: MediaGateway,
        onSleep: (Long) -> Unit = {},
        clock: () -> Long = { 0L },
        adopt: suspend (TrackEntity, File) -> TrackEntity = { e, f ->
            e.copy(localPath = f.absolutePath, audioBytes = f.length())
        },
    ) = ServerExtractClient(
        api = gateway,
        adopt = adopt,
        cacheDir = dir,
        sleep = { onSleep(it) },
        clock = clock,
    )

    @Test fun happyPathWritesServerBookkeeping() = runBlocking {
        val gateway = FakeGateway(listOf(
            ExtractJob(1, JobStatus.QUEUED),
            ExtractJob(1, JobStatus.RUNNING),
            ExtractJob(1, JobStatus.DONE, trackId = 31),
        ))
        val stages = mutableListOf<ServerExtractClient.Stage>()
        val saved = client(gateway).extract("https://v.douyin.com/x/", MediaKind.AUDIO, "lc") {
            stages += it.stage
        }

        assertEquals(listOf(Storage.CACHE), gateway.storages)
        assertEquals(31L, saved.serverId)
        assertEquals(17L, saved.serverSeq)
        assertEquals(2, saved.serverPage)
        assertEquals(1_700_000_000_000L, saved.serverUpdatedAtMs)
        assertEquals(SyncState.SYNCED.name, saved.syncState)
        assertEquals("lc", saved.accountKey)
        assertEquals("douyin", saved.platform)
        assertEquals("AUDIO", saved.mediaType)
        assertTrue("本地必须有文件路径", !saved.localPath.isNullOrBlank())
        assertEquals(media.size.toLong(), saved.audioBytes)
        assertEquals(ServerExtractClient.Stage.SUBMITTED, stages.first())
        assertEquals(ServerExtractClient.Stage.SAVED, stages.last())
        assertTrue(stages.count { it == ServerExtractClient.Stage.RESOLVING } >= 3)
    }

    @Test fun videoKeepsVideoKindAndFormat() = runBlocking {
        val gateway = FakeGateway(listOf(ExtractJob(1, JobStatus.DONE, trackId = 31)))
        val saved = client(gateway).extract("https://www.bilibili.com/video/BV1", MediaKind.VIDEO)
        assertEquals("VIDEO", saved.mediaType)
        assertEquals("mp4", saved.audioFormat)
    }

    @Test fun quotaFailureRetriesOnceWithMetadata() = runBlocking {
        val gateway = object : MediaGateway {
            val storages = mutableListOf<String>()
            override suspend fun startExtract(input: String, kind: MediaKind, storage: String): ExtractJob {
                storages += storage
                return ExtractJob(if (storage == Storage.CACHE) 1 else 2, JobStatus.QUEUED)
            }

            override suspend fun job(id: Long): ExtractJob =
                if (id == 1L) ExtractJob(1, JobStatus.FAILED, errorCode = "quota_exceeded")
                else ExtractJob(2, JobStatus.DONE, trackId = 31)

            override suspend fun track(id: Long): ServerTrack = serverTrack()
            override fun openMedia(trackId: Long, offset: Long): Response =
                Response.Builder()
                    .request(Request.Builder().url("http://localhost/v2/media/$trackId").build())
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(media.toResponseBody())
                    .build()
        }
        val saved = client(gateway).extract("https://v.douyin.com/x/", MediaKind.AUDIO)
        assertEquals(listOf(Storage.CACHE, Storage.METADATA), gateway.storages)
        assertEquals(31L, saved.serverId)
    }

    @Test fun failedJobSurfacesBusinessCode() = runBlocking {
        val gateway = FakeGateway(listOf(
            ExtractJob(1, JobStatus.FAILED, errorCode = "unsupported_link", errorDetail = "识别不出"),
        ))
        try {
            client(gateway).extract("nonsense", MediaKind.AUDIO)
            fail("任务失败必须抛出，且不落库")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.INPUT, e.problem)
        }
    }

    @Test fun pollingTimesOutWithoutSpinningForever() {
        var now = 0L
        val gateway = FakeGateway(listOf(ExtractJob(1, JobStatus.QUEUED)))
        val sleeps = mutableListOf<Long>()
        val ticking = client(gateway, onSleep = { sleeps += it; now += it }, clock = { now })
        try {
            runBlocking { ticking.extract("https://v.douyin.com/x/", MediaKind.AUDIO) }
            fail("轮询必须有上限")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.TIMEOUT, e.problem)
            assertTrue("总等待时间应达到音频上限", now >= PollPolicy.AUDIO_TIMEOUT_MS)
        }
        assertTrue("间隔不得超过 4 秒", sleeps.all { it <= PollPolicy.MAX_DELAY_MS })
    }

    @Test fun jobNotFoundStopsImmediately() = runBlocking {
        val gateway = object : MediaGateway {
            override suspend fun startExtract(input: String, kind: MediaKind, storage: String) =
                ExtractJob(1, JobStatus.QUEUED)

            override suspend fun job(id: Long): ExtractJob =
                throw ServerMediaJson.error(404, """{"detail":"任务不存在","code":"job_not_found"}""")

            override suspend fun track(id: Long): ServerTrack = serverTrack()
            override fun openMedia(trackId: Long, offset: Long): Response =
                throw AssertionError("任务已不存在，不该继续下载")
        }
        try {
            client(gateway).extract("https://v.douyin.com/x/", MediaKind.AUDIO)
            fail("job_not_found 必须停手")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.JOB_GONE, e.problem)
        }
    }

    @Test fun downloadFailureLeavesNoRowAndNoPartFile() = runBlocking {
        val gateway = object : MediaGateway {
            override suspend fun startExtract(input: String, kind: MediaKind, storage: String) =
                ExtractJob(1, JobStatus.DONE, trackId = 31)

            override suspend fun job(id: Long): ExtractJob =
                ExtractJob(1, JobStatus.DONE, trackId = 31)

            override suspend fun track(id: Long): ServerTrack = serverTrack()
            override fun openMedia(trackId: Long, offset: Long): Response =
                Response.Builder()
                    .request(Request.Builder().url("http://localhost/v2/media/$trackId").build())
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ByteArray(0).toResponseBody())
                    .build()
        }
        var adopted = 0
        try {
            client(gateway, adopt = { e, _ -> adopted++; e })
                .extract("https://v.douyin.com/x/", MediaKind.AUDIO)
            fail("下载失败不应返回记录")
        } catch (e: ServerMediaException) {
            assertEquals(0, adopted)
        }
        assertTrue("失败后不应残留 .part", dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    // ---------- 多 P：id 必须带页码 ----------

    private fun bilibiliTrack(page: Int) = ServerTrack(
        id = 88, platform = "bilibili", videoId = "BV1xx411c7mD", page = page,
        title = "多 P 标题", bytes = media.size.toLong(), updatedSeq = 3, hasFile = true,
    )

    private fun bilibiliGateway(page: Int) = object : MediaGateway {
        override suspend fun startExtract(input: String, kind: MediaKind, storage: String) =
            ExtractJob(1, JobStatus.DONE, trackId = 88)

        override suspend fun job(id: Long) = ExtractJob(1, JobStatus.DONE, trackId = 88)

        override suspend fun track(id: Long): ServerTrack = bilibiliTrack(page)

        override fun openMedia(trackId: Long, offset: Long): Response = Response.Builder()
            .request(Request.Builder().url("http://localhost/v2/media/$trackId").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(media.copyOfRange(offset.toInt(), media.size).toResponseBody())
            .build()
    }

    @Test fun bilibiliMultiPartDoesNotOverwriteFirstPart() = runBlocking {
        val p1 = client(bilibiliGateway(1)).extract("https://www.bilibili.com/video/BV1xx411c7mD", MediaKind.AUDIO)
        val p2 = client(bilibiliGateway(2))
            .extract("https://www.bilibili.com/video/BV1xx411c7mD?p=2", MediaKind.AUDIO)

        assertEquals("P1 的 id 仍是不带页码的键", LocalIds.localId("bilibili", "BV1xx411c7mD"), p1.id)
        assertEquals("P2 的 id 必须等于带 -p2 的键", LocalIds.localId("bilibili", "BV1xx411c7mD-p2"), p2.id)
        assertNotEquals("两个分 P 不能落到同一行", p1.id, p2.id)
        assertEquals(2, p2.serverPage)
    }

    // ---------- 断流：不删 .part，抛中文异常 ----------

    /** 读 [after] 个字节后抛 IOException，模拟"下到一半被断流"。 */
    private fun truncatedBody(after: Int): ResponseBody = object : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = media.size.toLong()

        override fun source(): BufferedSource = object : Source {
            private var sent = 0

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (sent >= after) throw java.io.IOException("unexpected end of stream")
                val count = minOf(byteCount, (after - sent).toLong()).toInt()
                sink.write(media, sent, count)
                sent += count
                return count.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE
            override fun close() = Unit
        }.buffer()
    }

    @Test fun brokenStreamKeepsPartForResume() {
        val gateway = object : MediaGateway {
            override suspend fun startExtract(input: String, kind: MediaKind, storage: String) =
                ExtractJob(1, JobStatus.DONE, trackId = 31)

            override suspend fun job(id: Long) = ExtractJob(1, JobStatus.DONE, trackId = 31)

            override suspend fun track(id: Long): ServerTrack = serverTrack()

            override fun openMedia(trackId: Long, offset: Long): Response = Response.Builder()
                .request(Request.Builder().url("http://localhost/v2/media/$trackId").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(truncatedBody(after = 4))
                .build()
        }
        var adopted = 0
        try {
            runBlocking {
                client(gateway, adopt = { e, _ -> adopted++; e })
                    .extract("https://v.douyin.com/x/", MediaKind.AUDIO)
            }
            fail("断流用尽次数后必须报错")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.NETWORK, e.problem)
            assertTrue("不能把原始 IOException 抛给上层", e.message.contains("网络中断"))
        }
        assertEquals("失败不落库", 0, adopted)
        val partFile = PartStore.file(dir, 31, 17, media.size.toLong())
        assertTrue("断流后 .part 必须留着供续传", partFile.exists())
        assertEquals("已收到的字节不能被清掉", 4L, partFile.length())
    }

    // ---------- `.part` 的版本标识与清理 ----------

    /** 记录每次 openMedia 的 offset；body 按 offset 切片返回。 */
    private inner class RecordingGateway(
        private val track: ServerTrack,
    ) : MediaGateway {
        val offsets = mutableListOf<Long>()

        override suspend fun startExtract(input: String, kind: MediaKind, storage: String) =
            ExtractJob(1, JobStatus.DONE, trackId = track.id)

        override suspend fun job(id: Long) = ExtractJob(1, JobStatus.DONE, trackId = track.id)

        override suspend fun track(id: Long): ServerTrack = track

        override fun openMedia(trackId: Long, offset: Long): Response {
            offsets += offset
            val start = offset.toInt()
            return Response.Builder()
                .request(Request.Builder().url("http://localhost/v2/media/$trackId").build())
                .protocol(Protocol.HTTP_1_1)
                .code(if (start > 0) 206 else 200)
                .message("OK")
                .apply {
                    if (start > 0) {
                        header("Content-Range", "bytes $start-${media.size - 1}/${media.size}")
                    }
                }
                .body(media.copyOfRange(start, media.size).toResponseBody())
                .build()
        }
    }

    @Test fun partNameCarriesVersionIdentity() {
        assertEquals("server-31-17-16.part", PartStore.name(31, 17, 16))
        assertEquals("bytes 为空时写 0", "server-31-17-0.part", PartStore.name(31, 17, null))
        assertNotEquals(
            "updatedSeq 变了名字必须跟着变",
            PartStore.name(31, 17, 16),
            PartStore.name(31, 18, 16),
        )
    }

    @Test fun stalePartOfPreviousVersionIsNeverResumed() = runBlocking {
        // 上一版（updatedSeq=17）下到 6 字节留下的断点
        val stale = PartStore.file(dir, 31, 17, media.size.toLong())
        stale.writeBytes(media.copyOfRange(0, 6))
        val gateway = RecordingGateway(serverTrack(updatedSeq = 18))

        val saved = client(gateway).extract("https://v.douyin.com/x/", MediaKind.AUDIO)

        assertEquals("旧版本的断点不能被续传：第一次请求就不能带 Range", listOf(0L), gateway.offsets)
        assertTrue("旧版本的 .part 应被清掉", !stale.exists())
        assertEquals("落库内容必须是完整媒体", media.size.toLong(), saved.audioBytes)
    }

    @Test fun expiredPartsAreSweptWhileFreshOnesStay() = runBlocking {
        val now = System.currentTimeMillis()
        val expired = File(dir, PartStore.name(99, 1, media.size.toLong()))
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val fresh = File(dir, PartStore.name(98, 1, media.size.toLong()))
            .apply { writeBytes(byteArrayOf(4, 5, 6)) }
        // 24 小时前修改的断点：服务器那份缓存早没了，续不上
        assertTrue(expired.setLastModified(now - 25L * 60 * 60 * 1000))
        assertTrue(fresh.setLastModified(now - 60L * 60 * 1000))

        client(RecordingGateway(serverTrack())).extract("https://v.douyin.com/x/", MediaKind.AUDIO)

        assertTrue("超过 24 小时的 .part 应在 extract 开始时被清掉", !expired.exists())
        assertTrue("未过期的 .part 不能被顺手删掉", fresh.exists())
    }
}
