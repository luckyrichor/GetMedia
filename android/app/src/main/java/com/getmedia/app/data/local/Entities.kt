package com.getmedia.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * 本地媒体库（音频与视频）。全部数据只在本机；`mediaType` 区分类型（见 [MediaKind]）。
 *
 * id 为本机生成的负数（提取、导入、剪辑副本各自派生），库里不存在服务器编号。
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: Long,
    val platform: String,
    val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSec: Double?,
    /** 平台封面地址；仅手机直提取可得。 */
    val coverUrl: String? = null,
    val audioBytes: Long?,
    val audioFormat: String,
    val sourceUrl: String,
    val audioSha256: String? = null,

    /** 本地文件路径；null 表示文件缺失 */
    val localPath: String? = null,
    @ColumnInfo(defaultValue = "0") val locallyEdited: Boolean = false,
    val sourceTrackId: Long? = null,
    val favorite: Boolean = false,
    @ColumnInfo(defaultValue = "1") val localCategoryId: Long = 1,
    @ColumnInfo(defaultValue = "0") val updatedAtMs: Long = 0,
    /** 本机重命名后保留的显示名。 */
    val localTitleOverride: String? = null,
    @ColumnInfo(defaultValue = "0") val addedAtMs: Long = 0,
    /**
     * 列表里的手动顺序；0 表示还没手动排过，这类记录排在最前面（新入库的自然在顶部）。
     * 拖动排序后会给整个库重新编号 1..N。
     */
    @ColumnInfo(defaultValue = "0") val sortIndex: Long = 0,
    @ColumnInfo(defaultValue = "'AUDIO'") val mediaType: String = MediaKind.AUDIO.name,

    // ---------- 服务器来源（P0-1 起写入；本机提取的行保持 null/LOCAL_ONLY） ----------
    //
    // 从云端清单"恢复"出来的行（RestoreService）是另一回事：serverId / serverSeq /
    // serverUpdatedAtMs / accountKey 全为 null，serverPage 记快照里的真实页码，
    // syncState = RESTORED。这四个 null 表示"这行的服务器身份未知"，RESTORED 表示
    // "别当新条目上传"——两条合起来才是 P0-2 的判断依据。

    /** 服务器 track id；P0-2 的匹配键。null 表示这行没在服务器上出现过（含恢复出来的行）。 */
    val serverId: Long? = null,
    /** 上次见到的服务器 `updated_seq`，P0-2 用它判断哪边更新。 */
    val serverSeq: Long? = null,
    /**
     * 服务器页码。B 站多 P 的行 id 已含页码（见 [LocalIds.key]），各 P 互不覆盖；
     * 这里留一份原文用于展示与排查，也是恢复出来的行唯一还能拿到的页码来源
     * （服务器提取写的 `sourceUrl` 没有 `?p=`）。
     */
    @ColumnInfo(defaultValue = "1") val serverPage: Int = 1,
    /** 服务器 `updated_at`（秒 ×1000）；null 表示未知。 */
    val serverUpdatedAtMs: Long? = null,
    /** 同步状态：`LOCAL_ONLY` / `SYNCED` / `RESTORED`；P0-2 的状态机从这里继续。 */
    @ColumnInfo(defaultValue = "'LOCAL_ONLY'") val syncState: String = SyncState.LOCAL_ONLY.name,
    /** 登录名；P0-2 按账号隔离同步，本机提取与恢复出来的行都留 null。 */
    val accountKey: String? = null,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    /** null=空类别，CONTAINER=子类别，LEAF=音频。 */
    val kind: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val lastAccessedAt: Long = 0,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val isDefault: Boolean = false,
)

@Entity(tableName = "playlist_items", primaryKeys = ["playlistId", "trackId"])
data class PlaylistItemEntity(
    val playlistId: Long,
    val trackId: Long,
    val addedAt: Long = System.currentTimeMillis(),
    val orderIndex: Int,
)

/**
 * 待重放的"本机删除"记录（P0-2 骨架，设计 §8）。
 *
 * 名字刻意中性：这张表和曲库在同一个库文件里，free 也带着它，所以表名/列名里不出现
 * sync / server / cloud 一类字样。free 的代码一行都不读它。
 */
@Entity(tableName = "track_removals", primaryKeys = ["platform", "videoKey", "account"])
data class TrackRemovalEntity(
    val platform: String,
    /** 与曲库同一套键形态：B 站分 P 是 `BV1xx-p2`。 */
    val videoKey: String,
    /** 登录名。删除按账号隔离，换账号不会拿别人的队列去删。 */
    val account: String,
    /** 那条记录在远端的编号；没有就留空，靠平台 + 键去对。 */
    val remoteId: Long? = null,
    val requestedAt: Long = 0,
)

/**
 * 每个账号各自处理到哪了（P0-2 骨架，设计 §8）。
 *
 * 同样取中性名：`lastPos` 是远端给的序号，只做"大于"比较，不做算术（序号全局递增、跨账号不连续）。
 */
@Entity(tableName = "library_cursor")
data class LibraryCursorEntity(
    @PrimaryKey val account: String,
    val lastPos: Long = 0,
    val lastAt: Long = 0,
    /** 上次失败的一句话摘要，只用于设置页展示，不含标题与账号。 */
    val note: String? = null,
)
