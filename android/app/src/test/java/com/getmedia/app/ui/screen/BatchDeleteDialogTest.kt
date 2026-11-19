package com.getmedia.app.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.ui.theme.GetMediaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 批量删除弹窗的文案回归。
 *
 * 背景：确认页曾经把标题写死成「删除 N 个音频？」，在「视频」分类下删 5 个视频也照念音频。
 * 现在按选中的条目取词，这里把三种选择（纯音频 / 纯视频 / 混选）逐个钉住。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BatchDeleteDialogTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(count: Int, kinds: List<String?>, deleting: Boolean = false) {
        rule.setContent {
            GetMediaTheme {
                BatchDeleteDialog(
                    count = count,
                    kinds = kinds,
                    deleting = deleting,
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun videoSelectionSaysVideo() {
        render(count = 2, kinds = listOf("VIDEO", "VIDEO"))

        rule.onNodeWithText("删除 2 个视频？").assertIsDisplayed()
        rule.onNodeWithText("视频文件", substring = true).assertIsDisplayed()
        // 整页不许再出现「音频」二字（标题、说明、按钮都算）
        rule.onAllNodesWithText("音频", substring = true).assertCountEquals(0)
    }

    @Test
    fun audioSelectionSaysAudio() {
        render(count = 3, kinds = listOf("AUDIO", "AUDIO", "AUDIO"))

        rule.onNodeWithText("删除 3 个音频？").assertIsDisplayed()
        rule.onNodeWithText("音频文件", substring = true).assertIsDisplayed()
    }

    @Test
    fun mixedSelectionSaysItem() {
        render(count = 2, kinds = listOf("AUDIO", "VIDEO"))

        rule.onNodeWithText("删除 2 项？").assertIsDisplayed()
        rule.onNodeWithText("视频文件", substring = true).assertDoesNotExist()
        rule.onNodeWithText("音频文件", substring = true).assertDoesNotExist()
    }

    @Test
    fun deletingDisablesTheConfirmButton() {
        render(count = 2, kinds = listOf("VIDEO", "VIDEO"), deleting = true)

        rule.onNodeWithText("删除中…").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("取消").assertIsNotEnabled()
    }
}
