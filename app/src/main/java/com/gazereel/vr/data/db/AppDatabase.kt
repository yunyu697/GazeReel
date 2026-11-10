package com.gazereel.vr.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gazereel.vr.data.RunLog

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
         * **建表语句必须和 Room 自己生成的一字不差。** 上一版在这里写了
         * `DEFAULT 0`，而实体上没有 `@ColumnInfo(defaultValue = ...)`，Room 生成的
         * DDL 里没有 DEFAULT —— 迁移跑完 Room 校验表结构不匹配，直接走
         * `fallbackToDestructiveMigration()` **把整个库删了重建**。
         * 用户看到的就是「扫描成功了，但断点全没了」。
         *
         * 教训：手写迁移不要凭想象加约束，照 Room 生成的 DDL 抄。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_progress_new` (" +
                        "`media_key` TEXT NOT NULL, " +
                        "`position_ms` INTEGER NOT NULL, " +
                        "`updated_at` INTEGER NOT NULL, " +
                        "`finished` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`media_key`))"
                )
                // 用 media_item 把旧的 media_id 翻译成路径接续过来，不能直接丢。
                db.execSQL(
                    "INSERT OR REPLACE INTO `play_progress_new` " +
                        "(media_key, position_ms, updated_at, finished) " +
                        "SELECT m.source_id || ':' || m.path, p.position_ms, p.updated_at, p.finished " +
                        "FROM play_progress p JOIN media_item m ON m.id = p.media_id"
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
                // V2.18：万一以后又出现迁移不匹配，至少要在日志里留下证据。
                // 否则用户只会看到「断点没了」，而我这边什么都看不到。
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        RunLog.append(
                            "db",
                            "数据库被销毁重建 —— 迁移缺失或不匹配，断点与格式记忆已丢失"
                        )
                    }
                })
                .build().also { instance = it }
        }
    }
}
