package com.getmedia.app.data.library

import androidx.room.withTransaction
import com.getmedia.app.BuildConfig
import com.getmedia.app.account.AccountApi
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.backup.MetadataSnapshot
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.CategoryDao
import com.getmedia.app.data.local.CategoryEntity
import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 类别路径：跨设备对齐类别用的唯一形态。
 *
 * 两台手机的类别编号互不相干（本地自增），所以服务器只存**路径**（`音乐/古风`）。
 * 这里负责两个方向的换算：
 * - [pathOf]：本地类别 id → 路径（推送时用）；
 * - [ensurePath]：云端路径 → 本地类别 id（拉取时用，缺哪级补哪级）。
 *
 * 「未分类」永远是 id=1，路径是空串——它不会在云端出现，也不需要同步。
 */
object CategoryPaths {

    const val DEFAULT_ID = 1L

    /** 路径长度上限与服务端一致（200 字）。超长的节点名在本地就会被截断，避免来回报错。 */
    private const val MAX_PATH = 200

    suspend fun pathOf(dao: CategoryDao, id: Long, all: List<CategoryEntity>? = null): String {
        if (id == DEFAULT_ID) return ""
        val nodes = (all ?: dao.allNow()).associateBy { it.id }
        var current = nodes[id] ?: return ""
        val parts = ArrayList<String>()
        val seen = HashSet<Long>()
        while (true) {
            if (!seen.add(current.id)) break          // 环形父子：能建出来就能坏出来，防御一下
            parts += current.name
            val parent = current.parentId?.let { nodes[it] } ?: break
            if (parent.id == DEFAULT_ID) break
            current = parent
        }
        return parts.asReversed().joinToString("/").take(MAX_PATH)
    }

    /**
     * 按路径找到本地类别，缺哪级补哪级；返回最末一级的 id。
     *
     * 只补不改：已存在的节点一个字段都不动（用户可能已经改了名字或加了子类别）。
     * 新建时直接给定 `kind`——中间节点是 `CONTAINER`、末节点是 `LEAF`，
     * 与 [com.getmedia.app.data.repo.CategoryRepository.create] 的规则一致，
     * 免得界面上出现"能装音频又能装子类别"的怪节点。
     */
    suspend fun ensurePath(dao: CategoryDao, path: String, now: Long = System.currentTimeMillis()): Long {
        val parts = path.split("/").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return DEFAULT_ID
        var parentId: Long? = null
        var current = DEFAULT_ID
        for ((index, part) in parts.withIndex()) {
            val all = dao.allNow()
            val found = all.firstOrNull { it.parentId == parentId && it.name == part }
            current = found?.id ?: dao.insert(
                CategoryEntity(
                    name = part.take(60),
                    parentId = parentId,
                    kind = if (index < parts.lastIndex) "CONTAINER" else "LEAF",
                    createdAt = now,
                ),
            )
            parentId = current
        }
        return current
    }
}

/** 云端列表成员：平台三元组，与曲库判重用的是同一套键。 */
data class CloudPlaylistItem(val platform: String, val videoId: String, val page: Int)

/** 云端一个播放列表（`deleted` 是墓碑，靠它让别的设备知道这个列表被删了）。 */
data class CloudPlaylist(
    val name: String,
    val deleted: Boolean,
    val seq: Long,
    val items: List<CloudPlaylistItem>,
)

/**
 * 播放列表的读写通道。拉取与写入分成两个接口，理由与曲库那边一样：
 * 拉取那条路径手上不该有写能力。
 */
interface PlaylistReadTransport {
    suspend fun list(): List<CloudPlaylist>
}

interface PlaylistWriteTransport {
    /** 整体替换；名称不存在则创建，墓碑则复活。 */
    suspend fun put(name: String, items: List<CloudPlaylistItem>): CloudPlaylist
    suspend fun delete(name: String)
}

/** 整理信息（收藏/类别）的单条写入。 */
interface MetaWriteTransport {
    suspend fun patchMeta(trackId: Long, favorite: Boolean, category: String): CloudTrack
}

/**
 * 真实的播放列表通道：`GET/PUT/DELETE /v2/sync/playlists`。
 *
 * 名称走 JSON 体与查询参数，**不放路径段**：列表名里出现 `/` 或中文时，路径参数会被
 * 边缘按段切开，编码一次也不一定救得回来。
 */
