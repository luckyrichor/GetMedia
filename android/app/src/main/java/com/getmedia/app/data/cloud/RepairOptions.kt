package com.getmedia.app.data.cloud

/**
 * 「这一条缺文件时能做哪些补救」——条目菜单、恢复条目页、导出确认页共用一份判断。
 *
 * 为什么放在 `main`：三个入口都在 `main` 的界面里，而"哪些行算云端行、哪些算恢复空壳"
 * 只有 full 源码集知道（判定要读 full 侧的库标记与 `RESTORED` 这些只在 full 出现的状态值）。
 * 所以 `main` 只认这份中性结构，`free` 的实现恒返回 [NONE]。
 *
 * 这里的两个动作都**只影响这一条本机行，永远不会新增行**：
 * - [canDownload]：把缺的文件取回来，按原主键回填（作品、分集、媒体类型必须核对一致）；
 * - [canRemoveShell]：这条是清单恢复出来的空壳（云端没有对应记录或已删除、本机也没有文件），
 *   只允许整条移除，不动云端记录、不写删除队列、不删任何文件。
 */
data class RepairOptions(
    val canDownload: Boolean = false,
    val canRemoveShell: Boolean = false,
    /** 不能下载但要解释一句时非空（例如没有可用的来源链接）。 */
    val note: String? = null,
) {
    val any: Boolean get() = canDownload || canRemoveShell

    companion object {
        val NONE = RepairOptions()
    }
}
