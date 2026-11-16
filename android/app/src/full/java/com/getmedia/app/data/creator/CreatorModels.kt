package com.getmedia.app.data.creator

/**
 * 博主批量提取的数据模型。
 *
 * 服务端只返回「一页作品列表」，媒体文件仍然由手机上的 DirectExtractor 逐条提取，
 * 所以这里没有任务 id、没有下载地址，只有展示与下载所需的最小字段。
 */
enum class CreatorPlatform(val wire: String, val label: String) {
    BILIBILI("bilibili", "B 站"),
    DOUYIN("douyin", "抖音");

    companion object {
        fun of(value: String?): CreatorPlatform? = entries.firstOrNull { it.wire == value }
    }
}

data class CreatorProfile(
    val platform: CreatorPlatform,
    val creatorId: String,
    val name: String,
    val homeUrl: String,
    val avatarUrl: String? = null,
    val signature: String? = null,
    val followerCount: Long? = null,
    val totalVideos: Long? = null,
)

/** 窗口里的一条作品；[position] 是「按发布时间倒序」全量列表里的 1-based 序号。 */
data class CreatorItem(
    val position: Int,
    val videoId: String,
    val title: String,
    val sourceUrl: String,
    val coverUrl: String? = null,
    val durationSec: Double? = null,
    val publishedAt: String? = null,
) {
    /** 发布日期只取前 10 位（服务端给的是 ISO 时间）。 */
    val publishedDay: String? get() = publishedAt?.take(10)?.takeIf { it.length == 10 }
}

data class CreatorPreview(
    val profile: CreatorProfile,
    val items: List<CreatorItem>,
    val total: Long?,
    val hasMore: Boolean,
    val start: Int,
    val count: Int,
)

/** 失败分类：决定界面给什么提示、是否引导去登录或重试。 */
enum class CreatorProblem {
    /** 本机未登录。 */
    LOGIN_REQUIRED,

    /** 输入不是博主主页、裸数字没选平台、范围超限。 */
    INPUT,

    /** 博主不存在或主页不可见。 */
    NOT_FOUND,

    /** 服务端需要平台凭证（抖音登录 / 验证码）。 */
    PLATFORM_AUTH,

    /** 平台风控、浏览器桥接不可用、服务端暂时不可用。 */
    UNAVAILABLE,

    /** 本机网络问题。 */
    NETWORK,

    /** 服务端 5xx 或返回了看不懂的内容。 */
    SERVER,
}

class CreatorException(
    val problem: CreatorProblem,
    override val message: String,
) : Exception(message)
