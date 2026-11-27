package com.getmedia.app.data.local

/**
 * 本机导入文件（`platform = local`）在服务器归档里的备份状态（v7 起）。
 *
 * 为什么单独一列而不是复用 [SyncState]：同步状态说的是"这一行和云端**记录**的关系"，
 * 这里说的是"这个**文件**传没传上去"。两者会分叉——元数据早就同步好了（`SYNCED`），
 * 文件却可能因为网络失败一直没备份。
 *
 * 存字符串而不是序号：以后加状态不用改库，也不会因为枚举顺序变化读错旧行。
 */
enum class ArchiveState {
    /** 不涉及：平台条目（B 站/抖音）的文件本来就不在服务器长存。 */
    NONE,

    /** 待上传。也涵盖"失败了在等下一次"——重试间隔写在 `archiveNextAt`。 */
    PENDING,

    /** 服务器上已经有这份文件（`storage = archive`）。 */
    DONE,

    /** 传不上去，而且重试也没用（文件超大、云端空间不足、请求被拒）。不再自动重试。 */
    UNABLE,

    /** 备份曾被淘汰清掉：手机上还有原件，服务器上已经没有了。 */
    EVICTED;

    companion object {
        /** 认不出来的值当 [NONE]：旧行本来就没有归档状态，不该被当成"等着传"。 */
        fun of(name: String?): ArchiveState = entries.firstOrNull { it.name == name } ?: NONE
    }
}
