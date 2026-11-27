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
 * 3. 不是完整版/管理员档，或**完整版已到期** → 同样一个请求都不发。免费档账号在"完整版"里
 *    也不显示同步入口（已定 7），既然没有入口，后台更不该偷偷发。
 *
 * "已同意版本"与"档位"都不知道时（旧版本升级上来的会话、或还没成功对过一次 `me()`）
 * 一律按"不许同步"处理：宁可晚一轮，也别在不知道身份与依据的情况下把标题传上去。
 */
object LibrarySyncGate {

    /**
     * 曲库同步要求的最低隐私说明版本。**写死**，不跟随服务端当前版本走：
     * 服务端还没升到这一版时，这里必须给出"不许"，而不是"服务端说第几版就算第几版"。
     *
     * 2026-11-27 从 3 提到 5：v5 新增 §1.8「本机导入文件的云端备份」——这是同步第一次
     * **上传文件本体**（此前只传元数据）。没同意过这一版的人不能替他传，所以门槛跟着升。
     * 将来隐私说明再升一版、且同步范围有实质变化时，改这个常量并在设计文档里记一笔。
     */
    const val MIN_PRIVACY_FOR_SYNC = 5

    /** `free` 及未识别档位都不许同步；`full`（有时效）与 `admin`（永久）允许。 */
    private val ALLOWED_TIERS = setOf("full", "admin")

    /**
     * 是不是"能同步"的档位。除了看等级，还要看**到期时间**：
     * 服务端每小时才把过期的 `full` 刷成 `free`（`sweep_expired_tiers`），本机不能等它，
     * 否则到期后最多一小时里仍在偷偷同步。`tierExpiresAt == null` 视为永久（admin 与"设为永久"的 full）。
     *
     * [now] 只给单元测试注入，正式代码用当前时间（秒）。
     */
    fun isOwner(session: SessionStore.Session?, now: Long = nowSeconds()): Boolean {
        if (session == null) return false
        if (session.tier?.lowercase() !in ALLOWED_TIERS) return false
        val expiresAt = session.tierExpiresAt ?: return true
        return now < expiresAt
    }

    /** 到期了但等级还没被服务端刷下去：界面上要用它说「已到期，续期后可继续同步」。 */
    fun expired(session: SessionStore.Session?, now: Long = nowSeconds()): Boolean {
        if (session == null) return false
        if (session.tier?.lowercase() !in ALLOWED_TIERS) return false
        val expiresAt = session.tierExpiresAt ?: return false
        return now >= expiresAt
    }

    fun maySync(session: SessionStore.Session?, now: Long = nowSeconds()): Boolean {
        if (session == null) return false
        if (session.consentRequired) return false
        val consented = session.consentedPrivacyVersion ?: return false
        if (consented < MIN_PRIVACY_FOR_SYNC) return false
        return isOwner(session, now)
    }

    internal fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}
