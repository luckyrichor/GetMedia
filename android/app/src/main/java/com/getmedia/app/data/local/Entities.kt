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
    // 从备份恢复时，serverId / serverSeq / serverUpdatedAtMs 仍为空，serverPage 记快照里的页码。
    // 新版可记录备份所属账号。RESTORED 表示服务器条目身份待核对，且不可作为本机新增上传。

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
    /** 登录名；未归属的本机提取和历史恢复行留 null。 */
    val accountKey: String? = null,

    // ---------- 阶段 5：云端删除保护与标题草稿 ----------
    //
    // serverStorage 是服务器那条记录的 storage（metadata / archive / cache）。手机只知道
    // "服务器有文件"，分不出归档与缓存；删除确认要先知道是不是 archive 才能决定要不要给
    // "同时从云端曲库移除"这个选项。null 表示还没拉到（旧数据），按服务端拒绝兜底。
    val serverStorage: String? = null,
    /** 最后一次服务器确认的标题；待推送草稿不会覆盖它，备份也用它。 */
    val confirmedTitle: String? = null,
    /** 待推送的标题草稿；null 表示没有草稿。 */
    val pendingTitle: String? = null,
    /** 草稿基于的云端版本（If-Match 用它）；多次改名不重置。 */
    val pendingTitleBaseSeq: Long? = null,
    /** 本地操作世代：迟到的旧响应不能覆盖后来草稿。 */
    val pendingTitleGen: Long? = null,

    /**
     * 收藏或类别是本机改的、还没推上去（v5 起）。
     *
     * 拉取时先看这一位：为 true 就**不拿云端的收藏/类别覆盖本地**，留给下一轮推送；
     * 推送成功才清回 false。没有它的话，"在这台手机点了收藏、还没推"会被下一分钟的
     * 前台轮询用云端旧值抹掉——用户看到的是收藏自己跳回去了。
     */
    @ColumnInfo(defaultValue = "0") val metaDirty: Boolean = false,

    // ---------- 本机导入文件的云端备份（v7 起） ----------
    //
    // 只有 `platform = local`（用户自己导入的文件）才用得上：这类条目没有原始链接，
    // 换手机时唯一的取回途径就是服务器上那份归档，所以必须真的把文件传上去。
    //
    // 状态取值见 [com.getmedia.app.data.local.ArchiveState]：
    // `NONE`（不涉及）/ `PENDING`（待上传，含退避等待）/ `DONE`（已备份）/
    // `UNABLE`（无法备份，不再自动重试）/ `EVICTED`（云端那份被空间淘汰清掉了）。
    @ColumnInfo(defaultValue = "'NONE'") val archiveState: String = "NONE",
    /** 连续失败次数；一轮 5 次都失败就进入冷却，冷却结束后归零重来。 */
    @ColumnInfo(defaultValue = "0") val archiveTries: Int = 0,
    /** 下一次允许尝试的时间（毫秒时间戳）；0 表示立即可试。 */
    @ColumnInfo(defaultValue = "0") val archiveNextAt: Long = 0,
    /** 一句话原因，只用于界面展示；成功或重新排队时清空。 */
    val archiveNote: String? = null,
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
    /**
     * 本地改动（改名、加删条目、拖动排序）还没推上去（v5 起）。
     *
     * 列表是整体替换推送的，所以不需要记录"改了哪一条"，一个标记就够：
     * 推成功清回 false；拉取时这一位为 true 的列表**不拿云端内容覆盖**。
     */
    @ColumnInfo(defaultValue = "0") val dirty: Boolean = false,
    /**
     * 最近一次与云端对齐的时间（0 表示**从未对齐**，含刚升级上来的老本地列表）。
     *
     * 首次对齐一律以云端为准：两台设备各自有一份同名列表时，先升级的那台不该把
     * 自己的内容盖到云端上——先看看云端有没有，有就听云端的，没有才把自己这份传上去。
     */
    @ColumnInfo(defaultValue = "0") val syncedAt: Long = 0,
)

/**
 * 待重放的"播放列表删除"（v5 起）。
 *
 * 按**名称**记录，因为名称就是跨设备身份（隐私说明 v3：两台设备同名的列表视为同一个）。
 * 与 [TrackRemovalEntity] 同一条纪律：先写队列再动手，推送成功才出队；失败留着重试，
 * 不会出现"本机删了、云端还在、下次拉取又建回来"的循环。
 */
@Entity(tableName = "playlist_removals", primaryKeys = ["account", "name"])
data class PlaylistRemovalEntity(
    val account: String,
    val name: String,
    val requestedAt: Long = System.currentTimeMillis(),
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
    /** 发起删除时看到的远端版本；条件 DELETE 的 If-Match 用它。 */
    val expectedSeq: Long? = null,
    /** 本地操作世代：迟到响应只能清掉同一世代，不能清掉后来的新操作。 */
    @ColumnInfo(defaultValue = "0") val generation: Long = 0,
    /** `QUEUED`（等重放）/ `WAITING`（归档保护或版本冲突，等用户处理）。 */
    @ColumnInfo(defaultValue = "'QUEUED'") val state: String = "QUEUED",
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    /** 一句话原因，只用于设置页展示；不含标题、不含账号。 */
    val lastError: String? = null,
)

/**
 * 从云端拉到的墓碑，等用户批量确认（P0-2 阶段 5）。
 *
 * 只存"云端已删除、本机还保留着文件或整理痕迹"的行；纯云端未下载且无痕迹的行
 * 由同步器直接移出列表（没有文件、没有用户数据可丢）。这里只记身份与版本，
 * **不碰曲库行、不碰文件**；用户选"保留本机文件"后按 [remoteId] 脱钩。
 */
@Entity(tableName = "remote_marks", primaryKeys = ["account", "remoteId"])
data class RemoteMarkEntity(
    val account: String,
    val remoteId: Long,
    val platform: String,
    /** 与曲库同一套键形态。 */
    val videoKey: String,
    val title: String,
    /** 墓碑的 `updated_seq`；用户处理后用它做一次幂等核对。 */
    val seq: Long,
    val seenAt: Long,
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
