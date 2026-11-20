package com.getmedia.app.data.cloud

import android.content.Context
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

    /**
     * 列表里这一条是不是"服务器上有、本机缺文件"。
     * 免费版恒为 false——免费版没有服务器曲库，这个状态不可能出现。
     */
    fun awaitingDownload(track: TrackEntity): Boolean

    /**
     * 把"云端有、本机没文件"的这一条下载到本机，返回落盘后的文件。
     * 失败抛异常，由调用方统一提示（[awaitingDownload] 为 false 时不该走到这里）。
     */
    suspend fun download(context: Context, track: TrackEntity): File

    /** App 回到前台：实现里自己判断闸门、节流与单飞，调用方不用关心。 */
    fun onForeground(context: Context)

    /** 登录/切换账号/退出登录后调一次。 */
    fun onAccountChanged(context: Context)

    /** 用户在询问里点了"上传"。 */
    fun acceptUpload(context: Context)

    /** 用户在询问里点了"暂不上传"：该账号以后不再问，也不再自动传。 */
    fun declineUpload(context: Context)
}

/** 一次"本机独有条目要不要上传"的询问。文案由 full 的实现渲染，`main` 只递数据。 */
data class CloudUploadAsk(val account: String, val count: Int)

// 具体实例 `val cloudSync: CloudSyncHook` 由风味源码集提供（与 `ui/ownerGate` 同一套做法：
// `main` 只引用符号，真实现分别在 `full`/`free` 里）。
