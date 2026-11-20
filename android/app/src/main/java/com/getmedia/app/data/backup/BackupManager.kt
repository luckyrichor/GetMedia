package com.getmedia.app.data.backup

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.getmedia.app.BuildConfig
import com.getmedia.app.account.ApiException
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.cloud.cloudSync
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.localextract.DirectExtractor
import com.getmedia.app.data.repo.DiagnosticsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 曲库清单备份的编排（设计 §5.3）：什么时候算指纹、什么时候传、失败怎么落状态。
 *
 * 触发时机一共五个，全部收敛到 [runCheck]：
 * - 进入前台 5 秒后；
 * - 退到后台时（用户改完就退出，不必等下次启动）；
 * - 前台停留每 10 分钟；
 * - 关键写操作后 30 秒防抖（[noteWrite]）；
 * - 登录成功后由会话变化触发一次（[publish] 之后前台检查会带上它）。
 *
 * 本地先按 10 分钟窗口拦一道，正常路径不会发出必被 429 的请求；服务端那道闸防的是
 * 多设备与改系统时间。任何失败都不弹窗，只落状态（设置页常驻显示）。
 */
// 单例持的是 applicationContext（不随 Activity 销毁），lint 的 StaticFieldLeak 在这里是误报。
@SuppressLint("StaticFieldLeak")
object BackupManager {
    data class UiState(
        val loggedIn: Boolean = false,
        val enabled: Boolean = true,
        val lastAtMs: Long = 0,
        val lastTracks: Int = 0,
        val lastId: String? = null,
        val dirty: Boolean = false,
        val blocked: String? = null,
        val unsupported: Boolean = false,
        val busy: Boolean = false,
        /** 被服务端限频到什么时候（ms）；>0 且未过期时状态行显示"刚备份过"。 */
        val throttleUntilMs: Long = 0,
        /** 首次登录的告知还没弹过。 */
        val noticePending: Boolean = false,
        /** 本地库为空时点了「立即备份」：等用户确认"空清单也要传"。 */
        val emptyConfirmPending: Boolean = false,
        /**
         * 云端有内容备份、用户还没决定是否恢复。为真时**自动备份暂停**，
         * 设置页常驻一条提示与「去处理」入口（见 [BackupCard]）。
         */
        val restoreDecisionPending: Boolean = false,
        /** 该弹决策弹窗了。用户点「稍后」之后仍 pending，只是不再弹（入口还在横幅上）。 */
        val restoreAskVisible: Boolean = false,
        /** 云端那份最新备份的条数与时间，弹窗与横幅要显示。 */
        val restoreCloudTracks: Int = 0,
        val restoreCloudAtMs: Long = 0,
    )

    private var context: Context? = null
    private var prefs: BackupPrefs? = null
    private var api: BackupTransport? = null

    /** 单测里替掉真实会话与网络；生产环境保持 null，一律走 [SessionStore] 与 [BackupApi]。 */
    @VisibleForTesting internal var sessionOverride: SessionStore.Session? = null
    @VisibleForTesting internal var transportOverride: BackupTransport? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var debounce: Job? = null
    private var redownload: RedownloadRunner? = null

    /** 登录后那一次检查（[onSessionSettled]）。 */
    private var loginCheck: Job? = null

    /** 上一次看到的登录名；同一账号的 token 刷新不该重跑登录检查。 */
    private var lastSessionLogin: String? = null

    /** 决策闸的结论。暂停与"本轮先不传"在界面上不一样，所以分开。 */
    private enum class Decision { Allow, Paused, Deferred }

    /**
     * 决策查询的会话内缓存。**不落盘**：查询失败时下一次进前台要重查，
     * 所以 [onForeground] 会清掉它；落盘会让一次网络抖动变成永久状态。
     */
    private var decisionLogin: String? = null
    private var decisionOutcome: Decision = Decision.Allow
    private val decisionLock = AtomicBoolean(false)

    /** 云端最新的那份有内容的备份：等用户选「恢复 / 用本机内容 / 稍后」。 */
    private var pendingCloudBackup: BackupItem? = null

