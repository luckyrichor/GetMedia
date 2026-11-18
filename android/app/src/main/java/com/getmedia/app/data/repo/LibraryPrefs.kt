package com.getmedia.app.data.repo

import android.content.Context
import com.getmedia.app.data.local.MediaKind

/**
 * 媒体库页的界面偏好：上次看的是音频还是视频。
 *
 * 只影响显示，不碰库里的数据；没存过（或值不认识）时按音频，不会出现「一个都没选」的状态。
 * 单独放一个文件、用独立的偏好文件，是为了不跟外观/播放记忆混在一起互相覆盖。
 */
object LibraryPrefs {
    private const val PREFS = "library-ui"
    private const val KEY_KIND = "media_kind"

    /** 上次选中的类型。没记录过时返回 [MediaKind.AUDIO]。 */
    fun kind(context: Context): MediaKind = MediaKind.of(prefs(context).getString(KEY_KIND, null))

    fun setKind(context: Context, kind: MediaKind) {
        prefs(context).edit().putString(KEY_KIND, kind.name).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
