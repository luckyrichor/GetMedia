package com.getmedia.app.update

/**
 * 版本号比较：服务端 `release.json` 的 `version` 只有 `0.1.27` 这种形态（`release.py` 用
 * `^\d+(\.\d+){1,3}$` 校验），没有 versionCode，所以客户端必须自己比。
 *
 * 规则：按段比较数字，缺的段补 0——`0.1.27` 大于 `0.1.26`，`0.2` 大于 `0.1.99`。
 * 任何一段不是纯数字就当作"无法判断"，返回 false：宁可漏提示一次，也不能把
 * 一个看不懂的版本当成新版反复打扰。
 */
object AppVersion {

    fun segments(version: String?): List<Int>? {
        val text = version?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split('.')
        if (parts.size < 2 || parts.size > 4) return null
        val nums = parts.map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
        return nums
    }

    /** [latest] 是否比 [current] 新。任一方无法解析都返回 false。 */
    fun isNewer(latest: String?, current: String?): Boolean {
        val a = segments(latest) ?: return false
        val b = segments(current) ?: return false
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
