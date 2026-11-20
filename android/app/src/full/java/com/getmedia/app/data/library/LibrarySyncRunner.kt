package com.getmedia.app.data.library

import android.content.Context
import androidx.room.withTransaction
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.cloud.CloudRemovedAsk
import com.getmedia.app.data.cloud.CloudUploadAsk
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LibraryCursorEntity
import com.getmedia.app.data.localextract.DirectExtractor
import com.getmedia.app.data.repo.TrackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 曲库同步的总调度（P0-2 阶段 3 的"首次接线"）。
 *
 * 顺序固定，不为了快而调换：
 *
 * 1. **先过闸**（[LibrarySyncGate]）：未同意最新隐私说明、非 owner 档、未登录，都在这里返回，
 *    一个请求都不发；
 * 2. **先拉后推**：每一轮都先 `pull`，再算"只在这台手机上"的条目——先推后拉会拿旧水位
 *    重复处理，也会把刚在网页端删掉的条目又建回来；
 * 3. **每个账号问一次**：有本机独有条目时只问一次（[CloudUploadAsk]），同意才自动上传；
 *    拒绝就永久不传（换机/重装会重新问，那是新的一份本地决策）。
 *
 * 单飞：一把 [Mutex]，前台触发与登录触发撞上时后来的直接返回，不排队。
 */
object LibrarySyncRunner {

    /** 进前台触发的节流：设计 §18.3.2 的"距上次 > 5 分钟"。 */
    private const val FOREGROUND_INTERVAL_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val prompt = LibraryUploadPrompt()
    private val removedPrompt = LibraryRemovedPrompt()

    /** 需要用户确认的一次询问；null 表示当前没有。 */
    val uploadAsk: StateFlow<CloudUploadAsk?> = prompt.state

    /** 云端墓碑的批量确认；null 表示当前没有。 */
    val remoteRemovedAsk: StateFlow<CloudRemovedAsk?> = removedPrompt.state

    internal data class RestoreStatus(
        val account: String,
        val working: Boolean = false,
        val message: String? = null,
        val notices: List<RestoreMatchNotice> = emptyList(),
    )

    private val restoreState = MutableStateFlow<RestoreStatus?>(null)
    internal val restoreStatus = restoreState.asStateFlow()

    private var started = false
    private var lastForegroundAt = 0L
    private var signature: SessionKey? = null

    /** 会话里影响"要不要同步"的四个字段；任一变化就重跑一轮（同意从过期变有效也算）。 */
    private data class SessionKey(
        val token: String,
        val loginName: String,
        val consentRequired: Boolean,
        val tier: String,
        val consentedPrivacyVersion: Int?,
    )

    fun onForeground(context: Context) {
        val app = prime(context)
        val now = System.currentTimeMillis()
        if (now - lastForegroundAt < FOREGROUND_INTERVAL_MS) return
        lastForegroundAt = now
        scope.launch { run(app) }
    }

    fun onAccountChanged(context: Context) {
        val app = prime(context)
        scope.launch { run(app) }
    }

    fun onBackupRestored(context: Context) = checkRestored(context, includeLegacy = false, manual = false)

    fun checkRestored(context: Context, includeLegacy: Boolean, manual: Boolean = true) {
        val session = SessionStore.current.value ?: return
        val app = prime(context)
        val token = syncToken(session, LibrarySyncPrefs(app), manual)
        if (token() == null) return
        scope.launch {
            mutex.withLock {
                if (token() == null) return@withLock
                val account = session.loginName
                restoreState.value = RestoreStatus(account, working = true)
                try {
                    matchRestored(AppDatabase.get(app), account, token, includeLegacy)
                } catch (e: LibrarySyncException) {
                    restoreFailure(account, token, e)
                }
            }
        }
    }

