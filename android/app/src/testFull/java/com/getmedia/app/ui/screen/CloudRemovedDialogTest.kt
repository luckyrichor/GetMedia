package com.getmedia.app.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.cloud.CloudRemovedAsk
import com.getmedia.app.ui.theme.GetMediaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * 「云端已移除 N 项」弹窗的两个按钮在视觉上必须分得出主次（0.1.14）。
 *
 * 之前两个都是 `TextButton`：源码里「保留本机文件」是 `confirmButton`（靠右），
 * 但两键同色同字重、都没有填充色，用户看不出哪个是默认项——真机验收时就是这么记的。
 * 现在主操作是填充底色的 `Button`，这里把主色像素占比钉住：主按钮必须有一块底色，
 * 次要按钮必须一点底色都没有。
 *
 * 用 `decorView.draw(Canvas)` 取像素：`captureToImage` 在 Robolectric 下会卡在
 * forceRedraw 超时（弹窗又是独立窗口，抓不到），这条路线和 `BackupCardRenderTest` 一致。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-xhdpi")
class CloudRemovedDialogTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val ask = CloudRemovedAsk(
        account = "owner1",
        count = 3,
        titles = listOf("这次小棉袄要挨打了", "另一条", "第三条"),
    )

    private var primary: Color = Color.Unspecified

    private fun renderButtons(which: String) {
        rule.setContent {
            GetMediaTheme(darkTheme = false) {
                primary = MaterialTheme.colorScheme.primary
                Column(Modifier.padding(24.dp)) {
                    when (which) {
                        "keep" -> CloudRemovedKeepButton {}
                        else -> CloudRemovedSnoozeButton {}
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun screen(): Bitmap {
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    /** 画面上"主按钮底色"的像素占比。 */
    private fun primaryRatio(): Float {
        val bmp = screen()
        val target = primary
        var hit = 0
        for (x in 0 until bmp.width) {
            for (y in 0 until bmp.height) {
                val c = bmp.getPixel(x, y)
                val r = ((c shr 16) and 0xFF) / 255f
                val g = ((c shr 8) and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                if (abs(r - target.red) < 0.06f && abs(g - target.green) < 0.06f && abs(b - target.blue) < 0.06f) hit++
            }
        }
        return hit.toFloat() / (bmp.width * bmp.height).toFloat()
    }

    private fun saveShot(name: String) {
        runCatching {
            val dir = File("build/reports/cloud-removed-shots").apply { mkdirs() }
            val bmp = screen()
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("[shot] $name 截图失败：$it") }
    }

    @Test
    fun keepButtonIsFilledWithPrimaryColor() {
        renderButtons("keep")
        saveShot("keep-primary.png")
        val ratio = primaryRatio()
        println("[fill] keep=$ratio")
        rule.onNodeWithText("保留本机文件").assertExists()
        assertTrue("主按钮应当有填充底色，实测主色占比 $ratio", ratio > 0.01f)
    }

    @Test
    fun snoozeButtonHasNoFilledBackground() {
        renderButtons("snooze")
        saveShot("snooze-secondary.png")
        val ratio = primaryRatio()
        println("[fill] snooze=$ratio")
        rule.onNodeWithText("稍后处理").assertExists()
        assertTrue("次要按钮不该有填充底色，实测主色占比 $ratio", ratio < 0.001f)
    }

    @Test
    fun dialogKeepsWordingAndBothActions() {
        rule.setContent {
            GetMediaTheme(darkTheme = false) {
                CloudRemovedDialog(ask = ask, onKeep = {}, onDismiss = {})
            }
        }
        rule.onNodeWithText("云端已移除 3 项").assertExists()
        rule.onNodeWithText("这些条目已在网页端删除", substring = true).assertExists()
        rule.onNodeWithText("这次小棉袄要挨打了", substring = true).assertExists()
        rule.onNodeWithText("本机独有", substring = true).assertExists()
        rule.onNodeWithText("保留本机文件").assertExists()
        rule.onNodeWithText("稍后处理").assertExists()
    }

    @Test
    fun clickingEachButtonCallsItsOwnCallback() {
        val kept = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        rule.setContent {
            GetMediaTheme(darkTheme = false) {
                CloudRemovedDialog(
                    ask = ask,
                    onKeep = { kept += "keep" },
                    onDismiss = { dismissed += "dismiss" },
                )
            }
        }

        rule.onNodeWithText("保留本机文件").performClick()
        assertEquals(listOf("keep"), kept)
        assertTrue("点主按钮不该触发稍后处理", dismissed.isEmpty())

        rule.onNodeWithText("稍后处理").performClick()
        assertEquals(listOf("dismiss"), dismissed)
        assertEquals(1, kept.size)
    }
}
