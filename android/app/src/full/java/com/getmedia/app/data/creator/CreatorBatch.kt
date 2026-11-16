package com.getmedia.app.data.creator

/**
 * 本机批量任务的状态机。
 *
 * GetMedia 没有服务端媒体库，任务表也就没有意义：勾选、执行、失败重试全部
 * 在手机上完成，逐条调用 TrackRepository.extract()。这里只放纯逻辑，
 * 便于单测覆盖「勾选、跳过未选、取消后不再开始新条目、只重试失败项」。
 */
enum class BatchState { PENDING, RUNNING, DONE, FAILED, CANCELLED, SKIPPED }

data class BatchEntry(
    val item: CreatorItem,
    val selected: Boolean = true,
    val state: BatchState = BatchState.PENDING,
    val error: String? = null,
)

data class CreatorBatch(
    val entries: List<BatchEntry> = emptyList(),
) {
    val selectedCount: Int get() = entries.count { it.selected }
    val doneCount: Int get() = entries.count { it.state == BatchState.DONE }
    val failedCount: Int get() = entries.count { it.state == BatchState.FAILED }
    val cancelledCount: Int get() = entries.count { it.state == BatchState.CANCELLED }
    val runningCount: Int get() = entries.count { it.state == BatchState.RUNNING }

    /** 还没跑过、且被勾选的条目：决定「开始」按钮是否可用。 */
    val pendingSelected: Int
        get() = entries.count { it.selected && it.state == BatchState.PENDING }

    val isRunning: Boolean get() = runningCount > 0

    /** 已结束（没有运行中、也没有待跑的选中项）。 */
    val settled: Boolean get() = !isRunning && pendingSelected == 0

    fun toggle(position: Int): CreatorBatch = copy(
        entries = entries.map {
            if (it.item.position == position && it.state != BatchState.RUNNING && it.state != BatchState.DONE) {
                it.copy(selected = !it.selected)
            } else it
        }
    )

    fun selectAll(selected: Boolean): CreatorBatch = copy(
        entries = entries.map {
            if (it.state == BatchState.PENDING || it.state == BatchState.FAILED || it.state == BatchState.CANCELLED) {
                it.copy(selected = selected)
            } else it
        }
    )

    /** 取下一个待执行条目并标记为 RUNNING；没有则返回 null。 */
    fun claimNext(): Pair<CreatorBatch, CreatorItem?> {
        val next = entries.firstOrNull { it.selected && it.state == BatchState.PENDING } ?: return this to null
        val updated = entries.map {
            if (it.item.position == next.item.position) it.copy(state = BatchState.RUNNING, error = null) else it
        }
        return copy(entries = updated) to next.item
    }

    fun markDone(position: Int): CreatorBatch = replace(position) { it.copy(state = BatchState.DONE, error = null) }

    fun markFailed(position: Int, message: String): CreatorBatch =
        replace(position) { it.copy(state = BatchState.FAILED, error = message) }

    /** 取消：未开始的勾选项标记取消，运行中的那条保持原状（可能已经接近完成）。 */
    fun cancel(): CreatorBatch = copy(
        entries = entries.map {
            if (it.state == BatchState.PENDING && it.selected) it.copy(state = BatchState.CANCELLED) else it
        }
    )

    /** 重试失败项：失败的重新变回待执行并勾上。 */
    fun retryFailed(): CreatorBatch = copy(
        entries = entries.map {
            if (it.state == BatchState.FAILED) it.copy(state = BatchState.PENDING, selected = true, error = null) else it
        }
    )

    private inline fun replace(position: Int, block: (BatchEntry) -> BatchEntry): CreatorBatch = copy(
        entries = entries.map { if (it.item.position == position) block(it) else it },
    )

    /** 结果摘要，留在页面上，不用等一闪而过的提示。 */
    fun summary(): String {
        val done = doneCount
        val failed = failedCount
        return when {
            done == 0 && failed == 0 && cancelledCount > 0 -> "已取消，没有开始提取"
            failed == 0 && cancelledCount > 0 -> "已停止：成功 $done 条，其余未开始"
            failed == 0 -> "批量完成：$done 条全部成功"
            done == 0 -> "批量失败：$failed 条都没能提取"
            else -> "批量完成：成功 $done 条，失败 $failed 条"
        }
    }
}
