package com.getmedia.app.data.backup

import android.content.Context

/**
 * 备份的本地状态（设计 §5.1）：`SharedPreferences("getmedia_backup")`，键按登录名前缀隔离，
 * 换账号不会互相串。不建表——这些是设备级偏好，不属于业务数据。
 */
class BackupPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class State(
        /** 用户开关，默认开启（首次登录告知后保持开启）。 */
        val enabled: Boolean = true,
        /** 上次成功上传的内容指纹。 */
        val lastHash: String? = null,
        /** 上次成功上传的时间（ms）；0 表示从没传过。 */
        val lastAtMs: Long = 0,
        /** 服务端返回的备份 id，设置页用来判断"云端有没有东西"。 */
        val lastId: String? = null,
        /** 上次上传的曲目数，状态行「上次备份：N 首」用。 */
        val lastTracks: Int = 0,
        /** 内容变过但还没传上去（跨启动保留）。 */
        val dirty: Boolean = false,
        /** 首次登录的告知弹窗是否已显示过。 */
        val noticeShown: Boolean = false,
        /** 被服务端停用的原因；有值时常驻显示，重启不清。 */
        val blocked: String? = null,
        /** 上次上传遇到 404（服务端还没有这套接口）。只影响显示，不阻止下次重试。 */
        val unsupported: Boolean = false,
        /**
         * 被服务端限频到什么时候（ms）；0 表示没有。**这不是失败**：
         * 上次成功的 `lastAtMs` / `lastTracks` 一律不动，只多一行提示。
         */
        val throttleUntilMs: Long = 0,
        /**
         * 登录后"云端有备份，要不要恢复"的决策（见 [BackupManager] 的决策闸）：
         * [RESTORE_UNKNOWN] 还没决定 / [RESTORE_RESTORED] 已恢复 / [RESTORE_KEEP_LOCAL] 以本机为准。
         *
         * 按登录名前缀存（同其它键），换账号不会串。未决定时自动备份暂停——
         * 本机寥寥几首传上去，会把云端有内容的备份顶掉。
         */
        val restoreDecision: String = RESTORE_UNKNOWN,
    )

    fun read(account: String): State = State(
        enabled = prefs.getBoolean(key(account, "enabled"), true),
        lastHash = prefs.getString(key(account, "last_hash"), null),
        lastAtMs = prefs.getLong(key(account, "last_at"), 0),
        lastId = prefs.getString(key(account, "last_id"), null),
        lastTracks = prefs.getInt(key(account, "last_tracks"), 0),
        dirty = prefs.getBoolean(key(account, "dirty"), false),
        noticeShown = prefs.getBoolean(key(account, "notice_shown"), false),
        blocked = prefs.getString(key(account, "blocked"), null),
        unsupported = prefs.getBoolean(key(account, "unsupported"), false),
        throttleUntilMs = prefs.getLong(key(account, "throttle_until"), 0),
        restoreDecision = prefs.getString(key(account, "restore_decision"), RESTORE_UNKNOWN)
            ?: RESTORE_UNKNOWN,
    )

    fun write(account: String, state: State) {
        prefs.edit()
            .putBoolean(key(account, "enabled"), state.enabled)
            .putString(key(account, "last_hash"), state.lastHash)
            .putLong(key(account, "last_at"), state.lastAtMs)
            .putString(key(account, "last_id"), state.lastId)
            .putInt(key(account, "last_tracks"), state.lastTracks)
            .putBoolean(key(account, "dirty"), state.dirty)
            .putBoolean(key(account, "notice_shown"), state.noticeShown)
            .putString(key(account, "blocked"), state.blocked)
            .putBoolean(key(account, "unsupported"), state.unsupported)
            .putLong(key(account, "throttle_until"), state.throttleUntilMs)
            .putString(key(account, "restore_decision"), state.restoreDecision)
            .apply()
    }

    fun update(account: String, change: (State) -> State): State = change(read(account)).also { write(account, it) }

    private fun key(account: String, name: String) = "$account.$name"

    companion object {
        private const val FILE = "getmedia_backup"

        /** 库或单份快照超过 512 KiB。 */
        const val BLOCKED_TOO_LARGE = "too_large"

        /** 服务端 422：客户端组装出了它不接受的内容。 */
        const val BLOCKED_INVALID = "invalid"

        /** 还没决定"云端有备份要不要恢复"：自动备份暂停，等一次性决策。 */
        const val RESTORE_UNKNOWN = "unknown"

        /** 用户从云端恢复过（设置页入口或登录弹窗）：以云端那份为准，随后照常自动备份。 */
        const val RESTORE_RESTORED = "restored"

        /** 用户选了"用本机内容"，或云端本来就没有有内容的备份：以本机为准。 */
        const val RESTORE_KEEP_LOCAL = "keep_local"
    }
}
