package com.getmedia.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.download.DownloadProgress
import com.getmedia.app.ui.theme.GetMediaTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * 条目行里的下载进度（[DownloadLine]）的渲染回归。
 *
 * 用户的原话是"不知道它到底是在下载、报错了还是怎么样"——所以这里钉的是**看得见**：
 * 百分比与字节数要真的画出来，别的条目上不能凭空多出一行。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadLineRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun tearDown() = DownloadProgress.finish()

    private fun render(trackId: Long) {
        rule.setContent {
            GetMediaTheme {
                Column(Modifier.padding(12.dp)) { DownloadLine(trackId) }
            }
        }
    }

    @Test
    fun showsPercentageAndBytesForTheTrackBeingDownloaded() {
        DownloadProgress.start(trackId = 7, title = "老友记第五季第14集")
        DownloadProgress.update(7, 81_223_192, 162_446_384)
        render(7)

        rule.onNodeWithText("正在下载").assertIsDisplayed()
        rule.onNodeWithText("50% · 77.5 MB / 154.9 MB", substring = true).assertIsDisplayed()
    }

    @Test
    fun totalLengthUnknownStillShowsSomething() {
        DownloadProgress.start(trackId = 7, title = "x")
        DownloadProgress.update(7, 3_145_728, null)
        render(7)

        // 没有总长度就不编百分比，但"已下载多少"要有
        rule.onNodeWithText("正在下载").assertIsDisplayed()
        rule.onNodeWithText("3.0 MB", substring = true).assertIsDisplayed()
    }

    @Test
    fun otherRowsGetNoExtraLine() {
        DownloadProgress.start(trackId = 7, title = "x")
        DownloadProgress.update(7, 1_000, 2_000)
        render(8)

        rule.onNodeWithText("正在下载").assertDoesNotExist()
    }

    @Test
    fun cancelIsOfferedOnlyWhenTheDownloadCanActuallyBeStopped() {
        // 没有取消回调（发起方拿不到自己的 Job）：不画一个点了没反应的按钮
        DownloadProgress.start(trackId = 7, title = "x")
        DownloadProgress.update(7, 1_000, 2_000)
        render(7)
        rule.onNodeWithText("取消").assertDoesNotExist()
    }

    @Test
    fun cancelIsVisibleAndFiresTheRegisteredCallback() {
        var fired = 0
        DownloadProgress.start(trackId = 7, title = "x") { fired++ }
        DownloadProgress.update(7, 1_000, 2_000)
        render(7)

        rule.onNodeWithText("取消").assertIsDisplayed().performClick()
        assertEquals(1, fired)
        // 取消取走回调，按钮随即消失——不能留一个还能再点的"取消"
        rule.onNodeWithText("取消").assertDoesNotExist()
    }
}
