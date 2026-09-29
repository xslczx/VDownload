package com.xslczx.vdownload.utils

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File

/**
 * 把已下载的媒体文件导出到系统相册（媒体库）。
 *
 * 两种落盘位置、两种导出策略：
 * 1. 文件在公共目录（Movies/Pictures/Music）：MediaScannerConnection 带显式
 *    MIME 扫描即可登记进 MediaStore（部分 ROM 对 mimeType=null 不索引）。
 * 2. 文件在应用私有目录（Android/data/<pkg>/files，未授权写存储时的回退位置）：
 *    Android 10+ 上 MediaScanner 对私有目录的扫描经常不生效，相册看不到。
 *    API 29+ 改用 MediaStore 直接插入（RELATIVE_PATH + 流拷贝），由系统保证
 *    相册可见；API 29 以下设备私有目录文件同样退回扫描（旧系统可用）。
 */
object GalleryExporter {

    fun export(context: Context, files: List<Pair<File, MediaCategory>>) {
        val existing = files.filter { it.first.exists() }
        if (existing.isEmpty()) return

        val scanCandidates = mutableListOf<Pair<File, MediaCategory>>()
        for ((file, category) in existing) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAppSpecificFile(file)) {
                if (!copyIntoMediaStore(context, file, category)) {
                    // MediaStore 插入失败（磁盘满等），退回扫描再试一次
                    scanCandidates += file to category
                }
            } else {
                scanCandidates += file to category
            }
        }

        if (scanCandidates.isEmpty()) return
        MediaScannerConnection.scanFile(
            context,
            scanCandidates.map { it.first.absolutePath }.toTypedArray(),
            scanCandidates.map { mimeTypeOf(it.second, it.first) }.toTypedArray()
        ) { path, uri ->
            Log.d("GalleryExporter", "scan: $path -> $uri")
        }
    }

    /** 应用私有外部目录（Android/data/...）下的文件 */
    private fun isAppSpecificFile(file: File): Boolean {
        return file.absolutePath.startsWith(
            File(Environment.getExternalStorageDirectory(), "Android/data").absolutePath
        )
    }

    /**
     * 把文件内容拷贝进 MediaStore（API 29+）。RELATIVE_PATH 指定公共媒体目录，
     * 拷贝完成后系统自动清除 IS_PENDING，相册即可见。
     *
     * @return true 表示已插入（或同名记录已存在，视为成功）
     */
    private fun copyIntoMediaStore(context: Context, file: File, category: MediaCategory): Boolean {
        val displayName = file.name
        val mimeType = mimeTypeOf(category, file)

        val (collection, relativePath) = when (category) {
            MediaCategory.IMAGE -> Pair(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                Environment.DIRECTORY_PICTURES
            )
            MediaCategory.VIDEO -> Pair(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                Environment.DIRECTORY_MOVIES
            )
            MediaCategory.AUDIO -> Pair(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                Environment.DIRECTORY_MUSIC
            )
            MediaCategory.OTHER -> Pair(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                Environment.DIRECTORY_DOWNLOADS
            )
        }

        // 同名同目录的记录已存在（重复导出同一文件），不再重复插入
        val alreadyExported = context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(displayName, "$relativePath/"),
            null
        )?.use { it.count > 0 } ?: false
        if (alreadyExported) {
            Log.d("GalleryExporter", "already in MediaStore: $displayName")
            return true
        }

        val rowUri = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            context.contentResolver.insert(collection, values)
        }.getOrElse {
            Log.e("GalleryExporter", "insert failed: $displayName", it)
            return false
        } ?: return false

        val copied = runCatching {
            context.contentResolver.openOutputStream(rowUri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } != null
        }.getOrElse {
            Log.e("GalleryExporter", "copy failed: $displayName", it)
            false
        }

        // 拷贝失败要删掉 pending 行，否则相册里留一个 0 字节的僵尸条目
        if (copied) {
            context.contentResolver.update(
                rowUri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null
            )
        } else {
            context.contentResolver.delete(rowUri, null, null)
            return false
        }

        Log.d("GalleryExporter", "copied into MediaStore: $displayName -> $rowUri")
        return true
    }

    private fun mimeTypeOf(category: MediaCategory, file: File): String {
        // 优先按扩展名拿精确 MIME，拿不到再按媒体类别兜底
        val byExtension = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase())
        return byExtension ?: when (category) {
            MediaCategory.IMAGE -> "image/jpeg"
            MediaCategory.VIDEO -> "video/mp4"
            MediaCategory.AUDIO -> "audio/mpeg"
            MediaCategory.OTHER -> "application/octet-stream"
        }
    }
}
