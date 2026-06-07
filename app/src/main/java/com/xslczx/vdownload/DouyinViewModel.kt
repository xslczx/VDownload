package com.xslczx.vdownload

import android.app.Application
import android.media.MediaScannerConnection
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xslczx.vdownload.databse.AppDatabase
import com.xslczx.vdownload.databse.DouyinVideo
import com.xslczx.vdownload.databse.DouyinVideoData
import com.xslczx.vdownload.utils.DownloadResult
import com.xslczx.vdownload.utils.MediaCategory
import com.xslczx.vdownload.utils.downloadAllMedia
import com.xslczx.vdownload.utils.extractUrlFromClipboard
import com.xslczx.vdownload.utils.fetchVideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DouyinViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val VIDEO_INFO_APP_KEY = "7e8c673248ef697b9697a563cf288ae1"
        const val STEP_EXTRACTING_URL = "正在提取链接…"
        const val STEP_PARSING_URL = "正在解析链接…"
        const val STEP_DOWNLOADING = "正在下载…"
        const val STEP_DOWNLOAD_COMPLETED = "下载完成"
        const val STEP_SAVING_RECORD = "正在保存记录…"
        const val ERROR_INVALID_URL = "链接无效"
        const val ERROR_PARSE_FAILED = "解析失败"
        const val ERROR_DOWNLOAD_FAILED = "下载失败"
        const val ERROR_UNKNOWN = "失败"
    }

    private val database = AppDatabase.getInstance(application)
    val videosLiveData = MutableLiveData<List<DouyinVideo>>()

    fun refreshVideo(text: String) {
        viewModelScope.launch {
            val normalizedUrl = normalizeUrl(text) ?: return@launch
            val savedVideo = database.videoDao().getLastByUrl(normalizedUrl)
            videosLiveData.postValue(savedVideo?.let(::listOf).orEmpty())
        }
    }

    fun refreshAllVideos() {
        viewModelScope.launch {
            videosLiveData.postValue(database.videoDao().getAll())
        }
    }

    fun deleteVideo(video: DouyinVideo, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            database.videoDao().delete(video.id)
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    suspend fun shouldProcessClipboardContent(text: String): Boolean {
        val normalizedUrl = normalizeUrl(text) ?: return false
        return database.videoDao().getLastByUrl(normalizedUrl) == null
    }

    fun processClipboardContent(
        text: String,
        onStep: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dispatchStep(onStep, STEP_EXTRACTING_URL)
                val normalizedUrl = normalizeUrl(text)
                if (normalizedUrl.isNullOrEmpty()) {
                    dispatchStep(onError, ERROR_INVALID_URL)
                    return@launch
                }

                if (database.videoDao().getLastByUrl(normalizedUrl) != null) {
                    withContext(Dispatchers.Main) {
                        onComplete()
                    }
                    return@launch
                }

                dispatchStep(onStep, STEP_PARSING_URL)
                val responseData = fetchVideoInfo(normalizedUrl, VIDEO_INFO_APP_KEY)?.data
                if (responseData == null) {
                    dispatchStep(onError, ERROR_PARSE_FAILED)
                    return@launch
                }

                val mediaList = collectMedia(responseData)
                if (mediaList.isEmpty()) {
                    dispatchStep(onError, ERROR_DOWNLOAD_FAILED)
                    return@launch
                }

                dispatchStep(onStep, STEP_DOWNLOADING)
                downloadAllMedia(
                    urls = mediaList,
                    concurrency = 3,
                    scope = this,
                    onEachProgress = { downloadUrl, progress ->
                        Log.d(">>>:Download", "Downloading $downloadUrl: $progress")
                    },
                    onOverallProgress = { progress ->
                        viewModelScope.launch(Dispatchers.Main) {
                            onStep("正在下载 $progress%")
                        }
                    },
                    onAllComplete = { results ->
                        handleDownloadResults(
                            normalizedUrl = normalizedUrl,
                            title = responseData.title,
                            results = results,
                            onStep = onStep,
                            onComplete = onComplete,
                            onError = onError
                        )
                    }
                )
            } catch (exception: Exception) {
                Log.e(">>>", "error", exception)
                dispatchStep(onError, ERROR_UNKNOWN)
            }
        }
    }

    private fun normalizeUrl(text: String): String? {
        return extractUrlFromClipboard(text)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun dispatchStep(callback: (String) -> Unit, message: String) {
        withContext(Dispatchers.Main) {
            callback(message)
        }
    }

    private fun collectMedia(responseData: DouyinVideoData): List<Media> {
        return buildSet {
            responseData.video?.takeIf { it.isNotBlank() }?.let { add(Media(it, true)) }
            if (responseData.video.isNullOrEmpty()) {
                responseData.image?.takeIf { it.isNotBlank() }?.let { add(Media(it, false)) }
            }
            if (isEmpty()) {
                responseData.atlas.orEmpty()
                    .filter { atlasUrl -> atlasUrl.isNotBlank() }
                    .forEach { atlasUrl -> add(Media(atlasUrl, false)) }
            }
        }.toList()
    }

    private fun handleDownloadResults(
        normalizedUrl: String,
        title: String?,
        results: Map<String, DownloadResult>,
        onStep: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        exportToGallery(results)
        Log.d(">>>:download", "下载:${results.values}")

        if (results.isEmpty() || results.values.any { it.file == null }) {
            viewModelScope.launch(Dispatchers.Main) {
                onError(ERROR_DOWNLOAD_FAILED)
            }
            return
        }

        val savedVideoPaths = extractSavedPaths(results, MediaCategory.VIDEO)
        val savedImagePaths = extractSavedPaths(results, MediaCategory.IMAGE)
        viewModelScope.launch(Dispatchers.Main) {
            onStep(STEP_DOWNLOAD_COMPLETED)
            onStep(STEP_SAVING_RECORD)
        }
        viewModelScope.launch(Dispatchers.IO) {
            database.videoDao().insert(
                DouyinVideo(
                    url = normalizedUrl,
                    title = title,
                    savedImagePaths = savedImagePaths.joinToString(","),
                    savedVideoPath = savedVideoPaths.joinToString(",")
                )
            )
            withContext(Dispatchers.Main) {
                onComplete()
            }
        }
    }

    private fun extractSavedPaths(
        results: Map<String, DownloadResult>,
        mediaCategory: MediaCategory
    ): List<String> {
        return results.values
            .filter { it.media == mediaCategory }
            .mapNotNull { it.file?.absolutePath }
    }

    private fun exportToGallery(results: Map<String, DownloadResult>) {
        val savedPaths = results.values.mapNotNull { it.file?.absolutePath }
        if (savedPaths.isEmpty()) return

        val existingPaths = savedPaths.filter { path -> File(path).exists() }
        if (existingPaths.isEmpty()) return

        MediaScannerConnection.scanFile(
            getApplication(),
            existingPaths.toTypedArray(),
            null
        ) { path, uri ->
            Log.d("HomeFragment", "exportToGallery: $path -> $uri")
        }
    }
}