    fun redownloadRestored(context: Context, id: Long, includeLegacy: Boolean) {
        val session = SessionStore.current.value ?: return
        val app = context.applicationContext
        val account = session.loginName
        val prefs = LibrarySyncPrefs(app)
        val token = syncToken(session, prefs, manual = true)
        if (token() == null || restoreState.value?.let {
            it.account == account && !it.working &&
                it.notices.any { notice -> notice.id == id && notice.issue == RestoreMatchIssue.UNMATCHED }
        } != true) return
        scope.launch {
            mutex.withLock {
                if (token() == null) return@withLock
                val db = AppDatabase.get(app)
                suspend fun stillUnmatched(): Boolean =
                    matchRestored(db, account, token, includeLegacy, showProgress = true)
                        .notices.any { it.id == id && it.issue == RestoreMatchIssue.UNMATCHED }
                try {
                    restoreState.value = RestoreStatus(account, working = true)
                    if (!stillUnmatched()) {
                        restoreState.value = restoreState.value?.copy(working = false)
                        return@withLock
                    }
                    val original = db.trackDao().byId(id)
                        ?.takeIf { it.accountKey == account || (it.accountKey == null && includeLegacy) }
                        ?: throw IllegalStateException("恢复条目已变化，请重新核对")
                    check(token() != null) { "账号或同步权限已变化" }
                    val repo = TrackRepository(app)
                    val extractor = DirectExtractor(app)
                    RestoredRedownload(app.cacheDir, { url, kind, file -> extractor.extractTo(url, kind, file) }) { row, file, bytes, hash, format ->
                        check(prefs.requireManualUpload(account, row.id)) { "无法保存此项的手动上传设置" }
                        repo.fillRestoredFile(row, file, bytes, hash, format, account) { token() != null }
                    }.download(original, ::stillUnmatched)
                    if (token() != null) restoreState.value = restoreState.value?.let {
                        it.copy(working = false, notices = it.notices.filterNot { notice -> notice.id == id },
                            message = "已重新下载到原条目，可在「上传本机新增」确认上传")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (token() != null) restoreState.value = restoreState.value?.copy(
                        working = false, message = "重新下载未完成：${e.message ?: "请稍后重试"}",
                    )
                }
            }
        }
    }

    fun requestUpload(account: String, count: Int) {
        val session = SessionStore.current.value
        if (session?.loginName == account && LibrarySyncGate.maySync(session)) {
            prompt.request(account, count, manual = true)
        }
    }

    fun dismiss(ask: CloudUploadAsk) = prompt.dismiss(ask)

    fun dismissRemoteRemoved(ask: CloudRemovedAsk) = removedPrompt.dismiss(ask)

    /** 用户在墓碑确认里选"保留本机文件"：脱钩、保留文件与全部整理信息。 */
    fun keepRemoteRemoved(context: Context, ask: CloudRemovedAsk) {
        val session = SessionStore.current.value ?: return
        if (session.loginName != ask.account || !LibrarySyncGate.maySync(session)) return
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                val db = AppDatabase.get(app)
                db.withTransaction {
                    val dao = db.libraryAuxDao()
                    val rows = db.trackDao().allNow()
                    dao.marks(ask.account).forEach { mark ->
                        val row = rows.firstOrNull { it.serverId == mark.remoteId && it.accountKey == ask.account }
                        if (row != null) {
                            // 脱钩：清掉服务器身份与待推送草稿；文件、收藏、类别、播放列表一概不动
                            db.trackDao().updateExisting(
                                row.copy(
                                    syncState = LibraryMark.DETACHED,
                                    serverId = null,
                                    serverSeq = null,
                                    serverUpdatedAtMs = null,
                                    accountKey = null,
                                    pendingTitle = null,
                                    pendingTitleBaseSeq = null,
                                    pendingTitleGen = null,
                                ),
                            )
                        }
                        dao.dropMark(ask.account, mark.remoteId)
                    }
                }
                removedPrompt.clear()
            }
        }
    }

    /** 关闭墓碑确认：不写任何决定，墓碑记录保留，下次同步再问。 */
    fun clearRemoteRemoved(account: String) = removedPrompt.clearFor(account)

    fun accept(context: Context, ask: CloudUploadAsk) {
        val session = SessionStore.current.value ?: return
        if (session.loginName != ask.account || !LibrarySyncGate.maySync(session)) return
        val app = context.applicationContext
        val prefs = LibrarySyncPrefs(app)
        if (!ask.manual && !prefs.enabled(ask.account)) return
        if (!prompt.decide(ask, prefs, SyncUploadDecision.ACCEPTED)) return
        scope.launch { uploadNow(app, session, manual = ask.manual) }
    }

    fun decline(context: Context, ask: CloudUploadAsk) {
        if (SessionStore.current.value?.loginName != ask.account) return
        prompt.decide(ask, LibrarySyncPrefs(context), SyncUploadDecision.DECLINED)
    }

    fun setEnabled(context: Context, account: String, enabled: Boolean) {
        if (SessionStore.current.value?.loginName != account) return
        LibrarySyncPrefs(context).setEnabled(account, enabled)
        if (enabled) onAccountChanged(context) else prompt.clearAutomatic()
    }

    // ---------- 内部 ----------

    /** 首次调用时记住 application context，并开始盯登录状态；返回 app context。 */
    private fun prime(context: Context): Context {
        val app = context.applicationContext
        if (!started) {
            started = true
            scope.launch {
                SessionStore.current.collect { session ->
                    val sig = session?.let {
                        SessionKey(it.token, it.loginName, it.consentRequired, it.tier.orEmpty(), it.consentedPrivacyVersion)
                    }
                    if (sig == signature) return@collect
                    restoreState.value = null
                    if (sig?.loginName != signature?.loginName) removedPrompt.reset()
                    if (sig?.loginName != signature?.loginName || !LibrarySyncGate.maySync(session)) prompt.clear()
                    signature = sig
                    if (session != null) run(app)
                }
            }
        }
        return app
    }

    private suspend fun run(context: Context) {
        val session = SessionStore.current.value
        if (!LibrarySyncGate.maySync(session)) {
            // 闸门关了就把待确认的询问收回去：账号已退出、或降级成免费档时不该继续挂着
            prompt.clear()
            return
        }
        val account = session!!.loginName
        val prefs = LibrarySyncPrefs(context)
        if (!prefs.enabled(account) || prompt.state.value?.manual == true) return
        if (!mutex.tryLock()) return
        try {
            val db = AppDatabase.get(context)
            val token = syncToken(session, prefs)
            val client = LibrarySyncClient(token = token)
            val uploader = uploader(db, client, account, prefs, manual = false)
            val round = LibrarySyncRound(
                pull = { acc ->
                    // ① 先重放本机删除：必须在拉取前，否则刚删的行会被拉回来
                    val replay = LibraryRemovalReplay(db, client, acc, valid = { token() != null }).replay()
                    if (replay.waiting > 0) note(db, acc, "${replay.waiting} 项云端删除待处理")
                    if (replay.stopped != null && replay.stopped != LibrarySyncProblem.NOT_AVAILABLE) {
                        note(db, acc, "云端删除未完成：${replay.reason}")
                    }
                    // ② 恢复项匹配（阶段 4）
                    try {
                        matchRestored(db, acc, token)
                    } catch (e: LibrarySyncException) {
                        restoreFailure(acc, token, e)
                        throw e
                    }
                    // ③ 拉取：同时收墓碑、更新服务器权威字段
                    LibrarySyncEngine(db, LibrarySyncApi(token = token)).pull(acc)
                    // ④ 标题草稿条件 PATCH（拉取之后，以最新服务器版本为准）
                    val titles = LibraryTitlePush(db, client, acc, valid = { token() != null }).push()
                    if (titles.stopped != null && titles.stopped != LibrarySyncProblem.NOT_AVAILABLE) {
                        note(db, acc, "标题推送未完成：${titles.reason}")
                    }
                    // ⑤ 墓碑确认
                    refreshRemovedAsk(db, acc, token)
                },
                candidateCount = { uploader.candidates().size },
                upload = { acc -> upload(uploader, db, acc) },
            )
            when (val outcome = round.run(session, prefs.decision(account))) {
                LibrarySyncRound.Outcome.Blocked,
                LibrarySyncRound.Outcome.Idle,
                LibrarySyncRound.Outcome.Uploaded,
                LibrarySyncRound.Outcome.Declined -> prompt.clearAutomatic()

                // 拉取失败不弹任何东西：NOT_AVAILABLE（服务器还没部署）与网络问题都该静默，
                // 只在本机留一句话，避免"每次进前台都弹同一个错"。
                is LibrarySyncRound.Outcome.PullFailed ->
                    note(db, account, "拉取未完成：${outcome.message}")

                is LibrarySyncRound.Outcome.Ask -> if (token() != null) {
                    prompt.request(account, outcome.count, manual = false)
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun matchRestored(
        db: AppDatabase,
        account: String,
        token: () -> String?,
        includeLegacy: Boolean = false,
        showProgress: Boolean = false,
    ): RestoreMatchResult {
        val result = RestoredMatcher(db, LibrarySyncApi(token = token, strictCatalog = true))
            .match(account, includeLegacy) { token() != null }
        if (token() != null) restoreState.value = RestoreStatus(
            account, working = showProgress,
            message = if (result.bound > 0) "已匹配 ${result.bound} 项，可从曲库下载" else null,
            notices = result.notices,
        )
        return result
    }

    private fun restoreFailure(account: String, token: () -> String?, e: LibrarySyncException) {
        if (token() != null) restoreState.value = RestoreStatus(
            account, message = "未完成云端核对：${e.message}",
        )
    }

    private suspend fun uploadNow(context: Context, session: SessionStore.Session, manual: Boolean) {
        val prefs = LibrarySyncPrefs(context)
        val token = syncToken(session, prefs, manual)
        mutex.withLock {
            if (token() == null) return
            val db = AppDatabase.get(context)
            val client = LibrarySyncClient(token = token)
            upload(uploader(db, client, session.loginName, prefs, manual), db, session.loginName)
        }
    }

    private fun uploader(
        db: AppDatabase, client: LibraryUploadTransport, account: String,
        prefs: LibrarySyncPrefs, manual: Boolean,
    ) = LibraryUploader(db, client, account, allowCandidate = { id ->
        manual || !prefs.requiresManualUpload(account, id)
    })

    private fun syncToken(
        startedAs: SessionStore.Session, prefs: LibrarySyncPrefs, manual: Boolean = false,
    ): () -> String? = {
        val current = SessionStore.current.value
        current?.takeIf {
            it.token == startedAs.token && it.loginName == startedAs.loginName && LibrarySyncGate.maySync(it) &&
                (manual || prefs.enabled(it.loginName))
        }?.token
    }

    private suspend fun upload(uploader: LibraryUploader, db: AppDatabase, account: String) {
        val summary = uploader.upload()
        val note = when {
            summary.stopped != null -> "上传未完成（${summary.stopReason}），下次自动重试"
            summary.conflicted > 0 -> "云端已删除 ${summary.conflicted} 项，本机文件保留"
            summary.isEmpty -> "没有需要上传的条目"
            else -> "已上传 ${summary.uploaded + summary.adopted} 项"
        }
        note(db, account, note)
    }

    /** 拉到墓碑后把"云端已移除 N 项"的批量确认摆出来；用户关掉后本次进程不再重复弹。 */
    private suspend fun refreshRemovedAsk(db: AppDatabase, account: String, token: () -> String?) {
        if (token() == null) return
        val marks = db.libraryAuxDao().marks(account)
        if (marks.isEmpty()) {
            removedPrompt.clearFor(account)
            return
        }
        removedPrompt.request(account, marks.map { it.title }, marks.size)
    }

    /** `library_cursor.note` 只放一句话摘要（不含标题、不含账号），给设置页展示用。 */
    private suspend fun note(db: AppDatabase, account: String, text: String) {
        val dao = db.libraryAuxDao()
        val current = dao.cursor(account) ?: LibraryCursorEntity(account = account)
        dao.putCursor(current.copy(note = text))
    }
}
