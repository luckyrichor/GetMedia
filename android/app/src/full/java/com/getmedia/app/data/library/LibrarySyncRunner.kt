package com.getmedia.app.data.library

import android.content.Context
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.cloud.CloudUploadAsk
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LibraryCursorEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
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

    /** 需要用户确认的一次询问；null 表示当前没有。 */
    val uploadAsk: StateFlow<CloudUploadAsk?> = prompt.state

    private var started = false
    private var lastForegroundAt = 0L
    private var signature: SessionKey? = null

    /** 会话里影响"要不要同步"的四个字段；任一变化就重跑一轮（同意从过期变有效也算）。 */
    private data class SessionKey(
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

    fun requestUpload(account: String, count: Int) {
        val session = SessionStore.current.value
        if (session?.loginName == account && LibrarySyncGate.maySync(session)) {
            prompt.request(account, count, manual = true)
        }
    }

    fun dismiss(ask: CloudUploadAsk) = prompt.dismiss(ask)

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
                        SessionKey(it.loginName, it.consentRequired, it.tier.orEmpty(), it.consentedPrivacyVersion)
                    }
                    if (sig == signature) return@collect
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
            val round = LibrarySyncRound(
                pull = { acc -> LibrarySyncEngine(db, LibrarySyncApi(token = token)).pull(acc).let { } },
                candidateCount = { acc -> LibraryUploader(db, LibrarySyncClient(token = token), acc).candidates().size },
                upload = { acc -> upload(LibraryUploader(db, LibrarySyncClient(token = token), acc), db, acc) },
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

    private suspend fun uploadNow(context: Context, session: SessionStore.Session, manual: Boolean) {
        val prefs = LibrarySyncPrefs(context)
        val token = syncToken(session, prefs, manual)
        mutex.withLock {
            if (token() == null) return
            val db = AppDatabase.get(context)
            upload(LibraryUploader(db, LibrarySyncClient(token = token), session.loginName), db, session.loginName)
        }
    }

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

    /** `library_cursor.note` 只放一句话摘要（不含标题、不含账号），给设置页展示用。 */
    private suspend fun note(db: AppDatabase, account: String, text: String) {
        val dao = db.libraryAuxDao()
        val current = dao.cursor(account) ?: LibraryCursorEntity(account = account)
        dao.putCursor(current.copy(note = text))
    }
}
