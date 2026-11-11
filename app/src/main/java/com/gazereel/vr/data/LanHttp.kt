package com.gazereel.vr.data

import android.util.Base64
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

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
 * PRD 6.3 / FR-09：局域网协议共用的 HTTP 客户端。
 *
 * 内部改用 OkHttp：进程内单例客户端自带连接池与连接复用，不再每请求新建
 * Socket、不再强制 Connection: close；http/https、Basic 认证、chunked、
 * Content-Length、透明 gzip 等交由成熟库处理。对上层仍只暴露 [request] +
 * [LanHttpResponse]，HTTP 随机读 / WebDAV PROPFIND / DLNA SOAP / 封面拉取
 * 等调用方无需改动，异常依旧以 IOException 为主。
 *
 * 注意：显式发送 Accept-Encoding: identity，避免 OkHttp 的透明 gzip 解压——
 * Range 字节偏移必须对应原始实体，不能对应压缩后的字节流。
 */
class LanHttpClient(
    private val credentials: SmbCredentials = SmbCredentials("", "")
) {
    companion object {
        // 局域网场景：都设有限超时，绝不使用无限超时。
        private const val CONNECT_TIMEOUT_SECONDS = 5L
        private const val READ_TIMEOUT_SECONDS = 8L
        private const val WRITE_TIMEOUT_SECONDS = 8L
        private const val MAX_REDIRECTS = 3

        /** 单例：连接池与线程池随客户端复用，避免每次请求重建。 */
        private val CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            // 重定向由下面手动处理，保持「最多 3 次、保留原 method/body/headers」的语义。
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun request(
        method: String,
        urlText: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        redirects: Int = 0
    ): LanHttpResponse {
        val call = try {
            CLIENT.newCall(buildRequest(method, urlText, headers, body))
        } catch (e: IllegalArgumentException) {
            // OkHttp 用 IllegalArgumentException 报非法 URL/方法；映射回 IOException，
            // 与旧实现 URL() 抛 MalformedURLException 的语义一致，调用方可统一 catch。
            throw IOException("非法请求：$urlText", e)
        }
        // execute() 的连不上 / 超时 / 读写失败本身就是 IOException，直接向上抛。
        val response = call.execute()
        try {
            val responseHeaders = LinkedHashMap<String, String>()
            for (i in 0 until response.headers.size) {
                responseHeaders[response.headers.name(i)] = response.headers.value(i)
            }
            val data = if (method.equals("HEAD", ignoreCase = true)) {
                ByteArray(0)
            } else {
                response.body?.bytes() ?: ByteArray(0)
            }
            val result = LanHttpResponse(response.code, responseHeaders, data)
            val code = response.code
            if (code in 301..303 || code == 307 || code == 308) {
                val location = result.header("Location")
                if (location != null && redirects < MAX_REDIRECTS) {
                    return request(
                        method, resolve(urlText, location), headers, body, redirects + 1
                    )
                }
            }
            return result
        } finally {
            response.close()
        }
    }

    private fun buildRequest(
        method: String,
        urlText: String,
        extraHeaders: Map<String, String>,
        body: ByteArray?
    ): Request {
        val builder = Request.Builder().url(urlText)
        // 顺序与旧实现一致：默认头 → 调用方头（可覆盖）→ 认证头（最终生效）。
        builder.header("Accept-Encoding", "identity")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        val url = URL(urlText)
        val user = url.userInfo?.substringBefore(':') ?: credentials.user
        val pass = url.userInfo?.substringAfter(':', "") ?: credentials.password
        if (user.isNotBlank()) {
            val token = Base64.encodeToString(
                "$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            builder.header("Authorization", "Basic $token")
        }
        builder.method(method, requestBody(method, body))
        return builder.build()
    }

    /** OkHttp 禁止 GET/HEAD 带 body，且要求 POST/PATCH 等必须带 body；这里做归一，避免抛异常。 */
    private fun requestBody(method: String, body: ByteArray?): RequestBody? {
        if (method.equals("GET", true) || method.equals("HEAD", true)) return null
        if (body != null) return body.toRequestBody()
        val requiresBody = method.equals("POST", true) || method.equals("PUT", true) ||
            method.equals("PATCH", true) || method.equals("PROPPATCH", true) ||
            method.equals("REPORT", true)
        return if (requiresBody) ByteArray(0).toRequestBody() else null
    }

    private fun resolve(baseUrlText: String, location: String): String =
        runCatching { URL(URL(baseUrlText), location).toString() }.getOrDefault(location)
}
