package com.getmedia.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.getmedia.app.account.*
import com.getmedia.app.ui.components.InlineBanner
import com.getmedia.app.ui.components.SectionCard
import com.getmedia.app.ui.components.SectionTitle
import com.getmedia.app.ui.components.uiMuted
import com.getmedia.app.ui.components.uiSolidPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 把一次联网操作包起来：忙碌态、统一错误文案；取消不当作错误。 */
@Stable
internal class Action {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var failure by mutableStateOf<Throwable?>(null)
        private set

    suspend fun run(block: suspend () -> Unit): Boolean {
        if (busy) return false
        busy = true; error = null; failure = null
        return try {
            block(); true
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            failure = e; error = AccountErrors.userMessage(e); false
        } finally {
            busy = false
        }
    }
}

@Composable
private fun Field(
    value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false,
    enabled: Boolean = true, singleLine: Boolean = true, keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = singleLine, enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun ErrorLine(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
}

private fun formatTime(sec: Long): String =
    if (sec <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(sec * 1000))

// ------------------------------------------------------------------ 登录 / 注册 / 找回

private enum class AuthMode(val label: String) { LOGIN("登录"), REGISTER("注册"), RECOVER("找回密码") }

@Composable
internal fun AuthPage(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val action = remember { Action() }
    var mode by remember { mutableStateOf(AuthMode.LOGIN) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    var legal by remember { mutableStateOf<LegalBundle?>(null) }
    var config by remember { mutableStateOf<ServerConfig?>(null) }
    var showLegal by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var newRecoveryCode by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        legal = AccountService.legal()
        config = runCatching { AccountService.api.config() }.getOrNull()
    }

    newRecoveryCode?.let { code ->
        RecoveryCodeView(code, "密码已重置，所有设备已退出", "请保存好新的恢复码；旧恢复码已作废。保存后用新密码登录。") {
            newRecoveryCode = null; mode = AuthMode.LOGIN; password = ""
        }
        return
    }

    fun submit(evict: String? = null) {
        scope.launch {
            val local = when (mode) {
                AuthMode.LOGIN -> null
                else -> AccountRules.checkLoginName(name)
            } ?: if (mode != AuthMode.LOGIN) AccountRules.checkPassword(password, name) else null
            if (local != null) { action.error = local; return@launch }
            val ok = action.run {
                when (mode) {
                    AuthMode.LOGIN -> AccountService.login(name.trim(), password, evict)
                    AuthMode.REGISTER -> AccountService.register(name.trim(), password, invite.ifBlank { null }, requireNotNull(legal))
                    AuthMode.RECOVER -> newRecoveryCode = AccountService.recover(name.trim(), recoveryCode, password)
                }
            }
            if (ok && mode != AuthMode.RECOVER) onDone()
            if (!ok) devices = action.failure?.let(AccountErrors::deviceLimit)
        }
    }

    Column(Modifier.fillMaxSize().background0().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(mode.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("账号只用于联系作者、同步与后续的会员能力；本机媒体库不需要登录也能用。", style = MaterialTheme.typography.bodySmall, color = uiMuted(), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        TabRow(selectedTabIndex = mode.ordinal) {
            AuthMode.entries.forEach { m -> Tab(selected = mode == m, onClick = { mode = m; action.error = null; devices = null }, text = { Text(m.label) }) }
        }
        Spacer(Modifier.height(12.dp))
        config?.maintenance?.let { InlineBanner(it) }
        Field(name, { name = it }, "登录名", enabled = !action.busy)
        if (mode == AuthMode.RECOVER) Field(recoveryCode, { recoveryCode = it }, "恢复码", enabled = !action.busy)
        Field(password, { password = it }, if (mode == AuthMode.RECOVER) "新密码" else "密码", secret = true, enabled = !action.busy)
        if (mode == AuthMode.REGISTER) {
            if (config?.registrationOpen == false) Text("当前暂停注册。", color = MaterialTheme.colorScheme.error)
            if (config?.inviteRequired == true) Field(invite, { invite = it }, "邀请码", enabled = !action.busy)
            Text("登录名 3–32 位英文字母、数字、下划线或连字符；密码至少 8 位。请记好登录名，找回密码需要它。", style = MaterialTheme.typography.bodySmall, color = uiMuted())
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Checkbox(agreed, { agreed = it }, enabled = !action.busy)
                Text("我已年满 18 周岁，并已阅读、同意《用户协议》和《隐私政策》", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = { showLegal = true }) { Text("查看协议全文") }
        }
        ErrorLine(action.error)
        val canSubmit = !action.busy && name.isNotBlank() && password.isNotEmpty() &&
            (mode != AuthMode.REGISTER || (agreed && legal != null && config?.registrationOpen != false)) &&
            (mode != AuthMode.RECOVER || recoveryCode.isNotBlank())
        Button(onClick = { submit() }, enabled = canSubmit, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            if (action.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(mode.label)
        }
        TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("暂不登录，继续使用本机功能") }
    }

    devices?.let { list ->
        AlertDialog(
            onDismissRequest = { devices = null },
            title = { Text("登录设备已达上限") },
            text = {
                Column {
                    Text("选择一台设备退出后，本机即可登录：")
                    list.forEach { d ->
                        TextButton(onClick = { devices = null; submit(d.id) }) { Text("${d.device.ifBlank { d.channel }} · 最近 ${formatTime(d.lastUsedAt)}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { devices = null }) { Text("取消") } },
        )
    }
    if (showLegal) LegalDialog(legal) { showLegal = false }
}

/** 页面底色（不透明，盖住背景图）。 */
@Composable
private fun Modifier.background0(): Modifier = this.then(Modifier.background(uiSolidPage()))

// ------------------------------------------------------------------ 协议

@Composable
internal fun LegalDialog(bundle: LegalBundle?, close: () -> Unit) {
    var which by remember { mutableStateOf(0) }
    val doc = bundle?.let { if (which == 0) it.userAgreement else it.privacyPolicy }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (which == 0) "用户协议" else "隐私政策") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Row { TextButton(onClick = { which = 0 }) { Text("用户协议") }; TextButton(onClick = { which = 1 }) { Text("隐私政策") } }
                if (doc == null) Text("协议加载中…") else {
                    Text("版本 v${doc.version} · 生效 ${doc.effectiveDate}", style = MaterialTheme.typography.labelSmall, color = uiMuted())
                    Column(Modifier.verticalScroll(rememberScrollState())) { Text(doc.text, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("关闭") } },
    )
}

// ------------------------------------------------------------------ 全屏拦截：恢复码确认 / 重新同意

@Composable
private fun RecoveryCodeView(code: String, title: String, hint: String, onConfirmed: () -> Unit) {
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background0().padding(20.dp), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = uiMuted(), modifier = Modifier.padding(vertical = 12.dp))
        SectionCard {
            Text(AccountRules.groupRecoveryCode(code), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp))
        }
        Text("恢复码只显示这一次，忘记密码时只能靠它找回账号。请抄写或截图保存到安全的地方。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(saved, { saved = it }); Text("我已保存恢复码")
        }
        Button(onClick = onConfirmed, enabled = saved, modifier = Modifier.fillMaxWidth()) { Text("继续") }
    }
}

@Composable
internal fun AccountGates() {
    val session by SessionStore.current.collectAsState()
    val scope = rememberCoroutineScope()
    val action = remember { Action() }
    val s = session ?: return
    val pending = s.pendingRecoveryCode
    if (pending != null) {
        Box(Modifier.fillMaxSize().background0()) {
            Column {
                RecoveryCodeView(pending, "请保存恢复码", "这是你找回账号的唯一凭据。") {
                    scope.launch { action.run { AccountService.ackRecovery() } }
                }
            }
            action.error?.let { Box(Modifier.align(Alignment.BottomCenter).padding(24.dp)) { ErrorLine(it) } }
        }
        return
    }
    if (s.consentRequired) ConsentPage(action)
}

@Composable
private fun ConsentPage(action: Action) {
    val scope = rememberCoroutineScope()
    var legal by remember { mutableStateOf<LegalBundle?>(null) }
    var show by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { legal = AccountService.legal() }
    Column(Modifier.fillMaxSize().background0().padding(20.dp), verticalArrangement = Arrangement.Center) {
        Text("协议已更新", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("用户协议或隐私政策有新版本。请阅读并同意后继续使用账号功能；不同意可退出登录，本机功能不受影响。", style = MaterialTheme.typography.bodyMedium, color = uiMuted(), modifier = Modifier.padding(vertical = 12.dp))
        TextButton(onClick = { show = true }) { Text("查看协议全文") }
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(agreed, { agreed = it }); Text("我已阅读并同意", style = MaterialTheme.typography.bodyMedium) }
        ErrorLine(action.error)
        Button(onClick = { scope.launch { action.run { AccountService.consent(requireNotNull(legal)) } } }, enabled = agreed && legal != null && !action.busy, modifier = Modifier.fillMaxWidth()) { Text("同意并继续") }
        TextButton(onClick = { scope.launch { AccountService.logout() } }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("不同意，退出登录") }
    }
    if (show) LegalDialog(legal) { show = false }
}

// ------------------------------------------------------------------ 账号与安全

@Composable
internal fun AccountPage(onLoggedOut: () -> Unit) {
    val session by SessionStore.current.collectAsState()
    val s = session
    val scope = rememberCoroutineScope()
    val load = remember { Action() }
    val act = remember { Action() }
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var tier by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var legal by remember { mutableStateOf<LegalBundle?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        load.run {
            AccountService.authed {
                tier = AccountService.api.me().tier
                devices = AccountService.api.devices()
            }
        }
    }
    LaunchedEffect(s?.token) { if (s != null) reload() }
    if (s == null) { LaunchedEffect(Unit) { onLoggedOut() }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionTitle("账号")
        SectionCard {
            Column(Modifier.padding(16.dp)) {
                Text(s.loginName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (tier == "free" || tier == null) "免费版账号" else "账号等级：$tier", style = MaterialTheme.typography.bodySmall, color = uiMuted())
            }
        }
        if (!s.recoveryConfirmed) InlineBanner("这个账号的恢复码尚未确认保存。当前版本无法在登录状态下重新生成恢复码，请务必记好密码。")
        ErrorLine(load.error)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
        ErrorLine(act.error)

        SectionTitle("已登录设备")
        SectionCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (devices.isEmpty()) Text(if (load.busy) "加载中…" else "暂无数据", color = uiMuted())
                devices.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.device.ifBlank { d.channel } + if (d.current) "（本机）" else "", style = MaterialTheme.typography.bodyMedium)
                            Text("最近使用 ${formatTime(d.lastUsedAt)} · v${d.appVersion}", style = MaterialTheme.typography.labelSmall, color = uiMuted())
                        }
                        if (!d.current) TextButton(enabled = !act.busy, onClick = {
                            scope.launch { if (act.run { AccountService.authed { AccountService.api.revokeDevice(d.id) } }) reload() }
                        }) { Text("退出") }
                    }
                }
                if (devices.count { !it.current } > 0) TextButton(enabled = !act.busy, onClick = {
                    scope.launch {
                        if (act.run { AccountService.authed { AccountService.api.revokeOthers() } }) { notice = "已让其他设备退出登录"; reload() }
                    }
                }) { Text("让其他设备全部退出") }
            }
        }

        SectionTitle("安全")
        SectionCard {
            Column {
                TextButton(onClick = { act.error = null; dialog = "password" }) { Text("修改密码") }
                TextButton(onClick = { scope.launch { if (legal == null) legal = AccountService.legal(); dialog = "legal" } }) { Text("用户协议与隐私政策") }
                TextButton(onClick = { scope.launch { AccountService.logout(); onLoggedOut() } }) { Text("退出登录") }
                TextButton(onClick = { act.error = null; dialog = "delete" }) { Text("注销账号", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    when (dialog) {
        "password" -> PasswordDialog(s.loginName, act, close = { dialog = null }) { notice = "密码已修改，其他设备已退出登录" }
        "delete" -> DeleteDialog(act, close = { dialog = null }) { onLoggedOut() }
        "legal" -> LegalDialog(legal) { dialog = null }
    }
}

@Composable
private fun PasswordDialog(loginName: String, act: Action, close: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!act.busy) close() },
        title = { Text("修改密码") },
        text = {
            Column {
                Field(old, { old = it }, "当前密码", secret = true)
                Field(new, { new = it }, "新密码（至少 8 位）", secret = true)
                ErrorLine(act.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !act.busy && old.isNotEmpty() && new.isNotEmpty(), onClick = {
                val local = AccountRules.checkPassword(new, loginName)
                if (local != null) { act.error = local; return@TextButton }
                scope.launch { if (act.run { AccountService.authed { AccountService.api.changePassword(old, new) } }) { onDone(); close() } }
            }) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = close, enabled = !act.busy) { Text("取消") } },
    )
}

