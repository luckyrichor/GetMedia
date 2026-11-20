package com.getmedia.app.data.cloud

import android.content.Context
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 曲库同步的能力接缝（P0-2）。
 *
 * 为什么要有这一层：同步器整个活在 `full` 源码集里（`tools/forbidden-heavy.txt` 禁止
 * 那几个同步类名与状态值出现在 `main`/`free`），但"列表标云端未下载""点开
 * 去下载""进前台触发一次"这三件事都发生在 `main` 的界面与宿主里。所以 `main` 只认这个接口，
 * 实现按风味给：
 *
 * - `free`：全部空实现（[awaitingDownload] 恒为 false，不会有任何请求）；
 * - `full`：接真实同步器与服务器提取。
 *
 * 命名刻意避开词表里的那些标识符，避免把禁用字符串带进免费版。
 */
interface CloudSyncHook {

    /** 需要用户确认"这些只在这台手机上、要不要上传"的一次询问；null 表示没有要问的。 */
    val uploadAsk: StateFlow<CloudUploadAsk?>

    /** 云端已删除、本机还留着文件的一批条目，等用户确认"保留本机文件"；null 表示没有。 */
    val remoteRemovedAsk: StateFlow<CloudRemovedAsk?>

    /** 这一条能不能排队"同时从云端曲库移除"（只看本机已知信息，服务端仍会兜底）。 */
    fun canQueueCloudDelete(track: TrackEntity, session: SessionStore.Session?): Boolean

    /** 已知是云端归档（只删本机，不写墓碑）。未知时返回 false，由服务端拒绝兜底。 */
    fun isCloudArchive(track: TrackEntity): Boolean

    /**
     * 把用户确认的云端删除写进待重放队列。**调用方必须已在 Room 事务里**，
     * 这样队列写入与删行、播放列表引用清理同生共死。返回实际入队条数。
     */
    suspend fun queueRemovals(db: AppDatabase, account: String, tracks: List<TrackEntity>,
                              cloudIds: Set<Long>, nowMs: Long): Int

    /**
     * 本机改名：绑定行只写待推送草稿，返回 true（调用方不再写本机标题）；
     * 纯本机/恢复/已脱钩行返回 false，走原来的本机改名。
     */
    suspend fun onTitleEdited(db: AppDatabase, track: TrackEntity, title: String, nowMs: Long): Boolean

    /**
     * 列表里这一条是不是"服务器上有、本机缺文件"。
     * 免费版恒为 false——免费版没有服务器曲库，这个状态不可能出现。
     */
    fun awaitingDownload(track: TrackEntity): Boolean

    /**
     * 把"云端有、本机没文件"的这一条下载到本机，返回落盘后的文件。
     * 失败抛异常，由调用方统一提示（[awaitingDownload] 为 false 时不该走到这里）。
     *
     * 这是**唯一**的下载入口（阶段 6）：条目菜单、恢复条目页、导出确认页的「先下载这 N 项」、
     * 批量重新下载全部走它，按行状态分派（服务器已存文件直取 / 服务器带 target 提取 /
     * 抖音本机提取 / 恢复空壳本机提取）。调用前不做任何隐式网络动作。
     */
    suspend fun download(context: Context, track: TrackEntity): File

    /** 这一条现在能做哪些补救（下载 / 只能移除空壳）；`free` 恒为 [RepairOptions.NONE]。 */
    fun repairOptions(track: TrackEntity): RepairOptions

    /** 重置本设备这个账号的"云端备份要不要恢复"决策，用于诊断与复验；不动云端与曲库。 */
    fun resetRestoreDecision(context: Context, account: String)

    /** App 回到前台：实现里自己判断闸门、节流与单飞，调用方不用关心。 */
    fun onForeground(context: Context)

    /** 登录/切换账号/退出登录后调一次。 */
    fun onAccountChanged(context: Context)

    val supportsManagedRestores: Boolean

    fun managesRestores(session: SessionStore.Session?): Boolean

    fun protectsRecovery(track: TrackEntity, session: SessionStore.Session?): Boolean

    fun onBackupRestored(context: Context)

    /** 关闭当前询问，不记录决定。 */
    fun dismissUpload(ask: CloudUploadAsk)

    /** 用户在询问里点了"上传"。 */
    fun acceptUpload(context: Context, ask: CloudUploadAsk)

    /** 用户在询问里点了"暂不上传"：该账号以后不再问，也不再自动传。 */
    fun declineUpload(context: Context, ask: CloudUploadAsk)

    /** 云端墓碑确认里点"保留本机文件"：脱钩、保留文件与整理信息。 */
    fun keepRemoteRemoved(context: Context, ask: CloudRemovedAsk)

    /** 关闭墓碑确认：什么都不改，下次同步再说。 */
    fun dismissRemoteRemoved(ask: CloudRemovedAsk)
}

/** 一次"本机独有条目要不要上传"的询问。文案由 full 的实现渲染，`main` 只递数据。 */
data class CloudUploadAsk(val account: String, val count: Int, val manual: Boolean = false)

/**
 * 一次"云端已移除 N 项，本机文件还在"的确认。
 *
 * 默认动作是保留本机文件；`main` 只递数据，具体文案与弹窗在 full 源码集里。
 */
data class CloudRemovedAsk(val account: String, val count: Int, val titles: List<String>)

// 具体实例 `val cloudSync: CloudSyncHook` 由风味源码集提供（与 `ui/ownerGate` 同一套做法：
// `main` 只引用符号，真实现分别在 `full`/`free` 里）。
