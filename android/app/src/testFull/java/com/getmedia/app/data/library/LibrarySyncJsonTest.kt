package com.getmedia.app.data.library

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步接口的 JSON 与错误映射。
 *
 * 这里最要紧的一条：**两个 409 靠 `code` 分开**。状态码一样，后果完全不同——
 * `track_deleted` 是"这一条没了"（要弹确认、文件不能删），
 * `library_full` 是"整个账号满了"（要停手提示清理）。按状态码分支就一定会写错。
 */
class LibrarySyncJsonTest {

    private fun err(status: Int, body: String, retry: String? = null) =
        LibrarySyncJson.error(status, body, LibrarySyncJson.retryAfterSeconds(retry))

    @Test fun tombstone409IsNotLibraryFull() {
        val body = """{"detail":"该条目已被删除","code":"track_deleted",
            "track":{"id":7,"platform":"bilibili","video_id":"BV1","page":1,"deleted":true}}"""
        val e = err(409, body)
        assertEquals(LibrarySyncProblem.TRACK_DELETED, e.problem)
        assertEquals("track_deleted", e.code)
        assertNotNull("墓碑要带上服务器那一行，弹确认时才有内容可显示", e.conflict)
        assertEquals(7L, e.conflict!!.optLong("id"))
    }

    @Test fun libraryFull409IsNotTombstone() {
        val body = """{"detail":"云端曲库已达上限（20000 条），请先在网页端清理","code":"library_full"}"""
        val e = err(409, body)
        assertEquals(LibrarySyncProblem.LIBRARY_FULL, e.problem)
        assertNull("满了不是某一条的冲突，不能带 conflict", e.conflict)
        assertTrue("提示里要说怎么办", e.message.contains("网页端"))
    }

    @Test fun bothConflictsShareTheStatusCode() {
        // 同一个 409、两种结论：这条断言就是"不许按状态码分支"的可执行版本
        val a = err(409, """{"code":"track_deleted","track":{"id":1}}""")
        val b = err(409, """{"code":"library_full"}""")
        assertEquals(409, 409) // 说明用：两者 HTTP 码相同
        assertTrue(a.problem != b.problem)
    }

    @Test fun unknown409IsNotGuessed() {
        val e = err(409, """{"code":"weird_conflict"}""")
        assertEquals(LibrarySyncProblem.SERVER, e.problem)
    }

    @Test fun bareOwner404MeansNotAvailable() {
        // owner 通道把"未登录 / 非 owner / 路由不存在"压成无 body 的裸 404
        val e = err(404, "")
        assertEquals(LibrarySyncProblem.NOT_AVAILABLE, e.problem)
        assertEquals("", e.code)
    }

    @Test fun unauthorizedIsLoginRequired() {
        assertEquals(LibrarySyncProblem.LOGIN_REQUIRED, err(401, """{"code":"unauthenticated"}""").problem)
    }

    @Test fun rateLimitedCarriesRetryAfter() {
        val e = err(429, """{"code":"rate_limited"}""", retry = "37")
        assertEquals(LibrarySyncProblem.RATE_LIMITED, e.problem)
        assertEquals(37L, e.retryAfterSeconds)
    }

