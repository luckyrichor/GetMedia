package com.getmedia.app.data.repo

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 视频画面「显示/不显示」全局偏好的持久化。
 *
 * 语义：默认显示；切到不显示后，重启（重新 init）也要保持不显示，
 * 直到用户再打开 —— 这是「全局开关」跨页面、跨软件切换都生效的前提。
 */
@RunWith(AndroidJUnit4::class)
class VideoDisplayPrefsTest {

    @Test
    fun defaultsToShow() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // 清掉可能的旧值，回到默认。
        VideoDisplayPrefs.setShowVideo(context, VideoDisplayPrefs.DEFAULT_SHOW)
        VideoDisplayPrefs.init(context)
        assertTrue("默认应显示画面", VideoDisplayPrefs.showVideo.value)
    }

    @Test
    fun turnOffPersistsAcrossReinit() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        VideoDisplayPrefs.setShowVideo(context, false)
        assertFalse("关闭后当前值应是不显示", VideoDisplayPrefs.showVideo.value)
        // 模拟重启：重新 init 从持久化读回。
        VideoDisplayPrefs.init(context)
        assertFalse("重新 init 后应仍是不显示", VideoDisplayPrefs.showVideo.value)
    }

    @Test
    fun turnBackOn() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        VideoDisplayPrefs.setShowVideo(context, false)
        VideoDisplayPrefs.setShowVideo(context, true)
        VideoDisplayPrefs.init(context)
        assertEquals("重新打开后应恢复显示", true, VideoDisplayPrefs.showVideo.value)
    }
}
