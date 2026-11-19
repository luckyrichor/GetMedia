package com.getmedia.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import com.getmedia.app.player.PlayerHolder
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.getmedia.app.ui.components.BackgroundLayer
import com.getmedia.app.ui.components.LocalBackgroundEnabled
import com.getmedia.app.ui.components.LocalCardAlpha
import com.getmedia.app.ui.screen.ExtractScreen
import com.getmedia.app.ui.screen.LibraryScreenV2
import com.getmedia.app.ui.screen.MyScreen
import com.getmedia.app.ui.screen.QueueBar
import com.getmedia.app.ui.theme.GetMediaTheme

private enum class Tab(val label: String, val icon: ImageVector) {
    LIBRARY("媒体库", Icons.Default.LibraryMusic),
    EXTRACT("提取", Icons.Default.Link),
    MY("我的", Icons.Default.Person),
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.getmedia.app.data.repo.BackgroundStore.init(this)
        setContent {
            GetMediaTheme {
                val background by com.getmedia.app.data.repo.BackgroundStore.state.collectAsState()
                // 从 B站/抖音「分享到 GetMedia」进来时直接落在提取页
                var shared by remember { mutableStateOf(sharedText(intent)) }
                var tab by remember { mutableStateOf(if (shared != null) Tab.EXTRACT else Tab.LIBRARY) }
                var openCategories by remember { mutableStateOf(false) }

                // 服务器提取失败后点"去登录"：把底部导航切到"我的"，登录页由 MyScreen 自己打开。
                LaunchedEffect(com.getmedia.app.ui.LoginRequest.pending) {
                    if (com.getmedia.app.ui.LoginRequest.pending) tab = Tab.MY
                }

                DisposableEffect(Unit) {
                    val listener = { newIntent: Intent ->
                        sharedText(newIntent)?.let { shared = it; tab = Tab.EXTRACT }
                        Unit
                    }
                    addOnNewIntentListener(listener)
                    onDispose { removeOnNewIntentListener(listener) }
                }

                // 背景图铺在最底层，页面自己让出底色，卡片保持不透明以保证可读性。
                CompositionLocalProvider(
                    LocalBackgroundEnabled provides background.enabled,
                    LocalCardAlpha provides background.cardAlpha,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        BackgroundLayer()
                        Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        Column {
                            if (tab == Tab.LIBRARY) QueueBar()
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                            )
                            NavigationBar(
                                containerColor = if (background.enabled) {
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
                                } else {
                                    NavigationBarDefaults.containerColor
                                },
                            ) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t; if (t == Tab.LIBRARY) openCategories = false },
                                    icon = { Icon(t.icon, contentDescription = t.label) },
                                    label = { Text(t.label) },
                                )
                            }
                            }
                        }
                    }
                ) { padding ->
                    Surface(Modifier.fillMaxSize().padding(padding), color = Color.Transparent) {
                        Box(Modifier.fillMaxSize()) {
                            when (tab) {
                                // 空库的「去提取」直接把底部导航切到提取页，不用用户自己找。
                                Tab.LIBRARY -> LibraryScreenV2(openCategories, onGoExtract = {
                                    openCategories = false
                                    tab = Tab.EXTRACT
                                })
                                Tab.EXTRACT -> ExtractScreen(prefill = shared, onSharedConsumed = { shared = null })
                                Tab.MY -> MyScreen(
                                    openAuth = com.getmedia.app.ui.LoginRequest.pending,
                                    onAuthOpened = { com.getmedia.app.ui.LoginRequest.consume() },
                                )
                            }
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.Transparent,
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                                MaterialTheme.colorScheme.surface,
                                            )
                                        )
                                    )
                            )
                        }
                    }
                }
                        // 首次登录告知（设计 §8.3）：不是新的同意项，只是一次明确告知，每账号一次。
                        val backupState by com.getmedia.app.data.backup.BackupManager.state.collectAsState()
                        if (backupState.noticePending) {
                            AlertDialog(
                                onDismissRequest = { com.getmedia.app.data.backup.BackupManager.ackNotice() },
                                title = { Text("曲库清单会备份到服务器") },
                                text = {
                                    Text(
                                        "为便于重装或换机后恢复，GetMedia 会把你的曲库清单（标题、博主、类别、播放列表顺序等，" +
                                            "不含音频文件和登录凭据）压缩后保存在服务器，每账号保留最近 3 份。" +
                                            "可随时在「我的 → 数据备份」关闭，或删除云端备份。",
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = { com.getmedia.app.data.backup.BackupManager.ackNotice() }) {
                                        Text("知道了")
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        com.getmedia.app.data.backup.BackupManager.ackNotice()
                                        tab = Tab.MY
                                    }) { Text("去设置") }
                                },
                            )
                        }
                        com.getmedia.app.ui.screen.AccountGates()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 曲库清单备份：进前台 5 秒后算一次指纹，之后每 10 分钟一次（设计 §5.3）。
        com.getmedia.app.data.backup.BackupManager.onForeground()
        lifecycleScope.launch {
            // 联网失败一律静默：账号状态对一次、每天一次心跳，都不影响本地功能。
            try {
                com.getmedia.app.account.AccountService.refresh()
                com.getmedia.app.account.AccountService.heartbeat()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {}
        }
        lifecycleScope.launch {
            try { PlayerHolder.restore(applicationContext) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                com.getmedia.app.data.repo.DiagnosticsStore.event("恢复播放", "播放状态恢复失败，可手动选择音频", false)
            }
        }
    }

    override fun onStop() {
        // 用户改完就退出时也补一次检查；失败只落状态，不打扰。
        com.getmedia.app.data.backup.BackupManager.onBackground()
        PlayerHolder.savePlayback(applicationContext, synchronous = true)
        super.onStop()
    }

    private fun sharedText(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        } else null
}
