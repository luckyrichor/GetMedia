package com.getmedia.app.data.creator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorJsonTest {

    private val payload = """
        {
          "profile": {
            "platform": "bilibili",
            "creator_id": "2",
            "name": "碧诗",
            "avatar_url": "https://i0.hdslb.com/face.jpg",
            "url": "https://space.bilibili.com/2/video",
            "total_videos": 99,
            "follower_count": 1234567,
            "signature": "介绍"
          },
          "range": {"start": 1, "count": 2},
          "items": [
            {
              "position": 1,
              "video_id": "BV1xx411c7mD",
              "title": "第一条",
              "description": "简介",
              "source_url": "https://www.bilibili.com/video/BV1xx411c7mD",
              "cover_url": "https://i0.hdslb.com/cover.jpg",
              "duration_sec": 61.5,
              "published_at": "2026-11-01T12:00:00+00:00",
              "stats": {"view_count": 1000},
              "comments": []
            },
            {
              "position": 2,
              "video_id": "BV1yy411c7mD",
              "title": "",
              "source_url": "https://www.bilibili.com/video/BV1yy411c7mD",
              "cover_url": "",
              "duration_sec": null,
              "published_at": null
            }
          ],
          "total": 99,
          "has_more": true,
          "fetched_at": "2026-11-15T00:00:00+00:00",
          "cookie_source": "guest"
        }
    """.trimIndent()

    @Test fun parsesProfileAndWindow() {
        val p = CreatorJson.preview(payload)
        assertEquals(CreatorPlatform.BILIBILI, p.profile.platform)
        assertEquals("碧诗", p.profile.name)
        assertEquals(1234567L, p.profile.followerCount)
        assertEquals(99L, p.profile.totalVideos)
        assertEquals("https://space.bilibili.com/2/video", p.profile.homeUrl)
        assertEquals(1, p.start)
        assertEquals(2, p.count)
        assertEquals(99L, p.total)
        assertTrue(p.hasMore)
        assertEquals(2, p.items.size)
        assertEquals("2026-11-01", p.items[0].publishedDay)
        assertEquals(61.5, p.items[0].durationSec!!, 0.001)
        assertEquals("第一条", p.items[0].title)
    }

    @Test fun fillsDefaultsForMissingFields() {
        val p = CreatorJson.preview(payload)
        val second = p.items[1]
        assertEquals("未命名作品", second.title)
        assertNull(second.coverUrl)
        assertNull(second.durationSec)
        assertNull(second.publishedDay)
    }

    @Test fun nullCountsStayNull() {
        val text = """{"profile":{"platform":"douyin","creator_id":"x","name":"n","url":"u",
            "avatar_url":null,"follower_count":null,"total_videos":null},"items":[]}"""
        val p = CreatorJson.preview(text)
        assertEquals(CreatorPlatform.DOUYIN, p.profile.platform)
        assertNull(p.profile.followerCount)
        assertNull(p.profile.totalVideos)
        assertNull(p.profile.avatarUrl)
        assertTrue(p.items.isEmpty())
        assertFalse(p.hasMore)
    }

    @Test fun skipsRowsWithoutSourceUrl() {
        val text = """{"profile":{"platform":"bilibili","creator_id":"1","name":"n","url":"u"},
            "items":[{"position":1,"title":"没有地址"},{"position":2,"title":"有地址",
            "source_url":"https://www.bilibili.com/video/BV1"}]}"""
        val p = CreatorJson.preview(text)
        assertEquals(1, p.items.size)
        assertEquals(2, p.items[0].position)
    }

    @Test fun malformedBodyBecomesServerProblem() {
        val e = assertThrows(CreatorException::class.java) { CreatorJson.preview("not-json") }
        assertEquals(CreatorProblem.SERVER, e.problem)
    }

    @Test fun missingProfileBecomesServerProblem() {
        val e = assertThrows(CreatorException::class.java) { CreatorJson.preview("""{"items":[]}""") }
        assertEquals(CreatorProblem.SERVER, e.problem)
    }

    @Test fun unknownPlatformBecomesServerProblem() {
        val e = assertThrows(CreatorException::class.java) {
            CreatorJson.preview("""{"profile":{"platform":"kuaishou","creator_id":"1","name":"n","url":"u"}}""")
        }
        assertEquals(CreatorProblem.SERVER, e.problem)
    }

    @Test fun errorKeepsServerDetailAndMapsProblem() {
        val e = CreatorJson.error(404, """{"detail":"该 UP 主不存在或主页不可见","code":"creator_not_found"}""")
        assertEquals(CreatorProblem.NOT_FOUND, e.problem)
        assertEquals("该 UP 主不存在或主页不可见", e.message)
    }

    @Test fun errorMappingByStatus() {
        assertEquals(CreatorProblem.LOGIN_REQUIRED, CreatorJson.error(401, "{}").problem)
        assertEquals(CreatorProblem.PLATFORM_AUTH, CreatorJson.error(428, """{"code":"creator_auth"}""").problem)
        assertEquals(CreatorProblem.INPUT, CreatorJson.error(400, """{"detail":"这是单个视频链接"}""").problem)
        assertEquals(CreatorProblem.UNAVAILABLE, CreatorJson.error(503, """{"code":"creator_unavailable"}""").problem)
        assertEquals(CreatorProblem.UNAVAILABLE, CreatorJson.error(429, """{"code":"rate_limited"}""").problem)
        assertEquals(CreatorProblem.SERVER, CreatorJson.error(502, """{"code":"creator_failed"}""").problem)
    }

    @Test fun errorWithoutBodyFallsBackToChineseText() {
        val e = CreatorJson.error(502, "<html>bad gateway</html>")
        assertEquals("服务器暂时不可用，请稍后重试", e.message)
    }

    @Test fun oldServerWithoutRouteIsExplained() {
        val e = CreatorJson.error(404, """{"detail":"未找到","code":"http_404"}""")
        assertEquals(CreatorProblem.NOT_FOUND, e.problem)
        // 带 code 的 404 优先用服务端 detail，不套兜底文案
        assertEquals("未找到", e.message)
    }

    /**
     * owner 通道（/getmedia/owner/api）在边缘把 401/403 都改写成无 body 的裸 404，
     * 客户端拿不到 code，也就分不清"没登录 / 不是 owner / 路由不存在"。
     * 文案必须把两种情况都说出来，并且给出可执行的动作（重新登录）。
     */
    @Test fun ownerChannelBareNotFoundAsksToCheckAccountOrRelogin() {
        for (body in listOf("", "<html><head><title>404 Not Found</title></head></html>", "  ")) {
            val e = CreatorJson.error(404, body)
            assertEquals(CreatorProblem.NOT_FOUND, e.problem)
            assertEquals("当前账号无权使用，或登录已过期，请重新登录", e.message)
            assertFalse(e.message.contains("服务器还没有这个功能"))
        }
    }
}
