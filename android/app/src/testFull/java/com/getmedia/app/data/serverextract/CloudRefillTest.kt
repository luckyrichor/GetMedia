package com.getmedia.app.data.serverextract

import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class CloudRefillTest {
    @get:Rule val tmp = TemporaryFolder()
    private val payload = "P3 audio".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val original = TrackEntity(
        id = -8031270299162834319, platform = "bilibili", videoId = "BV1KG411w7r2-p3",
        title = "renamed", uploader = null, durationSec = 37.0, audioBytes = null, audioFormat = "",
        sourceUrl = "https://www.bilibili.com/video/BV1KG411w7r2?p=3", favorite = true,
        localCategoryId = 8, localTitleOverride = "renamed", serverId = 6, accountKey = "owner",
    )
    private val archived = ServerTrack(
        id = 6, platform = "bilibili", videoId = original.videoId, mediaType = "audio", title = "P3",
        storage = "archive", bytes = payload.size.toLong(), sha256 = hash, hasFile = true,
    )

    private inner class Gateway(private vararg val details: ServerTrack) : MediaGateway {
        var starts = 0
        var reads = 0
        var requests = mutableListOf<Long>()
        var lostCache = false
        var sourceMarker: String? = "stored"
        override suspend fun track(id: Long): ServerTrack = details[minOf(reads++, details.lastIndex)]
        override suspend fun startExtract(input: String, kind: MediaKind, storage: String): ExtractJob =
            error("unexpected legacy extraction")
        override suspend fun startTargetedExtract(input: String, kind: MediaKind, target: ExtractTarget): ExtractJob {
            assertEquals(ExtractTarget(6, "bilibili", "BV1KG411w7r2", 3), target)
            assertEquals(original.sourceUrl, input)
            assertEquals(MediaKind.AUDIO, kind)
            starts++
            return ExtractJob(1, JobStatus.QUEUED)
        }
        override suspend fun job(id: Long): ExtractJob = ExtractJob(id, JobStatus.DONE, trackId = 6)
        override fun openMedia(trackId: Long, offset: Long): Response = error("unexpected fallback stream")
        override fun openStoredMedia(trackId: Long, offset: Long): Response {
            requests.add(trackId)
            val missing = lostCache && requests.size == 1
            return Response.Builder().request(Request.Builder().url("https://example.invalid/media").build())
                .protocol(Protocol.HTTP_1_1).code(if (missing) 409 else 200).message("test")
                .apply { sourceMarker?.let { header("X-GetMedia-Media-Source", it) } }
                .body(if (missing) """{"code":"stored_file_missing"}""".toResponseBody() else payload.toResponseBody())
                .build()
        }
    }

    private fun client(api: Gateway) = ServerExtractClient(api, { _, _ -> error("unexpected upsert") }, tmp.root)

    @Test fun archivedP3ReadsId6AndDoesNotSubmitOrCreate() = runBlocking {
        val api = Gateway(archived)
        val result = client(api).refillOriginal(original.copy(sourceUrl = "")) { row, detail, file ->
            assertEquals(original.id, row.id)
            assertEquals(6L, detail.id)
            assertEquals(hash, file.sha256)
            row.copy(localPath = file.file.path)
        }
        assertEquals(original.id, result.id)
        assertEquals(0, api.starts)
        assertEquals(listOf(6L), api.requests)
    }

    @Test fun cacheEvictedAfterLookupRechecksThenTargetsOriginal() = runBlocking {
        val cache = archived.copy(storage = "cache")
        val metadata = cache.copy(storage = "metadata", hasFile = false, bytes = null, sha256 = null)
        val api = Gateway(cache, metadata, cache.copy(videoId = "BV1KG411w7r2", page = 3, updatedSeq = 2))
        api.lostCache = true
        client(api).refillOriginal(original) { row, _, _ -> row }
        assertEquals(1, api.starts)
        assertEquals(3, api.reads)
        assertEquals(listOf(6L, 6L), api.requests)
    }

    @Test fun mismatchedResponseDoesNotReadOrSave() {
        val metadata = archived.copy(storage = "metadata", hasFile = false)
        for (wrong in listOf(
            archived.copy(videoId = "BV1KG411w7r2", page = 1),
            archived.copy(videoId = "BV1GJ411x7h7", page = 3),
            archived.copy(mediaType = "video"),
            archived.copy(id = 9),
        )) {
            val api = Gateway(metadata, wrong)
            val error = assertThrows(ServerMediaException::class.java) {
                runBlocking { client(api).refillOriginal(original) { _, _, _ -> error("unexpected write") } }
            }
            assertEquals("target_mismatch", error.code)
            assertEquals(emptyList<Long>(), api.requests)
        }
    }

    @Test fun wrongSizeOrHashDoesNotSave() {
        for (invalid in listOf(archived.copy(bytes = 5), archived.copy(sha256 = "0".repeat(64)))) {
            val api = Gateway(invalid)
            assertThrows(ServerMediaException::class.java) {
                runBlocking { client(api).refillOriginal(original) { _, _, _ -> error("unexpected write") } }
            }
            assertEquals(0, api.starts)
        }
    }

    @Test fun oldServerWithoutStoredMarkerFailsClosed() {
        val api = Gateway(archived)
        api.sourceMarker = null
        val error = assertThrows(ServerMediaException::class.java) {
            runBlocking { client(api).refillOriginal(original) { _, _, _ -> error("unexpected write") } }
        }
        assertEquals("stored_mode_unsupported", error.code)
        assertEquals(0, api.starts)
    }
}
