package com.gazereel.vr.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_item WHERE source_id = :sourceId ORDER BY title")
    fun observeBySource(sourceId: Long): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_item ORDER BY title")
    fun observeAll(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_item")
    suspend fun all(): List<MediaItemEntity>

    @Query("SELECT * FROM media_item WHERE id = :id")
    suspend fun byId(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_item WHERE source_id = :sourceId AND path = :path LIMIT 1")
    suspend fun byPath(sourceId: Long, path: String): MediaItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_item WHERE source_id = :sourceId")
    suspend fun deleteBySource(sourceId: Long)
}

@Dao
interface SourceDao {
    /** VR 用：只把没被隐藏的片库喂给片库页。 */
    @Query("SELECT * FROM source WHERE enabled = 1 ORDER BY id")
    fun observeEnabled(): Flow<List<SourceEntity>>

    /**
     * 配置页用：**必须包含被隐藏的片库**。
     * V2.17 一开始这里接成了 `observeEnabled()`，结果是「隐藏之后配置页也不显示它」——
     * 用户再也没有地方把它显示回来，等于删了还回不去。
     */
    @Query("SELECT * FROM source ORDER BY id")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun byId(id: Long): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity): Long

    // V2.6：片库不再只能新增——改名 / 换文件夹 / 换凭证都要能原地保存。
    @Update
    suspend fun update(source: SourceEntity)

    // V2.6：不用的片库可以删掉，连同它索引出来的媒体与凭证一起清理。
    @Query("DELETE FROM source WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE source SET last_scan_at = :at WHERE id = :id")
    suspend fun touchScan(id: Long, at: Long)

    /** V2.17：临时在 VR 里藏起一个片库，不用删掉再重新添加。 */
    @Query("UPDATE source SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}

@Dao
interface ProgressDao {
    /** 阶段二 FR-10：片库卡片需要实时显示「上次看到」的断点进度。 */
    @Query("SELECT * FROM play_progress")
    fun observeAll(): Flow<List<PlayProgressEntity>>

    @Query("SELECT * FROM play_progress WHERE media_id = :mediaId")
    suspend fun get(mediaId: Long): PlayProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(progress: PlayProgressEntity)

    @Query("SELECT * FROM play_progress WHERE finished = 0 ORDER BY updated_at DESC LIMIT 1")
    suspend fun lastPlayed(): PlayProgressEntity?

    // V2.6：删除片库时先删断点（依赖 media_item.id，必须在 deleteBySource 之前调用）。
    @Query("DELETE FROM play_progress WHERE media_id IN (SELECT id FROM media_item WHERE source_id = :sourceId)")
    suspend fun deleteBySource(sourceId: Long)
}

@Dao
interface FormatOverrideDao {
    @Query("SELECT * FROM format_override WHERE media_key = :key")
    suspend fun get(key: String): FormatOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: FormatOverrideEntity)

    @Query("DELETE FROM format_override")
    suspend fun clear()

    // V2.6：格式记忆的键是 "sourceId:path"，删片库时按前缀清掉，不留孤儿数据。
    @Query("DELETE FROM format_override WHERE substr(media_key, 1, length(:prefix)) = :prefix")
    suspend fun deleteBySourcePrefix(prefix: String)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM app_setting WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: AppSettingEntity)
}
