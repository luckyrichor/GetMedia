package com.getmedia.app.data.export

import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * 单项写出：临时名 → 写完校验字节数 → 改名；改名不支持时**按最终名直接重写一遍**。
 *
 * 为什么坚持"先临时名再改名"：写一半被中断时，用户目录里留下的是 `~getmedia-xxxx.part`，
 * 一眼能看出是半成品，也不会顶掉同名正常文件。但 `renameDocument` 并非所有 provider 都支持
 * （部分云盘与第三方文档 provider 返回 null 或抛 `UnsupportedOperationException`），
 * 所以必须有降级，否则在这些目录上一项都导不出来。
 *
 * 降级条件（只此一个）：`rename` 返回 false 或抛异常。此时在同一目录按最终名重新建文档、
 * 重新写一遍；重写也失败就删掉两个半成品，该项记失败。
 *
 * 不读用户目录里的旧文件、不覆盖同名文件：重名只在 [ExportNaming.uniqueName] 里加序号。
 */
class ExportWriter(
    private val target: ExportTarget,
    private val tempName: () -> String = { PART_PREFIX + UUID.randomUUID().toString().replace("-", "").take(8) + PART_SUFFIX },
) {

    /**
     * @property fileName 成功时的最终名；失败为 null。
     * @property reason 失败原因（固定文案，不含路径）。
     * @property rewritten 走了"按最终名直接重写"的降级路径。
     * @property noSpace 空间不足：这类失败没有继续跑的意义，整批中止。
     * @property abort 授权失效：后面每一项都会以同样方式失败，同样中止整批。
     * @property cancelled 写到一半被叫停（取消 / 到时限 / 被中断）。**不算失败**：半成品已删，
     *   这一项要留在"未执行"里等重试。
     */
    data class Outcome(
        val fileName: String?,
        val bytes: Long,
        val reason: String? = null,
        val rewritten: Boolean = false,
        val noSpace: Boolean = false,
        val abort: Boolean = false,
        val cancelled: Boolean = false,
    ) {
        val ok: Boolean get() = reason == null && fileName != null
    }

    /** 写不完整（字节数与源文件对不上）：provider 谎报成功、或写到一半断流。 */
    private class IncompleteWriteException : Exception()

    /**
     * @param stop 每个缓冲块之间问一次；为真就中止并删掉半成品。
     *   服务层（节点 C）用它响应通知里的「取消」、系统超时与 5 小时时限。
     */
    fun write(item: ExportPlan.Item, source: File, stop: () -> Boolean = { false }): Outcome {
        if (!source.isFile) return Outcome(null, 0, ExportPlan.REASON_MISSING_LOCAL)
        val length = source.length()
        val mimeType = ExportPlan.mimeOf(item.fileName.substringAfterLast('.', ""), item.track.mediaType)

        val dir = try {
            target.ensureDir(item.dirs)
        } catch (e: SecurityException) {
            return failure(e)
        } catch (e: IOException) {
            return failure(e)
        }

        var temp: ExportTarget.Doc? = null
        var finalDoc: ExportTarget.Doc? = null
        // 一旦发现 provider 不支持改名，后面的失败要说清是"降级路径也失败了"，
        // 否则用户只看到"写入失败"，不知道换个目录就能好
        var renameUnsupported = false
        try {
            val existing = target.childNames(dir)
            val name = ExportNaming.uniqueName(item.fileName, existing)
                ?: return Outcome(null, 0, REASON_TOO_MANY)

            temp = target.createDocument(dir, mimeType, tempName())
            val written = target.write(temp, source, stop)
            if (written != length) throw IncompleteWriteException()

            if (target.rename(temp, name)) {
                // 改名成功后旧句柄可能已失效，不再去删它
                temp = null
                return Outcome(name, written)
            }

            // 降级：provider 不支持改名，按最终名重写一遍
            renameUnsupported = true
            val rewritten = target.createDocument(dir, mimeType, name)
            finalDoc = rewritten
            val bytes = target.write(rewritten, source, stop)
            if (bytes != length) throw IncompleteWriteException()
            target.delete(temp)
            temp = null
            finalDoc = null
            return Outcome(name, bytes, rewritten = true)
        } catch (e: WriteCancelled) {
            // 取消不是失败：半成品必须清掉，界面上不出现这一项（进"未执行"，可重试）
            cleanup(temp, finalDoc)
            return Outcome(null, 0, cancelled = true)
        } catch (e: IncompleteWriteException) {
            cleanup(temp, finalDoc)
            return Outcome(null, 0, if (renameUnsupported) REASON_RENAME_FALLBACK else REASON_INCOMPLETE)
        } catch (e: SecurityException) {
            cleanup(temp, finalDoc)
            return failure(e)
        } catch (e: IOException) {
            cleanup(temp, finalDoc)
            if (renameUnsupported && !ExportPlan.isNoSpace(e)) {
                return Outcome(null, 0, REASON_RENAME_FALLBACK)
            }
            return failure(e)
        }
    }

    /** 清理：临时文件与"重写一半"的最终文件都删掉，失败静默（不因为清理失败把成功改判失败）。 */
    private fun cleanup(vararg docs: ExportTarget.Doc?) {
        docs.forEach { doc -> doc?.let { runCatching { target.delete(it) } } }
    }

    private fun failure(error: Throwable) = Outcome(
        fileName = null,
        bytes = 0,
        reason = ExportPlan.failureReason(error),
        noSpace = ExportPlan.isNoSpace(error),
        abort = error is SecurityException,
    )

    companion object {
        /** 临时名前缀：带 `~` 且在目录里会排到最后，用户一眼能认出是半成品。 */
        const val PART_PREFIX = "~getmedia-"
        const val PART_SUFFIX = ".part"

        const val REASON_TOO_MANY = "目标目录里同名文件过多"
        const val REASON_INCOMPLETE = "写入不完整，已删除半成品"
        /** 改名不被支持、按最终名直接写也失败：这一项换成别的目录就能导。 */
        const val REASON_RENAME_FALLBACK = "目标目录不支持改名，直接写入也失败"

        /** 残留临时文件判定（只用于提示个数，**不删**用户目录里的东西）。 */
        fun isPartFile(name: String): Boolean =
            name.lowercase(Locale.ROOT).startsWith(PART_PREFIX) &&
                name.lowercase(Locale.ROOT).endsWith(PART_SUFFIX)
    }
}
