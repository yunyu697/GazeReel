package com.gazereel.vr.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaItemEntity::class,
        SourceEntity::class,
        PlayProgressEntity::class,
        FormatOverrideEntity::class,
        AppSettingEntity::class
    ],
    version = 1,
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

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "cardboard_vr.db"
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
