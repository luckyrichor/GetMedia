package com.getmedia.app.data.library

import com.getmedia.app.account.SessionStore

/**
 * 同步的三道闸，**都在客户端本地判**（`docs/p0-2-sync-design.md` §18.5 阶段 3 硬性验收 ⑤）。
 *
 * 1. 没同意最新的隐私说明（服务端 `consent_required = true`）→ 一个请求都不发。
 *    上传的是曲库元数据，没有对应同意文本就没有依据；服务端的 403 只是兜底。
 * 2. **该账号已同意的隐私版本 ≥ [MIN_PRIVACY_FOR_SYNC]**，否则一个请求都不发。
 *    这一条是本地硬判定：不看服务端"现在生效的是第几版"，只看自己手里记录的同意版本。
 *    2026-11-19 手机 2 实测过反例——线上仍是隐私 v2 时，0.1.5 装上后照样拉了一次 `/v2/sync`
 *    并弹出首次上传询问，因为当时只有第 1 条闸，而 v2 生效期间 `consent_required` 是 false。
 *    第 2 条就是补这个洞。
 * 3. 不是 owner 档 → 同样一个请求都不发。免费档账号在"完整版"里也不显示同步入口
 *    （已定 7），既然没有入口，后台更不该偷偷发。
 *
 * "已同意版本"与"档位"都不知道时（旧版本升级上来的会话、或还没成功对过一次 `me()`）
 * 一律按"不许同步"处理：宁可晚一轮，也别在不知道身份与依据的情况下把标题传上去。
 */
object LibrarySyncGate {

    /**
     * 曲库同步要求的最低隐私说明版本。**写死**，不跟随服务端当前版本走：
     * 服务端还没升到 v3 时，这里必须给出"不许"，而不是"服务端说第几版就算第几版"。
     *
     * 将来隐私说明再升一版、且同步范围有实质变化时，改这个常量并在设计文档里记一笔。
     */
    const val MIN_PRIVACY_FOR_SYNC = 3

    /** `free` 及未识别档位都不许同步；只有 owner 及以上才允许。 */
    private val ALLOWED_TIERS = setOf("owner")

    fun isOwner(session: SessionStore.Session?): Boolean = session?.tier?.lowercase() in ALLOWED_TIERS

    fun maySync(session: SessionStore.Session?): Boolean {
        if (session == null) return false
        if (session.consentRequired) return false
        val consented = session.consentedPrivacyVersion ?: return false
        if (consented < MIN_PRIVACY_FOR_SYNC) return false
        return isOwner(session)
    }
}
