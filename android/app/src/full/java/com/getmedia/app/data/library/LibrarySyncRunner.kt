package com.getmedia.app.data.library

import android.content.Context
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.cloud.CloudUploadAsk
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LibraryCursorEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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
    private val askState = MutableStateFlow<CloudUploadAsk?>(null)

    /** 需要用户确认的一次询问；null 表示当前没有。 */
    val uploadAsk: StateFlow<CloudUploadAsk?> = askState.asStateFlow()

    private var started = false
    private var lastForegroundAt = 0L
    private var signature: Triple<String, Boolean, String>? = null

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

    fun accept(context: Context) {
        val app = prime(context)
        val account = SessionStore.current.value?.loginName ?: return
        LibrarySyncPrefs(app).setDecision(account, SyncUploadDecision.ACCEPTED)
        askState.value = null
        scope.launch { uploadNow(app, account) }
    }

    fun decline(context: Context) {
        val app = prime(context)
        val account = SessionStore.current.value?.loginName ?: return
        LibrarySyncPrefs(app).setDecision(account, SyncUploadDecision.DECLINED)
        askState.value = null
    }

    // ---------- 内部 ----------

    /** 首次调用时记住 application context，并开始盯登录状态；返回 app context。 */
    private fun prime(context: Context): Context {
        val app = context.applicationContext
        if (!started) {
            started = true
            scope.launch {
                SessionStore.current.collect { session ->
                    val sig = session?.let { Triple(it.loginName, it.consentRequired, it.tier.orEmpty()) }
                    if (sig == signature) return@collect
                    signature = sig
                    if (session == null) askState.value = null else run(app)
                }
            }
        }
        return app
    }

    private suspend fun run(context: Context) {
        val session = SessionStore.current.value
        if (!LibrarySyncGate.maySync(session)) {
            // 闸门关了就把待确认的询问收回去：账号已退出、或降级成免费档时不该继续挂着
            askState.value = null
            return
        }
        if (!mutex.tryLock()) return
        try {
            val account = session!!.loginName
            val db = AppDatabase.get(context)
            val round = LibrarySyncRound(
                pull = { acc -> LibrarySyncEngine(db, LibrarySyncApi()).pull(acc).let { } },
                candidateCount = { acc -> LibraryUploader(db, LibrarySyncClient(), acc).candidates().size },
                upload = { acc -> upload(LibraryUploader(db, LibrarySyncClient(), acc), db, acc) },
            )
            when (val outcome = round.run(session, LibrarySyncPrefs(context).decision(account))) {
                LibrarySyncRound.Outcome.Blocked,
                LibrarySyncRound.Outcome.Idle,
                LibrarySyncRound.Outcome.Uploaded,
                LibrarySyncRound.Outcome.Declined -> askState.value = null

                // 拉取失败不弹任何东西：NOT_AVAILABLE（服务器还没部署）与网络问题都该静默，
                // 只在本机留一句话，避免"每次进前台都弹同一个错"。
                is LibrarySyncRound.Outcome.PullFailed ->
                    note(db, account, "拉取未完成：${outcome.message}")

                is LibrarySyncRound.Outcome.Ask -> askState.value = CloudUploadAsk(account, outcome.count)
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun uploadNow(context: Context, account: String) {
        val session = SessionStore.current.value ?: return
        if (!LibrarySyncGate.maySync(session) || session.loginName != account) return
        if (!mutex.tryLock()) return
        try {
            val db = AppDatabase.get(context)
            upload(LibraryUploader(db, LibrarySyncClient(), account), db, account)
        } finally {
            mutex.unlock()
        }
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
