package com.gazereel.vr.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        SourceEntity::class,
        PlayProgressEntity::class,
        FormatOverrideEntity::class,
        AppSettingEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun sourceDao(): SourceDao
    abstract fun progressDao(): ProgressDao
    abstract fun formatOverrideDao(): FormatOverrideDao
    abstract fun settingDao(): SettingDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * V2.17：只加一列。用真实迁移而不是 destructive —— 用户已经配好片源、
         * 看过一半的视频都在库里，升级不能把这些清掉。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE source ADD COLUMN recursive INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * V2.18：断点表的主键从自增 `media_id` 换成 `media_key = sourceId:path`。
         *
         * 为什么必须换：`media_item` 的行只要被重建过（改递归范围、换文件夹、
         * 重扫时先删后插），自增 id 就会变，而断点还挂在旧 id 上 —— 用户看到的就是
         * 「隐藏之后再显示回来，断点没了」。路径是稳定的，断点该挂在路径上。
         *
         * 迁移要把现有断点接续过来：用 media_item 把 id 翻译成 path，不能直接丢，
         * 否则升级一次就清掉用户所有断点。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS play_progress_new (
                        media_key TEXT NOT NULL,
                        position_ms INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0,
                        finished INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(media_key)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO play_progress_new (media_key, position_ms, updated_at, finished)
                    SELECT m.source_id || ':' || m.path, p.position_ms, p.updated_at, p.finished
                    FROM play_progress p
                    JOIN media_item m ON m.id = p.media_id
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE play_progress")
                db.execSQL("ALTER TABLE play_progress_new RENAME TO play_progress")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "cardboard_vr.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build().also { instance = it }
        }
    }
}
