package com.getmedia.app.data.export

import java.io.File
import java.io.IOException

/**
 * 目标位置的写入口：**只管"往哪儿写"，不管"写什么"**。
 *
 * 真实实现是 [SafExportTarget]（SAF 树 + `DocumentsContract`）；单测用假实现复刻 provider 的脾气
 * （不支持改名、写一半失败、查不到剩余空间），所以这里不出现任何 Android 类型。
 *
 * 抛异常的约定：建不出来/写不进去一律 [IOException]（授权失效是 `SecurityException`），
 * 由 [ExportWriter] 归类成固定文案，绝不把异常消息（可能含用户目录名）带到界面上。
 */
interface ExportTarget {

    /** 目录句柄。外部只当令牌原样传回，不关心实现里塞的是 `Uri` 还是假实现的 key。 */
    interface Dir

    /** 文件句柄。 */
    interface Doc

    /** 逐级建目录（已存在就沿用），返回最内层句柄。 */
    fun ensureDir(dirs: List<String>): Dir

    /** 目录下已有的子项显示名。provider 查不到时返回空集，不要抛。 */
    fun childNames(dir: Dir): Set<String>

    /** 在 [dir] 下建一个空文件；失败抛 [IOException]。 */
    fun createDocument(dir: Dir, mimeType: String, name: String): Doc

    /**
     * 把 [source] 全量写进 [doc]，返回实际写入字节数。
     *
     * [stop] 在每个缓冲块之间被问一次：为真就抛 [WriteCancelled]（用户取消 / 到时限 / 被中断）。
     * 为什么要在这里开口子：SAF 的写是一次阻塞 `copyTo`，不插检查点的话大文件要整份写完
     * 才会响应取消，用户点"取消"后看到几十秒没反应，临时文件也一直躺着。
     */
    fun write(doc: Doc, source: File, stop: () -> Boolean = { false }): Long

    /**
     * 改名。provider 不支持（`renameDocument` 返回 null）或抛异常都返回 `false`，
     * 由 [ExportWriter] 走"按最终名直接重写一遍"的降级路径。
     */
    fun rename(doc: Doc, newName: String): Boolean

    /** 删除。清理阶段的尽力而为，失败静默。 */
    fun delete(doc: Doc)

    /** 目标可用字节数；查不到返回 -1（云盘常见），由调用方按"无法确认"处理。 */
    fun availableBytes(): Long
}

/**
 * 写入中途被叫停。
 *
 * 继承 [IOException] 是为了让"写不进去"的 catch 也能兜住它；[ExportWriter] 会先单独捕获这个类型，
 * 走"删掉半成品、不记失败"的路径——取消不是失败，不该出现在摘要的失败列表里。
 */
class WriteCancelled : IOException("write cancelled")
