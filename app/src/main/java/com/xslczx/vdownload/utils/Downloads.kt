package com.xslczx.vdownload.utils

import android.Manifest
import android.os.Environment
import android.util.Log
import com.blankj.utilcode.util.PermissionUtils
import com.blankj.utilcode.util.Utils
import com.xslczx.vdownload.Media
import com.xslczx.vdownload.MyApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载单个 URL，返回保存的 File（失败抛异常）
 */
suspend fun downloadSingle(
    url: String,
    client: OkHttpClient,
    onProgress: (Int) -> Unit = {},
    retries: Int = 2
): DownloadResult = withContext(Dispatchers.IO) {
    if (url.isBlank()) {
        return@withContext DownloadResult(exception = IllegalArgumentException("URL must not be blank"))
    }

    var lastError: Exception? = null
    var temporaryFile: File? = null
    repeat(retries + 1) { attempt ->
        try {
            var contentType: String? = null
            var contentDisposition: String? = null
            try {
                val headRequest = Request.Builder().url(url).head().build()
                client.newCall(headRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        contentType = response.header("Content-Type")
                        contentDisposition = response.header("Content-Disposition")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }

            val detectedMedia = MediaTypeDetector.detect(contentType = contentType)
            val urlPath = URL(url).path
            val extensionResult = ExtensionGuesser.guessBestExtension(url, contentType, contentDisposition)
            val rawFileName = File(urlPath).name.takeIf { it.isNotBlank() }?.substringBeforeLast(".") ?: "download"
            val sanitizedFileName = sanitizeFileName(rawFileName)
            val targetDirectory = resolveOutputDirectory(detectedMedia.category)
            val targetFile = ExtensionGuesser.uniqueFile(targetDirectory, sanitizedFileName, extensionResult.extension)
            val tempFile = File(
                MyApp.instance.cacheDir,
                "${targetFile.nameWithoutExtension}.${extensionResult.extension}.part"
            )
            temporaryFile = tempFile

            Log.d(
                ">>>>:FilePaths",
                "Target file: ${targetFile.absolutePath}, Temp file: ${tempFile.absolutePath}"
            )

            val downloadRequest = Request.Builder().url(url).get().build()
            client.newCall(downloadRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code} 下载失败")
                }

                val responseBody = response.body ?: throw RuntimeException("空响应体")
                val totalBytes = responseBody.contentLength().takeIf { it > 0 } ?: -1L
                tempFile.outputStream().buffered().use { outputStream ->
                    responseBody.byteStream().use { inputStream ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var downloadedBytes = 0L
                        var lastReportedProgress = -1
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                if (progress != lastReportedProgress) {
                                    onProgress(progress)
                                    lastReportedProgress = progress
                                }
                            }
                        }
                        outputStream.flush()
                    }
                }
            }

            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            temporaryFile = null // 已成功落盘，无需再清理

            return@withContext DownloadResult(file = targetFile, media = detectedMedia.category)
        } catch (e: CancellationException) {
            // 协程取消不能当成下载失败进重试，必须原样上抛
            throw e
        } catch (exception: Exception) {
            lastError = exception
            // 失败的半成品临时文件没有保留价值，留在 cacheDir 只会持续占空间
            temporaryFile?.delete()
            delay(300L * (attempt + 1))
        }
    }

    DownloadResult(exception = lastError).also {
        // 所有重试都失败时兜底再清一次，防止异常路径下的残留
        temporaryFile?.delete()
    }
}

// 每个下载文件都会调用，正则预编译成常量避免重复编译
private val illegalFileNameChars = "[^a-zA-Z0-9._-]".toRegex()

private fun sanitizeFileName(rawFileName: String): String {
    return rawFileName
        .replace(illegalFileNameChars, "_")
        .replace("~", "_")
        .replace(":", "_")
        .trim()
        .ifEmpty { "download" }
}

private fun resolveOutputDirectory(mediaCategory: MediaCategory): File {
    val directoryType = when (mediaCategory) {
        MediaCategory.AUDIO -> Environment.DIRECTORY_MUSIC
        MediaCategory.VIDEO -> Environment.DIRECTORY_MOVIES
        MediaCategory.IMAGE -> Environment.DIRECTORY_PICTURES
        else -> Environment.DIRECTORY_DOWNLOADS
    }

    val outputDirectory = if (PermissionUtils.isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
        Environment.getExternalStoragePublicDirectory(directoryType)
    } else {
        Utils.getApp().getExternalFilesDir(null)
    } ?: throw IllegalStateException("Unable to resolve output directory")

    if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
        throw IllegalStateException("Unable to create output directory: ${outputDirectory.absolutePath}")
    }

    return outputDirectory
}

/**
 * 批量并发下载入口
 *
 * 挂起直到所有并发任务完成并返回结果，调用方可以据此把「任务进行中」的标记
 * 保留到本次下载彻底结束，避免下载过程中被重复触发。
 */
suspend fun downloadAllMedia(
    urls: List<Media>,
    concurrency: Int = 3,
    onEachProgress: (url: String, progress: Int) -> Unit = { _, _ -> },
    onOverallProgress: (overallPercent: Int) -> Unit = {}
): Map<String, DownloadResult> = coroutineScope {
    if (urls.isEmpty()) {
        onOverallProgress(100)
        return@coroutineScope emptyMap()
    }

    // 复用全局客户端：每次批量新建再废弃会重复分配连接池与线程池
    val client = sharedOkHttpClient

    val progressMap = ConcurrentHashMap<String, Int>()
    urls.forEach { progressMap[it.path] = 0 }

    fun computeOverallProgress(): Int {
        val totalProgress = urls.size * 100
        val currentProgress = urls.sumOf { progressMap[it.path] ?: 0 }
        return ((currentProgress * 100) / totalProgress).coerceIn(0, 100)
    }

    val semaphore = Semaphore(concurrency.coerceAtLeast(1))
    val downloadTasks = urls.map { media ->
        async {
            semaphore.withPermit {
                val result = downloadSingle(media.path, client, onProgress = { progress ->
                    progressMap[media.path] = progress
                    onEachProgress(media.path, progress)
                    onOverallProgress(computeOverallProgress())
                })

                if (result.file != null) {
                    progressMap[media.path] = 100
                    onOverallProgress(computeOverallProgress())
                }

                media.path to result
            }
        }
    }

    val results = downloadTasks.awaitAll().toMap()
    onOverallProgress(100)
    results
}
