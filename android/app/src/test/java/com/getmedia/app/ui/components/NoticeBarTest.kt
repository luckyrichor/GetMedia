package com.getmedia.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 深色提示条：右上角的「×」要真的能把提示关掉，而不是只画一个图标。
 *
 * 用户的原话是"每次都要停留两三秒，会遮挡画面"，所以这里断言的是
 * 点完 × 之后 [SnackbarHostState.currentSnackbarData] 立刻变 null（提示真的没了）。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoticeBarTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(sdk = [33], qualifiers = "w393dp-h851dp-xhdpi")
    fun dismissClosesTheNoticeImmediately() {
        val state = SnackbarHostState()
        lateinit var scope: CoroutineScope
        var result: SnackbarResult? = null
        rule.setContent {
            MaterialTheme {
                scope = rememberCoroutineScope()
                NoticeHost(state)
            }
        }
        rule.runOnIdle { scope.launch { result = state.showSnackbar("已开启视频画面") } }
        rule.onNodeWithText("已开启视频画面").assertIsDisplayed()

        rule.onNodeWithTag(NoticeBarTags.DISMISS).assertIsDisplayed()
        rule.onNodeWithTag(NoticeBarTags.DISMISS).performClick()
        rule.waitForIdle()
        rule.runOnIdle { }

        assertNull("点「×」后提示应立刻消失", state.currentSnackbarData)
        assertEquals(SnackbarResult.Dismissed, result)
        rule.onNodeWithText("已开启视频画面").assertDoesNotExist()
    }

    /** 截图存证：浅色、深色各一张，确认「×」在右上角、文字没被压住。 */
    @Test
    @Config(sdk = [33], qualifiers = "w393dp-h851dp-xhdpi")
    fun noticeBarScreenshots() {
        val state = SnackbarHostState()
        var dark by mutableStateOf(false)
        lateinit var scope: CoroutineScope
        rule.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                // 模拟真实页面的位置：贴底、有背景色，否则截图看不出深色下的边界。
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    scope = rememberCoroutineScope()
                    NoticeHost(state, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                }
            }
        }
        rule.runOnIdle { scope.launch { state.showSnackbar("已关闭视频画面（只播声音）") } }
        rule.waitForIdle()
        saveShot("notice-light")
        rule.onNodeWithTag(NoticeBarTags.DISMISS).performClick()
        rule.waitForIdle()
        dark = true
        rule.waitForIdle()
        rule.runOnIdle { scope.launch { state.showSnackbar("按文件大小：大→小") } }
        rule.waitForIdle()
        saveShot("notice-dark")
    }

    private fun saveShot(name: String) {
        runCatching {
            val dir = File("build/reports/notice-shots").apply { mkdirs() }
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("[shot] $name 截图失败：$it") }
    }

    /** 没有回调时（纯展示）不画「×」：避免出现点了没反应的按钮。 */
    @Test
    @Config(sdk = [33], qualifiers = "w393dp-h851dp-xhdpi")
    fun barWithoutCallbackHasNoDismissButton() {
        rule.setContent { MaterialTheme { NoticeBar("按文件大小：大→小") } }
        rule.onNodeWithText("按文件大小：大→小").assertIsDisplayed()
        rule.onNodeWithTag(NoticeBarTags.DISMISS).assertDoesNotExist()
    }
}
