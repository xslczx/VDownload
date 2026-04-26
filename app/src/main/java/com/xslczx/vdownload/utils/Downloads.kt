package com.xslczx.vdownload.utils

import android.Manifest
import android.app.Application
import android.os.Environment
import android.util.Log
import com.blankj.utilcode.util.FileUtils
import com.blankj.utilcode.util.PermissionUtils
import com.blankj.utilcode.util.Utils
import com.xslczx.vdownload.Media
import com.xslczx.vdownload.MyApp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 下载单个 URL，返回保存的 File（失败抛异常）
 */
suspend fun downloadSingle(
    url: String,
    client: OkHttpClient,
    onProgress: (Int) -> Unit = {},
    retries: Int = 2
): DownloadResult = withContext(Dispatchers.IO) {
    var lastEx: Exception? = null
    repeat(retries + 1) { attempt ->
        try {
            // HEAD 试探（容错）
            var contentType: String? = null
            var contentDisposition: String? = null
            try {
                val headReq = Request.Builder().url(url).head().build()
                client.newCall(headReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        contentType = resp.header("Content-Type")
                        contentDisposition = resp.header("Content-Disposition")
                    }
                }
            } catch (_: Exception) {}

            val detectedMedia = MediaTypeDetector.detect(contentType = contentType)

            // 提取 URL 文件名并结合响应头推断后缀
            val urlPath = URL(url).path
            val result = ExtensionGuesser.guessBestExtension(url, contentType, contentDisposition)

            val rawName = File(urlPath).name.takeIf { it.isNotBlank() }?.substringBeforeLast(".") ?: "download"
            val sanitizedRawName = sanitizeFileName(rawName)

            val type = when (detectedMedia.category) {
                MediaCategory.AUDIO -> Environment.DIRECTORY_MUSIC
                MediaCategory.VIDEO -> Environment.DIRECTORY_MOVIES
                MediaCategory.IMAGE -> Environment.DIRECTORY_PICTURES
                else -> Environment.DIRECTORY_DOWNLOADS
            }
            val granted = PermissionUtils.isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val file = if (granted) Environment.getExternalStoragePublicDirectory(type) else Utils.getApp().getExternalFilesDir(null)!!
            if (!file.exists()) {
                file.mkdirs()
            }

            // 生成唯一的目标文件
            val targetFile = ExtensionGuesser.uniqueFile(file, sanitizedRawName, result.extension)

            // 创建临时文件，使用目标文件名的一部分
            val tmpFile = File(MyApp.instance.cacheDir, "${targetFile.nameWithoutExtension}.${result.extension}.part")

            // 打印文件路径进行调试
            Log.d(">>>>:FilePaths", "Target file: ${targetFile.absolutePath}, Temp file: ${tmpFile.absolutePath}")


            // GET 下载
            val getReq = Request.Builder().url(url).get().build()
            client.newCall(getReq).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} 下载失败")
                val body = resp.body ?: throw RuntimeException("空响应体")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: -1L

                tmpFile.outputStream().buffered().use { out ->
                    val input = body.byteStream()
                    val buf = ByteArray(8 * 1024)
                    var read: Int
                    var acc = 0L
                    var lastEmitted = -1
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        acc += read
                        if (totalBytes > 0) {
                            val progress = ((acc * 100) / totalBytes).toInt().coerceIn(0, 100)
                            if (progress != lastEmitted) {
                                onProgress(progress)
                                lastEmitted = progress
                            }
                        }
                    }
                    out.flush()
                }

                // 重命名
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    tmpFile.delete()
                }
            }

            return@withContext DownloadResult(file = targetFile, media = detectedMedia.category)
        } catch (e: Exception) {
            lastEx = e
            delay(300L * (attempt + 1)) // 简单退避
        }
    }
    DownloadResult(exception = lastEx)
}

private fun sanitizeFileName(rawFileName: String): String {
    return rawFileName
        .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        .replace("~", "_")
        .replace(":", "_")
        .trim()
        .ifEmpty { "download" }
}


/**
 * 批量并发下载入口
 */
fun downloadAllMedia(
    urls: List<Media>,
    concurrency: Int = 3,
    scope: CoroutineScope,
    onEachProgress: (url: String, progress: Int) -> Unit = { _, _ -> },
    onOverallProgress: (overallPercent: Int) -> Unit = {},
    onAllComplete: (Map<String, DownloadResult>) -> Unit
): Job {
    val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    // 保存每个 url 当前的进度 0..100
    val progressMap = ConcurrentHashMap<String, Int>()
    urls.forEach { progressMap[it.path] = 0 } // 初始化为 0

    // 计算总体进度（所有 url 的进度平均，带权重）
    fun computeOverall(): Int {
        val total = urls.size * 100
        val sum = urls.sumOf { progressMap[it.path] ?: 0 }
        return ((sum * 100) / total).coerceIn(0, 100) // 0..100
    }

    val semaphore = Semaphore(concurrency)
    return scope.launch {
        val deferreds = urls.map { media ->
            async {
                semaphore.withPermit {
                    val result = downloadSingle(media.path, client, onProgress = { p ->
                        progressMap[media.path] = p
                        // 每个 url 的单独回调
                        onEachProgress(media.path, p)
                        // 计算并反馈整体进度（包含前面已完成的 index 和当前的部分进度）
                        onOverallProgress(computeOverall())
                    })

                    // 如果成功，确保进度置为 100，再刷新一次整体
                    if (result.file != null) {
                        progressMap[media.path] = 100
                        onOverallProgress(computeOverall())
                    }

                    media.path to result
                }
            }
        }

        val results = deferreds.awaitAll().toMap()
        // 最终确保整体 100%
        onOverallProgress(100)
        onAllComplete(results)
    }
}
