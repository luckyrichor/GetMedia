package com.getmedia.app.update

/**
 * 「这一次进前台要不要查新版本」的判断。
 *
 * 抽成纯函数（时间戳进、布尔出），是因为它同时管着两条容易出错的事：
 * 1. 自动检查的节流——同一版本内 6 小时最多一次，不做后台轮询；
 * 2. **刚装上新版就放行**——上一版留下的计时器不该压在新版头上。
 *
 * 第 2 条是 0.1.31 发布时暴露的问题：0.1.30 装完启动那次把计时器点亮，
 * 之后 6 小时内无论怎么进前台都不会再查，于是「已经发了新版」这件事
 * 要等到下一轮才能被看见。
 */
object UpdateGate {

    /** 同一版本内两次自动检查的最小间隔：6 小时。 */
    const val AUTO_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /**
     * @param now 当前时间（毫秒）
     * @param lastCheckAt 上次**成功**查询的时间；0 表示从没查过
     * @param checkedVersion 上次查询时 App 自己的版本号；null 表示没有记录
     * @param currentVersion 当前 App 版本号
     */
    fun shouldCheck(
        now: Long,
        lastCheckAt: Long,
        checkedVersion: String?,
        currentVersion: String,
    ): Boolean {
        // 版本变了（刚升级）：上一次查询属于另一个版本，直接作废。
        if (checkedVersion != currentVersion) return true
        val elapsed = now - lastCheckAt
        // 系统时间被往回调时 elapsed 为负，按"还没到点"处理，不额外发请求。
        return elapsed >= AUTO_INTERVAL_MS
    }
}
