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

    fun enabled(account: String): Boolean = prefs.getBoolean("$account.enabled", true)

    fun setEnabled(account: String, enabled: Boolean) {
        prefs.edit().putBoolean("$account.enabled", enabled).apply()
    }

    fun decision(account: String): SyncUploadDecision {
        val raw = prefs.getString("$account.decision", null) ?: return SyncUploadDecision.NONE
        return runCatching { SyncUploadDecision.valueOf(raw) }.getOrDefault(SyncUploadDecision.NONE)
    }

    fun setDecision(account: String, value: SyncUploadDecision) {
        prefs.edit().putString("$account.decision", value.name).apply()
    }

    /**
     * "补绑定回填"做过没有（每账号一次）。
     *
     * 本机导入、从未上传的条目在服务器上有同键行却一直没有 `serverId`；增量水位不会把早期行
     * 再送回来，所以升级后要强制全量拉一次才能补上。做成就置位，避免每轮都全量拉。
     */
    fun bindingBackfillDone(account: String): Boolean =
        prefs.getBoolean("$account.bound-backfill", false)

    fun markBindingBackfillDone(account: String) {
        prefs.edit().putBoolean("$account.bound-backfill", true).apply()
    }

    /**
     * "云端对账"做过没有（每账号一次）。
     *
     * 0.1.40 及以前，云端被硬删（没写墓碑）之后，本机那些还记着 `serverId` 的行会永远卡在
     * `LibraryUploader.eligible()` 的第一道判断上：既不上传，也不进「上传本机新增」的计数，
     * 界面上就是「0 条，点不了」。对账要拿云端 id 全集才能断言"这条真的没了"，所以只能
     * 全量拉一次；做成就置位，避免每轮都全量。
     */
    fun cloudReconcileDone(account: String): Boolean =
        prefs.getBoolean("$account.cloud-reconcile", false)

    fun markCloudReconcileDone(account: String) {
        prefs.edit().putBoolean("$account.cloud-reconcile", true).apply()
    }

    /**
     * 上一次"给没备份成功的原件立刻重试一次"是在哪个 versionCode 做的。
     *
     * 为什么不用一次性布尔：`archiveNextAt` 是退避（2/5/15/60/300 秒，一轮 5 次后冷却逐轮
     * 翻倍，最长 6 小时），而客户端逻辑会随版本变——0.1.41 及以前每一次上传都必然失败
     * （nginx 把 1m 的上限带进了认证子请求），退避次数早就攒到顶。**每个版本升级后重试一次**
     * 才是这个语义；用布尔会漏掉"上一版已经放行过、这一版又改了规则"的情况。
     */
    fun archiveRetryVersion(account: String): Int =
        prefs.getInt("$account.archive-retry-version", 0)

    fun markArchiveRetryVersion(account: String, versionCode: Int) {
        prefs.edit().putInt("$account.archive-retry-version", versionCode).apply()
    }

    /**
     * 上一次"升级后强制全量对账"是在哪个 versionCode 做的。
     *
     * 增量拉取只送 `updated_seq` 比水位大的行，所以**本机已经记下的错误状态永远等不到重发**。
     * 0.1.44 的真机故障就是这样：手机 A 拉到了《出山3》却把标题丢了（`mergeServer` 的
     * "本机改名优先"分支），同时把新版本号写进了 `serverSeq`——此后版本号再也比不过，
     * 增量拉取永远不会再送这一行，光改合并规则救不回来。
     *
     * 每次升级后全量拉一遍（幂等，2000 条也就 10 次请求），让合并规则的变化能作用到存量行上。
     * 判据用 versionCode 而不是布尔，理由同 [archiveRetryVersion]。
     */
    fun fullResyncVersion(account: String): Int =
        prefs.getInt("$account.full-resync-version", 0)

    fun markFullResyncVersion(account: String, versionCode: Int) {
        prefs.edit().putInt("$account.full-resync-version", versionCode).apply()
    }

    fun requiresManualUpload(account: String, id: Long): Boolean =
        prefs.getBoolean("$account.restored.$id.manual", false)

    fun requireManualUpload(account: String, id: Long): Boolean =
        prefs.edit().putBoolean("$account.restored.$id.manual", true).commit()
}
