package com.getmedia.app.data.library

import com.getmedia.app.account.SessionStore

/** 每个账号只做一次的"要不要把本机独有条目传上去"的决策（存本机，见 [LibrarySyncPrefs]）。 */
enum class SyncUploadDecision { NONE, ACCEPTED, DECLINED }

/**
 * 一轮同步的**纯调度**：闸门 → 拉 → 数本机独有条目 → 按决策决定问/传/不传。
 *
 * 单独抽出来是为了能被 JVM 单测直接钉住，尤其是"闸门关了不许发任何请求"这一条
 * （阶段 3 硬性验收 ⑤）：测试里把三个动作换成计数器，断言次数为 0，比在生产代码里
 * 找注释可靠。所有 Android 依赖（Room、OkHttp、SharedPreferences）都以 lambda 形式注入。
 *
 * 顺序不可调换：
 * 1. 闸门在**最前**——没同意隐私说明、非 owner 档、未登录，连拉都不许拉；
 * 2. 先拉后推——拉取失败就不推（否则推的还是旧判断）；
 * 3. 有本机独有条目且还没决策过，就**只问一次**（[Outcome.Ask]），不擅自上传。
 */
class LibrarySyncRound(
    private val pull: suspend (String) -> Unit,
    private val candidateCount: suspend (String) -> Int,
    private val upload: suspend (String) -> Unit,
) {

    sealed interface Outcome {
        /** 闸门关：**一个请求都没发**。 */
        data object Blocked : Outcome

        /** 拉取失败（网络、服务器还没部署、会话失效）：不推，只留一句话。 */
        data class PullFailed(val message: String) : Outcome

        /** 拉完发现没有"只在这台手机上"的条目。 */
        data object Idle : Outcome

        /** 有 N 条本机独有条目，要问用户一次。 */
        data class Ask(val count: Int) : Outcome

        /** 该账号同意过：这一轮已经传完。 */
        data object Uploaded : Outcome

        /** 该账号拒绝过：不问、也不传。 */
        data object Declined : Outcome
    }

    suspend fun run(session: SessionStore.Session?, decision: SyncUploadDecision): Outcome {
        if (!LibrarySyncGate.maySync(session)) return Outcome.Blocked
        val account = requireNotNull(session).loginName
        try {
            pull(account)
        } catch (e: LibrarySyncException) {
            return Outcome.PullFailed(e.message.orEmpty())
        }
        val count = candidateCount(account)
        if (count <= 0) return Outcome.Idle
        return when (decision) {
            SyncUploadDecision.ACCEPTED -> {
                upload(account)
                Outcome.Uploaded
            }
            SyncUploadDecision.DECLINED -> Outcome.Declined
            SyncUploadDecision.NONE -> Outcome.Ask(count)
        }
    }
}