class PlaylistSyncClient(
    baseUrl: String = BuildConfig.OWNER_SERVER_BASE,
    private val client: OkHttpClient = AccountApi.defaultClient(),
    private val token: () -> String? = SessionStore::token,
) : PlaylistReadTransport, PlaylistWriteTransport {

    private val base = baseUrl.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()

    init { require(base.startsWith("https://") || AccountApi.ALLOW_TEST_HTTP) { "只允许 HTTPS" } }

    override suspend fun list(): List<CloudPlaylist> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url((base + PATH).toHttpUrl())
            .header("Accept", "application/json")
            .header("X-GetMedia-Session", session())
            .get()
            .build()
        call(request) { text ->
            val root = JSONObject(text)
            val array = root.optJSONArray("playlists") ?: JSONArray()
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::parsePlaylist)
            }
        }
    }

    override suspend fun put(name: String, items: List<CloudPlaylistItem>): CloudPlaylist =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("name", name)
                .put("items", JSONArray().apply {
                    items.forEach { item ->
                        put(JSONObject().put("platform", item.platform)
                            .put("video_id", item.videoId).put("page", item.page))
                    }
                })
                .toString()
            val request = Request.Builder()
                .url((base + PATH).toHttpUrl())
                .header("Accept", "application/json")
                .header("X-GetMedia-Session", session())
                .put(body.toRequestBody(json))
                .build()
            call(request) { text -> parsePlaylist(JSONObject(text)) }
        }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        val url = (base + PATH).toHttpUrl().newBuilder()
            .addQueryParameter("name", name)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-GetMedia-Session", session())
            .delete()
            .build()
        call(request) { /* 响应体只有确认字段，不需要参与任何判断 */ }
        Unit
    }

    private fun parsePlaylist(o: JSONObject): CloudPlaylist {
        val items = o.optJSONArray("items")
        val parsed = ArrayList<CloudPlaylistItem>()
        if (items != null) {
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val platform = it.text("platform").orEmpty().trim()
                val videoId = it.text("video_id").orEmpty().trim()
                if (platform.isEmpty() || videoId.isEmpty()) continue
                parsed += CloudPlaylistItem(platform, videoId, it.optInt("page", 1).coerceIn(1, 9999))
            }
        }
        return CloudPlaylist(
            name = o.text("name").orEmpty().trim(),
            deleted = o.optBoolean("deleted", false),
            seq = o.optLong("updated_seq", 0),
            items = parsed,
        )
    }

    private fun <T> call(request: Request, parse: (String) -> T): T = try {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw LibrarySyncJson.error(
                    response.code, text, LibrarySyncJson.retryAfterSeconds(response.header("Retry-After")),
                )
            }
            parse(text)
        }
    } catch (e: IOException) {
        throw LibrarySyncException(
            LibrarySyncProblem.NETWORK, "网络不可用，请检查连接后重试", detail = e.message.orEmpty(),
        )
    }

    private fun session(): String =
        token() ?: throw LibrarySyncException(LibrarySyncProblem.LOGIN_REQUIRED, "请先登录")

    companion object {
        const val PATH = "/v2/sync/playlists"
    }
}

/**
 * 收藏与类别的推送。
 *
 * 只挑 `metaDirty = 1` 且已绑 `serverId` 的行——没绑定说明服务器上还没有这一条，
 * 等 [LibrarySyncEngine] 补上绑定后下一轮自然会推。
 *
 * 失败处理分三类：**能重试的**（网络/限流/服务器错/接口还没部署）停下整批、保留脏位；
 * **这条没救的**（云端已删、明确 404、其它 4xx）清掉脏位，不让一条坏数据卡住后面所有条目。
 */
class LibraryMetaPush(
    private val db: AppDatabase,
    private val transport: MetaWriteTransport,
    private val account: String,
) {

    data class Result(val pushed: Int = 0, val stopped: LibrarySyncProblem? = null, val reason: String = "")

    suspend fun push(): Result {
        var pushed = 0
        val categories = db.categoryDao().allNow()
        for (row in db.trackDao().metaDirty(account)) {
            val categoryPath = CategoryPaths.pathOf(db.categoryDao(), row.localCategoryId, categories)
            val serverId = row.serverId ?: continue
            try {
                val cloud = transport.patchMeta(serverId, row.favorite, categoryPath)
                db.trackDao().markMetaClean(
                    row.id, cloud.updatedSeq,
                    (cloud.updatedAtSec * 1000).takeIf { it > 0 } ?: row.serverUpdatedAtMs ?: 0L,
                )
                pushed++
            } catch (e: LibrarySyncException) {
                when (e.problem) {
                    LibrarySyncProblem.NETWORK, LibrarySyncProblem.RATE_LIMITED,
                    LibrarySyncProblem.SERVER, LibrarySyncProblem.NOT_AVAILABLE,
                    LibrarySyncProblem.LOGIN_REQUIRED ->
                        return Result(pushed, e.problem, e.message)
                    else -> db.trackDao().clearMetaDirty(row.id)
                }
            }
        }
        return Result(pushed)
    }
}

