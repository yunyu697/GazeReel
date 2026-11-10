package com.gazereel.vr.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.RandomAccessSource
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import kotlin.math.min

/** 片库扫描的统一条目：网络源的 path 是完整 URL，SMB/本地的 path 是相对路径。 */
data class IndexEntry(
    val path: String,
    val title: String,
    val sizeBytes: Long = 0L,
    val isDirectory: Boolean = false
)

internal val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "webm", "m4v", "ts", "avi")

internal fun hasVideoExtension(value: String): Boolean {
    val clean = value.substringBefore('?').substringBefore('#')
    return clean.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
}

internal fun titleOf(urlOrPath: String): String {
    val clean = urlOrPath.substringBefore('?').substringBefore('#')
        .trimEnd('/').substringAfterLast('/')
    return runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrDefault(clean)
        .substringBeforeLast('.')
        .ifBlank { clean }
}

/**
 * MediaRef 对网络协议的地址约定（PRD 5.1）：
 * - host = 配置页填写的 base URL（用于推导同名海报/字幕）；
 * - path = 可直接访问的完整 URL（WebDAV href、HTTP 目录项、DLNA res）。
 * SMB 仍沿用 host/share/path 三段式，互不影响。
 */
fun MediaRef.networkUrl(): String {
    val p = path
    return if (p.startsWith("http://", true) || p.startsWith("https://", true)) {
        p
    } else {
        host.trimEnd('/') + "/" + p.trimStart('/')
    }
}

/** 同名附属文件的 URL：a.mp4 → a.srt / a.jpg。 */
fun MediaRef.sidecarUrl(extension: String): String {
    val url = networkUrl()
    val base = url.substringBefore('?').substringBefore('#').substringBeforeLast('.')
    return "$base.$extension"
}

/**
 * PRD 6.3 / FR-09：HTTP / WebDAV / DLNA 共用一条随机读链路。
 * 每次以 1 MB 窗口发 Range 请求，窗口内的连续读零网络开销；seek 出窗才重新取。
 * 服务器忽略 Range 时返回 200 + 全文，此时按 windowStart=0 处理，避免错位解码。
 */
class HttpRandomAccessSource(
    private val credentials: SmbCredentials
) : RandomAccessSource {
    companion object {
        const val WINDOW_BYTES = 1 shl 20
    }

    private val client = LanHttpClient(credentials)
    private var url: String = ""
    private var total: Long = -1L
    private var position: Long = 0L
    private var window: ByteArray = ByteArray(0)
    private var windowStart: Long = -1L

    override fun open(ref: MediaRef): Long {
        url = ref.networkUrl()
        position = 0L
        window = ByteArray(0)
        windowStart = -1L
        total = probeLength()
        return total
    }

    private fun probeLength(): Long {
        val head = runCatching {
            client.request("HEAD", url, mapOf("Range" to "bytes=0-0"))
        }.getOrNull()
        head?.header("Content-Range")
            ?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }?.let { return it }
        head?.header("Content-Length")?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }?.let { return it }

        // 部分轻量 HTTP 服务器不允许 HEAD，退化为 Range GET 探测总长与 Range 支持情况。
        val get = runCatching {
            client.request("GET", url, mapOf("Range" to "bytes=0-0"))
        }.getOrElse { throw IOException("片源不可达：${it.message}", it) }
        if (get.code !in 200..299) throw IOException("片源返回 HTTP ${get.code}：$url")
        get.header("Content-Range")
            ?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }?.let { return it }
        return get.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
    }

    override fun read(out: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (total >= 0L && position >= total) return -1

        val inside = position - windowStart
        if (windowStart < 0L || inside < 0L || inside >= window.size) {
            val end = if (total > 0L) {
                min(total - 1, position + WINDOW_BYTES - 1)
            } else {
                position + WINDOW_BYTES - 1
            }
            val response = client.request(
                "GET", url, mapOf("Range" to "bytes=$position-$end")
            )
            if (response.code !in 200..299) {
                throw IOException("Range 读取失败 HTTP ${response.code}：$url")
            }
            if (response.body.isEmpty()) return -1
            // 206 表示服务器按 Range 返回；200 表示忽略 Range 返回了完整实体。
            windowStart = if (response.code == 206) position else 0L
            window = response.body
        }
        val index = (position - windowStart).toInt()
        if (index < 0 || index >= window.size) return -1
        val count = min(length, window.size - index)
        System.arraycopy(window, index, out, offset, count)
        position += count
        return count
    }

    override fun seekTo(position: Long) {
        this.position = position.coerceAtLeast(0L)
        // 仍在窗口内则保留预读数据，顺序读与小幅回拖不需要重新请求。
        if (windowStart >= 0L &&
            (this.position < windowStart || this.position >= windowStart + window.size)
        ) {
            window = ByteArray(0)
            windowStart = -1L
        }
    }

    override fun close() {
        window = ByteArray(0)
        windowStart = -1L
    }
}

