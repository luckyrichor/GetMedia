package com.getmedia.app.ui.screen

import androidx.compose.runtime.Composable

/**
 * 调试包：与正式包一致，不显示「重置云端恢复决策」。
 *
 * 这个入口只在预览包里出现（`src/preview`），免得开发时顺手点到、把本机的
 * 恢复决策清掉。debug/release 都要有一份同签名实现，否则 `main` 里的
 * `MyScreen` 在这些变体下编译不过。
 */
@Composable
internal fun RestoreResetSection(account: String?) = Unit
