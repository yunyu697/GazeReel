package com.gazereel.vr.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * V2.45：配置页的片库卡片要显示「这个片库到底扫到几个视频」。
 * 原来标题行只写「N 个片源」，用户看不出某个片库是不是空的、重扫有没有用。
 */
data class SourceCountRow(
    @ColumnInfo(name = "source_id") val sourceId: Long,
    @ColumnInfo(name = "video_count") val videoCount: Int
)

@Dao
interface MediaDao {
    @Query("SELECT source_id, COUNT(*) AS video_count FROM media_item GROUP BY source_id")
    fun observeCountsBySource(): Flow<List<SourceCountRow>>

    // alpha35：全库统一按文件名（大小写不敏感）升序，path 兜底。
    // 与 FileNameOrder.COMPARATOR 一一对应，别只改一边。
    @Query("SELECT * FROM media_item WHERE source_id = :sourceId ORDER BY title COLLATE NOCASE ASC, path ASC")
    fun observeBySource(sourceId: Long): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_item ORDER BY title COLLATE NOCASE ASC, path ASC")
    fun observeAll(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_item ORDER BY title COLLATE NOCASE ASC, path ASC")
    suspend fun all(): List<MediaItemEntity>

    /**
     * alpha35 / 数据层：自动连播 / 上一部下一部专用。
     *
     * 原来 `nextOf()` 直接 `all()` 查全表，隐藏掉的片源照样会被连播出来——
     * 用户藏起一个柜子，过一会儿它自己又播上了，且完全不报错。
     * 只 JOIN 到 `enabled = 1` 的片源，排序仍用同一套文件名序。
     */
    @Query("SELECT m.* FROM media_item m INNER JOIN source s ON s.id = m.source_id WHERE s.enabled = 1 ORDER BY m.title COLLATE NOCASE ASC, m.path ASC")
    suspend fun allVisible(): List<MediaItemEntity>

    /**
     * V2.46 / P0：扫描完成后返回「这个片库里一共有几个视频」。
     * 原来 MediaIndexer.scan() 返回的是「本次变更数」，一部没变时报 0，
     * 配置页就显示「这次没找到视频」——和卡片上「120 个视频」自相矛盾。
     */
    @Query("SELECT COUNT(*) FROM media_item WHERE source_id = :sourceId")
    suspend fun countBySource(sourceId: Long): Int

    @Query("SELECT * FROM media_item WHERE id = :id")
    suspend fun byId(id: Long): MediaItemEntity?

    @Query("SELECT * FROM media_item WHERE source_id = :sourceId AND path = :path LIMIT 1")
    suspend fun byPath(sourceId: Long, path: String): MediaItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_item WHERE source_id = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /**
     * alpha35 / 数据层：重扫差集删除。
     *
     * NAS 上删掉的文件原来会永远留在库里变成打不开的幽灵卡片，因为扫描只
     * upsert 不做差集。调用方必须按 SQLite 的 999 参数上限分块传入。
     */
    @Query("DELETE FROM media_item WHERE source_id = :sourceId AND path IN (:paths)")
    suspend fun deleteByPaths(sourceId: Long, paths: List<String>)
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

    /**
     * alpha35 / 数据层：删片库 / 换凭证前的引用计数。
     *
     * 同一台设备上开多个文件夹会共用同一个 `credentialRef`，原来删一个片库
     * 就无条件 `credentials.remove(ref)`，其余片库集体连不上且很难查。
     * 删除/换掉凭证前先数一数还有谁在用，归零才真正移除。
     */
    @Query("SELECT COUNT(*) FROM source WHERE credential_ref = :ref")
    suspend fun countByCredentialRef(ref: String): Int
}

@Dao
interface ProgressDao {
    /** 阶段二 FR-10：片库卡片需要实时显示「上次看到」的断点进度。 */
    @Query("SELECT * FROM play_progress")
    fun observeAll(): Flow<List<PlayProgressEntity>>

    @Query("SELECT * FROM play_progress WHERE media_key = :mediaKey")
    suspend fun get(mediaKey: String): PlayProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(progress: PlayProgressEntity)

    @Query("SELECT * FROM play_progress WHERE finished = 0 ORDER BY updated_at DESC LIMIT 1")
    suspend fun lastPlayed(): PlayProgressEntity?

    /**
     * V2.18：断点挂在 `sourceId:path` 上，所以删片库时按前缀清，不再依赖 media_item 的 id。
     * 注意 [com.gazereel.vr.ui.SetupActivity.purgeMedia] 已经**不再**调用它——
     * 改递归范围或换文件夹不该丢掉断点，那是用户攒下来的东西。
     */
    @Query("DELETE FROM play_progress WHERE substr(media_key, 1, length(:prefix)) = :prefix")
    suspend fun deleteBySourcePrefix(prefix: String)

    /** alpha35：重扫差集删掉的行，连它的断点一起清，不留孤儿。 */
    @Query("DELETE FROM play_progress WHERE media_key IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)
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

    /** alpha35：重扫差集删掉的行，连它记过的格式一起清。 */
    @Query("DELETE FROM format_override WHERE media_key IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM app_setting WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: AppSettingEntity)
}
