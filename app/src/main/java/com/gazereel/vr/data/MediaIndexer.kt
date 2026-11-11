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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
        private const val MAX_SCAN_DIRS = 300
        private const val MAX_SCAN_ENTRIES = 20_000
        /** V2.29：同层目录并发枚举的上限，别把 NAS 打爆。 */
        private const val ENUM_WORKERS = 6
        /** 单层整体预算；某台设备不应答时不让整次扫描挂死。 */
        private const val ENUM_LEVEL_TIMEOUT_MS = 20_000L
    }

    private val posters = PosterRepository(context.applicationContext)
    private val dlna = DlnaScanner(context.applicationContext)

    /**
     * V2.29：已保存 SMB 凭据的 host → 账号快照（key 统一小写）。
     * 名字解析跑在普通线程里，不能在那儿调 Room 的 suspend DAO，
     * 所以由 [lanScan] 在挂起上下文一次性读好，provider 只做内存查表。
     */
    @Volatile
    private var knownSmbCredentials: Map<String, SmbCredentials> = emptyMap()

    // V2.17：名字解析要用系统的 mDNS，所以把 Context 递进去。
    // V2.29：再递一个「按 host 找回已保存凭据」的回调，让设备名探测
    // 能用已认证会话（需账号的 NAS 才肯报名字），而不是只显示 IP。
    private val smbHosts = LanSmbScanner(context.applicationContext) { host ->
        knownSmbCredentials[host.lowercase()]
    }
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
        val started = System.currentTimeMillis()
        // 在挂起上下文里读一次凭据快照；名字解析线程随后只读这张内存表。
        knownSmbCredentials = try {
            savedSmbCredentials()
        } catch (t: Throwable) {
            RunLog.append("smb", "读取已保存凭据失败 · ${t.message}")
            emptyMap<String, SmbCredentials>()
        }
        val dlna = async(Dispatchers.IO) {
            runCatching { this@MediaIndexer.dlna.discoverDetailed() }.getOrNull()
        }
        val smb = async(Dispatchers.IO) {
            runCatching { smbHosts.scan() }.getOrDefault(emptyList())
        }
        val found = dlna.await()
        val smbList = smb.await()
        RunLog.append(
            "smb",
            "局域网发现 · SMB ${smbList.size} 台 · DLNA ${found?.servers.orEmpty().size} 台 · " +
                "耗时 ${System.currentTimeMillis() - started}ms"
        )
        LanScan(
            servers = found?.servers.orEmpty(),
            smbHosts = smbList,
            responders = found?.responders ?: 0
        )
    }

    /** V2.6：删除片库时连同生成的文字封面一起清掉。 */
    fun deletePosters(sourceId: Long) = posters.deleteBySource(sourceId)

    /**
     * V2.18：扫描失败自动重建连接再试一次。
     *
     * 真机反馈「反复进出 VR 之后再回配置页扫描，扫描不成功」，而重启 App 就好了 ——
     * 这是连接半死的典型症状（服务端限流、树连接已断、会话被回收）。
     * 与其让用户去重启，不如自己把整条连接丢掉重来一次。
     *
     * V2.29：新增 [forceRefresh]（**默认 false，既有调用点全部不变**）。
     * 用户显式点「重扫」时把它置 true，递增 [SmbScanCache] 版本号，
     * 让共享列表 / 已枚举目录的缓存一次性失效，真正做到重新枚举；
     * 普通扫描仍吃缓存，重复重扫不再重复枚举。
     */
    suspend fun scan(source: SourceEntity, forceRefresh: Boolean = false): Int = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        if (forceRefresh) {
            SmbScanCache.bump()
            RunLog.append("smb", "重扫 · ${source.address} · 强制刷新共享/目录缓存")
        }
        val result = try {
            scanOnce(source)
        } catch (first: Exception) {
            RunLog.append("scan", "扫描失败，重建连接后重试：${first.message}")
            runCatching { pool.invalidate() }
            delay(500)
            scanOnce(source)
        }
        RunLog.append(
            "smb",
            "扫描完成 · ${source.address} · 变更 $result 项 · 耗时 ${System.currentTimeMillis() - started}ms"
        )
        result
    }

    /**
     * 名字解析用的「已认证凭据」：从已保存的 SMB 片库里按 host 找回账号。
     * 参考应用不能输凭据，我们能——这里正是把这个优势接到设备名显示上。
     * 只在 [lanScan] 的挂起上下文调用一次（Room 的 all() 是 suspend）。
     */
    private suspend fun savedSmbCredentials(): Map<String, SmbCredentials> {
        val byHost = LinkedHashMap<String, SmbCredentials>()
        for (source in db.sourceDao().all()) {
            if (source.type != SourceType.SMB.name || source.credentialRef.isBlank()) continue
            val host = parseUncFull(source.address).first
            if (host.isBlank() || byHost.containsKey(host.lowercase())) continue
            val credentials = decodeCredentials(credentialStore.get(source.credentialRef))
            if (credentials.user.isNotBlank()) byHost[host.lowercase()] = credentials
        }
        return byHost
    }

    private suspend fun scanOnce(source: SourceEntity): Int {
        val credentials = decodeCredentials(credentialStore.get(source.credentialRef))
        val entries = list(source, credentials, source.recursive)
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
        return changed.size
    }

    /** 阶段二 FR-10：可由配置页或后台任务单独触发的海报刷新入口。返回新生成张数。 */
    suspend fun refreshPosters(
        sourceId: Long,
        shouldContinue: () -> Boolean = { true }
    ): Int = withContext(Dispatchers.IO) {
        val source = db.sourceDao().byId(sourceId) ?: return@withContext 0
        updatePosters(
            source,
            decodeCredentials(credentialStore.get(source.credentialRef)),
            shouldContinue
        )
    }

    private suspend fun updatePosters(
        source: SourceEntity,
        credentials: SmbCredentials,
        shouldContinue: () -> Boolean = { true }
    ): Int {
        val items = db.mediaDao().all().filter { it.sourceId == source.id }
        val generated = runCatching { posters.refresh(items, source, credentials, shouldContinue) }
            .onFailure { RunLog.append("poster", "封面生成失败 · ${source.displayName} · ${it.message}") }
            .getOrDefault(emptyMap())
        val updated = items.mapNotNull { item ->
            generated[item.id]?.takeIf { it != item.posterPath }?.let { item.copy(posterPath = it) }
        }
        updated.chunked(PAGE_SIZE).forEach { db.mediaDao().upsert(it) }
        if (updated.isNotEmpty()) {
            RunLog.append("poster", "${source.displayName} · 新封面 ${updated.size} 张（共 ${items.size} 部）")
        }
        return updated.size
    }

    /**
     * V2.6：公开给 FolderBrowser 复用——选文件夹和扫描片库必须是同一套列目录逻辑，
     * 否则「选的时候看得见、扫的时候看不见」。返回值同时含目录与视频，调用方各自筛。
     */
    fun list(
        source: SourceEntity,
        credentials: SmbCredentials,
        recursive: Boolean = false
    ): List<IndexEntry> {
        val type = runCatching { SourceType.valueOf(source.type) }.getOrDefault(SourceType.LOCAL)
        return when (type) {
            SourceType.SMB -> smbEntries(source, credentials, recursive)
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

    /**
     * V2.17：SMB 片源的列举。**浏览只列一层，扫描才递归**——
     * 浏览时递归会把整棵树摊平，用户就没法一层层点进去了。
     *
     * V2.29：递归改成**按层并发**。原来是单线程 BFS，下一个目录要等上一个
     * 的 SMB 往返回来才开始；现在同一层最多 [ENUM_WORKERS] 个目录同时列，
     * 层与层之间仍逐层推进（子目录要先列出来才能知道下一层），
     * 两道闸 [MAX_SCAN_DIRS] / [MAX_SCAN_ENTRIES] 与目录级缓存照旧，
     * 既快又不会把 NAS 打爆。起点这一层单独列，失败照旧冒泡报错。
     */
    private fun smbEntries(
        source: SourceEntity,
        credentials: SmbCredentials,
        recursive: Boolean
    ): List<IndexEntry> {
        val (host, share, folder) = parseUncFull(source.address)
        val ref = MediaRef(source.id, SourceType.SMB, host, share, path = "")
        val started = System.currentTimeMillis()

        // 起点这一层列不出来必须报错（否则用户以为片库是空的）；
        // 不放进并发任务里，免得异常被 getOrNull() 吞掉。
        val (rootEntries, rootChildren) = expand(folder, pool.list(ref, credentials, folder))
        val result = rootEntries.toMutableList()
        var dirs = 1
        if (!recursive || rootChildren.isEmpty() || dirs >= MAX_SCAN_DIRS) {
            return finishSmbEntries(host, share, result, dirs, started)
        }

        val workers = Executors.newFixedThreadPool(ENUM_WORKERS) { runnable ->
            Thread(runnable, "smb-enum").apply { isDaemon = true }
        }
        var frontier: List<String> = rootChildren
        try {
            while (frontier.isNotEmpty() && dirs < MAX_SCAN_DIRS && result.size < MAX_SCAN_ENTRIES) {
                val batch = frontier.take(MAX_SCAN_DIRS - dirs)
                val tasks = batch.map { current ->
                    Callable {
                        // 下层某个子文件夹没权限就跳过，不能因为一个目录让整次扫描失败。
                        val listing = runCatching { pool.list(ref, credentials, current) }.getOrNull()
                        if (listing == null) Level(emptyList(), emptyList())
                        else expand(current, listing)
                    }
                }
                // 单层整体限时；超时的目录被取消并跳过（宁可少收，不能卡死）。
                val futures = workers.invokeAll(tasks, ENUM_LEVEL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                val next = mutableListOf<String>()
                for (future in futures) {
                    val level = runCatching { future.get() }.getOrNull() ?: continue
                    result += level.entries
                    next += level.children
                }
                dirs += batch.size
                frontier = next
            }
        } finally {
            workers.shutdownNow()
        }
        return finishSmbEntries(host, share, result, dirs, started)
    }

    /** 一层目录的结果：可见条目 + 可继续下钻的子目录。 */
    private data class Level(val entries: List<IndexEntry>, val children: List<String>)

    /** 把一层 SMB 条目转成 IndexEntry，并挑出子目录；浏览与扫描共用同一套命名。 */
    private fun expand(current: String, listing: List<SmbEntry>): Level {
        val entries = ArrayList<IndexEntry>(listing.size)
        val children = ArrayList<String>()
        for (entry in listing) {
            // path 必须是 share 内的完整相对路径，否则选中子目录后播放会打不开文件。
            val relative = if (current.isBlank()) entry.name else "$current\\${entry.name}"
            if (entry.isDirectory) children += relative
            entries += IndexEntry(
                path = relative,
                title = if (entry.isDirectory) entry.name
                else entry.name.substringBeforeLast('.'),
                sizeBytes = entry.sizeBytes,
                isDirectory = entry.isDirectory
            )
        }
        return Level(entries, children)
    }

    private fun finishSmbEntries(
        host: String,
        share: String,
        result: List<IndexEntry>,
        dirs: Int,
        started: Long
    ): List<IndexEntry> {
        RunLog.append(
            "smb",
            "目录枚举 · $host/$share · 并发 $ENUM_WORKERS · $dirs 个目录 · ${result.size} 项 · " +
                "耗时 ${System.currentTimeMillis() - started}ms"
        )
        return result
    }
}

internal fun serializeFormat(format: com.gazereel.vr.core.VrFormat): String =
    listOf(format.projection.name, format.layout.name, format.fov.name).joinToString("/")