    /** 用户点过「稍后」：横幅还在，弹窗先不弹。只在本会话内有效。 */
    private var decisionSnoozed: Boolean = false

    /** 上一次发布状态时的账号；换账号要清掉内存里的决策，避免串到别人身上。 */
    private var publishedLogin: String? = null

    fun initialize(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        prefs = BackupPrefs(app)
        api = transportOverride ?: BackupApi()
        scope.launch {
            SessionStore.current.collect { session ->
                publish()
                // 登录（或换账号）本身就是一次触发点：前台那条 5 秒检查在没登录时早就
                // 返回了，之后要等 10 分钟周期才会再跑一遍，恢复决策框就会迟迟不出现
                // （全新安装后"库被同步拉满、却没弹过恢复框"就是这么来的，0.1.13 修）。
                onSessionSettled(session)
            }
        }
    }

    /**
     * 登录名变化后补一次检查。同一账号的 token 刷新不重跑。
     *
     * 只在**登录名变化**时排程：换账号要各自走一遍自己的决策；同一账号反复触发没有意义。
     * 给单测也留了入口（生产由 [initialize] 里的会话收集调用）。
     */
    @VisibleForTesting
    internal fun onSessionSettled(session: SessionStore.Session?) {
        val login = session?.loginName
        if (login == lastSessionLogin) return
        lastSessionLogin = login
        loginCheck?.cancel(); loginCheck = null
        if (session == null || session.consentRequired) return
        loginCheck = scope.launch {
            delay(BackupRules.LOGIN_CHECK_DELAY_MS)
            runCheck("登录后")
        }
    }

    /** 只给单测：清掉 context/prefs/api 与在跑的定时任务，让下一个用例重新初始化。 */
    @VisibleForTesting
    internal fun resetForTest() {
        ticker?.cancel(); ticker = null
        debounce?.cancel(); debounce = null
        loginCheck?.cancel(); loginCheck = null
        lastSessionLogin = null
        context = null; prefs = null; api = null; redownload = null
        sessionOverride = null; transportOverride = null
        decisionLogin = null; decisionOutcome = Decision.Allow
        pendingCloudBackup = null; decisionSnoozed = false; publishedLogin = null
        _state.value = UiState()
    }

    /** 只给单测：会话换成另一个账号时，真实环境靠 [SessionStore.current] 的收集回调走到 [publish]。 */
    @VisibleForTesting
    internal fun publishForTest() = publish()

    private fun sessionNow(): SessionStore.Session? = sessionOverride ?: SessionStore.current.value

    /**
     * 五个触发点的共同前门（设计 §5.3）：未登录、待重新同意、用户关掉、被服务端停用，
     * 四种情况都在**排协程之前**返回，因此这些状态下一次网络请求都不会发生——
     * [com.getmedia.app.data.backup.BackupGateTest] 用记账的假实现盯住这一点。
     */
    private fun gate(): SessionStore.Session? {
        val p = prefs ?: return null
        val session = sessionNow() ?: return null
        if (session.consentRequired) return null
        if (!BackupRules.shouldRun(p.read(session.loginName))) return null
        return session
    }

    // ---------- 生命周期 ----------

