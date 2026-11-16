package com.getmedia.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** 未开放功能的统一提示。免费版用它替代真实页面，保证产物里没有重型实现。 */
@Composable
fun NotAvailableDialog(title: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text("暂未开放。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}
