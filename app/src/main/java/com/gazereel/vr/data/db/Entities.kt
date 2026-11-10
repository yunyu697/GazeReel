package com.gazereel.vr.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * PRD 7：片库主表。列名统一 snake_case，与 PRD 数据表定义一致；
 * Room 默认使用 Kotlin 属性名（camelCase），故逐字段显式声明 @ColumnInfo。
 */
@Entity(tableName = "media_item")
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0L,
    @ColumnInfo(name = "width") val width: Int = 0,
    @ColumnInfo(name = "height") val height: Int = 0,
    @ColumnInfo(name = "codec") val codec: String = "",
    @ColumnInfo(name = "detected_format") val detectedFormat: String = "",
    @ColumnInfo(name = "poster_path") val posterPath: String = "",
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long = 0L,
    @ColumnInfo(name = "mtime") val mtime: Long = 0L
)

/** 数据源配置；密码只存 Keystore 引用，绝不入库（PRD 3.5 隐私）。 */
@Entity(tableName = "source")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "credential_ref") val credentialRef: String = "",
    @ColumnInfo(name = "last_scan_at") val lastScanAt: Long = 0L,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true
)

/** 断点续播与最近播放。 */
@Entity(tableName = "play_progress")
data class PlayProgressEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "position_ms") val positionMs: Long = 0L,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
    @ColumnInfo(name = "finished") val finished: Boolean = false
)

/** 用户手动覆盖的格式，键为 media_key（sourceId:path）。 */
@Entity(tableName = "format_override")
data class FormatOverrideEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_key") val mediaKey: String,
    @ColumnInfo(name = "projection") val projection: String,
    @ColumnInfo(name = "layout") val layout: String,
    @ColumnInfo(name = "fov") val fov: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L
)

/** 凝视时长、自动连播、自动淡出、手势、亮度、音量、IPD 等开关。 */
@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String
)