/**
 * 播放列表的双向同步：先重放本机删除 → 拉云端 → 推本地改动。
 *
 * 冲突规则与隐私说明 v3 写的一致：**同名即同一个列表**，整个列表以最后一次写入为准。
 * 本地有未推送改动（`dirty = 1`）时不拿云端覆盖，留给本轮推送——否则用户刚拖好的顺序
 * 会被下一分钟的轮询用云端旧顺序顶掉。
 */
class LibraryPlaylistSync(
    private val db: AppDatabase,
    private val transport: PlaylistReadTransport,
    private val writer: PlaylistWriteTransport,
) {

    data class Result(
        val pulled: Int = 0,
        val created: Int = 0,
        val removed: Int = 0,
        val pushed: Int = 0,
        val blocked: Int = 0,
        val stopped: LibrarySyncProblem? = null,
        val reason: String = "",
    )

    suspend fun sync(account: String, push: Boolean = true): Result {
        val dao = db.playlistDao()

        // ① 先重放本机删除：不先删，刚删掉的列表会在 ② 里被云端建回来
        if (push) {
            for (removal in dao.removals(account)) {
                try {
                    writer.delete(removal.name)
                    dao.dropRemoval(account, removal.name)
                } catch (e: LibrarySyncException) {
                    when (e.problem) {
                        LibrarySyncProblem.NETWORK, LibrarySyncProblem.RATE_LIMITED,
                        LibrarySyncProblem.SERVER, LibrarySyncProblem.NOT_AVAILABLE,
                        LibrarySyncProblem.LOGIN_REQUIRED ->
                            return Result(stopped = e.problem, reason = e.message)
                        // 名称不合法之类：这条永远推不上去，丢掉，否则每次都卡在队首
                        else -> dao.dropRemoval(account, removal.name)
                    }
                }
            }
        }

        // ② 拉云端（接口不可用就整轮停手：没有云端内容可对，也不该把本地硬推上去）
        val cloud = try {
            transport.list()
        } catch (e: LibrarySyncException) {
            return Result(stopped = e.problem, reason = e.message)
        }

        var pulled = 0
        var created = 0
        var removed = 0
        var blocked = 0
        val now = System.currentTimeMillis()
        // 用户在本机删掉、还没重放成功的作品：列表成员对不上时**不补占位行**，
        // 否则下一轮就把刚删的作品又"复活"成一条未下载条目。
        val queuedKeys = db.libraryAuxDao().removals(account)
            .mapTo(HashSet()) { it.platform.lowercase() to it.videoKey }
        db.withTransaction {
            val rows = db.trackDao().allNow()
            for (remote in cloud) {
                if (remote.name.isBlank()) continue
                val local = dao.byName(remote.name)
                if (remote.deleted) {
                    if (local != null) {
                        dao.clear(local.id)
                        dao.delete(local.id)
                        removed++
                    }
                    continue
                }
                if (local == null) {
                    val id = dao.insert(PlaylistEntity(name = remote.name, dirty = false, syncedAt = now))
                    writeItems(id, remote.items, rows, account, queuedKeys, now)
                    created++
                } else if (local.syncedAt == 0L) {
                    // 首次对齐（含从老版本升上来）：以云端为准，哪怕本地这一份正在待推送。
                    // 反过来的话，先升级的设备会把自己的内容盖掉另一台已经同步好的列表。
                    dao.clear(local.id)
                    writeItems(local.id, remote.items, rows, account, queuedKeys, now)
                    dao.markSynced(local.id, now)
                    pulled++
                } else if (!local.dirty) {
                    dao.clear(local.id)
                    writeItems(local.id, remote.items, rows, account, queuedKeys, now)
                    pulled++
                } else {
                    // 本地有未推送的改动：不覆盖，交给 ③
                    blocked++
                }
            }
            // 存量修复：列表里有些成员指向的曲库行已经不在了（历史遗留：先建行、行后来被删，
            // 或者这台设备从来就没这行）。云端列表带着完整三元组，正好能把它们补回来。
            // 只"补行"不动成员与顺序，所以不会盖掉用户还没推上去的改动。
            healMissing(account, cloud, queuedKeys, now)
        }

        // ③ 推本地改过的列表：整体替换，一次写要么全成要么全不成
        var pushed = 0
        if (push) {
            val rowsById = db.trackDao().allNow().associateBy { it.id }
            for (local in dao.dirtyNow()) {
                // 首次对齐已经在 ② 里按云端内容重写过，还标着脏就是"云端没有这个列表"——
                // 这时把本地这份传上去才对（比如刚升级、云端还是空的）。
                val items = dao.itemsNow(local.id).sortedBy { it.orderIndex }.mapNotNull { item ->
                    val track = rowsById[item.trackId] ?: return@mapNotNull null
                    CloudPlaylistItem(
                        platform = track.platform.lowercase(),
                        videoId = MetadataSnapshot.videoIdOf(track),
                        page = MetadataSnapshot.pageOf(track),
                    )
                }
                try {
                    writer.put(local.name, items)
                    dao.markSynced(local.id, System.currentTimeMillis())
                    pushed++
                } catch (e: LibrarySyncException) {
                    when (e.problem) {
                        LibrarySyncProblem.NETWORK, LibrarySyncProblem.RATE_LIMITED,
                        LibrarySyncProblem.SERVER, LibrarySyncProblem.NOT_AVAILABLE,
                        LibrarySyncProblem.LOGIN_REQUIRED ->
                            return Result(pulled, created, removed, pushed, blocked, e.problem, e.message)
                        else -> dao.markClean(local.id)   // 这一条推不上去，别卡住别的列表
                    }
                }
            }
        }
        return Result(pulled, created, removed, pushed, blocked)
    }

    /**
     * 把云端条目写成本地条目。
     *
     * **对不上曲库行的成员不再丢弃**（0.1.33 起）：按确定性 id 补一条"占位行"——状态与
     * 「云端未下载」完全相同（`CLOUD_ONLY`、无文件、无 `serverId`），列表里照样占位、
     * 界面上标未下载；等另一台把那件作品传上云端，曲库拉取会按同一个 id 把这一行绑上
     * （[LibrarySyncEngine] 的 `bindSameKey`），不需要任何清理动作。
     *
     * 之前这里直接 `return@forEachIndexed`：成员在这台设备上凭空消失，而且**不会自愈**
     * （每一轮都按云端重建，缺的行一直缺）。更糟的是这台设备一旦有本地改动，整体替换会
     * 把"少一条"的整表 PUT 上去，云端跟着少——另一台再拉下来也少了。11-22 的
     * 「生死相随」就是这么丢的：手机 2 曲库对不上，推了 5 条把云端的 6 条盖掉。
     *
     * 两个例外仍然跳过：
     * - 键在本机删除队列里（`queuedKeys`）：用户刚删过这件作品，补回来等于复活；
     * - 键算不出（空平台/空作品号已在解析时过滤，这里不会出现）。
     */
    private suspend fun writeItems(
        playlistId: Long,
        items: List<CloudPlaylistItem>,
        rows: List<TrackEntity>,
        account: String,
        queuedKeys: Set<Pair<String, String>>,
        now: Long,
    ) {
        if (items.isEmpty()) return
        val byId = rows.associateBy { it.id }
        val additions = ArrayList<PlaylistItemEntity>(items.size)
        val missing = LinkedHashMap<Long, TrackEntity>()
        items.forEachIndexed { index, item ->
            val known = localIdOf(item, rows, byId)
            if (known != null) {
                additions += PlaylistItemEntity(playlistId, known, orderIndex = index)
                return@forEachIndexed
            }
            val key = LocalIds.key(item.platform, item.videoId, item.page)
            if ((item.platform.lowercase() to key) in queuedKeys) return@forEachIndexed
            val id = LocalIds.localId(item.platform, item.videoId, item.page)
            missing.getOrPut(id) { placeholder(id, item, account, now) }
            additions += PlaylistItemEntity(playlistId, id, orderIndex = index)
        }
        // IGNORE：占位行与真实行撞 id 时绝不覆盖真实行（真实行的键与 id 一定一致，
        // 走到这里只是因为本轮 `rows` 是事务开始时的快照）。
        if (missing.isNotEmpty()) db.trackDao().insertIgnore(missing.values.toList())
        if (additions.isNotEmpty()) db.playlistDao().add(additions)
    }

    /**
     * 存量修复：成员指向的曲库行不存在时，按云端三元组把行补回来（占位行）。
     *
     * 为什么需要它：`writeItems` 保证的是"以后写进来的成员都有行"。可历史上已经写进来的
     * 成员可能还指着一条后来被删掉的行——界面按 id 找行找不到就整条不渲染，于是卡片写着
     * "6 项"、点进去只看到 2 条。成员的身份信息只剩一个 id，反推不出平台与作品号，
     * 只有云端列表还带着完整三元组，所以修复必须借云端这一份。
     *
     * 三个约束：
     * - **只补行，不碰成员**：不加、不删、不排序，用户没推上去的改动原样保留；
     * - 键在本机删除队列里的不补（用户删过这件作品，补回来等于复活）；
     * - `insertIgnore`，与已有行撞 id 时绝不覆盖。
     */
    private suspend fun healMissing(
        account: String,
        cloud: List<CloudPlaylist>,
        queuedKeys: Set<Pair<String, String>>,
        now: Long,
    ) {
        val items = db.playlistDao().itemsAll()
        if (items.isEmpty()) return
        val present = items.mapTo(HashSet()) { it.trackId }
        val rows = db.trackDao().allNow().mapTo(HashSet()) { it.id }
        val filling = LinkedHashMap<Long, TrackEntity>()
        for (remote in cloud) {
            if (remote.deleted) continue
            for (item in remote.items) {
                val id = LocalIds.localId(item.platform, item.videoId, item.page)
                if (id !in present || id in rows) continue
                val key = LocalIds.key(item.platform, item.videoId, item.page)
                if ((item.platform.lowercase() to key) in queuedKeys) continue
                filling.getOrPut(id) { placeholder(id, item, account, now) }
            }
        }
        if (filling.isNotEmpty()) db.trackDao().insertIgnore(filling.values.toList())
    }

    /**
     * 列表成员的占位行：**有位置、没文件、不能播**，标成「云端未下载」。
     *
     * 标题只能拿成员自己的标识：云端列表只存平台三元组（`platform/video_id/page`），
     * 没有标题；曲库里对不上也意味着这台设备拿不到标题。`local` 平台的 `video_id`
     * 本来就是作品名，所以这类占位条显示正常；`bilibili`/`douyin` 会先显示作品号，
     * 等云端曲库行到了以后由 [LibrarySyncEngine] 的合并逻辑换成真标题。
     *
     * `mediaType` 只能猜 `AUDIO`：三元组里没有类型。真行绑定后也不会改回来，
     * 所以占位条若其实是视频，界面上会按音频措辞显示——只影响措辞，不影响播放。
     */
    private fun placeholder(
        id: Long,
        item: CloudPlaylistItem,
        account: String,
        now: Long,
    ) = TrackEntity(
        id = id,
        platform = item.platform,
        videoId = LocalIds.key(item.platform, item.videoId, item.page),
        title = item.videoId,
        uploader = null,
        durationSec = null,
        audioBytes = null,
        audioFormat = "",
        sourceUrl = "",
        localPath = null,
        favorite = false,
        localCategoryId = MetadataSnapshot.DEFAULT_CATEGORY_ID,
        updatedAtMs = now,
        addedAtMs = now,
        sortIndex = 0,
        mediaType = MediaKind.AUDIO.name,
        serverId = null,
        syncState = LibraryMark.CLOUD_ONLY,
        accountKey = account,
    )

    /** 先用确定性 id 直接命中；历史上按旧规则写入的行再用键比对兜底。 */
    private fun localIdOf(item: CloudPlaylistItem, rows: List<TrackEntity>, byId: Map<Long, TrackEntity>): Long? {
        val direct = LocalIds.localId(item.platform, item.videoId, item.page)
        if (byId.containsKey(direct)) return direct
        return rows.firstOrNull { row ->
            row.platform.equals(item.platform, ignoreCase = true) &&
                MetadataSnapshot.videoIdOf(row) == item.videoId &&
                MetadataSnapshot.pageOf(row) == item.page
        }?.id
    }
}

/** 值为 JSON `null` 时裸 `optString` 会返回字符串 `"null"`，不能用。 */
private fun JSONObject.text(name: String): String? =
    if (isNull(name)) null else optString(name).ifBlank { null }
