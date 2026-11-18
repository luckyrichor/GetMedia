package com.getmedia.app.data.serverextract

/** 服务端的三种存储策略（设计 §3）。P0-1 默认 [CACHE]，配额不足时退化到 [METADATA]。 */
object Storage {
    /** 服务器落一份临时文件，TTL 1 天；手机可用 Range 续传。 */
    const val CACHE = "cache"

    /** 只存元数据，不落盘；取文件时当场解析后中转，无法续传。 */
    const val METADATA = "metadata"

    /** 长期保存，计 owner 配额；与"文件以手机为准"冲突，P0-1 不用。 */
    const val ARCHIVE = "archive"
}
