package com.getmedia.app.data.library

import org.json.JSONObject

/**
 * 同步接口的失败分类。**光看 HTTP 状态码不够**：
 *
 * - `409` 同时用于"云端已删除这条"（`track_deleted`）和"云端曲库满了"（`library_full`），
 *   前者要弹"云端已删除"确认、文件保留，后者要停手并提示去网页端清理；
 * - `404` 在 owner 通道上是**裸的**——未登录、不是 owner、路由不存在（服务器还没部署这一版）
 *   压成同一个响应，只能一起归到 [NOT_AVAILABLE]。
 *
 * 所以分支一律按 [LibrarySyncException.code] 走，不按状态码走。
 */
enum class LibrarySyncProblem {
    /** 401：令牌失效，交给账号模块处理，同步停手。 */
    LOGIN_REQUIRED,

    /** 裸 404：服务器没这个接口，或当前账号无权。静默停手，部署/升级后自愈。 */
    NOT_AVAILABLE,

    /** 409 + `track_deleted`：这个键在服务器上是墓碑。带上服务器那一行（[LibrarySyncException.conflict]）。 */
    TRACK_DELETED,

    /** 409 + `library_full`：该账号曲库达到上限。停止上传，提示去网页端清理，不动本地。 */
    LIBRARY_FULL,

    /** 409 + `archive_protected`：云端归档行拒绝手机写墓碑。保留队列，等用户处理。 */
    ARCHIVE_PROTECTED,

    /** 412 + `track_version_conflict`：条目在云端已变化。保留队列/草稿，不盲目重发。 */
    VERSION_CONFLICT,

    /** 404 + `track_not_found`：明确的"这条不存在"（区别于裸 404 的"接口不可用"）。 */
    TRACK_NOT_FOUND,

    /** 409 + `track_deleted` 之外，服务器回的成功体里 `deleted=true`：墓碑。 */
    TOMBSTONE,

    /** 429：读 `Retry-After` 整批退避。 */
    RATE_LIMITED,

    /** 其它 4xx：请求本身不被接受。 */
    INPUT,

    /** 网络不可用（IOException）。水位不推进，下次重跑。 */
    NETWORK,

    /** 其它 5xx 或无法识别的响应。 */
    SERVER,
}

/** 同步接口抛出的唯一异常类型。 */
class LibrarySyncException(
    val problem: LibrarySyncProblem,
    override val message: String,
    val code: String = "",
    val retryAfterSeconds: Long? = null,
    /** 仅 [LibrarySyncProblem.TRACK_DELETED]：服务器回的冲突行，原样留着给"云端已删除"确认用。 */
    val conflict: JSONObject? = null,
    /** 诊断用的一句话（例如底层 IO 的原因），不参与任何分支判断。 */
    val detail: String = "",
) : Exception(message)

/**
 * 云端一条条目的元数据。**没有文件、没有封面**——快照与同步都不带这两样。
 */
data class CloudTrack(
    val id: Long,
    val platform: String,
    val videoId: String,
    val page: Int,
    val title: String,
    val uploader: String?,
    val durationSec: Double?,
    val sourceUrl: String,
    val mediaType: String,
    val deleted: Boolean,
    val updatedSeq: Long,
    val updatedAtSec: Long,
    /** 服务器那条的存储类型（metadata / archive / cache）；旧响应没有就留空。 */
    val storage: String = "",
    /** 服务器是否已有文件；与 [storage] 一起用于删除确认与下载提示。 */
    val hasFile: Boolean = false,
    /** 收藏（schema v19；旧服务器不返回就是 false）。 */
    val favorite: Boolean = false,
    /** 类别路径（`音乐/古风`）；空串表示未分类。 */
    val category: String = "",
)

/** 一页拉取结果。[nextSeq] 是本页最大 `updated_seq`，**不能**当水位直接用（见引擎）。 */
data class PullPage(val items: List<CloudTrack>, val nextSeq: Long, val hasMore: Boolean)

/**
 * 上传一条"本机新增"要带的东西：**只有这 8 个字段**。
 *
 * 服务端 `POST /v2/tracks` 收多一个字段就 422（契约见 `docs/p0-2-sync-design.md` §18.2）。
 * 刻意不带 `localPath` / `favorite` / 类别：那些是本机整理痕迹，服务器不存。
 */
data class UploadEntry(
    val platform: String,
    val videoId: String,
    val page: Int,
    val mediaType: String,
    val title: String,
    val uploader: String?,
    val durationSec: Double?,
    val sourceUrl: String,
)

/**
 * 上传结果。[created] 为 false 表示服务器上已经有这个键（幂等重放、或网页端先建过）：
 * 按设计以服务器那一行为准，包括标题。
 */
data class CreatedTrack(val created: Boolean, val track: CloudTrack)

/** `DELETE /v2/sync/tracks/{id}` 的成功体（重复删除也返回同一形状）。 */
data class DeleteResult(val id: Long, val deleted: Boolean, val updatedSeq: Long)

