package com.getmedia.app.data.repo

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 视频画面是否显示的全局偏好。
 *
 * 用户可能只想听视频里的声音、不想看画面。这个开关是**全局**的：一旦设为「不显示」，
 * 无论从哪个页面进来、还是从别的软件切回，视频都只出声不出画面，直到再次打开。
 *
 * 只影响画面渲染，不影响声音与播放进度。默认显示（[DEFAULT_SHOW] = true）。
 */
object VideoDisplayPrefs {
    private const val PREFS = "video-display"
    private const val KEY_SHOW = "show_video"

    /** 默认显示画面。 */
    const val DEFAULT_SHOW = true

    private val _showVideo = MutableStateFlow(DEFAULT_SHOW)
    val showVideo: StateFlow<Boolean> = _showVideo

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 启动时读一次持久化值；没存过时用默认值（显示）。 */
    fun init(context: Context) {
        _showVideo.value = prefs(context).getBoolean(KEY_SHOW, DEFAULT_SHOW)
    }

    fun setShowVideo(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW, show).apply()
        _showVideo.value = show
    }
}
