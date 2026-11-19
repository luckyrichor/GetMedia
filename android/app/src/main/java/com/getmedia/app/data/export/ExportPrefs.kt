package com.getmedia.app.data.export

import android.content.Context
import android.net.Uri
import java.time.LocalDate

/**
 * 导出相关的本机偏好：上次选的目录、文件夹名、四个命名开关、通知权限是否问过。
 *
 * 上次目录 URI 含用户目录名，属**非敏感但也不该外泄**的信息：只存本机 SharedPreferences，
 * 不进日志、不进诊断包、不进云端备份（备份只取曲库表）。
 */
object ExportPrefs {

    private const val FILE = "getmedia:export"
    private const val KEY_TREE = "export.tree"
    private const val KEY_TREES_KNOWN = "export.trees.known"
    private const val KEY_FOLDER = "export.folder"
    private const val KEY_BY_TYPE = "export.byType"
    private const val KEY_BY_UPLOADER = "export.byUploader"
    private const val KEY_WITH_UPLOADER = "export.withUploader"
    private const val KEY_WITH_DATE = "export.withDate"
    private const val KEY_NOTIFY_ASKED = "export.notifyAsked"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun lastTree(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun lastFolder(context: Context): String? = prefs(context).getString(KEY_FOLDER, null)

    fun byType(context: Context): Boolean = prefs(context).getBoolean(KEY_BY_TYPE, false)

    fun byUploader(context: Context): Boolean = prefs(context).getBoolean(KEY_BY_UPLOADER, false)

    fun withUploader(context: Context): Boolean = prefs(context).getBoolean(KEY_WITH_UPLOADER, false)

    fun withDate(context: Context): Boolean = prefs(context).getBoolean(KEY_WITH_DATE, false)

    /** 通知权限只问一次：拒绝之后每次点导出都弹一次，是骚扰。 */
    fun notifyAsked(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFY_ASKED, false)

    fun markNotifyAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_ASKED, true).apply()
    }

    /** 记住本次选择；下次打开确认页时原样带出来（目录 + 文件夹名 + 四个开关）。 */
    fun remember(
        context: Context,
        tree: Uri,
        folder: String?,
        byType: Boolean,
        byUploader: Boolean,
        withUploader: Boolean,
        withDate: Boolean,
    ) {
        prefs(context).edit()
            .putString(KEY_TREE, tree.toString())
            .putString(KEY_FOLDER, folder)
            .putBoolean(KEY_BY_TYPE, byType)
            .putBoolean(KEY_BY_UPLOADER, byUploader)
            .putBoolean(KEY_WITH_UPLOADER, withUploader)
            .putBoolean(KEY_WITH_DATE, withDate)
            .apply()
    }

    /** 最近用过的目录（新→旧），授权数量上限只有 5 个，靠这张表决定释放谁。 */
    fun knownTrees(context: Context): List<String> =
        prefs(context).getString(KEY_TREES_KNOWN, null)
            ?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun rememberKnownTrees(context: Context, trees: List<String>) {
        prefs(context).edit().putString(KEY_TREES_KNOWN, trees.joinToString("\n")).apply()
    }

    /** 默认文件夹名：`2026-11 导出`（界面里可清空，清空就直接放进所选目录）。 */
    fun defaultFolder(today: LocalDate = LocalDate.now()): String =
        "%d-%02d 导出".format(today.year, today.monthValue)
}
