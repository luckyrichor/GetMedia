package com.getmedia.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.repo.DiagnosticsStore
import com.getmedia.app.ui.components.HeroCard
import com.getmedia.app.ui.components.PageColumn
import com.getmedia.app.ui.components.RowDivider
import com.getmedia.app.ui.components.SectionCard
import com.getmedia.app.ui.components.SectionTitle
import com.getmedia.app.ui.components.SettingRow
import com.getmedia.app.ui.components.StatusChip
import com.getmedia.app.ui.components.SubPageScaffold
import com.getmedia.app.ui.components.uiAccentContainer
import com.getmedia.app.ui.components.uiHairline
import com.getmedia.app.ui.components.uiIsDark
import com.getmedia.app.ui.components.uiMuted
import com.getmedia.app.ui.components.uiSuccess
import com.getmedia.app.ui.components.uiSuccessContainer
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MyScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by AppDatabase.get(context).trackDao().observeAll().collectAsState(initial = emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    var showAppearance by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    val session by com.getmedia.app.account.SessionStore.current.collectAsState()
    val placeholders = com.getmedia.app.placeholder.PlaceholderCatalog.items
    val background by com.getmedia.app.data.repo.BackgroundStore.state.collectAsState()
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知" }
    val diagnosticExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = DiagnosticsStore.exportFailures()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: error("无法创建文件")
                }
            }.onFailure { DiagnosticsStore.event("导出诊断日志", it.javaClass.simpleName, false) }
        }
    }
    val bytes = tracks.sumOf { it.localPath?.let { path -> File(path).takeIf(File::exists)?.length() } ?: 0L }
    if (showAppearance) {
        // 系统返回键与左上角箭头走同一条路：回到「我的」，而不是直接退出应用。
        BackHandler { showAppearance = false }
        SubPageScaffold("背景图片", onBack = { showAppearance = false }) { AppearanceScreen() }
        return
    }
    when (page) {
        "auth" -> { BackHandler { page = null }; AuthPage(onDone = { page = null }); return }
        "account" -> { BackHandler { page = null }; SubPageScaffold("账号与安全", onBack = { page = null }) { AccountPage(onLoggedOut = { page = null }) }; return }
        "contact" -> { BackHandler { page = null }; SubPageScaffold("联系作者", onBack = { page = null }) { ContactPage(needLogin = { page = "auth" }) }; return }
        "more" -> { BackHandler { page = null }; SubPageScaffold("更多能力", onBack = { page = null }) { PlaceholderPage() }; return }
    }
    PageColumn(Modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(14.dp))
        Text("我的", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "账号、外观、存储与诊断",
            style = MaterialTheme.typography.bodySmall,
            color = uiMuted(),
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        SectionTitle("账号")
        SectionCard {
            SettingRow(
                Icons.Rounded.Person,
                session?.loginName ?: "登录 / 注册",
                subtitle = if (session == null) "联系作者需要账号；本机功能无需登录" else "账号与安全、已登录设备",
                showChevron = true,
                onClick = { page = if (session == null) "auth" else "account" },
            )
        }
        SectionTitle("外观")
        SectionCard {
            SettingRow(
                Icons.Rounded.Image,
                "背景图片",
                subtitle = if (background.enabled) "已启用" else "用喜欢的图片当页面背景",
                value = if (background.enabled) "已开启" else "默认",
                onClick = { showAppearance = true },
            )
        }

        SectionTitle("存储")
        SectionCard {
            SettingRow(
                Icons.Rounded.Storage,
                "存储空间",
                value = "${bytes / 1024 / 1024} MB",
                subtitle = "${tracks.size} 首音频保存在本机",
                onClick = { dialog = "storage" },
            )
        }

        SectionTitle("诊断与反馈")
        SectionCard {
            SettingRow(Icons.Rounded.Description, "导出诊断日志", onClick = { diagnosticExport.launch("GetMedia-diagnostics.txt") })
            RowDivider()
            SettingRow(Icons.Rounded.ContentCopy, "复制诊断日志", onClick = { scope.launch {
                runCatching {
                    val text = DiagnosticsStore.exportFailures().ifBlank { "暂无失败诊断日志" }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("GetMedia 诊断日志", text))
                }.onSuccess { Toast.makeText(context, "已复制诊断日志", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(context, "复制失败，请重试", Toast.LENGTH_SHORT).show() }
            } })
            RowDivider()
            SettingRow(Icons.Rounded.Feedback, "联系作者", subtitle = "反馈问题或提出建议", showChevron = true, onClick = { page = "contact" })
        }

        if (placeholders.isNotEmpty()) {
            SectionTitle("更多")
            SectionCard {
                SettingRow(Icons.Rounded.Cloud, "更多能力", subtitle = "即将到来的功能", showChevron = true, onClick = { page = "more" })
            }
        }

        SectionTitle("关于")
        SectionCard {
            SettingRow(Icons.Rounded.BugReport, "版本 $version", subtitle = "分享链接提取音频", showChevron = true, onClick = { dialog = "about" })
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "GetMedia · 本机处理优先",
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = uiMuted().copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
    when (dialog) {
        "storage" -> InfoDialog("存储空间", "本机已保存 ${tracks.size} 首音频，约 ${bytes / 1024 / 1024} MB。") { dialog = null }
        "about" -> InfoDialog("GetMedia $version", "分享链接提取音频，分类、播放和管理均可在本机完成。") { dialog = null }
    }
}

@Composable
private fun InfoDialog(title: String, text: String, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(text) }, confirmButton = { AreaButton(onClick = close) { Text("关闭") } })
}
