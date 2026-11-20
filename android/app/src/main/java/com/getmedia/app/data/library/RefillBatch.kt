package com.getmedia.app.data.library

import com.getmedia.app.data.repo.ErrorText

/**
 * 「先下载这 N 项」的批量执行与汇总（0.1.16）。
 *
 * 为什么要单独拎出来：0.1.15 的做法是"下载入口没抛异常就算成功"，
 * 于是只要某一行的库字段与磁盘不一致（字段说没有、磁盘其实有），下载会瞬间返回，
 * 计数 +1，但列表上的「云端未下载」还挂着——用户看到的就是"说下载了 2 项，其中一条没文件"。
 *
 * 现在成功的定义收紧成两条，缺一不可：
 *
 * 1. 下载入口正常返回（没抛异常）；
 * 2. **回读**这一行，`localPath` 指向的文件真实存在且长度大于 0（[landed]）。
 *
 * 另外三条：
 * - 同一个 id 只处理一次：恢复条目、播放列表混进同一批时会出现重复项，重复计一次就是虚报；
 * - 每条都给结果，失败带原因（人话，来自 [ErrorText]）；
 * - 汇总文案里的数字只数"真正落地"的条数。
 *
 * 这个类不碰 Android 与 Room，四个场景都能在 JVM 单测里直接喂假实现。
 */
object RefillBatch {

    data class Item(val id: Long, val title: String)

    data class Result(val id: Long, val title: String, val ok: Boolean, val reason: String? = null)

    /** 按 id 去重，保留首次出现的顺序与标题。 */
    fun dedupe(items: List<Item>): List<Item> {
        val seen = HashSet<Long>(items.size)
        return items.filter { seen.add(it.id) }
    }

    /**
     * @param download 真正取文件；抛异常即失败（`repo.byId` 为 null 时也应由它抛"这一条已被移除"）
     * @param landed   回读：这一行现在是否真的有可用的本机文件
     */
    suspend fun run(
        items: List<Item>,
        download: suspend (Long) -> Unit,
        landed: suspend (Long) -> Boolean,
    ): List<Result> = dedupe(items).map { item ->
        val failure = runCatching { download(item.id) }.exceptionOrNull()
        when {
            failure != null -> Result(item.id, item.title, ok = false, reason = ErrorText.humanMessage(failure))
            // 入口说成功、回读却没有文件：宁可算失败也不虚报，原因写清楚，方便以后查。
            !runCatching { landed(item.id) }.getOrDefault(false) ->
                Result(item.id, item.title, ok = false, reason = "下载完成但文件没有落到本机")
            else -> Result(item.id, item.title, ok = true)
        }
    }

    fun okCount(results: List<Result>): Int = results.count { it.ok }

    fun failed(results: List<Result>): List<Result> = results.filter { !it.ok }

    /**
     * 汇总文案（完整一句，测试与日志用）。
     * 界面上分两行显示时用 [okText] + [failuresText]，避免同一句里塞两种语气。
     */
    fun message(results: List<Result>, maxListed: Int = MAX_LISTED): String {
        val ok = okText(results)
        val bad = failuresText(results, maxListed)
        return when {
            bad == null -> ok
            ok.isEmpty() -> bad
            else -> "$ok；$bad"
        }
    }

    /** 成功的部分：全成功时给一句能直接开始导出的话；一条没成返回空串。 */
    fun okText(results: List<Result>): String {
        val ok = okCount(results)
        if (ok == 0) return ""
        return if (failed(results).isEmpty()) "已下载 $ok 项，可以开始导出了" else "已下载 $ok 项"
    }

    /**
     * 失败的部分：逐条列"标题（原因）"，最多 [maxListed] 条，多出来的写成"等 N 项"。
     * 全部成功返回 null。
     */
    fun failuresText(results: List<Result>, maxListed: Int = MAX_LISTED): String? {
        val bad = failed(results)
        if (bad.isEmpty()) return null
        val listed = bad.take(maxListed).joinToString("；") { "${it.title}（${it.reason ?: "没取到"}）" }
        val more = if (bad.size > maxListed) "；另有 ${bad.size - maxListed} 项" else ""
        return "没取到 ${bad.size} 项：$listed$more"
    }

    const val MAX_LISTED = 3
}
