package com.getmedia.app.data.backup

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.getmedia.app.BuildConfig
import com.getmedia.app.account.SessionStore
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

    fun initialize(context: Context) {
        if (this.context != null) return
        val app = context.applicationContext
        this.context = app
        prefs = BackupPrefs(app)
        api = transportOverride ?: BackupApi()
        scope.launch { SessionStore.current.collect { publish() } }
    }

    /** 只给单测：清掉 context/prefs/api 与在跑的定时任务，让下一个用例重新初始化。 */
    @VisibleForTesting
    internal fun resetForTest() {
        ticker?.cancel(); ticker = null
        debounce?.cancel(); debounce = null
        context = null; prefs = null; api = null; redownload = null
        sessionOverride = null; transportOverride = null
        _state.value = UiState()
    }

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
            if (localTrackCount() == 0) {
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

    private suspend fun localTrackCount(): Int {
        val c = context ?: return 0
        return runCatching { AppDatabase.get(c).trackDao().allNow().size }.getOrDefault(0)
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

    // ---------- 恢复 ----------

    /** 云端有哪些备份。失败直接交给调用方显示，不弹窗、不写状态。 */
    suspend fun listCloud(): Result<List<BackupItem>> {
        val api = api ?: return Result.failure(IllegalStateException("未初始化"))
        if (sessionNow() == null) return Result.failure(IllegalStateException("未登录"))
        return runCatching { api.list() }
    }

    /**
     * 下载 + 校验 + 合并恢复。整个过程是一个事务：校验不过就中止，本地库一个字节都不动。
     * 恢复完标脏——合并后的清单和上次上传的不一样，下一个窗口该传一次。
     */
    suspend fun restoreNow(backupId: String): Result<RestoreService.Result> {
        val api = api ?: return Result.failure(IllegalStateException("未初始化"))
        val c = context ?: return Result.failure(IllegalStateException("未初始化"))
        val session = sessionNow() ?: return Result.failure(IllegalStateException("未登录"))
        return runCatching {
            val payload = api.download(backupId)
            val snapshot = SnapshotReader.read(payload.gzip, payload.sha256)
            val result = RestoreService(AppDatabase.get(c)).restore(snapshot)
            prefs?.update(session.loginName) { it.copy(dirty = true) }
            DiagnosticsStore.event(
                "数据备份",
                "已恢复：新增 ${result.addedTracks} 首，跳过 ${result.skippedTracks} 首（本地已有）",
                true,
            )
            result
        }
    }

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
        if (!busy.compareAndSet(false, true)) return

        try {
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
            _state.value = UiState(loggedIn = false, busy = _state.value.busy)
            return
        }
        val stored = p.read(session.loginName)
        val now = System.currentTimeMillis()
        // 限频窗口已过就清掉提示，否则状态行会一直停在"刚备份过"。
        val s = if (stored.throttleUntilMs in 1 until now) {
            p.update(session.loginName) { it.copy(throttleUntilMs = 0) }
        } else {
            stored
        }
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
        )
    }
}
