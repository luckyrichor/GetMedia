package com.getmedia.app.ui.screen

import androidx.activity.ComponentActivity
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.library.LibrarySyncPrefs
import com.getmedia.app.data.library.LibraryUploadPrompt
import com.getmedia.app.data.library.SyncUploadDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LibraryUploadPromptTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun render(initial: SyncUploadDecision): LibrarySyncPrefs {
        val prefs = LibrarySyncPrefs(rule.activity)
        rawPrefs().edit().clear().commit()
        if (initial != SyncUploadDecision.NONE) prefs.setDecision("owner", initial)
        val prompt = LibraryUploadPrompt()
        rule.setContent {
            val ask by prompt.state.collectAsState()
            Column {
                LibrarySyncControls(enabled = true, count = 1, onToggle = {}, onRequest = {
                    prompt.request("owner", 1, manual = true)
                })
                ask?.let { request ->
                    UploadAskDialog(request, onUpload = {}, onDecline = {
                        prompt.decide(request, prefs, SyncUploadDecision.DECLINED)
                    }, onDismiss = { prompt.dismiss(request) })
                }
            }
        }
        return prefs
    }

    private fun rawPrefs() = rule.activity.getSharedPreferences("library-cloud-sync", Context.MODE_PRIVATE)

    @Test fun backLeavesDecisionAtNone() {
        val prefs = render(SyncUploadDecision.NONE)
        rule.onNodeWithText("上传本机新增（1 条）").performClick()
        rule.onNodeWithText("上传本机新增").assertIsDisplayed()
        rule.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        rule.onNodeWithText("上传本机新增").assertDoesNotExist()
        assertEquals(SyncUploadDecision.NONE, prefs.decision("owner"))
        assertFalse(rawPrefs().contains("owner.decision"))
    }

    @Test fun declinedCanOpenAgainFromSettings() {
        val prefs = render(SyncUploadDecision.DECLINED)
        rule.onNodeWithText("上传本机新增（1 条）").performClick()
        rule.onNodeWithText("上传本机新增").assertIsDisplayed()
        assertEquals(SyncUploadDecision.DECLINED, prefs.decision("owner"))
    }

    @Test fun explicitDeclineRecordsDecision() {
        val prefs = render(SyncUploadDecision.NONE)
        rule.onNodeWithText("上传本机新增（1 条）").performClick()
        rule.onNodeWithText("暂不上传").performClick()
        rule.onNodeWithText("上传本机新增").assertDoesNotExist()
        assertEquals(SyncUploadDecision.DECLINED, prefs.decision("owner"))
    }
}
