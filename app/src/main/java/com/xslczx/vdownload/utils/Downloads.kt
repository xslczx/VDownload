package com.xslczx.vdownload.utils

import android.Manifest
import android.os.Environment
import android.util.Log
import com.blankj.utilcode.util.PermissionUtils
import com.blankj.utilcode.util.Utils
import com.xslczx.vdownload.Media
import com.xslczx.vdownload.MyApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    if (url.isBlank()) {
        return@withContext DownloadResult(exception = IllegalArgumentException("URL must not be blank"))
    }

    var lastError: Exception? = null
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
            } catch (_: Exception) {
            }

            val detectedMedia = MediaTypeDetector.detect(contentType = contentType)
            val urlPath = URL(url).path
            val extensionResult = ExtensionGuesser.guessBestExtension(url, contentType, contentDisposition)
            val rawFileName = File(urlPath).name.takeIf { it.isNotBlank() }?.substringBeforeLast(".") ?: "download"
            val sanitizedFileName = sanitizeFileName(rawFileName)
            val targetDirectory = resolveOutputDirectory(detectedMedia.category)
            val targetFile = ExtensionGuesser.uniqueFile(targetDirectory, sanitizedFileName, extensionResult.extension)
            val temporaryFile = File(
                MyApp.instance.cacheDir,
                "${targetFile.nameWithoutExtension}.${extensionResult.extension}.part"
            )

            Log.d(
                ">>>>:FilePaths",
                "Target file: ${targetFile.absolutePath}, Temp file: ${temporaryFile.absolutePath}"
            )

            val downloadRequest = Request.Builder().url(url).get().build()
            client.newCall(downloadRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code} 下载失败")
                }

                val responseBody = response.body ?: throw RuntimeException("空响应体")
                val totalBytes = responseBody.contentLength().takeIf { it > 0 } ?: -1L
                temporaryFile.outputStream().buffered().use { outputStream ->
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

            if (!temporaryFile.renameTo(targetFile)) {
                temporaryFile.copyTo(targetFile, overwrite = true)
                temporaryFile.delete()
            }

            return@withContext DownloadResult(file = targetFile, media = detectedMedia.category)
        } catch (exception: Exception) {
            lastError = exception
            delay(300L * (attempt + 1))
        }
    }

    DownloadResult(exception = lastError)
}

private fun sanitizeFileName(rawFileName: String): String {
    return rawFileName
        .replace("[^a-zA-Z0-9._-]".toRegex(), "_")
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
 */
fun downloadAllMedia(
    urls: List<Media>,
    concurrency: Int = 3,
    scope: CoroutineScope,
    onEachProgress: (url: String, progress: Int) -> Unit = { _, _ -> },
    onOverallProgress: (overallPercent: Int) -> Unit = {},
    onAllComplete: (Map<String, DownloadResult>) -> Unit
): Job {
    if (urls.isEmpty()) {
        return scope.launch {
            onOverallProgress(100)
            onAllComplete(emptyMap())
        }
    }

    val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    val progressMap = ConcurrentHashMap<String, Int>()
    urls.forEach { progressMap[it.path] = 0 }

    fun computeOverallProgress(): Int {
        val totalProgress = urls.size * 100
        val currentProgress = urls.sumOf { progressMap[it.path] ?: 0 }
        return ((currentProgress * 100) / totalProgress).coerceIn(0, 100)
    }

    val semaphore = Semaphore(concurrency.coerceAtLeast(1))
    return scope.launch {
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
        onAllComplete(results)
    }
}
