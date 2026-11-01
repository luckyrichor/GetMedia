package com.getmedia.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * GetMedia 本地库。schema 从 1 起，不迁移其他应用的旧数据。
 * 之后的结构变更必须新增 Migration，不得使用破坏性迁移。
 */
@Database(
    entities = [TrackEntity::class, CategoryEntity::class, PlaylistEntity::class, PlaylistItemEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun categoryDao(): CategoryDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "getmedia.db",
            )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        db.execSQL("INSERT OR IGNORE INTO categories (id,name,parentId,kind,createdAt,accessCount,lastAccessedAt) VALUES (1,'未分类',NULL,'LEAF',0,0,0)")
                    }
                })
                .build()
                .also { instance = it }
        }
    }
}
