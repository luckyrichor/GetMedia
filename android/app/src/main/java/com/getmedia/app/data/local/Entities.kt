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
