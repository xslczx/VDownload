package com.xslczx.vdownload.utils

import android.util.Log
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
    destDir: File,
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

            val result = ExtensionGuesser.guessBestExtension(url, contentType, contentDisposition)
            if (result.conflictWithMime) {
                Log.w(">>>:ExtGuesser", "Content-Type 和选中的后缀冲突")
            }
            if (result.conflictWithUrlExt) {
                Log.w(">>>:ExtGuesser", "URL 本身的后缀与选中不同")
            }
            val urlObj = URL(url)
            val rawName = File(urlObj.path).name.takeIf { it.isNotBlank() }?.substringBeforeLast(".") ?: "download"
            val targetFile = ExtensionGuesser.uniqueFile(destDir, rawName, result.extension)
            val tmpFile = File(destDir, "${targetFile.nameWithoutExtension}.${result.extension}.part")

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
            val detectedMedia = MediaTypeDetector.detect(contentType = contentType, file = targetFile)
            return@withContext DownloadResult(file = targetFile, media = detectedMedia.category)
        } catch (e: Exception) {
            lastEx = e
            delay(300L * (attempt + 1)) // 简单退避
        }
    }
    DownloadResult(exception = lastEx)
}


/**
 * 批量并发下载入口
 */
fun downloadAllMedia(
    urls: List<String>,
    destDir: File,
    concurrency: Int = 3,
    scope: CoroutineScope,
    onEachProgress: (url: String, progress: Int) -> Unit = { _, _ -> },
    onOverallProgress: (overallPercent: Int) -> Unit = {},
    onAllComplete: (Map<String, DownloadResult>) -> Unit
): Job {
    require(destDir.exists() || destDir.mkdirs())
    val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    // 保存每个 url 当前的进度 0..100
    val progressMap = ConcurrentHashMap<String, Int>()
    urls.forEach { progressMap[it] = 0 } // 初始化为 0

    // 计算总体进度（所有 url 的进度平均，带权重）
    fun computeOverall(): Int {
        val total = urls.size * 100
        val sum = urls.sumOf { progressMap[it] ?: 0 }
        return ((sum * 100) / total).coerceIn(0, 100) // 0..100
    }

    val semaphore = Semaphore(concurrency)
    return scope.launch {
        val deferreds = urls.mapIndexed { index, url ->
            async {
                semaphore.withPermit {
                    val result = downloadSingle(url, destDir, client, onProgress = { p ->
                        progressMap[url] = p
                        // 每个 url 的单独回调
                        onEachProgress(url, p)
                        // 计算并反馈整体进度（包含前面已完成的 index 和当前的部分进度）
                        onOverallProgress(computeOverall())
                    })

                    // 如果成功，确保进度置为 100，再刷新一次整体
                    if (result.file != null) {
                        progressMap[url] = 100
                        onOverallProgress(computeOverall())
                    }

                    url to result
                }
            }
        }

        val results = deferreds.awaitAll().toMap()
        // 最终确保整体 100%
        onOverallProgress(100)
        onAllComplete(results)
    }
}