    fun onForeground() {
        // 上一次查询失败（网络）时既没传也没落决策，进前台重新查一次。
        decisionLogin = null
        if (gate() == null) return
        scope.launch {
            delay(BackupRules.FOREGROUND_DELAY_MS)
            runCheck("进入前台")
        }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(BackupRules.TICK_MS)
                runCheck("前台周期")
            }
        }
    }

    fun onBackground() {
        ticker?.cancel(); ticker = null
        debounce?.cancel(); debounce = null
        if (gate() == null) return
        scope.launch { runCheck("退到后台") }
    }

    /** 关键写操作后调用：30 秒防抖，一次连续编辑只算一次。 */
    fun noteWrite() {
        if (gate() == null) {
            debounce?.cancel(); debounce = null
            return
        }
        debounce?.cancel()
        debounce = scope.launch {
            delay(BackupRules.DEBOUNCE_MS)
            runCheck("写操作后")
        }
    }

    // ---------- 用户动作 ----------

    fun setEnabled(enabled: Boolean) {
        val p = prefs ?: return
        val login = sessionNow()?.loginName ?: return
        p.update(login) { it.copy(enabled = enabled) }
        if (!enabled) { debounce?.cancel(); debounce = null }
        publish()
        if (enabled) scope.launch { runCheck("用户开启") }
    }

    /**
     * "立即备份"：跳过"指纹没变"与 10 分钟窗口，用户明确要求就传一份新的。
     *
     * 本地一首都没有时**不直接传**：空清单会占掉云端 3 份里的一份，可能把有内容的备份
     * 挤掉。改成先置 [UiState.emptyConfirmPending]，由界面弹确认；确认后走
     * [confirmEmptyBackup]（`allowEmpty = true`）。
     */
    fun backupNow() {
        if (gate() == null) return
        scope.launch {
            if (localContentCount() == 0) {
                _state.value = _state.value.copy(emptyConfirmPending = true)
                return@launch
            }
            runCheck("立即备份", force = true)
        }
    }

    /** 用户在确认框里点了"仍要上传"：空库这次放行。 */
    fun confirmEmptyBackup() {
        _state.value = _state.value.copy(emptyConfirmPending = false)
        if (gate() == null) return
        scope.launch { runCheck("立即备份（空库已确认）", force = true, allowEmpty = true) }
    }

    fun cancelEmptyConfirm() {
        _state.value = _state.value.copy(emptyConfirmPending = false)
    }

    /**
     * 本机**有内容**的条目数——与快照同一个口径（[MetadataSnapshot.keepInSnapshot]）。
     *
     * 不能数 `allNow().size`：同步会把"云端未下载"的行也写进 `tracks`，登录后本机一行
     * 文件都没有时总行数却是几十。用总行数会让"空清单要先确认"这条静默失效——
     * 既不弹确认、`runCheck` 里又按空清单拦掉上传，用户点「立即备份」什么都看不到
     * （0.1.13 修）。这里与 `runCheck` 的 `snapshot.counts.tracks == 0` 必须是同一口径。
     */
    private suspend fun localContentCount(): Int {
        val c = context ?: return 0
        return runCatching {
            val db = AppDatabase.get(c)
            val items = db.playlistDao().itemsAll()
            db.trackDao().allNow().count { MetadataSnapshot.keepInSnapshot(it, items) }
        }.getOrDefault(0)
    }

    /**
     * 删掉云端的一份备份。误传的空清单只能靠这个清掉——
     * 「删除云端备份」是全部删除，代价太大。
     *
     * 删的若正是"上次备份"（`lastId`），顺手把 id 清空，设置页才不会显示云端还有东西；
     * `lastHash` 不动：同 [deleteCloud]，用户刚删掉的东西不该在下一个窗口被自动传回来。
     */
    fun deleteOne(backupId: String, onDone: (Result<Boolean>) -> Unit) {
        val api = api ?: return onDone(Result.failure(IllegalStateException("未初始化")))
        val p = prefs ?: return onDone(Result.failure(IllegalStateException("未初始化")))
        val login = sessionNow()?.loginName
            ?: return onDone(Result.failure(IllegalStateException("未登录")))
        scope.launch {
            val result = runCatching { api.delete(backupId) }
            result.onSuccess {
                p.update(login) { s -> if (s.lastId == backupId) s.copy(lastId = null) else s }
                // 删掉的正是"等用户决定要不要恢复"的那份：弹窗与横幅不能再指着它。
                if (pendingCloudBackup?.id == backupId) {
                    clearPendingDecision()
                    decisionLogin = null
                }
                publish()
            }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    fun deleteCloud(onDone: (Result<Int>) -> Unit) {
        val api = api ?: return
        val p = prefs ?: return
        val login = sessionNow()?.loginName ?: return
        scope.launch {
            val result = runCatching { api.deleteAll() }
            result.onSuccess {
                // 只清 id，不动 last_hash：用户刚删掉的东西不该在下一个窗口被自动传回来。
                p.update(login) { s -> s.copy(lastId = null) }
                // 云端都删空了，"要不要恢复"这个问题也就不存在了；下次检查会自行落成"以本机为准"。
                clearPendingDecision()
                decisionLogin = null
                publish()
            }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    fun ackNotice() {
        val p = prefs ?: return
        val login = sessionNow()?.loginName ?: return
        p.update(login) { it.copy(noticeShown = true) }
        publish()
    }

    // ---------- 登录后的恢复决策 ----------

    /**
     * 云端有备份、用户还没说要不要恢复时，自动备份先停。
     *
     * 只挡"本地为空"不够：重装后用户先随手加了一两首，本机 2 首、云端 128 首，
     * 指纹一变就会上传；服务端每账号只留 3 份，几轮下来有内容的备份全被"一两首"挤掉。
     * 所以第一次登录（该账号还没决策过）先问一句，用户选了才继续。
     *
     * 三种结论：
     * - [Decision.Allow]：不用问（云端没有有内容的备份 / 已经决策过 / 服务端没这套接口），照常往下走；
     * - [Decision.Paused]：云端有内容备份，等用户选（上传暂停，但界面照常可用）；
     * - [Decision.Deferred]：这一轮查不动（网络失败、并发），**本轮不上传也不落决策**，下次进前台再查。
     *
     * 只在用户已同意隐私说明之后调用——`consentRequired` 时 [gate] 早就拦住了。
     */
    private suspend fun resolveRestoreDecision(login: String, manual: Boolean): Decision {
        val p = prefs ?: return Decision.Allow
        val api = api ?: return Decision.Allow

        // 用户点「立即备份」= 明确要用本机这份，不再问。是决策，不是绕过。
        if (manual) {
            p.update(login) { it.copy(restoreDecision = BackupPrefs.RESTORE_KEEP_LOCAL) }
            clearPendingDecision()
            DiagnosticsStore.event("数据备份", "用户点「立即备份」，按本机内容继续，不再询问恢复", true)
            return Decision.Allow
        }

        pendingCloudBackup?.let { return Decision.Paused }
        if (decisionLogin == login) return decisionOutcome
        // 另一个检查正在查：这一轮先不传，等它落下结论。
        if (!decisionLock.compareAndSet(false, true)) return Decision.Deferred

        return try {
            val items = api.list()
            val newest = items.filter { it.tracks > 0 }.maxByOrNull { it.createdAt }
            decisionLogin = login
            if (newest != null) {
                pendingCloudBackup = newest
                decisionSnoozed = false
                decisionOutcome = Decision.Paused
                DiagnosticsStore.event(
                    "数据备份",
                    "云端有 ${newest.tracks} 首的备份，等用户决定恢复还是以本机为准；自动备份暂停",
                    true,
                )
                Decision.Paused
            } else {
                // 云端没有可恢复的东西（空列表，或只有空备份）：不必打扰用户。
                p.update(login) { it.copy(restoreDecision = BackupPrefs.RESTORE_KEEP_LOCAL) }
                clearPendingDecision()
                decisionOutcome = Decision.Allow
                Decision.Allow
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            decisionLogin = login
            if (error is ApiException && error.status == 404) {
                // 服务端还没有这套接口：按既有规则静默（上传侧同样落到 unsupported），
                // 不弹窗、不落决策，部署后自愈。
                p.update(login) { it.copy(unsupported = true) }
                decisionOutcome = Decision.Allow
                Decision.Allow
            } else {
                // 网络或服务端故障：保守处理，这一轮不上传、不落决策。
                decisionOutcome = Decision.Deferred
                DiagnosticsStore.event(
                    "数据备份",
                    "读取云端备份失败（${error.message ?: "网络不可用"}），本轮不上传，下次进前台再查",
                    false,
                )
                Decision.Deferred
            }
        } finally {
            decisionLock.set(false)
        }
    }

    /** 用户选「用本机内容」：以本机为准，立刻解禁并传一份，让云端反映这次决定。 */
    fun keepLocalNow() {
        val p = prefs ?: return
        val login = sessionNow()?.loginName ?: return
        p.update(login) { it.copy(restoreDecision = BackupPrefs.RESTORE_KEEP_LOCAL) }
        clearPendingDecision()
        decisionLogin = login
        decisionOutcome = Decision.Allow
        DiagnosticsStore.event("数据备份", "用户选择以本机为准，自动备份恢复", true)
        publish()
        if (gate() == null) return
        scope.launch { runCheck("恢复决策：用本机内容", force = true) }
    }

    /** 用户选「稍后」：弹窗收起，但决策没做，自动备份仍然暂停（横幅上有「去处理」）。 */
    fun snoozeRestoreDecision() {
        decisionSnoozed = true
        publish()
    }

    /** 横幅上的「去处理」：把弹窗叫回来。 */
    fun showRestoreDecision() {
        decisionSnoozed = false
        publish()
    }

    private fun clearPendingDecision() {
        pendingCloudBackup = null
        decisionSnoozed = false
    }

    // ---------- 恢复 ----------

    /** 云端有哪些备份。失败直接交给调用方显示，不弹窗、不写状态。 */
    suspend fun listCloud(): Result<List<BackupItem>> {
        val api = api ?: return Result.failure(IllegalStateException("未初始化"))
        if (sessionNow() == null) return Result.failure(IllegalStateException("未登录"))
        return runCatching { api.list() }
    }

    /** 只比较备份中的条目主键，不恢复、不改备份决策。 */
    suspend fun previewRestore(backupId: String): Result<RestoreService.Preview> {
        val api = api ?: return Result.failure(IllegalStateException("未初始化"))
        val c = context ?: return Result.failure(IllegalStateException("未初始化"))
        val session = sessionNow() ?: return Result.failure(IllegalStateException("未登录"))
        return runCatching {
            val payload = api.download(backupId)
            val snapshot = SnapshotReader.read(payload.gzip, payload.sha256)
            val preview = RestoreService(AppDatabase.get(c)).preview(snapshot)
            check(isSameSession(session)) { "账号已变化，请重新打开备份" }
            preview
        }
    }

    private fun isSameSession(startedAs: SessionStore.Session): Boolean =
        sessionNow()?.let { it.loginName == startedAs.loginName && it.token == startedAs.token } == true

    /**
     * 下载 + 校验 + 合并恢复。整个过程是一个事务：校验不过就中止，本地库一个字节都不动。
     * 恢复完标脏——合并后的清单和上次上传的不一样，下一个窗口该传一次。
     */
    suspend fun restoreNow(backupId: String): Result<RestoreService.Result> {
        val api = api ?: return Result.failure(IllegalStateException("未初始化"))
        val c = context ?: return Result.failure(IllegalStateException("未初始化"))
        val session = sessionNow() ?: return Result.failure(IllegalStateException("未登录"))
        val result = runCatching {
            val payload = api.download(backupId)
            val snapshot = SnapshotReader.read(payload.gzip, payload.sha256)
            val result = RestoreService(AppDatabase.get(c)).restore(
                snapshot,
                account = session.loginName.takeIf { cloudSync.managesRestores(session) },
                valid = { isSameSession(session) },
            )
            // 恢复成功即决策：以云端那份为准，自动备份随即解禁（同设置页入口与新弹窗）。
            prefs?.update(session.loginName) {
                it.copy(dirty = true, restoreDecision = BackupPrefs.RESTORE_RESTORED)
            }
            clearPendingDecision()
            decisionLogin = session.loginName
            decisionOutcome = Decision.Allow
            DiagnosticsStore.event(
                "数据备份",
                "已恢复：新增 ${result.addedTracks} 首，跳过 ${result.skippedTracks} 首（本地已有）",
                true,
            )
            result
        }
        // 决策与「暂停」标志都变了，界面要立刻知道（横幅与弹窗都靠它）。
        result.getOrNull()?.let {
            publish()
            if (it.addedTracks > 0) cloudSync.onBackupRestored(c)
        }
        return result
    }

    fun usesManagedRestore(): Boolean = cloudSync.managesRestores(sessionNow())

    /** "全部重新下载"的进度源；第一次用时才建（避免启动时就打开数据库）。 */
    fun redownloadState(): StateFlow<RedownloadRunner.State>? = runner()?.state

    suspend fun pendingDownloadIds(): List<Long> = runner()?.pendingIds().orEmpty()

    fun startRedownload(ids: List<Long>) {
        runner()?.start(ids)
    }

    fun resumeRedownload() {
        runner()?.resume()
    }

    fun stopRedownload() {
        runner()?.stop()
    }

    private fun runner(): RedownloadRunner? {
        val c = context ?: return null
        return redownload ?: RedownloadRunner(
            AppDatabase.get(c),
            extract = { url, kind -> DirectExtractor(c).extract(url, kind) },
            canRedownload = { track -> !cloudSync.protectsRecovery(track, sessionNow()) },
            // 阶段 6：批量重下也走统一下载入口，云端行不再被无条件拉去本机直提取。
            downloadRow = { track -> cloudSync.download(c, track) },
        ).also { redownload = it }
    }

    // ---------- 核心 ----------

    private suspend fun runCheck(trigger: String, force: Boolean = false, allowEmpty: Boolean = false) {
        val p = prefs ?: return
        val c = context ?: return
        val api = api ?: return
        val session = sessionNow() ?: return publish()
        // 新版协议还没重新同意：先不上传，等用户看过条款。
        if (session.consentRequired) return

        // 登录后先决定"云端那份要不要恢复"，再谈上传（设计 §5.3 最后一行）。
        // 用户没选之前一律不传：本机零星几首会把云端有内容的备份挤掉。
        val before = p.read(session.loginName)
        if (!before.enabled || before.blocked != null) return
        if (before.restoreDecision == BackupPrefs.RESTORE_UNKNOWN) {
            when (resolveRestoreDecision(session.loginName, manual = force)) {
                Decision.Paused, Decision.Deferred -> return publish()
                Decision.Allow -> Unit
            }
        }

        if (!busy.compareAndSet(false, true)) return

        try {
            // 决策可能刚落地（例如上面查完发现云端是空的），重新读一次。
            val stored = p.read(session.loginName)
            if (!stored.enabled || stored.blocked != null) return

            val db = AppDatabase.get(c)
            val snapshot = MetadataSnapshot.build(
                tracks = db.trackDao().allNow(),
                categories = db.categoryDao().allNow(),
                playlists = db.playlistDao().allNow(),
                items = db.playlistDao().itemsAll(),
                appVersion = BuildConfig.VERSION_NAME,
                nowSec = System.currentTimeMillis() / 1000,
            )

            // 本地一首都没有：不上传，也不动 lastHash。
            //
            // 空清单本身是合法快照，但它没有任何恢复价值，却会占掉云端 3 份里的一份
            // （够了 3 份就把最旧的挤掉）。新设备 / 重装后先登录的场景正好是空库，
            // 这里必须挡住。用户明确点「立即备份」并确认后才走 allowEmpty。
            //
            // 口径：`counts.tracks` 是**过滤后**的条数（[MetadataSnapshot.keepInSnapshot]），
            // 不含"云端未下载且没有任何整理痕迹"的行——同步拉下来的纯云端行不算本机内容。
            // 界面上「立即备份」的确认用的是同一个口径（[localContentCount]）。
            if (!allowEmpty && snapshot.counts.tracks == 0) return

            if (!BackupRules.shouldUpload(stored, snapshot.fingerprint, System.currentTimeMillis(), force)) {
                // 变过了但还没到窗口：先标脏，跨启动也能记住"欠一次"
                if (snapshot.fingerprint != stored.lastHash && !stored.dirty) {
                    p.update(session.loginName) { it.copy(dirty = true) }
                }
                return
            }

            _state.value = _state.value.copy(busy = true)
            try {
                val uploaded = api.upload(
                    snapshot.gzip,
                    BuildConfig.VERSION_NAME,
                    android.os.Build.MODEL.orEmpty(),
                )
                p.update(session.loginName) {
                    it.copy(
                        lastHash = snapshot.fingerprint,
                        lastAtMs = System.currentTimeMillis(),
                        lastId = uploaded.id,
                        lastTracks = snapshot.counts.tracks,
                        dirty = false,
                        blocked = null,
                        unsupported = false,
                    )
                }
                DiagnosticsStore.event(
                    "数据备份",
                    "已上传 ${snapshot.counts.tracks} 首 / ${snapshot.counts.playlists} 个列表（${snapshot.gzip.size} 字节，$trigger）",
                    true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                when (val outcome = BackupOutcome.of(error)) {
                    BackupOutcome.TooLarge ->
                        p.update(session.loginName) { it.copy(blocked = BackupPrefs.BLOCKED_TOO_LARGE, dirty = false) }

                    is BackupOutcome.Invalid -> {
                        p.update(session.loginName) { it.copy(blocked = BackupPrefs.BLOCKED_INVALID, dirty = false) }
                        // 422 必须留下原因，否则只能靠复现抓包才能知道哪条数据不合规
                        DiagnosticsStore.event("数据备份", "422：${outcome.detail}", false)
                    }

                    // 服务端还没有这套接口：静默，下次照常再试（部署后自愈）
                    BackupOutcome.Unsupported -> p.update(session.loginName) { it.copy(unsupported = true) }

                    // 限频：不是失败。上一次成功的记录（时间/条数/id）一律不动，
                    // 只记一个"到点之前别再说失败"的时间点，供设置页显示。
                    BackupOutcome.RateLimited -> p.update(session.loginName) {
                        it.copy(throttleUntilMs = System.currentTimeMillis() + BackupRules.WINDOW_MS)
                    }
                    is BackupOutcome.Failed -> p.update(session.loginName) { it.copy(dirty = true) }

                    // 会话失效交给账号模块；已完成不会从这里出来
                    BackupOutcome.Unauthorized -> Unit
                    is BackupOutcome.Done -> Unit
                }
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        } finally {
            busy.set(false)
            publish()
        }
    }

    private fun publish() {
        val p = prefs ?: return
        val session = sessionNow()
        if (session == null) {
            // 退出登录：内存里的决策与"等用户选"的备份一律清掉，不能跟着走到别人身上。
            publishedLogin = null
            clearPendingDecision()
            decisionLogin = null
            decisionOutcome = Decision.Allow
            _state.value = UiState(loggedIn = false, busy = _state.value.busy)
            return
        }
        if (publishedLogin != session.loginName) {
            // 换账号：同一个 App 进程里 A 的待决策不能弹在 B 的界面上。
            publishedLogin = session.loginName
            clearPendingDecision()
            decisionLogin = null
            decisionOutcome = Decision.Allow
        }
        val stored = p.read(session.loginName)
        val now = System.currentTimeMillis()
        // 限频窗口已过就清掉提示，否则状态行会一直停在"刚备份过"。
        val s = if (stored.throttleUntilMs in 1 until now) {
            p.update(session.loginName) { it.copy(throttleUntilMs = 0) }
        } else {
            stored
        }
        // 待重新同意协议时既不问也不显示：恢复决策要在同意之后（同首次登录告知）。
        val pending = pendingCloudBackup.takeIf { !session.consentRequired }
        _state.value = UiState(
            loggedIn = true,
            enabled = s.enabled,
            lastAtMs = s.lastAtMs,
            lastTracks = s.lastTracks,
            lastId = s.lastId,
            dirty = s.dirty,
            blocked = s.blocked,
            unsupported = s.unsupported,
            busy = _state.value.busy,
            throttleUntilMs = s.throttleUntilMs,
            noticePending = !s.noticeShown && !session.consentRequired,
            emptyConfirmPending = _state.value.emptyConfirmPending,
            restoreDecisionPending = pending != null,
            restoreAskVisible = pending != null && !decisionSnoozed,
            restoreCloudTracks = pending?.tracks ?: 0,
            restoreCloudAtMs = normalizeMs(pending?.createdAt ?: 0),
        )
    }

    /** 服务端 `created_at` 是秒；兼容一下已经是毫秒的情况。 */
    private fun normalizeMs(value: Long): Long =
        if (value in 1..9_999_999_999L) value * 1000 else value
}
