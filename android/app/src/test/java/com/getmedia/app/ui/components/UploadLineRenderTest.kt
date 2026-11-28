package com.getmedia.app.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.download.DownloadProgress
import com.getmedia.app.data.download.UploadProgress
import com.getmedia.app.ui.theme.GetMediaTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * 条目行里的上传进度（[UploadLine]）的渲染回归。
 *
 * 用户的原话是"从云端下载有进度条了，可是上传没有啊……我看不到它是不是正在上传"。
 * 所以这里钉的是**看得见**：百分比与字节数要真的画出来、别的条目上不能凭空多出一行、
 * 传完要消失、以及和下载可以同时各占一行（两个状态槽位互不覆盖）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UploadLineRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun tearDown() {
        UploadProgress.finish()
        DownloadProgress.finish()
    }

    private fun render(trackId: Long) {
        rule.setContent {
            GetMediaTheme {
                Column(Modifier.padding(12.dp)) { UploadLine(trackId) }
            }
        }
    }

    @Test
    fun showsPercentageAndBytesForTheTrackBeingUploaded() {
        UploadProgress.start(trackId = 7, title = "自己录的一段", total = 162_446_384)
        UploadProgress.update(7, 81_223_192, 162_446_384)
        render(7)

        rule.onNodeWithText("正在上传").assertIsDisplayed()
        rule.onNodeWithText("50% · 77.5 MB / 154.9 MB", substring = true).assertIsDisplayed()
    }

    @Test
    fun firstFrameBeforeAnyByteDoesNotReadAsUnknown() {
        // 上传一开始 done 一定是 0。formatSize(0) 是"-"（列表里表示"未知"），
        // 但这里要表达的是"真的一个字节都还没传"，不能显示成"0% · - / 154.9 MB"
        UploadProgress.start(trackId = 7, title = "x", total = 162_446_384)
        render(7)

        rule.onNodeWithText("0% · 0 B / 154.9 MB", substring = true).assertIsDisplayed()
    }

    @Test
    fun otherRowsGetNoExtraLine() {
        UploadProgress.start(trackId = 7, title = "x", total = 2_000)
        UploadProgress.update(7, 1_000, 2_000)
        render(8)

        rule.onNodeWithText("正在上传").assertDoesNotExist()
    }

    @Test
    fun lineGoesAwayWhenTheUploadEnds() {
        UploadProgress.start(trackId = 7, title = "x", total = 2_000)
        UploadProgress.update(7, 2_000, 2_000)
        render(7)
        rule.onNodeWithText("正在上传").assertIsDisplayed()

        UploadProgress.finish()
        rule.waitForIdle()

        rule.onNodeWithText("正在上传").assertDoesNotExist()
    }

    /**
     * 下载是用户点的、上传是后台自动的，两者随时可能撞在一起。分成两个槽位就是为了
     * 这一刻：两条都要看得见，各自的百分比不能互相盖掉。
     */
    @Test
    fun downloadAndUploadCoexistOnTheSameRow() {
        DownloadProgress.start(trackId = 7, title = "x")
        DownloadProgress.update(7, 1_000, 2_000)
        UploadProgress.start(trackId = 7, title = "x", total = 8_000)
        UploadProgress.update(7, 6_000, 8_000)
        rule.setContent {
            GetMediaTheme {
                Column(Modifier.padding(12.dp)) {
                    DownloadLine(7)
                    UploadLine(7)
                }
            }
        }

        rule.onNodeWithText("正在下载").assertIsDisplayed()
        rule.onNodeWithText("正在上传").assertIsDisplayed()
        rule.onNodeWithText("50%", substring = true).assertIsDisplayed()
        rule.onNodeWithText("75%", substring = true).assertIsDisplayed()
    }
}
