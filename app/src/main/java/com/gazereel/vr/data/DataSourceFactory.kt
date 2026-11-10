package com.gazereel.vr.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.RandomAccessSource
import com.gazereel.vr.core.SourceType
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地片源：既能读真实文件路径（USB OTG / 旧版存储），也能读 content:// URI。
 *
 * V2.7 起「手机里的视频」走 MediaStore，拿到的是 content:// 而不是文件路径；
 * 分区存储下直接用 File 打开会失败，必须经 ContentResolver 拿文件描述符。
 * 两条路径共用同一个 RandomAccessSource 接口，上层 Media3 与渲染层无感知。
 */
class LocalRandomAccessSource(private val context: Context) : RandomAccessSource {
    private var handle: RandomAccessFile? = null
    private var stream: FileInputStream? = null
    private var channel: FileChannel? = null
    private var descriptor: ParcelFileDescriptor? = null

    override fun open(ref: MediaRef): Long {
        if (ref.path.startsWith("content://")) {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(ref.path), "r")
                ?: throw IOException("打不开这个视频：存储权限可能被撤销，回应用重新授权一次")
            val input = FileInputStream(pfd.fileDescriptor)
            descriptor = pfd
            stream = input
            channel = input.channel
            return if (pfd.statSize > 0) pfd.statSize else input.channel.size()
        }
        handle = RandomAccessFile(File(ref.path), "r")
        return handle!!.length()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        channel?.let { ch ->
            val read = ch.read(ByteBuffer.wrap(buffer, offset, length))
            return if (read < 0) -1 else read
        }
        return handle?.read(buffer, offset, length) ?: -1
    }

    override fun seekTo(position: Long) {
        channel?.position(position)
        handle?.seek(position)
    }

    override fun close() {
        runCatching { channel?.close() }
        runCatching { stream?.close() }
        runCatching { descriptor?.close() }
        runCatching { handle?.close() }
        channel = null
        stream = null
        descriptor = null
        handle = null
    }
}

/** 播放前注册 MediaRef，Media3 DataSource 用自定义 URI 反查完整引用。 */
object MediaRefRegistry {
    private val refs = ConcurrentHashMap<String, MediaRef>()

    fun register(ref: MediaRef): Uri {
        val uri = ref.toUri()
        refs[uri.toString()] = ref
        return uri
    }

    fun resolve(uri: Uri): MediaRef? = refs[uri.toString()]
}

/** Keystore 中的凭证打包格式：user + NUL 字符 + password。 */
fun encodeCredentials(user: String, password: String): String = "$user\u0000$password"
fun decodeCredentials(raw: String?): SmbCredentials {
    // V2.6：没有凭证时必须是真正的空身份。旧版返回 "guest" 会让 smbj 走 NTLM
    // 而不是 guest 匿名登录，也会让 HTTP 请求凭空带上 Basic 头。
    if (raw.isNullOrEmpty()) return SmbCredentials("", "")
    val parts = raw.split("\u0000", limit = 2)
    return SmbCredentials(parts[0], parts.getOrElse(1) { "" })
}

class RandomAccessSourceFactory(
    private val pool: SmbSessionPool,
    private val credentialStore: CredentialStore,
    private val context: Context
) {
    fun create(ref: MediaRef): RandomAccessSource = when (ref.type) {
        SourceType.SMB -> SmbRandomAccessSource(
            ref,
            decodeCredentials(credentialStore.get(ref.credentialRef)),
            pool
        )
        SourceType.LOCAL -> LocalRandomAccessSource(context)
        // PRD 阶段二 / FR-09：WebDAV、HTTP 目录与 DLNA 的 res URL 都是纯 HTTP(S)
        // 资源，统一走 Range 随机读，上层 ProgressiveMediaSource 完全无感知。
        SourceType.WEBDAV, SourceType.HTTP, SourceType.DLNA -> HttpRandomAccessSource(
            decodeCredentials(credentialStore.get(ref.credentialRef))
        )
        // FTP/SFTP 需要独立的随机读协议实现，排在阶段三；给出明确错误而不是静默失败。
        SourceType.FTP -> throw UnsupportedOperationException(
            "FTP / SFTP 随机读计划在阶段三接入，本期请使用 SMB / WebDAV / HTTP / DLNA"
        )
    }
}
