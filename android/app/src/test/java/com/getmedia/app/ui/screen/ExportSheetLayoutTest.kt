package com.getmedia.app.ui.screen

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.export.ExportTrack
import com.getmedia.app.ui.theme.GetMediaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream

/**
 * 导出确认页在 **320 dp 窄屏 + 字体 200%** 下的版式回归（节点 C 补做 9.2 第 14 项）。
 *
 * 为什么不用模拟器：这种组合要改系统字号、改分辨率、再截图，手工做一遍容易漏；
 * Robolectric 能一次跑完亮暗两版，并把结果存成 PNG 留在仓库里做前后对比。
 *
 * 断言的三件事：开始按钮**可见、可点、高度 ≥ 48 dp**——200% 字体最容易把它挤出屏幕或压成一条缝。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportSheetLayoutTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val tracks = listOf(
        ExportTrack(id = 1, title = "深夜电台里的一首很长的歌名用来撑满整行", audioFormat = "m4a", audioBytes = 12_000_000, localPath = "/tmp/a.m4a"),
        ExportTrack(id = 2, title = "短音频", audioFormat = "mp3", audioBytes = 3_000_000, localPath = "/tmp/b.mp3"),
        ExportTrack(id = 3, title = "视频稿件", audioFormat = "mp4", mediaType = "VIDEO", audioBytes = 88_000_000, localPath = "/tmp/c.mp4"),
    )

    private fun forceFontScale(scale: Float) {
        val config = Configuration(rule.activity.resources.configuration).apply { fontScale = scale }
        rule.activity.resources.updateConfiguration(config, rule.activity.resources.displayMetrics)
    }

    private fun render(dark: Boolean) {
        val density = rule.activity.resources.displayMetrics.density
        // 双保险：Activity 的 Configuration 与 Compose 的 LocalDensity 都调到 200%
        forceFontScale(2f)
        rule.setContent {
            GetMediaTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        ExportSheet(
                            tracks = tracks,
                            onDismiss = {},
                            preview = ExportPreview(
                                tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FGetMedia"),
                                available = -1L, // provider 查不到额度：走"无法确认剩余空间"那一态
                                rootNames = setOf("2026-11 导出"),
                                leftovers = 3,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Dialog 是独立窗口，截的是 Dialog 的 decorView 而不是 Activity 的。 */
    private fun saveShot(name: String) {
        runCatching {
            val view = ShadowDialog.getLatestDialog()?.window?.decorView
                ?: rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File("build/reports/export-shots").apply { mkdirs() }
            FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("[shot] $name ${view.width}x${view.height}")
        }.onFailure { println("[shot] $name 截图失败：$it") }
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun startButtonStaysReachableAt200PercentLight() {
        render(dark = false)

        rule.onNodeWithText("开始导出").assertIsDisplayed()
        rule.onNodeWithText("开始导出").assertIsEnabled()
        rule.onNodeWithText("开始导出").assertHasClickAction()
        rule.onNodeWithText("开始导出").assertHeightIsAtLeast(48.dp)
        // 提示在滚动区里：320 dp + 200% 字体时一屏放不下，滚过去再看
        rule.onNodeWithText("无法确认剩余空间", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("上次导出留下 3 个临时文件", substring = true).performScrollTo().assertIsDisplayed()
        // 截图放在滚动之后：要拍到的是"提示都在 + 开始按钮在屏幕里"
        saveShot("export-c-confirm-320dp-200-light")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun startButtonStaysReachableAt200PercentDark() {
        render(dark = true)

        rule.onNodeWithText("开始导出").assertIsDisplayed()
        rule.onNodeWithText("开始导出").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("无法确认剩余空间", substring = true).performScrollTo().assertIsDisplayed()
        saveShot("export-c-confirm-320dp-200-dark")
    }
}
