package com.getmedia.app.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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

    private fun render(
        count: Int,
        kinds: List<String?>,
        deleting: Boolean = false,
        cloudCount: Int = 0,
        archiveCount: Int = 0,
        localOnlyCount: Int = 0,
        onConfirm: (Boolean) -> Unit = {},
    ) {
        rule.setContent {
            GetMediaTheme {
                BatchDeleteDialog(
                    count = count,
                    kinds = kinds,
                    deleting = deleting,
                    cloudCount = cloudCount,
                    archiveCount = archiveCount,
                    localOnlyCount = localOnlyCount,
                    onConfirm = onConfirm,
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
    fun cloudDeletionIsDefaultCheckedAndReported() {
        var confirmed: Boolean? = null
        render(count = 2, kinds = listOf("AUDIO", "AUDIO"), cloudCount = 2,
            archiveCount = 1, localOnlyCount = 1, onConfirm = { confirmed = it })

        rule.onNodeWithText("同时从云端曲库移除（其他设备也会同步到）").assertIsDisplayed()
        rule.onNodeWithText("2 项会同时从云端曲库移除", substring = true).assertIsDisplayed()
        rule.onNodeWithText("1 项云端归档会保留，只删除本机", substring = true).assertIsDisplayed()
        rule.onNodeWithText("1 项仅在本机删除", substring = true).assertIsDisplayed()
        rule.onNodeWithText("删除").performClick()
        assertEquals(true, confirmed)
    }

    @Test
    fun archiveOnlyExplainsCloudKeepsIt() {
        render(count = 1, kinds = listOf("VIDEO"), cloudCount = 0,
            archiveCount = 1, localOnlyCount = 0)
        rule.onNodeWithText("云端归档会保留，只删除本机", substring = true).assertIsDisplayed()
        rule.onNodeWithText("同时从云端曲库移除（其他设备也会同步到）").assertDoesNotExist()
    }

    @Test
    fun deletingDisablesTheConfirmButton() {
        render(count = 2, kinds = listOf("VIDEO", "VIDEO"), deleting = true)

        rule.onNodeWithText("删除中…").assertIsDisplayed().assertIsNotEnabled()
        rule.onNodeWithText("取消").assertIsNotEnabled()
    }
}
