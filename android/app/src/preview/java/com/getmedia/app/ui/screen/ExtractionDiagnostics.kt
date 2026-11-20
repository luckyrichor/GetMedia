package com.getmedia.app.ui.screen

import androidx.compose.runtime.Composable

/**
 * 预览包：与正式包一致，不显示提取诊断面板。
 *
 * 这个函数原本只有 `debug`（真实面板）和 `release`（空）两份实现，靠构建类型源集分版。
 * 新增 `preview` 构建类型后，它也必须有一份，否则 `compileFullPreviewKotlin` 会找不到符号。
 * 行为上向正式包看齐：预览包是"新版本的试装包"，不是调试包。
 */
@Composable
fun ExtractionDiagnostics() = Unit