    @Test fun retryAfterOnlyAcceptsSecondsInRange() {
        assertNull("HTTP 日期形式不支持", LibrarySyncJson.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(LibrarySyncJson.retryAfterSeconds("0"))
        assertNull(LibrarySyncJson.retryAfterSeconds("999999"))
        assertEquals(5L, LibrarySyncJson.retryAfterSeconds(" 5 "))
    }

    @Test fun pageParsesItemsAndFlags() {
        val text = """{"items":[
            {"id":1,"platform":"bilibili","video_id":"BV1","page":1,"title":"一",
             "uploader":"UP","duration_sec":12.5,"source_url":"https://b/1","media_type":"audio",
             "deleted":false,"updated_seq":11,"updated_at":1700000000},
            {"id":2,"platform":"bilibili","video_id":"BV1","page":2,"title":"二",
             "deleted":true,"updated_seq":12,"updated_at":1700000001}],
            "next_seq":12,"has_more":true,"server_time":1700000002}"""
        val page = LibrarySyncJson.page(text)
        assertEquals(2, page.items.size)
        assertEquals(12L, page.nextSeq)
        assertTrue(page.hasMore)
        assertEquals(2, page.items[1].page)
        assertTrue(page.items[1].deleted)
        assertEquals(12.5, page.items[0].durationSec!!, 0.001)
        assertNull("没有 uploader 时是 null，不是空串", page.items[1].uploader)
    }

    @Test fun jsonNullFieldsAreTreatedAsAbsent() {
        // 服务器对没有作者 / 没有类别的行给的是 JSON null；裸 optString 会得到字符串 "null"
        val text = """{"items":[{"id":5,"platform":"bilibili","video_id":"BV9","page":1,
                        "title":null,"uploader":null,"category":null,"source_url":null,
                        "storage":null,"media_type":null,"updated_seq":5,"updated_at":1}],
                       "next_seq":5,"has_more":false}"""
        val item = LibrarySyncJson.page(text).items.single()
        assertNull("作者是 null 就要当没作者，不能变成名字叫 null", item.uploader)
        assertEquals("", item.category)
        assertEquals("", item.sourceUrl)
        assertEquals("", item.storage)
        assertEquals("未命名作品", item.title)
        assertEquals("audio", item.mediaType)
    }

    @Test fun rowWithNullPlatformIsDropped() {
        val text = """{"items":[{"id":6,"platform":null,"video_id":"BV9","updated_seq":6},
                                 {"id":7,"platform":"bilibili","video_id":"BV8","updated_seq":7}],
                       "next_seq":7,"has_more":false}"""
        val page = LibrarySyncJson.page(text)
        assertEquals("平台是 null 的行算不出本地 id，只能丢", 1, page.items.size)
        assertEquals(7L, page.items[0].id)
    }

    @Test fun pageDropsRowsWithoutKey() {
        val text = """{"items":[{"id":1,"platform":"","video_id":"BV1","updated_seq":1},
                                 {"id":2,"platform":"bilibili","video_id":"","updated_seq":2},
                                 {"id":0,"platform":"bilibili","video_id":"BV3","updated_seq":3},
                                 {"id":4,"platform":"bilibili","video_id":"BV4","updated_seq":4}],
                       "next_seq":4,"has_more":false}"""
        val page = LibrarySyncJson.page(text)
        assertEquals("没有键的行算不出本地 id，只能丢", 1, page.items.size)
        assertEquals(4L, page.items[0].id)
    }

    @Test fun pageStripsRedundantPageSuffixFromVideoId() {
        // 服务器把分 P 放在 page 列；万一 video_id 带着后缀，先剥掉，避免算 key 时变成 -p2-p2
        val text = """{"items":[{"id":1,"platform":"bilibili","video_id":"BV1-p2","page":2,
                        "title":"x","updated_seq":1,"updated_at":1}],"next_seq":1,"has_more":false}"""
        assertEquals("BV1", LibrarySyncJson.page(text).items[0].videoId)
    }

    @Test fun brokenBodyIsServerError() {
        assertEquals(LibrarySyncProblem.SERVER, runCatching { LibrarySyncJson.page("not json") }
            .exceptionOrNull().let { (it as LibrarySyncException).problem })
        assertEquals(LibrarySyncProblem.SERVER, runCatching { LibrarySyncJson.page("""{"next_seq":1}""") }
            .exceptionOrNull().let { (it as LibrarySyncException).problem })
    }

    @Test fun conflictRowKeepsRawJson() {
        val track = JSONObject().put("id", 9).put("title", "云端的名字")
        val e = LibrarySyncJson.error(
            409, JSONObject().put("code", "track_deleted").put("track", track).toString(),
        )
        assertEquals("云端的名字", e.conflict!!.optString("title"))
    }

    /**
     * 裸 400 是**边缘**的错，不是请求写错了。
     *
     * 手机传本机原件时请求体到一半连接断掉，nginx 在访问日志里就记一行 `400 0`（响应体为空）。
     * 应用自己的错误一定带 `code`（errors.ApiError 构造时必填），所以没有 code 的 400 只可能
     * 来自 nginx。若按 INPUT 处理，[LocalArchiveBackup] 会把它判成"重试也没用"，
     * 一次断网就把本机原件永久标成"无法备份"，而那个状态界面上根本看不到。
     */
    @Test fun bareFourHundredIsRetryableNotInput() {
        val e = err(400, "")
        assertEquals(LibrarySyncProblem.SERVER, e.problem)
        assertTrue("要给用户一个稍后自动重试的暗示", e.message.contains("稍后"))
    }

    @Test fun fourHundredWithABusinessCodeStaysInput() {
        val e = err(400, """{"code":"bad_ext","detail":"不支持的文件类型"}""")
        assertEquals(LibrarySyncProblem.INPUT, e.problem)
        assertEquals("bad_ext", e.code)
    }
}
