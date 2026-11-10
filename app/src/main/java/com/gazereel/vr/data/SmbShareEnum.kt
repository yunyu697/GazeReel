package com.gazereel.vr.data

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.EnumSet

/**
 * 设备上有哪些共享 —— 由设备自己回答，不再靠猜。
 *
 * V2.6 的 `probeShares()` 只对 26 个常见共享名逐个 TREE_CONNECT 试连，
 * 用户 NAS 上叫「影片」或任何自定义名字的共享永远试不到，界面就退化成
 * 「这一层没有可再展开的文件夹」。这里走 Windows 资源管理器自己走的那条路：
 * IPC$ 命名管道上的 srvsvc 接口，NetrShareEnum（opnum 15）第 1 层，
 * 由服务器把 SHARE_INFO_1 列表直接回给我们。
 *
 * 手写 DCE/RPC 是因为 smbj 0.13 没有 srvsvc 的高层 API（只提供 NamedPipe）。
 * 协议按 DCE 1.1 + NDR32 编码，报文很小，单帧足够；失败一律抛出，
 * 由调用方回退到启发式探测 —— 枚举失败不该让「添加片库」整体失败。
 */
internal object SrvsvcShareEnum {

    private const val PIPE_NAME = "srvsvc"
    private const val SRVSVC_UUID = "4b324fc8-1670-01d3-1278-5a47bfe8a98e"
    private const val NDR32_UUID = "8a885d04-1ceb-11c9-9fe8-08002b104860"
    private const val OP_NUM_NET_SHARE_ENUM = 15

    private const val PTYPE_REQUEST = 0x00
    private const val PTYPE_RESPONSE = 0x02
    private const val PTYPE_FAULT = 0x03
    private const val PTYPE_BIND = 0x0B
    private const val PTYPE_BIND_ACK = 0x0C
    private const val PFC_LAST_FRAG = 0x02
    private const val CALL_ID = 1

