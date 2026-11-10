package com.gazereel.vr.data

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocketFactory

/** 一次完整的 HTTP 响应；headers 保留原值，查找时忽略大小写。 */
class LanHttpResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/**
 * PRD 6.3 / FR-09：局域网协议共用的轻量 HTTP/1.1 客户端（阶段二）。
 *
 * 为什么不用 HttpURLConnection：WebDAV 目录列举必须用 PROPFIND，而 Android 的
 * HttpURLConnection.setRequestMethod 只接受固定方法白名单；DLNA 的 SOAP POST、
 * HTTP Range 随机读也统一走这里，避免播放与扫描各用一套网络栈。
 *
 * 已覆盖：http/https、Basic 认证（含 URL userInfo）、Content-Length、chunked、
 * 最多 3 次重定向、连接/读取超时。每次请求 Connection: close，不做连接池复用——
 * Range 读取以 1 MB 窗口为单位，局域网下建连开销可接受。
 */
class LanHttpClient(
    private val credentials: SmbCredentials = SmbCredentials("", "")
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_REDIRECTS = 3
    }

    fun request(
        method: String,
        urlText: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        redirects: Int = 0
    ): LanHttpResponse {
        val url = URL(urlText)
        val https = url.protocol.equals("https", ignoreCase = true)
        require(https || url.protocol.equals("http", ignoreCase = true)) {
            "局域网客户端只支持 http/https：${url.protocol}"
        }
        val port = if (url.port > 0) url.port else if (https) 443 else 80
        val socket: Socket = if (https) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket()
        } else {
            Socket()
        }
        try {
            socket.connect(InetSocketAddress(url.host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val response = exchange(socket, url, method, headers, body)
            if (response.code in 301..303 || response.code == 307 || response.code == 308) {
                val location = response.header("Location")
                if (location != null && redirects < MAX_REDIRECTS) {
                    return request(method, URL(url, location).toString(), headers, body, redirects + 1)
                }
            }
            return response
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun exchange(
        socket: Socket,
        url: URL,
        method: String,
        extraHeaders: Map<String, String>,
        body: ByteArray?
    ): LanHttpResponse {
        val target = buildString {
            append(url.path.ifEmpty { "/" })
            url.query?.let { append('?').append(it) }
        }
        val requestHeaders = LinkedHashMap<String, String>()
        requestHeaders["Host"] = if (url.port > 0) "${url.host}:${url.port}" else url.host
        requestHeaders["Connection"] = "close"
        // 禁止压缩：Range 字节偏移必须对应原始实体，不能对应 gzip 后的字节流。
        requestHeaders["Accept-Encoding"] = "identity"
        requestHeaders.putAll(extraHeaders)

        val user = url.userInfo?.substringBefore(':') ?: credentials.user
        val pass = url.userInfo?.substringAfter(':', "") ?: credentials.password
        if (user.isNotBlank()) {
            val token = Base64.encodeToString(
                "$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            requestHeaders["Authorization"] = "Basic $token"
        }
        if (body != null) requestHeaders["Content-Length"] = body.size.toString()

        val out = socket.getOutputStream()
        val head = buildString {
            append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
            requestHeaders.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (body != null) out.write(body)
        out.flush()

        val input = socket.getInputStream()
        val statusLine = readAsciiLine(input) ?: throw IOException("HTTP 响应为空：$url")
        val statusParts = statusLine.split(' ', limit = 3)
        val code = statusParts.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("非法状态行：$statusLine")

        val responseHeaders = LinkedHashMap<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                responseHeaders[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
        }

        val chunked = responseHeaders.entries.any {
            it.key.equals("Transfer-Encoding", true) && it.value.contains("chunked", true)
        }
        val length = responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Length", true) }
            ?.value?.trim()?.toLongOrNull() ?: -1L

        val data = when {
            method.equals("HEAD", true) -> ByteArray(0)
            chunked -> readChunked(input)
            length >= 0L -> readExactly(input, length.toInt())
            else -> readToEnd(input)
        }
        return LanHttpResponse(code, responseHeaders, data)
    }

    /** 逐字节读到 CRLF；不能用 BufferedReader，它会预读并吞掉响应体。 */
    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.lastOrNull() == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var done = 0
        while (done < count) {
            val n = input.read(out, done, count - done)
            if (n < 0) break
            done += n
        }
        return if (done == count) out else out.copyOf(done)
    }

    private fun readToEnd(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readAsciiLine(input) // 尾部 CRLF
                break
            }
            buffer.write(readExactly(input, size))
            readAsciiLine(input) // 每个 chunk 后的 CRLF
        }
        return buffer.toByteArray()
    }
}
