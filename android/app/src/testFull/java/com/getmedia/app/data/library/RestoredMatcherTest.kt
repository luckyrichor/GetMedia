package com.getmedia.app.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.cloud.cloudSync
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LibraryCursorEntity
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.repo.TrackRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RestoredMatcherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val dao = db.trackDao()
    private val repo = TrackRepository(context, db)
    private val owner = "stage4"
    private val bv = "BV1KG411w7r2"
    private val source = "https://www.bilibili.com/video/$bv"
    private val row = TrackEntity(
        id = -48, platform = "bilibili", videoId = "$bv-p3", title = "备份标题",
        uploader = "UP", durationSec = 37.0, audioBytes = 367577, audioFormat = "m4a",
        sourceUrl = source, favorite = true, localCategoryId = 8, localTitleOverride = "改名",
        sortIndex = 6, syncState = SyncState.RESTORED.name, accountKey = owner,
    )
    private val cloud = CloudTrack(6, "bilibili", bv, 3, "云端标题", "UP", 37.0,
        source, "audio", false, 11, 7)
    private val payload = "verified test audio"
    private val checksum = MessageDigest.getInstance("SHA-256")
        .digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }

    @After fun closeDb() = db.close()

    private fun page(vararg items: CloudTrack, more: Boolean = false) =
        PullPage(items.toList(), items.maxOfOrNull { it.updatedSeq } ?: 0, more)

    private fun matcher(vararg pages: Any): RestoredMatcher {
        val queue = ArrayDeque(pages.toList())
        return RestoredMatcher(db, object : LibrarySyncTransport {
            override suspend fun pull(sinceSeq: Long, limit: Int): PullPage =
                when (val next = queue.removeFirst()) {
                    is Exception -> throw next
                    else -> next as PullPage
                }
        })
    }

    @Test fun uniqueMatchChangesOnlyLinkageAndOriginalIdCanBeDownloaded() = runBlocking {
        dao.insertIgnore(listOf(row))
        val playlist = db.playlistDao().insert(PlaylistEntity(name = "原列表"))
        val item = PlaylistItemEntity(playlist, row.id, orderIndex = 7)
        db.playlistDao().add(listOf(item))

        val catalog = matcher(page(cloud, more = true), page(cloud.copy(updatedSeq = 12)))
        assertEquals(RestoreMatchResult(bound = 1), catalog.match(owner, false) { true })
        val linked = requireNotNull(dao.byId(row.id))
        assertEquals(row.copy(serverId = 6, serverSeq = 12, serverPage = 3, serverUpdatedAtMs = 7000,
            syncState = SyncState.SYNCED.name), linked)
        assertTrue(cloudSync.awaitingDownload(linked))
        assertEquals(RestoreMatchResult(), matcher().match(owner, false) { true })
        val staged = File.createTempFile("stage4-stored", ".part", context.cacheDir).apply { writeText(payload) }
        val saved = repo.fillExistingFile(linked, staged, payload.length.toLong(), checksum, 12, 7000) { true }
        assertEquals(row.id, saved.id)
        assertEquals(6L, saved.serverId)
        assertEquals("改名", saved.localTitleOverride)
        assertEquals(8L, saved.localCategoryId)
        assertTrue(saved.favorite)
        assertEquals(listOf(item), db.playlistDao().itemsNow(playlist))
        assertEquals(payload, repo.download(saved).getOrThrow().readText())
        assertFalse(cloudSync.awaitingDownload(saved))
        assertEquals(1, dao.allNow().size)
    }

    @Test fun bindingCopiesServerStorageSoArchiveIsKnownLocally() = runBlocking {
        dao.insertIgnore(listOf(row))
        val archived = cloud.copy(storage = "archive")
        assertEquals(RestoreMatchResult(bound = 1), matcher(page(archived)).match(owner, false) { true })

        val linked = requireNotNull(dao.byId(row.id))
        assertEquals("archive", linked.serverStorage)
        // 归档行不给"同时从云端移除"的选项；删除确认只说明"云端归档会保留"
        assertTrue(cloudSync.isCloudArchive(linked))
        assertFalse(cloudSync.canQueueCloudDelete(linked, session()))
    }

    private fun session() = com.getmedia.app.account.SessionStore.Session(
        loginName = owner, token = "t", expiresAt = Long.MAX_VALUE, deviceId = "d",
        tier = "full", consentedPrivacyVersion = 3,
    )

    @Test fun distinctCloudIdsForEquivalentPartitionAcrossPagesLeaveOriginal() = runBlocking {
        dao.insertIgnore(listOf(row))
        val legacy = cloud.copy(id = 8, videoId = "$bv-p3", page = 1, updatedSeq = 18)
        val result = matcher(page(cloud, more = true), page(legacy)).match(owner, false) { true }
        assertEquals(RestoreMatchIssue.AMBIGUOUS, result.notices.single().issue)
        assertEquals(listOf(row), dao.allNow())
    }

    @Test fun conflictingPageAndWrongMediaTypeNeverBindOrEnableRedownload() = runBlocking {
        dao.insertIgnore(listOf(row))
        for (item in listOf(cloud.copy(videoId = "$bv-p3", page = 2),
            cloud.copy(sourceUrl = "$source?p=1"), cloud.copy(mediaType = "video"))) {
            val result = matcher(page(item)).match(owner, false) { true }
            assertEquals(RestoreMatchIssue.CONFLICT, result.notices.single().issue)
            assertEquals(row, dao.byId(row.id))
        }
    }

    @Test fun tombstoneAndAlreadyOccupiedIdKeepOriginal() = runBlocking {
        dao.insertIgnore(listOf(row))
        assertEquals(RestoreMatchIssue.TOMBSTONE,
            matcher(page(cloud.copy(deleted = true))).match(owner, false) { true }.notices.single().issue)
        dao.insertIgnore(listOf(row.copy(id = -72, syncState = SyncState.SYNCED.name, serverId = 6)))
        assertEquals(RestoreMatchIssue.CONFLICT,
            matcher(page(cloud)).match(owner, false) { true }.notices.single().issue)
        assertEquals(row, dao.byId(row.id))
    }

    @Test fun duplicateLocalRowsAndSameServerIdWithContradictoryIdentityAreRejected() = runBlocking {
        dao.insertIgnore(listOf(row, row.copy(id = -72)))
        val duplicate = matcher(page(cloud)).match(owner, false) { true }
        assertEquals(listOf(RestoreMatchIssue.LOCAL_DUPLICATE, RestoreMatchIssue.LOCAL_DUPLICATE),
            duplicate.notices.map { it.issue })
        dao.updateExisting(row.copy(id = -72, accountKey = "other"))
        val contradiction = matcher(page(cloud, more = true), page(cloud.copy(page = 1, updatedSeq = 18)))
        assertEquals(RestoreMatchIssue.CONFLICT, contradiction.match(owner, false) { true }.notices.single().issue)
        assertEquals(row, dao.byId(row.id))
    }

    @Test fun legacyRequiresConfirmationAndForeignAccountRemainsIntact() = runBlocking {
        val legacy = row.copy(accountKey = null)
        val foreign = row.copy(id = -49, accountKey = "other")
        dao.insertIgnore(listOf(legacy, foreign))
        assertEquals(RestoreMatchIssue.LEGACY_ACCOUNT, matcher().match(owner, false) { true }.notices.single().issue)
        assertEquals(1, matcher(page(cloud)).match(owner, true) { true }.bound)
        assertEquals(owner, dao.byId(row.id)?.accountKey)
        assertEquals(foreign, dao.byId(foreign.id))
    }

    @Test fun partialPullAndSessionChangeDoNotWriteOrAdvanceIncrementalCursor() = runBlocking {
        dao.insertIgnore(listOf(row))
        db.libraryAuxDao().putCursor(LibraryCursorEntity(owner, 99, 7))
        val result = runCatching { matcher(page(cloud, more = true),
            LibrarySyncException(LibrarySyncProblem.NETWORK, "network")).match(owner, false) { true } }
        assertTrue(result.isFailure)
        var valid = true
        val switched = RestoredMatcher(db, object : LibrarySyncTransport {
            override suspend fun pull(sinceSeq: Long, limit: Int) = page(cloud).also { valid = false }
        })
        assertTrue(runCatching { switched.match(owner, false) { valid } }.isFailure)
        assertEquals(99L, db.libraryAuxDao().cursor(owner)?.lastPos)
        assertEquals(row, dao.byId(row.id))
    }

    @Test fun unmatchedOnlyNotifiesAndDoesNotUploadEvenIfRestoredAlreadyHasFile() = runBlocking {
        dao.insertIgnore(listOf(row))
        val uploader = uploader()
        val result = matcher(page(cloud.copy(page = 1))).match(owner, false) { true }
        assertEquals(RestoreMatchIssue.UNMATCHED, result.notices.single().issue)
        assertEquals(row, dao.byId(row.id))
        assertTrue(uploader.candidates().isEmpty())
        val file = File.createTempFile("stage4-old", ".audio", context.cacheDir).apply { writeText(payload) }
        dao.updateExisting(row.copy(localPath = file.absolutePath))
        assertTrue(uploader.candidates().isEmpty())
        assertTrue(uploader.upload().isEmpty)
    }

    @Test fun explicitRedownloadPromotesOnlyVerifiedFileAndRetainsOrganization() = runBlocking {
        dao.insertIgnore(listOf(row))
        val playlist = db.playlistDao().insert(PlaylistEntity(name = "原列表"))
        val item = PlaylistItemEntity(playlist, row.id, orderIndex = 3)
        db.playlistDao().add(listOf(item))
        assertEquals(RestoreMatchIssue.UNMATCHED, matcher(page()).match(owner, false) { true }.notices.single().issue)
        val prefs = LibrarySyncPrefs(context)
        val automatic = uploader { !prefs.requiresManualUpload(owner, it) }
        assertTrue(automatic.candidates().isEmpty())
        assertEquals(row, dao.byId(row.id))

        for (wrong in listOf(row.copy(videoId = bv), row.copy(mediaType = "VIDEO"), row.copy(audioSha256 = "wrong"))) {
            val outcome = runCatching { redownload(wrong, prefs).download(row) { true } }
            assertTrue(outcome.isFailure)
            assertEquals(row, dao.byId(row.id))
            assertTrue(uploader().candidates().isEmpty())
        }

        val saved = redownload(row.copy(audioSha256 = checksum), prefs).download(row) {
            dao.rename(row.id, "下载中改名", 123)
            true
        }
        assertEquals(row.id, saved.id)
        assertEquals(SyncState.LOCAL_ONLY.name, saved.syncState)
        assertEquals("下载中改名", saved.localTitleOverride)
        assertEquals(8L, saved.localCategoryId)
        assertTrue(saved.favorite)
        assertEquals(listOf(item), db.playlistDao().itemsNow(playlist))
        assertEquals(payload, repo.download(saved).getOrThrow().readText())
        assertEquals(listOf(saved), uploader().candidates())
        assertTrue(automatic.upload().isEmpty)
    }

    private fun redownload(result: TrackEntity, prefs: LibrarySyncPrefs) = RestoredRedownload(
        context.cacheDir, { input, _, file ->
            assertEquals("$source?p=3", input)
            file.writeText(payload)
            result.copy(localPath = file.absolutePath, audioBytes = file.length())
        },
    ) { original, file, size, hash, format ->
        check(prefs.requireManualUpload(owner, original.id))
        repo.fillRestoredFile(original, file, size, hash, format, owner) { true }
    }

    private fun uploader(allow: (Long) -> Boolean = { true }) = LibraryUploader(
        db, object : LibraryUploadTransport {
            override suspend fun create(entry: UploadEntry): CreatedTrack = error("意外上传")
        }, owner, allowCandidate = allow,
    )
}
