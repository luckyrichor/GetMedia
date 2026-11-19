package com.getmedia.app.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.backup.BackupItem
import com.getmedia.app.data.backup.BackupManager
import com.getmedia.app.data.backup.BackupPrefs
import com.getmedia.app.ui.theme.GetMediaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 设置页「数据备份」卡片的渲染回归。
 *
 * 跑在 JVM（Robolectric）上：模拟器里没有 owner 会话，登录态的分支拍不到，
 * 所以这里直接注入 [BackupManager.UiState] 渲染真实组件，把四种状态都留一张图。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupCardRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(state: BackupManager.UiState, dark: Boolean = false) {
        rule.setContent {
            GetMediaTheme(darkTheme = dark) {
                Box(Modifier.width(360.dp).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 12.dp)) {
                        BackupSection(injected = state)
                    }
                }
            }
        }
    }

    private fun saveShot(name: String) {
        runCatching {
            val dir = File("build/reports/backup-shots").apply { mkdirs() }
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("[shot] $name 截图失败：$it") }
    }

    private val normal = BackupManager.UiState(
        loggedIn = true, enabled = true, lastAtMs = 1_763_462_520_000, lastTracks = 128,
        lastId = "bkp_1", dirty = false,
    )

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun normalStateLight() {
        render(normal)
        saveShot("backup-light-normal")
        rule.onNodeWithText("上次备份：128 首", substring = true).assertIsDisplayed()
        rule.onNodeWithText("立即备份").assertIsDisplayed()
        rule.onNodeWithText("删除云端备份").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun normalStateDark() {
        render(normal, dark = true)
        saveShot("backup-dark-normal")
        rule.onNodeWithText("上次备份：128 首", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun blockedTooLargeStaysVisible() {
        render(normal.copy(blocked = BackupPrefs.BLOCKED_TOO_LARGE))
        saveShot("backup-light-blocked")
        rule.onNodeWithText("备份已停用：库太大（超过 512 KiB）").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun loggedOutShowsHint() {
        render(BackupManager.UiState(loggedIn = false))
        saveShot("backup-light-loggedout")
        rule.onNodeWithText("登录后可用备份").assertIsDisplayed()
    }

    // ---------- 恢复：选一份备份 ----------

    private val cloud = listOf(
        BackupItem("bkp_new", 41_000, 128, 1_763_462_520, "Pixel 8"),
        BackupItem("bkp_old", 39_500, 121, 1_762_000_000, "Redmi K70"),
    )

    private fun renderPicker(dark: Boolean) {
        rule.setContent {
            GetMediaTheme(darkTheme = dark) {
                Box(Modifier.width(360.dp).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    androidx.compose.material3.Surface(
                        Modifier.padding(16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("选择要恢复的备份", style = MaterialTheme.typography.titleLarge)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                            BackupPickerContent(cloud, cloud.first()) {}
                        }
                    }
                }
            }
        }
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun pickerLight() {
        renderPicker(dark = false)
        saveShot("restore-light-picker")
        rule.onNodeWithText("128 首", substring = true).assertIsDisplayed()
        rule.onNodeWithText("恢复只补", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun pickerDark() {
        renderPicker(dark = true)
        saveShot("restore-dark-picker")
        rule.onNodeWithText("128 首", substring = true).assertIsDisplayed()
    }
}
