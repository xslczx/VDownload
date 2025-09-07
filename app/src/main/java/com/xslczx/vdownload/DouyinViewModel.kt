package com.xslczx.vdownload

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xslczx.vdownload.databse.AppDatabase
import com.xslczx.vdownload.databse.DouyinVideo
import com.xslczx.vdownload.utils.MediaCategory
import com.xslczx.vdownload.utils.downloadAllMedia
import com.xslczx.vdownload.utils.extractUrlFromClipboard
import com.xslczx.vdownload.utils.fetchVideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class DouyinViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getInstance(application)
    val videosLiveData = MutableLiveData<List<DouyinVideo>>()

    fun refreshVideo(text: String) {
        viewModelScope.launch {
            val url = extractUrlFromClipboard(text)
            if (url.isNullOrEmpty()) {
                return@launch
            }
            val lastByUrl = db.videoDao().getLastByUrl(url)
            val list = if (lastByUrl == null) emptyList() else arrayListOf(lastByUrl)
            videosLiveData.postValue(list)
        }
    }

    fun refreshAllVideos() {
        viewModelScope.launch {
            videosLiveData.postValue(db.videoDao().getAll())
        }
    }

    fun deleteVideo(video: DouyinVideo, onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            db.videoDao().delete(video.id)
            withContext(Dispatchers.Main) {
                onComplete?.invoke()
            }
        }
    }

    suspend fun shouldProcessClipboardContent(text: String): Boolean {
        val url = extractUrlFromClipboard(text)
        if (url.isNullOrEmpty()) return false
        val lastByUrl = db.videoDao().getLastByUrl(url)
        return lastByUrl==null
    }

    fun processClipboardContent(
        text: String,
        onStep: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    onStep("正在提取链接…")
                }
                val url = extractUrlFromClipboard(text)
                if (url.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        onError("链接无效")
                    }
                    return@launch
                }
                val lastByUrl = db.videoDao().getLastByUrl(url)
                if (lastByUrl != null) {
                    withContext(Dispatchers.Main) {
                        onComplete()
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    onStep("正在解析链接…")
                }
                val apiResponse = fetchVideoInfo(url, UUID.randomUUID().toString().lowercase()) //这appKey还是随便写一个吧
                val data = apiResponse?.data
                if (data == null) {
                    withContext(Dispatchers.Main) {
                        onError("解析失败")
                    }
                    return@launch
                }

                val urls = mutableSetOf<String>()

                data.video?.let { urls.add(it) }
                data.atlas?.let { urls.addAll(it) }
                data.image?.let { urls.add(it) }
                data.url?.let { urls.add(it) }

                withContext(Dispatchers.Main) {
                    onStep("正在下载…")
                }
                val dir = getApplication<Application>().getExternalFilesDir(null)
                downloadAllMedia(
                    urls = urls.toList(),
                    destDir = dir!!,
                    concurrency = 3,
                    scope = this,
                    onEachProgress = { url, progress ->
                        Log.d(">>>:Download", "Downloading $url: $progress")
                    },
                    onOverallProgress = { progress ->
                        viewModelScope.launch(Dispatchers.Main) {
                            onStep("正在下载 $progress%")
                        }
                    },
                    onAllComplete = { results ->
                        val success = results.values.all { it.file != null }
                        if (success) {
                            viewModelScope.launch(Dispatchers.Main) {
                                onStep("下载完成")
                            }
                            Log.d(">>>:download", "下载完成:$results")
                            val videoPaths = results.filter { it.value.media== MediaCategory.VIDEO }.mapNotNull { it.value.file?.absolutePath }
                            val imagePaths = results.filter { it.value.media== MediaCategory.IMAGE }.mapNotNull { it.value.file?.absolutePath }
                            viewModelScope.launch(Dispatchers.Main) {
                                onStep("正在保存记录…")
                            }
                            viewModelScope.launch(Dispatchers.IO) {
                                val record = DouyinVideo(
                                    url = url,
                                    title = data.title,
                                    savedImagePaths = imagePaths.joinToString(","),
                                    savedVideoPath = videoPaths.joinToString(",")
                                )
                                db.videoDao().insert(record)
                                viewModelScope.launch(Dispatchers.Main) {
                                    onComplete()
                                }
                            }
                        } else {
                            viewModelScope.launch(Dispatchers.Main) {
                                onError("下载失败")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(">>>","error",e)
                withContext(Dispatchers.Main) {
                    onError("失败")
                }
            }
        }
    }
}