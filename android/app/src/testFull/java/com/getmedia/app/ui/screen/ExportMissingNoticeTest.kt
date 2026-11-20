package com.getmedia.app.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.export.ExportTrack
import com.getmedia.app.ui.theme.GetMediaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportMissingNoticeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirmationShowsCloudAndLostSeparately() {
        rule.setContent {
            GetMediaTheme {
                ExportSheet(
                    tracks = listOf(
                        ExportTrack(id = 1, title = "cloud A", serverId = 11),
                        ExportTrack(id = 2, title = "cloud B", serverId = 12),
                        ExportTrack(id = 3, title = "lost", serverId = 13, localPath = "/missing-getmedia-test.m4a"),
                    ),
                    onDismiss = {},
                )
            }
        }

        val cloudNotice = rule.onNodeWithText("其中 2 项尚未下载到本机，请先下载")
        val lostNotice = rule.onNodeWithText("其中 1 项本机文件缺失，需要重新提取")
        cloudNotice.performScrollTo().assertIsDisplayed()
        lostNotice.performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("跳过这 2 项").performScrollTo().performClick()
        cloudNotice.assertDoesNotExist()
        lostNotice.performScrollTo().assertIsDisplayed()
    }
}
