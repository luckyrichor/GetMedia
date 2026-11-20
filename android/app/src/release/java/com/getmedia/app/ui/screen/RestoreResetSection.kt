package com.getmedia.app.ui.screen

import androidx.compose.runtime.Composable

/**
 * 正式包：`诊断与反馈` 里没有「重置云端恢复决策」这一项。
 *
 * 真实实现只在 `src/preview`（预览构建）里。这里刻意留空——不画这一行、不画分隔线，
 * 也不要把参数往下传，正式包 APK 里因此不存在该入口的任何代码或文案。
 */
@Composable
internal fun RestoreResetSection(account: String?) = Unit
