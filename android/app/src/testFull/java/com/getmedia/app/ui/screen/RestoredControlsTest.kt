package com.getmedia.app.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.library.LibrarySyncRunner
import com.getmedia.app.data.library.RestoreMatchIssue
import com.getmedia.app.data.library.RestoreMatchNotice
import com.getmedia.app.ui.theme.GetMediaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
class RestoredControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun conflictOnlyShowsHonestReasonAndOnlyUnmatchedCanRedownload() {
        val requested = mutableListOf<Long>()
        rule.setContent {
            GetMediaTheme {
                RestoredControls(
                    status = LibrarySyncRunner.RestoreStatus(
                        "owner", notices = listOf(
                            RestoreMatchNotice(1, "歧义项", RestoreMatchIssue.AMBIGUOUS),
                            RestoreMatchNotice(2, "待下载项", RestoreMatchIssue.UNMATCHED),
                        ),
                    ),
                    canCheck = true,
                    onCheck = {},
                    onConfirmAccount = {},
                    onRedownload = { requested.add(it) },
                )
            }
        }
        rule.onNodeWithText("这一项在云端有多条可能匹配，已保留原样").assertIsDisplayed()
        rule.onNodeWithText("当前账号云端未找到匹配项，可以重新下载").assertIsDisplayed()
        assertEquals(emptyList<Long>(), requested)
        rule.onNodeWithText("重新下载").performClick()
        assertEquals(listOf(2L), requested)
    }
}
