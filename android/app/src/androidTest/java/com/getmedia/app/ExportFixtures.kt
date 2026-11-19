package com.getmedia.app

import android.content.Context
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.TrackEntity
import java.io.File
import java.io.FileOutputStream

/**
 * 批量导出的端到端素材：铺一批**真实的本地文件 + 库记录**。
 *
 * 只写 `-9100xxxx` 区间的合成 id，铺之前先检查不存在，绝不碰用户已有的音频。
 * 由 `BatchDeleteInstrumentation` 的 `mode=seedExport|cleanExport` 驱动——androidTest 的
 * `testInstrumentationRunner` 只能登记一个类，所以这里只放逻辑，不单独做 Instrumentation。
 */
object ExportFixtures {

    fun ids(count: Int): List<Long> = (1..count).map { -(9_100_000_000L + it) }

    private const val PLAYLIST = "ExportBatch"

    suspend fun seed(context: Context, db: AppDatabase, count: Int, megabytes: Int) {
        ids(count).forEach { id -> check(db.trackDao().byId(id) == null) { "合成 id $id 已存在，先 cleanExport" } }
        val folder = File(context.filesDir, "audio").apply { mkdirs() }
        db.openHelper.writableDatabase.execSQL("DELETE FROM playlists WHERE name='$PLAYLIST'")
        val listId = db.playlistDao().insert(PlaylistEntity(name = PLAYLIST))

        ids(count).forEachIndexed { index, id ->
            val video = index % 2 == 1
            val format = if (video) "mp4" else "wav"
            val file = File(folder, "exportbatch${index + 1}.$format")
            writeFixture(file, megabytes)
            db.trackDao().upsertLocal(
                TrackEntity(
                    id = id,
                    platform = "local",
                    videoId = "exportbatch${index + 1}",
                    title = "批量导出验证 ${index + 1} · ${if (video) "视频" else "音频"}",
                    uploader = "验证素材",
                    durationSec = (index + 1).toDouble(),
                    audioBytes = file.length(),
                    audioFormat = format,
                    sourceUrl = "",
                    audioSha256 = "fixture-export-$index",
                    localPath = file.path,
                    addedAtMs = 1_700_000_000_000L + index,
                    mediaType = if (video) MediaKind.VIDEO.name else MediaKind.AUDIO.name,
                ),
            )
        }
        db.playlistDao().add(ids(count).mapIndexed { index, id -> PlaylistItemEntity(listId, id, orderIndex = index) })
    }

    suspend fun clean(context: Context, db: AppDatabase, count: Int) {
        val folder = File(context.filesDir, "audio")
        ids(count).forEach { id ->
            val row = db.trackDao().byId(id) ?: return@forEach
            row.localPath?.let { runCatching { File(it).delete() } }
            db.trackDao().deleteByIds(listOf(id))
        }
        (1..count).forEach { index ->
            listOf("wav", "mp4").forEach { ext -> runCatching { File(folder, "exportbatch$index.$ext").delete() } }
        }
        db.openHelper.writableDatabase.execSQL("DELETE FROM playlists WHERE name='$PLAYLIST'")
    }

    /** 指定大小的文件：44 字节 RIFF 头 + 填充。内容不重要，导出是原样字节拷贝。 */
    private fun writeFixture(file: File, megabytes: Int) {
        val target = megabytes * 1024L * 1024L
        if (file.exists() && file.length() == target) return
        FileOutputStream(file).use { out ->
            out.write(riffHeader(target - 44))
            val chunk = ByteArray(64 * 1024)
            var written = 44L
            while (written < target) {
                val size = minOf(chunk.size.toLong(), target - written).toInt()
                out.write(chunk, 0, size)
                written += size
            }
        }
    }

    private fun riffHeader(dataBytes: Long): ByteArray = java.nio.ByteBuffer.allocate(44)
        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray()).putInt((dataBytes + 36).toInt())
        .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        .putInt(44_100).putInt(88_200).putShort(2).putShort(16)
        .put("data".toByteArray()).putInt(dataBytes.toInt())
        .array()
}
