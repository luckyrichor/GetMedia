package com.getmedia.app.data.export

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

/**
 * [ExportTarget] 的 SAF 实现：所有写操作都走 `DocumentsContract`，不碰真实路径。
 *
 * 为什么不用 `DocumentFile`：它每个方法都要再查一次 provider，`renameTo` 在部分 provider 上
 * 直接返回 false 而拿不到原因；`DocumentsContract` 能区分"返回 null"和"抛异常"，降级才好判断。
 */
class SafExportTarget(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : ExportTarget {

    private class DirHandle(val uri: Uri) : ExportTarget.Dir
    private class DocHandle(val uri: Uri) : ExportTarget.Doc

    /** 目录句柄缓存：同一批导出里同一层级只解析一次。 */
    private val dirCache = HashMap<List<String>, DirHandle>()

    private val rootId: String get() = DocumentsContract.getTreeDocumentId(treeUri)

    private fun docUri(documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    override fun ensureDir(dirs: List<String>): ExportTarget.Dir = dirCache.getOrPut(dirs) {
        var currentUri = docUri(rootId)
        for (name in dirs) {
            val existing = children(currentUri).firstOrNull { it.first == name }
                ?: children(currentUri).firstOrNull { it.first.equals(name, ignoreCase = true) }
            currentUri = if (existing != null) docUri(existing.second) else create(currentUri, MIME_DIR, name)
        }
        DirHandle(currentUri)
    }

    override fun childNames(dir: ExportTarget.Dir): Set<String> =
        children((dir as DirHandle).uri).mapTo(LinkedHashSet()) { it.first }

    override fun createDocument(dir: ExportTarget.Dir, mimeType: String, name: String): ExportTarget.Doc =
        DocHandle(create((dir as DirHandle).uri, mimeType, name))

    override fun write(doc: ExportTarget.Doc, source: File, stop: () -> Boolean): Long {
        val uri = (doc as DocHandle).uri
        val out = try {
            resolver.openOutputStream(uri, "w")
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "无法写入目标文件", e)
        } ?: throw IOException("无法写入目标文件")
        out.use { sink ->
            source.inputStream().use { input ->
                // 手写循环而不是 copyTo：取消检查要落在这块 64 KiB 的边界上，
                // 大视频才不至于"点了取消还要等整份写完"
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                while (true) {
                    if (stop()) throw WriteCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    total += read
                }
                sink.flush()
                return total
            }
        }
    }

    override fun rename(doc: ExportTarget.Doc, newName: String): Boolean {
        val uri = (doc as DocHandle).uri
        return try {
            DocumentsContract.renameDocument(resolver, uri, newName) != null
        } catch (e: SecurityException) {
            // 授权没了是整批的事，不能降级成"直接写入"，否则会在只读目录里再失败一次
            throw e
        } catch (e: Exception) {
            false
        }
    }

    override fun delete(doc: ExportTarget.Doc) {
        runCatching { DocumentsContract.deleteDocument(resolver, (doc as DocHandle).uri) }
    }

    override fun availableBytes(): Long = SafExportTarget.availableBytes(resolver, docUri(rootId))

    /**
     * 统计目标目录里上次残留的临时文件个数（只数**不删**：用户目录里的东西不在本应用权限内处理）。
     *
     * 只查本次计划真正会用到的目录，逐层按名字找，找不到就跳过——不会为了统计去建目录。
     */
    fun countLeftovers(paths: Collection<List<String>>): Int = paths.distinct().sumOf { path ->
        runCatching {
            var currentUri: Uri? = docUri(rootId)
            for (name in path) {
                val hit = currentUri?.let { children(it).firstOrNull { child -> child.first.equals(name, ignoreCase = true) } }
                currentUri = hit?.let { docUri(it.second) }
                if (currentUri == null) break
            }
            currentUri?.let { uri -> children(uri).count { ExportWriter.isPartFile(it.first) } } ?: 0
        }.getOrDefault(0)
    }

    private fun children(dirUri: Uri): List<Pair<String, String>> {
        val parentId = DocumentsContract.getDocumentId(dirUri)
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val out = ArrayList<Pair<String, String>>()
        val cursor = try {
            resolver.query(
                childUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            null
        }
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out += name to id
            }
        }
        return out
    }

    private fun create(parentUri: Uri, mimeType: String, name: String): Uri = try {
        DocumentsContract.createDocument(resolver, parentUri, mimeType, name)
            ?: throw IOException("目标目录不允许新建文件")
    } catch (e: SecurityException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException(e.message ?: "目标目录不允许新建文件", e)
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val MIME_DIR = DocumentsContract.Document.MIME_TYPE_DIR

        /**
         * 可用空间。**查不到就返回 -1**——很多 provider（尤其云盘）不实现这一列，
         * 界面据此显示"无法确认剩余空间"并放行，而不是假装算出一个数。
         *
         * 用的是 `Root.COLUMN_AVAILABLE_BYTES`（平台允许在 document 查询上取这一列），
         * `Document` 里并没有同名常量。
         */
        fun availableBytes(resolver: ContentResolver, documentUri: Uri): Long = try {
            resolver.query(documentUri, arrayOf(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES), null, null, null)
                ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }
}

/**
 * 目录授权：持久化、判定、释放、显示名。
 *
 * 系统对每个应用的持久化授权数量有上限（约 128 个，且低版本更少），我们自己只保留最近 5 个，
 * 多出来的用 `releasePersistableUriPermission` 释放，避免以后 `takePersistableUriPermission` 抛
 * `SecurityException`。
 */
object SafGrants {

    private const val MAX_GRANTS = 5
    private const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** 持久化授权。失败返回 false：一次性授权在后台服务里必失效，宁可不开始。 */
    fun persist(context: Context, uri: Uri): Boolean {
        val ok = try {
            context.contentResolver.takePersistableUriPermission(uri, FLAGS)
            true
        } catch (e: SecurityException) {
            false
        }
        if (!ok) return false
        val known = (ExportPrefs.knownTrees(context) + uri.toString()).distinct().takeLast(MAX_GRANTS)
        val dropped = ExportPrefs.knownTrees(context) - known.toSet()
        dropped.forEach { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(Uri.parse(old), FLAGS)
            }
        }
        ExportPrefs.rememberKnownTrees(context, known)
        return true
    }

    /** 当前是否还持有这个目录的写权限（用户可能在系统设置里撤过）。 */
    fun isPersisted(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    /**
     * 目录显示名：`内部存储/Download/GetMedia`。
     * 从 docId（`primary:Download/GetMedia`）解析，不再多查一次 provider。
     */
    fun label(uri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault("")
        val volume = docId.substringBefore(':', "")
        val path = docId.substringAfter(':', "")
        val prefix = when (volume) {
            "primary" -> "内部存储"
            "home" -> "文档"
            "" -> ""
            else -> volume
        }
        return when {
            prefix.isEmpty() -> path
            path.isEmpty() -> prefix
            else -> "$prefix/$path"
        }
    }
}
