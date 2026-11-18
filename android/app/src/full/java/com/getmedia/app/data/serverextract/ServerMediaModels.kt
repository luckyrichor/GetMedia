package com.getmedia.app.data.serverextract

/**
 * 服务器提取（P0-1）的数据模型与失败分类。
 *
 * 这里只描述"服务器说了什么"，不含任何 UI 文案拼接之外的逻辑；
 * 解析与错误映射放在 [ServerMediaJson]，命令式流程放在 [ServerExtractClient]。
 */

/** 失败分类。决定提示文案、能不能重试、要不要引导去登录。 */
enum class ServerProblem {
    /** 本机没有令牌，或令牌已失效（免费通道 401）。 */
    LOGIN_REQUIRED,

    /** 服务器以裸 404 回复：owner 通道把"未登录 / 不是 owner / 路由不存在"压成同一个响应。 */
    NOT_OWNER,

    /** 链接本身有问题（识别不出、不是作品页）。 */
    INPUT,

    /** 429，带 Retry-After。 */
    RATE_LIMITED,

    /** 任务在服务器上已不存在（服务端重启或任务被清理）。 */
    JOB_GONE,

    /** 轮询超过上限，任务可能仍在服务器执行。 */
    TIMEOUT,

    /** 服务器存储配额不足或该 storage 被关闭；客户端应改用 metadata 再试一次。 */
    STORAGE_FULL,

    /** 平台拒绝、没有可用分片、下载失败——可重试。 */
    UNAVAILABLE,

    /** 网络不可用。 */
    NETWORK,

    /** 其他服务端错误。 */
    SERVER,
}

/**
 * 统一的失败异常。`message` 是可直接展示的中文文案，`detail` 保留服务器原文，
 * `code` 是服务端的稳定业务码（`quota_exceeded` 之类），供编排层做分支判断。
 */
class ServerMediaException(
    val problem: ServerProblem,
    override val message: String,
    val code: String = "",
    val detail: String = "",
) : Exception(message)

/** `POST /v2/resolve` 的结果。P0-1 只在需要前置校验时用；主流程不依赖它。 */
data class ResolveInfo(
    val platform: String,
    val videoId: String,
    val title: String,
    val durationSec: Double? = null,
    val uploader: String? = null,
    val coverUrl: String? = null,
    val sourceUrl: String? = null,
    val estimatedBytes: Long = 0,
)

/** 任务状态。未知值按 [UNKNOWN] 处理：不当作终态，让轮询走到超时为止。 */
enum class JobStatus {
    QUEUED, RUNNING, DONE, FAILED, UNKNOWN;

    val terminal: Boolean get() = this == DONE || this == FAILED

    companion object {
        fun of(raw: String?): JobStatus = when (raw?.lowercase()) {
            "queued" -> QUEUED
            "running" -> RUNNING
            "done" -> DONE
            "failed" -> FAILED
            else -> UNKNOWN
        }
    }
}

/** `POST /v2/extract` 的 202 响应与 `GET /v2/extraction-jobs/{id}` 的同构部分。 */
data class ExtractJob(
    val id: Long,
    val status: JobStatus,
    val mediaType: String = "",
    val storage: String = "",
    val platform: String? = null,
    val videoId: String? = null,
    val trackId: Long? = null,
    val errorCode: String? = null,
    val errorDetail: String? = null,
)

/**
 * `GET /v2/tracks/{id}` 的结果。
 *
 * 任务响应里没有 `page` / `updated_seq` / `updated_at`，而落库要写这三项，
 * 所以任务 `done` 之后还要取一次 track（P0-1 设计 §2 第 ③ 步之后）。
 */
data class ServerTrack(
    val id: Long,
    val platform: String,
    val videoId: String,
    val page: Int = 1,
    val title: String,
    val uploader: String? = null,
    val durationSec: Double? = null,
    val coverUrl: String? = null,
    val sourceUrl: String = "",
    val storage: String = "",
    val bytes: Long? = null,
    val updatedSeq: Long = 0,
    val updatedAtSec: Long? = null,
    val hasFile: Boolean = false,
)
