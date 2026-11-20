package com.getmedia.app.data.library

import com.getmedia.app.account.SessionStore

/**
 * 同步的两道闸，**都在客户端本地判**（`docs/p0-2-sync-design.md` §18.5 阶段 3 硬性验收 ⑤）。
 *
 * 1. 没同意最新的隐私说明 → 一个请求都不发。上传的是曲库元数据，没有对应同意文本就没有依据；
 *    服务端的 403 只是兜底，不能当成第一道防线——那意味着请求已经发出去了（还带着标题）。
 * 2. 不是 owner 档 → 同样一个请求都不发。免费档账号在"完整版"里也不显示同步入口
 *    （已定 7），既然没有入口，后台更不该偷偷发。
 *
 * 档位还不知道（旧版本升级上来的会话、或还没成功对过一次 `me()`）按"不许同步"处理：
 * 宁可晚一轮，也别在不知道身份的情况下把标题传上去。
 */
object LibrarySyncGate {

    /** `free` 及未识别档位都不许同步；只有 owner 及以上才允许。 */
    private val ALLOWED_TIERS = setOf("owner")

    fun maySync(session: SessionStore.Session?): Boolean {
        if (session == null) return false
        if (session.consentRequired) return false
        val tier = session.tier ?: return false
        return tier.lowercase() in ALLOWED_TIERS
    }
}