/** WebDAV 目录列举：PROPFIND Depth:1 → 207 Multi-Status → href + getcontentlength。 */
class WebDavScanner(private val credentials: SmbCredentials) {
    private val client = LanHttpClient(credentials)

    fun list(baseUrl: String): List<IndexEntry> {
        val body = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<d:propfind xmlns:d="DAV:"><d:prop>""" +
            """<d:displayname/><d:getcontentlength/><d:resourcetype/>""" +
            """</d:prop></d:propfind>"""
        val response = client.request(
            "PROPFIND", baseUrl,
            mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8"),
            body.toByteArray(Charsets.UTF_8)
        )
        if (response.code !in 200..299) {
            throw IOException("WebDAV PROPFIND 返回 HTTP ${response.code}（检查地址与账号）")
        }
        return parseMultiStatus(baseUrl, String(response.body, Charsets.UTF_8))
    }

    private fun parseMultiStatus(baseUrl: String, xml: String): List<IndexEntry> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val result = mutableListOf<IndexEntry>()
        var inResponse = false
        var href = ""
        var size = 0L
        var isDirectory = false
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            val name = parser.name?.substringAfterLast(':') ?: ""
            when {
                event == XmlPullParser.START_TAG && name == "response" -> {
                    inResponse = true; href = ""; size = 0L; isDirectory = false
                }
                event == XmlPullParser.START_TAG && name == "collection" -> {
                    if (inResponse) isDirectory = true
                }
                event == XmlPullParser.START_TAG && name == "href" -> {
                    if (inResponse) href = parser.nextText().trim()
                }
                event == XmlPullParser.START_TAG && name == "getcontentlength" -> {
                    if (inResponse) size = parser.nextText().trim().toLongOrNull() ?: 0L
                }
                event == XmlPullParser.END_TAG && name == "response" -> {
                    val absolute = runCatching { URL(URL(baseUrl), href).toString() }
                        .getOrDefault(href)
                    val self = absolute.trimEnd('/').equals(baseUrl.trimEnd('/'), true)
                    // 有些服务会把父集合也回进来，别让浏览层出现「往回跳到站外」的项。
                    val ancestor = baseUrl.trimEnd('/')
                        .startsWith(absolute.trimEnd('/'), ignoreCase = true)
                    if (inResponse && !self && !ancestor) {
                        if (isDirectory) {
                            // V2.6：目录也要返回，FolderBrowser 才能让用户点选文件夹。
                            val dirUrl = absolute.trimEnd('/') + "/"
                            result += IndexEntry(dirUrl, titleOf(dirUrl), 0L, true)
                        } else if (hasVideoExtension(absolute)) {
                            result += IndexEntry(absolute, titleOf(absolute), size)
                        }
                    }
                    inResponse = false
                }
            }
        }
        return result.distinctBy { it.path }
    }
}

/** 普通 HTTP 目录（nginx autoindex / python -m http.server 等）的列举。 */
class HttpDirectoryScanner(private val credentials: SmbCredentials) {
    private val client = LanHttpClient(credentials)

    fun list(baseUrl: String): List<IndexEntry> {
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val response = client.request("GET", base)
        if (response.code !in 200..299) {
            throw IOException("HTTP 目录返回 HTTP ${response.code}（检查地址与账号）")
        }
        val html = String(response.body, Charsets.UTF_8)
        val links = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)
        val baseHost = runCatching { URL(base).host }.getOrNull()
        return links.findAll(html).mapNotNull { match ->
            val href = match.groupValues[1].trim()
            // 排序参数、脚本、父目录链接都不是可浏览对象。
            if (href.isEmpty() || href.startsWith("?") || href.startsWith("#") ||
                href.startsWith("javascript:", true) || href.startsWith("../") ||
                href == "/" || href == "./" || href == "../"
            ) return@mapNotNull null
            val absolute = runCatching { URL(URL(base), href).toString() }.getOrNull()
                ?: return@mapNotNull null
            val resolved = runCatching { URL(absolute) }.getOrNull() ?: return@mapNotNull null
            if (baseHost != null && !resolved.host.equals(baseHost, true)) return@mapNotNull null
            val clean = absolute.substringBefore('?').substringBefore('#')
            if (clean.trimEnd('/') == base.trimEnd('/')) return@mapNotNull null
            val isDir = href.endsWith("/") || clean.endsWith("/")
            when {
                // V2.6：目录也要返回，FolderBrowser 才能让用户点选文件夹。
                isDir -> IndexEntry(
                    clean.trimEnd('/') + "/", titleOf(clean), 0L, true
                )
                hasVideoExtension(clean) -> IndexEntry(absolute, titleOf(absolute))
                else -> null
            }
        }.distinctBy { it.path }.toList()
    }
}

/**
 * DLNA / UPnP：SSDP M-SEARCH 发现 ContentDirectory 服务，
 * 再以 SOAP Browse(BrowseDirectChildren) 取 DIDL-LITE 的 item 与 res URL。
 * 播放阶段 res URL 直接交给 HttpRandomAccessSource，无需 DLNA 专属解码链路。
 */
class DlnaScanner(private val context: Context) {
    data class Server(val label: String, val controlUrl: String)

    private val http = LanHttpClient()

    fun discover(timeoutMs: Int = 2_500): List<Server> {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifi?.createMulticastLock("cardboardvr-ssdp")
        runCatching { multicastLock?.acquire() }

        val locations = LinkedHashSet<String>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 700
                socket.broadcast = true
                val probe = (
                    "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:schemas-upnp-org:service:ContentDirectory:1\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
                val group = InetAddress.getByName("239.255.255.250")
                socket.send(DatagramPacket(probe, probe.size, group, 1900))

                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.US_ASCII)
                        val location = Regex(
                            """LOCATION:\s*(.+)""",
                            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
                        ).find(text)?.groupValues?.get(1)?.trim()
                        if (!location.isNullOrEmpty()) locations += location
                    } catch (ignored: SocketTimeoutException) {
                        // 单次等待超时不代表发现结束，继续等到总时限。
                    }
                }
            }
        } finally {
            runCatching { multicastLock?.release() }
        }
        return locations.mapIndexedNotNull { index, location ->
            runCatching { contentDirectoryUrl(location, index) }.getOrNull()
        }
    }

    private fun contentDirectoryUrl(location: String, index: Int): Server {
        val response = http.request("GET", location)
        if (response.code !in 200..299) throw IOException("设备描述不可达：$location")
        val xml = String(response.body, Charsets.UTF_8)
        val urlBase = Regex("""<URLBase>\s*(.*?)\s*</URLBase>""", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        val locationUrl = URL(location)
        val base = if (urlBase.isNotEmpty()) URL(locationUrl, urlBase) else locationUrl
        val services = Regex("""<service>([\s\S]*?)</service>""", RegexOption.IGNORE_CASE)
        for (match in services.findAll(xml)) {
            val block = match.groupValues[1]
            if (!block.contains("ContentDirectory:1", ignoreCase = true)) continue
            val control = Regex("""<controlURL>\s*(.*?)\s*</controlURL>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1)?.trim()
            if (!control.isNullOrEmpty()) {
                return Server("DLNA 服务器 ${index + 1}", URL(base, control).toString())
            }
        }
        throw IOException("设备未提供 ContentDirectory 服务：$location")
    }

    fun browse(server: Server): List<IndexEntry> = browseUrl(server.controlUrl)

    /** V2.6：用户选中的媒体服务器以 controlURL 存进 source.address，之后只扫这一台。 */
    fun browseUrl(controlUrl: String, objectId: String = "0"): List<IndexEntry> {
        val safeId = objectId.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val soap = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
            """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">""" +
            """<ObjectID>$safeId</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>""" +
            """<Filter>*</Filter><StartingIndex>0</StartingIndex>""" +
            """<RequestedCount>0</RequestedCount><SortCriteria></SortCriteria>""" +
            """</u:Browse></s:Body></s:Envelope>"""
        val response = http.request(
            "POST", controlUrl,
            mapOf(
                "Content-Type" to "text/xml; charset=utf-8",
                "SOAPACTION" to "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
            ),
            soap.toByteArray(Charsets.UTF_8)
        )
        if (response.code !in 200..299) {
            throw IOException("DLNA Browse 返回 HTTP ${response.code}")
        }
        val didl = extractSoapResult(String(response.body, Charsets.UTF_8))
        return if (didl.isEmpty()) emptyList() else parseDidl(didl)
    }

    /** <Result> 内是转义后的 DIDL-LITE；用 XmlPullParser 的 nextText 自动反转义。 */
    private fun extractSoapResult(xml: String): String {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.START_TAG &&
                parser.name?.substringAfterLast(':') == "Result"
            ) {
                return parser.nextText()
            }
        }
        return ""
    }

    private fun parseDidl(didl: String): List<IndexEntry> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(didl))
        val result = mutableListOf<IndexEntry>()
        var inItem = false
        var title = ""
        var res = ""
        var protocol = ""
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            val name = parser.name?.substringAfterLast(':') ?: ""
            when {
                event == XmlPullParser.START_TAG && name == "item" -> {
                    inItem = true; title = ""; res = ""; protocol = ""
                }
                event == XmlPullParser.START_TAG && name == "title" -> {
                    if (inItem) title = parser.nextText().trim()
                }
                event == XmlPullParser.START_TAG && name == "res" -> {
                    if (inItem) {
                        protocol = parser.getAttributeValue(null, "protocolInfo").orEmpty()
                        res = parser.nextText().trim()
                    }
                }
                event == XmlPullParser.END_TAG && name == "item" -> {
                    if (res.isNotEmpty() &&
                        (protocol.contains("video", true) || hasVideoExtension(res))
                    ) {
                        result += IndexEntry(res, title.ifBlank { titleOf(res) })
                    }
                    inItem = false
                }
            }
        }
        return result.distinctBy { it.path }
    }
}
