package com.getmedia.app.data.local

/**
 * 与服务器的同步状态。P0-1 只会写 [SYNCED]（服务器提取并已落本地文件）；
 * [LOCAL_ONLY] 表示这行只在本机存在，P0-2 的同步器据此决定要不要上传。
 *
 * 存字符串而不是序号：以后加状态不用改库，也不会因为枚举顺序变化读错旧行。
 */
enum class SyncState {
    LOCAL_ONLY,
    SYNCED;

    companion object {
        fun of(name: String?): SyncState = entries.firstOrNull { it.name == name } ?: LOCAL_ONLY
    }
}