@Composable
private fun DeleteDialog(act: Action, close: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!act.busy) close() },
        title = { Text("注销账号") },
        text = {
            Column {
                Text("注销后账号立即停用并退出所有设备，7 天后账号数据（含你提交的反馈文字与图片）会被物理删除；本机媒体库不受影响。冷静期内如需恢复，请联系作者。")
                Field(password, { password = it }, "输入密码确认", secret = true)
                ErrorLine(act.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !act.busy && password.isNotEmpty(), onClick = {
                scope.launch {
                    if (act.run { AccountService.authed { AccountService.api.deleteAccount(password) } }) { SessionStore.clear(); close(); onDeleted() }
                }
            }) { Text("确认注销", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = close, enabled = !act.busy) { Text("取消") } },
    )
}

// ------------------------------------------------------------------ 联系作者

private val categories = listOf("bug" to "问题", "suggestion" to "建议", "account" to "账号", "other" to "其他")

@Composable
internal fun ContactPage(needLogin: () -> Unit) {
    val session by SessionStore.current.collectAsState()
    val scope = rememberCoroutineScope()
    val action = remember { Action() }
    var text by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("suggestion") }
    var contact by rememberSaveable { mutableStateOf("") }
    var withDiag by rememberSaveable { mutableStateOf(true) }
    // 同一条反馈重试时保持同一个幂等键，服务端据此去重；成功后换新。
    var key by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString().replace("-", "")) }
    var sent by remember { mutableStateOf(false) }

    if (session == null) {
        Column(Modifier.fillMaxSize().padding(vertical = 24.dp)) {
            Text("联系作者需要先登录，这样可以限制滥用并在需要时回复你。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = needLogin, modifier = Modifier.padding(top = 16.dp)) { Text("去登录 / 注册") }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        if (sent) InlineBanner("已收到，谢谢你的反馈。")
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { (id, label) -> FilterChip(selected = category == id, onClick = { category = id }, label = { Text(label) }) }
        }
        OutlinedTextField(
            value = text, onValueChange = { if (it.length <= 2000) text = it }, label = { Text("想对作者说的话（${text.length}/2000）") },
            minLines = 5, modifier = Modifier.fillMaxWidth(),
        )
        Field(contact, { if (it.length <= 200) contact = it }, "联系方式（选填，只作为正文的一部分）")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(withDiag, { withDiag = it })
            Text("  附带基本环境信息（版本、系统、机型）", style = MaterialTheme.typography.bodySmall)
        }
        Text("不会附带账号密码、会话或你保存的任何链接与媒体。", style = MaterialTheme.typography.labelSmall, color = uiMuted())
        ErrorLine(action.error)
        Button(
            enabled = !action.busy && text.isNotBlank(),
            onClick = {
                scope.launch {
                    val diag = if (withDiag) AccountService.clientInfo().let {
                        org.json.JSONObject().put("app_version", it.appVersion).put("os", it.os).put("device", it.device).put("channel", "android")
                            .put("locale", Locale.getDefault().toLanguageTag()).toString()
                    } else null
                    val ok = action.run { AccountService.authed { AccountService.api.feedback(text.trim(), category, contact.ifBlank { null }, diag, key) } }
                    if (ok) { sent = true; text = ""; key = java.util.UUID.randomUUID().toString().replace("-", "") }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { if (action.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("发送") }
    }
}

// ------------------------------------------------------------------ 占位入口

@Composable
internal fun PlaceholderPage() {
    var open by remember { mutableStateOf<com.getmedia.app.placeholder.Placeholder?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionTitle("更多能力")
        SectionCard {
            Column {
                com.getmedia.app.placeholder.PlaceholderCatalog.items.forEach { p ->
                    TextButton(onClick = { open = p }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(p.title, style = MaterialTheme.typography.bodyLarge)
                            Text(p.summary, style = MaterialTheme.typography.bodySmall, color = uiMuted())
                        }
                    }
                }
            }
        }
    }
    open?.let { p ->
        AlertDialog(onDismissRequest = { open = null }, title = { Text(p.title) }, text = { Text("暂未开放。") }, confirmButton = { TextButton(onClick = { open = null }) { Text("知道了") } })
    }
}
