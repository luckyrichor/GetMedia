package com.getmedia.app.data.library

import android.content.Context

/**
 * 每个账号一次的同步决策，存在本机（按账号隔离，退出登录不清）。
 *
 * 为什么不用 Room：这是"这台设备对这个账号问过没有"的界面状态，不是曲库数据；
 * 多一张表就要多一次迁移，而它换机/重装本来就该重新问一遍。
 *
 * 三种取值：
 * - [Decision.NONE] 还没问过 → 同步发现"只在这台手机上"的条目时问一次；
 * - [Decision.ACCEPTED] 同意过 → 以后每轮自动上传；
 * - [Decision.DECLINED] 拒绝过 → **不再问、也不自动传**（同步详情里手动发起是阶段 5 之后的入口）。
 */
class LibrarySyncPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("library-cloud-sync", Context.MODE_PRIVATE)

    fun decision(account: String): SyncUploadDecision {
        val raw = prefs.getString("$account.decision", null) ?: return SyncUploadDecision.NONE
        return runCatching { SyncUploadDecision.valueOf(raw) }.getOrDefault(SyncUploadDecision.NONE)
    }

    fun setDecision(account: String, value: SyncUploadDecision) {
        prefs.edit().putString("$account.decision", value.name).apply()
    }
}
