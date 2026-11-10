package com.gazereel.vr.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/**
 * V2.7「手机里的视频」——走系统索引，不再用 File 遍历。
 *
 * 为什么必须换：
 * 1. Android 10 起分区存储，`File("/sdcard/DCIM").listFiles()` 读不到别人的目录，
 *    旧实现即便申请了权限也只能看到空列表；
 * 2. 旧实现一次只看一层目录，用户还得自己记住文件夹名——正好违反「零输入」。
 *
 * MediaStore 是系统已经建好的视频索引，用户不需要选目录、不需要知道路径，
 * 进来就能看到整机视频。path 写 content:// URI，播放侧用 ContentResolver 的
 * 文件描述符做随机读，不依赖真实文件路径。
 */
class LocalVideoScanner(private val context: Context) {

    private val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    /**
     * relativeDir 为空 = 整机全部视频；否则只看该目录（例如 `DCIM/Camera/`）。
     * 排序按添加时间倒序——刚拍的排最前，符合「我想看刚才那段」的真实需求。
     */
    fun list(relativeDir: String): List<IndexEntry> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE
        )
        val selection = if (relativeDir.isBlank()) null
        else "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = if (relativeDir.isBlank()) null else arrayOf("$relativeDir%")

        val out = mutableListOf<IndexEntry>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                    out += IndexEntry(
                        path = uri.toString(),
                        title = name.substringBeforeLast('.'),
                        sizeBytes = cursor.getLong(sizeCol)
                    )
                }
            }
        }
        return out
    }

    /** 按目录聚合成「叠」：给「只想看某个相册」的人一个可点选列表。 */
    fun folders(): List<IndexEntry> {
        val projection = arrayOf(MediaStore.Video.Media.RELATIVE_PATH)
        val counts = mutableMapOf<String, Int>()
        runCatching {
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val dir = cursor.getString(col) ?: continue
                    counts[dir] = (counts[dir] ?: 0) + 1
                }
            }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .map { (dir, count) ->
                IndexEntry(
                    path = dir,
                    title = dir.trimEnd('/').substringAfterLast('/').ifBlank { dir },
                    sizeBytes = count.toLong(),
                    isDirectory = true
                )
            }
    }

    /** 有权限才看得见视频；配置页用它决定「点一下就能用」还是「先授权」。 */
    fun hasAnyVideo(): Boolean = runCatching {
        context.contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID), null, null, null)
            ?.use { it.count > 0 } ?: false
    }.getOrDefault(false)
}
