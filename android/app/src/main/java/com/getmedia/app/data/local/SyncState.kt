package com.getmedia.app.data.local

/**
 * 与服务器的同步状态。P0-1 会写 [SYNCED]（服务器提取并已落本地文件）；
 * [LOCAL_ONLY] 表示这行只在本机存在，P0-2 的同步器据此决定要不要上传。
 *
 * [RESTORED] 是给"从云端清单恢复"的行用的：元数据来自备份，本地**没有音频文件**
 * （重新下载成功后才补上）。P0-2 **不得**把它当成"用户在本机新增的条目"直接上传——
 * 这些行原本就存在于用户库里，盲目上传会在服务器上造出重复条目。正确做法是先按
 * `(platform, video_id, page)` 与服务器列表匹配：匹配上就转 [SYNCED]，匹配不上再问用户。
 *
 * 存字符串而不是序号：以后加状态不用改库，也不会因为枚举顺序变化读错旧行。
 */
enum class SyncState {
    LOCAL_ONLY,
    SYNCED,
    RESTORED;

    companion object {
        /**
         * 认不出来的值当 [LOCAL_ONLY]（也是迁移给旧行的默认值）。这只可能来自库被手工
         * 改坏——[mayUploadToLibrary] 还要求有本地文件，所以这里兜底不会凭空上传什么。
         */
        fun of(name: String?): SyncState = entries.firstOrNull { it.name == name } ?: LOCAL_ONLY

        /**
         * P0-2 上传曲库的准入规则，只此一条：**本机新增 + 已经落成本地文件**。
         *
         * - [RESTORED] 不管有没有文件都不从这里上传：它不是新条目，先按
         *   `(platform, video_id, page)` 与服务器列表匹配，匹配不上再问用户；
         * - [SYNCED] 本来就在服务器上；
         * - 没有文件的行传上去也只是个空壳。
         */
        fun mayUploadToLibrary(state: SyncState, hasLocalFile: Boolean): Boolean =
            state == LOCAL_ONLY && hasLocalFile
    }
}