    /** 服务器上的共享列表；`IPC$` 之类以 `$` 结尾的管理共享由调用方过滤。 */
    fun list(session: Session, host: String): List<String> {
        val ipc = session.connectShare("IPC\$") as? PipeShare
            ?: throw IOException("这台设备没有提供 IPC\$ 管道，无法列出共享")
        try {
            val pipe = ipc.open(
                PIPE_NAME,
                SMB2ImpersonationLevel.Impersonation,
                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            try {
                val bindAck = readFully(pipe, pipe.transact(bindPdu(CALL_ID)))
                if (bindAck.size < 16 || (bindAck[2].toInt() and 0xFF) != PTYPE_BIND_ACK) {
                    throw IOException("srvsvc 拒绝绑定")
                }
                val response = readFully(
                    pipe,
                    pipe.transact(requestPdu(CALL_ID + 1, enumStub(host)))
                )
                return parseShares(response)
            } finally {
                runCatching { pipe.close() }
            }
        } finally {
            runCatching { ipc.close() }
        }
    }

    // ------------------------------------------------------------ 报文构造

    internal fun bindPdu(callId: Int): ByteArray {
        val contexts = ByteArrayOutputStream().apply {
            writeLe16(0)                                   // context id
            write(1)                                       // transfer syntax 数量
            write(0)                                       // 保留
            write(uuidBytes(SRVSVC_UUID))
            writeLe16(3); writeLe16(0)                     // SRVSVC 3.0
            write(uuidBytes(NDR32_UUID))
            writeLe16(2); writeLe16(0)                     // NDR32 2.0
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(5); write(0); write(PTYPE_BIND); write(0x03)  // 版本 5.0 / bind / first+last
            writeLe32(0x10)                                     // 小端 + ASCII + IEEE
            writeLe16((16 + 12 + contexts.size).toLong())        // frag length
            writeLe16(0)                                         // auth length
            writeLe32(callId.toLong())
            writeLe16(4280)                                      // max xmit frag
            writeLe16(4280)                                      // max recv frag
            writeLe32(0)                                         // assoc group
            write(1)                                             // context 数量
            write(byteArrayOf(0, 0, 0))
            write(contexts)
        }.toByteArray()
    }

    internal fun requestPdu(callId: Int, stub: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(5); write(0); write(PTYPE_REQUEST); write(0x03)
            writeLe32(0x10)
            writeLe16((24 + stub.size).toLong())
            writeLe16(0)
            writeLe32(callId.toLong())
            writeLe32(stub.size.toLong())   // alloc hint
            writeLe16(0)                    // p_cont_id
            writeLe16(OP_NUM_NET_SHARE_ENUM.toLong())
            write(stub)
        }.toByteArray()

    /**
     * NetrShareEnum 的入参。
     *
     * V2.16 修正：**NDR 把所有定长字段排在前面，变长内容（deferred）统一排在后面。**
     * 上一版把 ServerName 的字符串紧跟在它的引用号之后、再写 Level / EntriesRead，
     * 服务器读到的 Level 其实是字符串的第一个 DWORD，整条报文被判为畸形请求——
     * 真机上表现成「设备没有返回共享列表」。这是编码顺序错误，不是设备不支持。
     *
     * 定长部分（28 字节）：ServerName 引用 / Level / EntriesRead / Buffer / 最大长度 /
     * TotalEntries 引用 / ResumeHandle 引用；变长部分：ServerName 字符串、两个引用指向的值。
     */
    internal fun enumStub(host: String): ByteArray = ByteArrayOutputStream().apply {
        writeLe32(0x0002_0000L)                 // ServerName 引用
        writeLe32(1)                            // Level = 1
        writeLe32(0)                            // EntriesRead = 0
        writeLe32(0)                            // Buffer = NULL
        writeLe32(0xFFFF_FFFFL)                 // PreferedMaximumLength
        writeLe32(0x0002_0004L)                 // TotalEntries 引用
        writeLe32(0x0002_0008L)                 // ResumeHandle 引用
        // ↓ 以下才是延迟数据，必须排在全部定长字段之后。
        writeWString("\\\\$host")
        writeLe32(0)                            // *TotalEntries = 0
        writeLe32(0)                            // *ResumeHandle = 0
    }.toByteArray()

    /** NDR 的 [string] WCHAR*：max_count / offset / actual_count 三个 DWORD，再是字符。 */
    private fun ByteArrayOutputStream.writeWString(value: String) {
        val units = value.length + 1                       // 含结尾 0
        writeLe32(units.toLong())                          // max_count
        writeLe32(0)                                       // offset
        writeLe32(units.toLong())                          // actual_count
        write(value.toByteArray(Charsets.UTF_16LE))
        write(0); write(0)                                 // 结尾 0
        while (size() % 4 != 0) write(0)
    }

    // ------------------------------------------------------------ 应答解析

    internal fun parseShares(pdu: ByteArray): List<String> {
        if (pdu.size < 24) throw IOException("srvsvc 应答过短：${pdu.size} 字节")
        when (pdu[2].toInt() and 0xFF) {
            PTYPE_RESPONSE -> Unit
            PTYPE_FAULT -> throw IOException("srvsvc 返回 fault：设备不允许枚举共享")
            else -> throw IOException("srvsvc 应答类型异常：${pdu[2]}")
        }
        val stub = pdu.copyOfRange(24, pdu.size)
        val level = u32(stub, 0)
        if (level != 1L) throw IOException("srvsvc 返回的层级 $level 不是 1")
        val entriesRead = u32(stub, 4).toInt()
        if (entriesRead <= 0) return emptyList()
        if (u32(stub, 8) == 0L) return emptyList()          // Buffer 为空

        // 应答的定长部分：Level / EntriesRead / Buffer 引用 / TotalEntries / ResumeHandle，
        // 共 20 字节，数组紧随其后。但不同实现可能把 TotalEntries 内联或延后，
        // 所以几个偏移都试一遍，取能读出合理名字的那个。
        for (offset in intArrayOf(20, 24, 12, 16)) {
            val names = runCatching { readEntries(stub, offset, entriesRead) }.getOrNull()
            if (!names.isNullOrEmpty() && names.all(::plausible)) return names
        }
        throw IOException(
            "srvsvc 应答无法解析（${pdu.size} 字节，前 8 字节 " +
                pdu.take(8).joinToString(" ") { "%02x".format(it) } + "）"
        )
    }

    private fun readEntries(stub: ByteArray, start: Int, count: Int): List<String> {
        var p = start
        val maxCount = u32(stub, p).toInt()
        p += 4
        if (maxCount < count || maxCount > 65_535) throw IOException("条目数不合理：$maxCount")
        val refs = ArrayList<LongArray>(count)
        repeat(count) {
            val nameRef = u32(stub, p)
            val remarkRef = u32(stub, p + 8)
            p += 12
            refs += longArrayOf(nameRef, remarkRef)
        }
        // 延迟指针按出现顺序排在定长部分之后：name0, remark0, name1, remark1…
        val names = ArrayList<String>(count)
        for (ref in refs) {
            var name = ""
            if (ref[0] != 0L) {
                val read = readWString(stub, p)
                name = read.first
                p = read.second
            }
            if (ref[1] != 0L) p = readWString(stub, p).second
            if (name.isNotEmpty()) names += name
        }
        return names
    }

    private fun readWString(data: ByteArray, offset: Int): Pair<String, Int> {
        if (offset + 12 > data.size) throw IOException("字符串越界")
        val units = u32(data, offset + 8).toInt()
        if (units <= 0 || units > 4096) throw IOException("字符串长度不合理：$units")
        val begin = offset + 12
        val textBytes = (units - 1) * 2
        if (begin + textBytes > data.size) throw IOException("字符串越界")
        val text = String(data, begin, textBytes, Charsets.UTF_16LE)
        val next = begin + units * 2
        return text to ((next + 3) and 3.inv())
    }

    private fun plausible(name: String): Boolean =
        name.isNotBlank() && name.length <= 128 && name.none { it.code < 0x20 }

    // ------------------------------------------------------------ 小工具

    /** 应答可能分多帧；按 PFC_LAST_FRAG 拼到最后一帧为止。 */
    private fun readFully(pipe: NamedPipe, first: ByteArray): ByteArray {
        var buffer = first
        var guard = 0
        while (guard++ < 8 && buffer.size >= 16 && (buffer[3].toInt() and PFC_LAST_FRAG) == 0) {
            val chunk = ByteArray(65_536)
            val read = runCatching { pipe.read(chunk) }.getOrDefault(-1)
            if (read <= 0) break
            buffer += chunk.copyOf(read)
        }
        return buffer
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > data.size) throw IOException("应答越界")
        return (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun ByteArrayOutputStream.writeLe16(value: Long) {
        write((value and 0xFF).toInt())
        write(((value shr 8) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeLe32(value: Long) {
        write((value and 0xFF).toInt())
        write(((value shr 8) and 0xFF).toInt())
        write(((value shr 16) and 0xFF).toInt())
        write(((value shr 24) and 0xFF).toInt())
    }

    /** UUID 的 data1/2/3 小端、data4 原样，这是 DCE/RPC 的写法。 */
    private fun uuidBytes(uuid: String): ByteArray {
        val parts = uuid.split('-')
        require(parts.size == 5) { "UUID 格式错误：$uuid" }
        val out = ByteArray(16)
        fun putLe(value: Long, offset: Int, width: Int) {
            for (i in 0 until width) out[offset + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
        putLe(parts[0].toLong(16), 0, 4)
        putLe(parts[1].toLong(16), 4, 2)
        putLe(parts[2].toLong(16), 6, 2)
        val tail = parts[3] + parts[4]
        for (i in 0 until 8) {
            out[8 + i] = tail.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
