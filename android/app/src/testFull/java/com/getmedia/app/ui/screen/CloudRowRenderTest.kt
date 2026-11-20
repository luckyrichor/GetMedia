package com.getmedia.app.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.library.LibraryMark
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.ui.theme.GetMediaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 阶段 3 硬性验收 ① 的截图证据：库列表里的「云端未下载」标记。
 *
 * 跑在 JVM（Robolectric）上，直接渲染真实的 `LibraryAudioRow`——不是另画一个示意卡片，
 * 所以标签的位置、字号、颜色与真机一致。两种主题各留一张图（要求里点名要亮/暗两张）。
 *
 * **真机截图未拍**：拍它得先有两台手机同时在线且装了带同步的版本，而阶段 3 的发布闸门
 * （服务端 `POST /v2/tracks` 部署 + 隐私说明 v3 生效）还没过，本轮不装手机。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CloudRowRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun cloudRow(state: String = LibraryMark.CLOUD_ONLY, localPath: String? = null) = TrackEntity(
        id = -1001L,
        platform = "bilibili",
        videoId = "BV1xx411c7mD",
        title = "云端上的一条作品",
        uploader = "某UP",
        durationSec = 245.0,
        audioBytes = null,
        audioFormat = "",
        sourceUrl = "https://www.bilibili.com/video/BV1xx411c7mD?p=1",
        localPath = localPath,
        addedAtMs = 1_700_000_000_000,
        mediaType = MediaKind.AUDIO.name,
        serverId = 41L,
        serverSeq = 88L,
        syncState = state,
        accountKey = "boss",
    )

    private fun render(track: TrackEntity, dark: Boolean) {
        rule.setContent {
            GetMediaTheme(darkTheme = dark) {
                Column(
                    Modifier.width(360.dp).fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 12.dp),
                ) {
                    LibraryAudioRow(
                        track = track, onPlay = {}, onMore = {},
                        number = 1, longPressSelects = false,
                    )
                }
            }
        }
    }

    private fun saveShot(name: String) {
        runCatching {
            val dir = File("build/reports/cloud-row-shots").apply { mkdirs() }
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("[shot] $name 截图失败：$it") }
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun cloudRowLight() {
        render(cloudRow(), dark = false)
        rule.onNodeWithText("云端未下载").assertIsDisplayed()
        saveShot("cloud-row-light")
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun cloudRowDark() {
        render(cloudRow(), dark = true)
        rule.onNodeWithText("云端未下载").assertIsDisplayed()
        saveShot("cloud-row-dark")
    }

    /** 下载完成后同一行不再带标记（避免"下完了还标着没下载"）。 */
    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun syncedRowHasNoBadge() {
        val file = File(rule.activity.cacheDir, "synced-${System.nanoTime()}.audio").apply {
            writeBytes(ByteArray(1024) { 1 })
        }
        render(cloudRow(state = SyncState.SYNCED.name, localPath = file.absolutePath), dark = false)
        rule.onNodeWithText("云端未下载").assertDoesNotExist()
        file.delete()
    }

    /**
     * 0.1.16 回归：回填之后即使同步状态还没来得及更新（仍是 `CLOUD_ONLY`），
     * 只要磁盘上文件真的在，标签就必须消失——判定以磁盘为准，与条目菜单同口径。
     */
    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun refilledRowLosesBadgeEvenWithStaleState() {
        val file = File(rule.activity.cacheDir, "refilled-${System.nanoTime()}.audio").apply {
            writeBytes(ByteArray(4096) { 7 })
        }
        render(cloudRow(state = LibraryMark.CLOUD_ONLY, localPath = file.absolutePath), dark = false)
        rule.onNodeWithText("云端未下载").assertDoesNotExist()
        file.delete()
    }

    /**
     * 反过来也要成立：字段说有文件、磁盘上却真的没了，仍然要提示"云端未下载"，
     * 不能因为字段乐观就不给用户指路。
     */
    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun missingFileStillShowsBadge() {
        render(
            cloudRow(state = SyncState.SYNCED.name, localPath = "/nonexistent/definitely-missing.audio"),
            dark = false,
        )
        rule.onNodeWithText("云端未下载").assertIsDisplayed()
    }
}
