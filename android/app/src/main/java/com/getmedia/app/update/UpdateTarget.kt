package com.getmedia.app.update

import com.getmedia.app.account.LatestRelease

/**
 * 「这一台该下载哪个安装包」。
 *
 * 免费版与完整版是**两个不同包名**的安装包，可以同机并存。下错了不会覆盖，
 * 而是装出另一个 App——0.1.27 的免费版就是这样：`/v2/config` 只给了一个
 * `latest.apk`（完整版），免费版点"去下载"装出来的是完整版。
 *
 * 所以规则是：
 * - 完整版 → 用主链接 `apk`；
 * - 免费版 → **只认** `free` 那组链接；
 * - 免费版没有 `free` 时返回 null，本次不提示。**绝不退回主链接**。
 */
object UpdateTarget {

    data class Link(val apk: String, val sha256: String)

    fun pick(latest: LatestRelease, freeEdition: Boolean): Link? {
        val link = if (freeEdition) {
            Link(latest.freeApk, latest.freeSha256)
        } else {
            Link(latest.apk, latest.sha256)
        }
        if (link.apk.isBlank()) return null
        // 只接受 https：明文链接在真实网络里会被劫持，宁可不提示。
        if (!link.apk.startsWith("https://")) return null
        return link
    }
}
