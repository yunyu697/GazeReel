package com.gazereel.vr.data

import android.content.Context
import com.gazereel.vr.core.FormatDetector
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.SourceType
import com.gazereel.vr.data.db.AppDatabase
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * PRD 6.4 / NET-05 / FR-09：按 SourceType 分派目录扫描，分页写入 Room。
 * 阶段二起 SMB、WebDAV、HTTP、DLNA、本地五类源共用同一张 media_item 表；
 * 网络源入库的 path 是可直接访问的完整 URL，SMB 仍是 share 内相对路径。
 * 扫描走独立低优先级 IO，不与高码率视频流共用读缓冲。
 */
class MediaIndexer(
    context: Context,
    private val db: AppDatabase,
    private val pool: SmbSessionPool,
    private val credentialStore: CredentialStore,
    private val detector: FormatDetector = FormatDetector()
) {
    companion object {
        private const val PAGE_SIZE = 100
    }

    private val posters = PosterRepository(context.applicationContext)
    private val dlna = DlnaScanner(context.applicationContext)
    private val smbHosts = LanSmbScanner()
    // V2.7：手机视频走 MediaStore，不再用 File 单层遍历。
    private val local = LocalVideoScanner(context.applicationContext)

    fun posterLoader(): (String) -> android.graphics.Bitmap? = posters::load

    /** V2.6：DLNA 的第一层浏览对象是「设备」，不是路径。 */
    fun dlnaServers(): List<DlnaScanner.Server> = dlna.discover()

    /**
     * V2.15：找设备要同时找两种设备。
     *
     * SSDP 只认 DLNA，只开 SMB 的 NAS 一辈子不会应答 —— 用户「局域网里有两台 NAS，
     * 却提示没找到设备」就是这么来的。两条探测并行跑，总时长还是 SSDP 那 4 秒，
     * 不会因为多扫一遍端口让用户多等。
     */
    suspend fun lanScan(): LanScan = coroutineScope {
        val dlna = async(Dispatchers.IO) {
            runCatching { this@MediaIndexer.dlna.discoverDetailed() }.getOrNull()
        }
        val smb = async(Dispatchers.IO) {
            runCatching { smbHosts.scan() }.getOrDefault(emptyList())
        }
        val found = dlna.await()
        LanScan(
            servers = found?.servers.orEmpty(),
            smbHosts = smb.await(),
            responders = found?.responders ?: 0
        )
    }

    /** V2.6：删除片库时连同生成的文字封面一起清掉。 */
    fun deletePosters(sourceId: Long) = posters.deleteBySource(sourceId)

    suspend fun scan(source: SourceEntity): Int = withContext(Dispatchers.IO) {
        val credentials = decodeCredentials(credentialStore.get(source.credentialRef))
        val entries = list(source, credentials)
        val existing = db.mediaDao().all()
            .filter { it.sourceId == source.id }
            .associateBy { it.path }

        val changed = mutableListOf<MediaItemEntity>()
        for (entry in entries) {
            if (entry.isDirectory) continue
            // V2.7：手机视频的 path 是 content:// URI，不以扩展名结尾，不能按扩展名过滤。
            if (!entry.path.startsWith("content://") && !hasVideoExtension(entry.path)) continue
            val old = existing[entry.path]
            // 增量判据：路径 + 大小；DLNA 等不提供大小的源每次都刷新标题。
            if (old != null && entry.sizeBytes > 0L && old.sizeBytes == entry.sizeBytes) continue
            val detected = detector.detect(entry.title)
            // V2.5：扫描期拿不到真实宽高，低置信度的猜测不再固化进库，
            // 留给播放时按当前规则现场重判；只有高置信度的显式标记才写入。
            val remembered = if (!detected.needsConfirmation && detected.format.confidence >= 0.85f) {
                serializeFormat(detected.format)
            } else ""
            changed += MediaItemEntity(
                id = old?.id ?: 0L,
                sourceId = source.id,
                path = entry.path,
                title = entry.title,
                detectedFormat = remembered,
                posterPath = old?.posterPath ?: "",
                sizeBytes = entry.sizeBytes,
                mtime = System.currentTimeMillis()
            )
        }

        changed.chunked(PAGE_SIZE).forEach { page -> db.mediaDao().upsert(page) }
        db.sourceDao().touchScan(source.id, System.currentTimeMillis())
        // 海报是低优先级增强：失败只记录在日志，绝不让扫描整体失败。
        updatePosters(source, credentials)
        changed.size
    }

    /** 阶段二 FR-10：可由配置页或后台任务单独触发的海报刷新入口。 */
    suspend fun refreshPosters(sourceId: Long) = withContext(Dispatchers.IO) {
        val source = db.sourceDao().byId(sourceId) ?: return@withContext
        updatePosters(source, decodeCredentials(credentialStore.get(source.credentialRef)))
    }

    private suspend fun updatePosters(source: SourceEntity, credentials: SmbCredentials) {
        val items = db.mediaDao().all().filter { it.sourceId == source.id }
        val generated = runCatching { posters.refresh(items, source, credentials) }
            .getOrDefault(emptyMap())
        val updated = items.mapNotNull { item ->
            generated[item.id]?.takeIf { it != item.posterPath }?.let { item.copy(posterPath = it) }
        }
        updated.chunked(PAGE_SIZE).forEach { db.mediaDao().upsert(it) }
    }

    /**
     * V2.6：公开给 FolderBrowser 复用——选文件夹和扫描片库必须是同一套列目录逻辑，
     * 否则「选的时候看得见、扫的时候看不见」。返回值同时含目录与视频，调用方各自筛。
     */
    fun list(source: SourceEntity, credentials: SmbCredentials): List<IndexEntry> {
        val type = runCatching { SourceType.valueOf(source.type) }.getOrDefault(SourceType.LOCAL)
        return when (type) {
            SourceType.SMB -> {
                val (host, share, folder) = parseUncFull(source.address)
                val ref = MediaRef(source.id, SourceType.SMB, host, share, path = "")
                pool.list(ref, credentials, folder).map { entry ->
                    // path 必须是 share 内的完整相对路径，否则选中子目录后播放会打不开文件。
                    val relative = if (folder.isBlank()) entry.name else "$folder\\${entry.name}"
                    IndexEntry(
                        path = relative,
                        title = if (entry.isDirectory) entry.name
                        else entry.name.substringBeforeLast('.'),
                        sizeBytes = entry.sizeBytes,
                        isDirectory = entry.isDirectory
                    )
                }
            }
            SourceType.WEBDAV -> WebDavScanner(credentials).list(source.address)
            SourceType.HTTP -> HttpDirectoryScanner(credentials).list(source.address)
            SourceType.DLNA -> if (source.address.startsWith("http", true)) {
                // 用户点选过的那一台；不再每次重新发现整个局域网。
                dlna.browseUrl(source.address)
            } else dlna.discover().flatMap { server ->
                runCatching { dlna.browse(server) }.getOrDefault(emptyList())
            }
            // V2.7：走系统索引。address 为空 = 整机全部视频；否则只取该目录。
            SourceType.LOCAL -> local.list(source.address)
            SourceType.FTP -> throw UnsupportedOperationException(
                "FTP / SFTP 随机读与列举计划在阶段三接入"
            )
        }
    }
}

internal fun serializeFormat(format: com.gazereel.vr.core.VrFormat): String =
    listOf(format.projection.name, format.layout.name, format.fov.name).joinToString("/")
