package com.getmedia.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 页面底部的深色提示条（就是那个"黑色小方块"）的统一实现。
 *
 * 和 Material3 默认 [SnackbarHost] 的区别只有一个：右上角多一个「×」，
 * 可以立刻关掉。默认提示条要等 2–4 秒自己消失，横在播放画面上很碍事。
 *
 * 颜色刻意写死成深色（两种主题下都是深色），因为界面里一直管它叫"黑色提示框"；
 * 深色主题下再加一条细描边，否则黑条会糊进深色背景里分不清边界。
 */
private val NoticeContainer = Color(0xF2201E26)
private val NoticeContainerDark = Color(0xF22E2B36)
private val NoticeContent = Color(0xFFF3F0F8)

/** 提示条的语义标记，渲染回归用例用它们定位。 */
object NoticeBarTags {
    const val BAR = "notice-bar"
    const val DISMISS = "notice-dismiss"
}

/** 替换 Scaffold 的 `snackbarHost = { SnackbarHost(state) }`，行为一致，多了右上角的「×」。 */
@Composable
fun NoticeHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        NoticeBar(text = data.visuals.message, onDismiss = { data.dismiss() })
    }
}

/**
 * 单条提示。[onDismiss] 为 null 时不显示「×」（比如只是显示一句话的场景）。
 */
@Composable
fun NoticeBar(text: String, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(14.dp)
    val dark = uiIsDark()
    Box(
        modifier
            // Scaffold 的 snackbarHost 不给外边距（M3 的默认提示条是自己带 12dp 的），
            // 所以这里自己留：否则提示条会贴着屏幕左右边和底部，圆角都被切掉。
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .widthIn(max = 560.dp)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(if (dark) NoticeContainerDark else NoticeContainer)
            .then(
                if (dark) Modifier.border(1.dp, Color(0x33FFFFFF), shape) else Modifier,
            )
            .testTag(NoticeBarTags.BAR),
    ) {
        Text(
            text,
            // 右侧留出「×」的位置，长句子折行也不会压到按钮下面。
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = if (onDismiss != null) 46.dp else 16.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = NoticeContent,
        )
        if (onDismiss != null) {
            // 放在右上角：拇指够得到，也不挡句子的开头。
            Box(
                Modifier.align(Alignment.TopEnd)
                    .size(44.dp)
                    .testTag(NoticeBarTags.DISMISS)
                    .semantics { contentDescription = "关闭提示" }
                    .clickable(role = Role.Button, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Close, null, Modifier.size(16.dp),
                    tint = NoticeContent.copy(alpha = 0.72f),
                )
            }
        }
    }
}
