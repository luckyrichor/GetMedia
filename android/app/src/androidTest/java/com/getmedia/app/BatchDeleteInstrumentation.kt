package com.getmedia.app

import android.app.Instrumentation
import android.os.Bundle
import com.getmedia.app.data.local.*
import com.getmedia.app.data.repo.TrackRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** Uses only synthetic negative IDs and fixture files; never deletes a user's tracks. */
class BatchDeleteInstrumentation : Instrumentation() {
    private var mode = "test"
    private var count = 24
    private var megabytes = 16
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "test"
        count = arguments?.getString("count")?.toIntOrNull() ?: 24
        megabytes = arguments?.getString("mb")?.toIntOrNull() ?: 16
        start()
    }
    override fun onStart() {
        val report = Bundle()
        try {
            runBlocking {
                val context = targetContext
                // 批量导出的端到端素材：只铺/清 -9100xxxx 区间的合成行，供真机手工跑导出
                if (mode == "seedExport" || mode == "cleanExport") {
                    val db = AppDatabase.get(context)
                    withContext(Dispatchers.IO) {
                        if (mode == "seedExport") ExportFixtures.seed(context, db, count, megabytes)
                        else ExportFixtures.clean(context, db, count)
                    }
                    val left = ExportFixtures.ids(count).count { db.trackDao().byId(it) != null }
                    report.putString("stream", "Export fixtures $mode done, tracks=$left\n")
                    return@runBlocking
                }
                if (mode == "diagnostics") {
                    // 这里只验手机直提取：服务器提取页在 full 源码集，androidTest 要为 free 一起编译，
                    // 引用它会把这个类拖进 free 的测试编译单元。
                    val vm = withContext(Dispatchers.Main) { com.getmedia.app.ui.screen.ExtractViewModel(TrackRepository(context), BuildConfig.VERSION_NAME).apply { input = "invalid"; submit() } }
                    while (withContext(Dispatchers.Main) { vm.busy }) delay(50)
                    val text = com.getmedia.app.data.repo.DiagnosticsStore.exportFailures()
                    check(text.contains("手机直提取")) { "Failure task missing: $text" }
                    check(text.contains("提取失败")) { "Failure reason missing: $text" }
                    report.putString("stream", "Diagnostic task PASS: local extraction failure persisted with reason\n")
                    return@runBlocking
                }
                val db = AppDatabase.get(context)
                val ids = setOf(-90000001L, -90000002L)
                val playlistDao = db.playlistDao()
                if (mode != "check") {
                    withContext(Dispatchers.IO) {
                        ids.forEachIndexed { index, id ->
                            check(db.trackDao().byId(id) == null) { "Synthetic ID already exists; abort to preserve existing audio" }
                            val file = File(context.filesDir, "audio/batchcheck${index + 1}.wav")
                            writeTone(file, index + 1)
                            db.trackDao().upsertLocal(TrackEntity(
                                id = id, platform = "local", videoId = "batchcheck${index + 1}",
                                title = "BatchCheck ${if (index == 0) "A" else "B"}", uploader = "验证音频",
                                durationSec = (index + 1).toDouble(), audioBytes = file.length(), audioFormat = "wav",
                                sourceUrl = "", audioSha256 = "fixture-$index", localPath = file.path))
                        }
                        val list = playlistDao.insert(PlaylistEntity(name = "BatchCheck"))
                        playlistDao.add(ids.mapIndexed { index, id -> PlaylistItemEntity(list, id, orderIndex = index) })
                        playlistDao.firstId()?.let { playlistDao.add(listOf(PlaylistItemEntity(it, ids.first(), orderIndex = 999))) }
                    }
                    if (mode == "test") {
                        // Cancellation while awaiting the file lock must still complete the confirmed deletion.
                        val field = TrackRepository::class.java.getDeclaredField("fileMutex").apply { isAccessible = true }
                        val lock = field.get(null) as Mutex
                        lock.lock()
                        val deleting = launch { TrackRepository(context).removeAll(ids) }
                        delay(100)
                        deleting.cancel()
                        lock.unlock()
                        deleting.join()
                    }
                }
                if (mode != "create") withContext(Dispatchers.IO) {
                    check(ids.all { db.trackDao().byId(it) == null }) { "Metadata remains" }
                    val references = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM playlist_items WHERE trackId IN (-90000001,-90000002)").use { it.moveToFirst(); it.getInt(0) }
                    check(references == 0) { "Playlist references remain" }
                    check((1..2).none { File(context.filesDir, "audio/batchcheck$it.wav").exists() }) { "Files remain" }
                    db.openHelper.writableDatabase.execSQL("DELETE FROM playlists WHERE name='BatchCheck'")
                }
            }
            if (mode != "diagnostics") report.putString("stream", "BatchDelete $mode PASS: metadata, references and files verified\n")
            finish(RESULT_OK, report)
        } catch (error: Throwable) {
            report.putString("stream", "BatchDelete $mode FAIL: ${error.message}\n")
            finish(RESULT_CANCELED, report)
        }
    }
    private fun writeTone(file: File, seconds: Int) {
        file.parentFile?.mkdirs()
        val samples = 44100 * seconds
        val buffer = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples * 2)
        repeat(samples) { buffer.putShort((sin(it * 2 * Math.PI * (660 + seconds * 110) / 44100) * 2000).toInt().toShort()) }
        file.writeBytes(buffer.array())
    }
    companion object { private const val RESULT_OK = -1; private const val RESULT_CANCELED = 0 }
}
