package com.getmedia.app.data.serverextract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerMediaJsonTest {

    @Test fun resolveParsesAllFields() {
        val info = ServerMediaJson.resolve(
            """{"platform":"douyin","video_id":"7412","title":"标题","duration_sec":12.5,
                "uploader":"作者","cover_url":"https://c/1.jpg","source_url":"https://s/1","estimated_bytes":1024}"""
        )
        assertEquals("douyin", info.platform)
        assertEquals("7412", info.videoId)
        assertEquals(12.5, info.durationSec!!, 0.001)
        assertEquals(1024L, info.estimatedBytes)
        assertEquals("https://c/1.jpg", info.coverUrl)
    }

    @Test fun resolveRejectsMissingIdentity() {
        try {
            ServerMediaJson.resolve("""{"title":"只有标题"}""")
            fail("缺 platform / video_id 应当报错")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.SERVER, e.problem)
        }
    }

    @Test fun extractParsesQueuedJob() {
        val job = ServerMediaJson.extract("""{"id":7,"status":"queued"}""")
        assertEquals(7L, job.id)
        assertEquals(JobStatus.QUEUED, job.status)
        assertTrue(!job.status.terminal)
    }

    @Test fun jobDoneCarriesTrackId() {
        val job = ServerMediaJson.job(
            """{"id":7,"status":"done","media_type":"audio","storage":"cache",
                "platform":"bilibili","video_id":"BV1","track_id":31}"""
        )
        assertEquals(JobStatus.DONE, job.status)
        assertTrue(job.status.terminal)
        assertEquals(31L, job.trackId)
    }

    @Test fun unknownStatusIsNotTerminal() {
        val job = ServerMediaJson.job("""{"id":9,"status":"reticulating"}""")
        assertEquals(JobStatus.UNKNOWN, job.status)
        assertTrue("未知状态不能让轮询提前收手", !job.status.terminal)
    }

    @Test fun trackParsesServerBookkeeping() {
        val track = ServerMediaJson.track(
            """{"id":31,"platform":"douyin","video_id":"7412","page":2,"title":"t",
                "bytes":4096,"updated_seq":17,"updated_at":1700000000,"has_file":true,
                "storage":"cache","source_url":"https://s/1"}"""
        )
        assertEquals(2, track.page)
        assertEquals(4096L, track.bytes)
        assertEquals(17L, track.updatedSeq)
        assertEquals(1700000000L, track.updatedAtSec)
        assertTrue(track.hasFile)
    }

    @Test fun bareNotFoundMeansNoPermissionOrExpired() {
        val e = ServerMediaJson.error(404, "")
        assertEquals(ServerProblem.NOT_OWNER, e.problem)
        assertTrue(e.message.contains("无权使用"))
    }

    @Test fun notFoundWithCodeUsesBusinessCode() {
        val e = ServerMediaJson.error(404, """{"detail":"任务不存在","code":"job_not_found"}""")
        assertEquals(ServerProblem.JOB_GONE, e.problem)
        assertEquals("job_not_found", e.code)
    }

    @Test fun unauthorizedIsLoginRequired() {
        assertEquals(ServerProblem.LOGIN_REQUIRED, ServerMediaJson.error(401, "").problem)
    }

    @Test fun rateLimitedCarriesRetryAfter() {
        val e = ServerMediaJson.error(429, """{"code":"rate_limited"}""", retryAfterSeconds = 5)
        assertEquals(ServerProblem.RATE_LIMITED, e.problem)
        assertTrue(e.detail.contains("retry_after=5s"))
    }

    @Test fun jobFailureCodesMapToDesignTable() {
        val cases = mapOf(
            "unsupported_link" to ServerProblem.INPUT,
            "resolve_failed" to ServerProblem.UNAVAILABLE,
            "playurl_empty" to ServerProblem.UNAVAILABLE,
            "download_failed" to ServerProblem.UNAVAILABLE,
            "media_upstream_failed" to ServerProblem.UNAVAILABLE,
            "quota_exceeded" to ServerProblem.STORAGE_FULL,
            "storage_disabled" to ServerProblem.STORAGE_FULL,
            "job_not_found" to ServerProblem.JOB_GONE,
        )
        cases.forEach { (code, problem) ->
            assertEquals("code=$code", problem, ServerMediaJson.failure(code, null).problem)
        }
    }

    @Test fun unknownFailureKeepsServerDetail() {
        val e = ServerMediaJson.failure("weird_code", "上游 502")
        assertEquals(ServerProblem.SERVER, e.problem)
        assertEquals("上游 502", e.message)
    }

    @Test fun retryAfterOnlyAcceptsSeconds() {
        assertEquals(7L, ServerMediaJson.retryAfterSeconds("7"))
        assertNull(ServerMediaJson.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(ServerMediaJson.retryAfterSeconds(null))
    }
}
