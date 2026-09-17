package com.xslczx.vdownload.utils

import android.util.Log
import com.squareup.moshi.Moshi
import com.xslczx.vdownload.databse.DouyinApiResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.security.MessageDigest

fun String.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    val bytes = md.digest(this.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

fun extractUrlFromClipboard(text: String): String? {
    val value = ClipboardUtils.extractCleanUrl(text)
    Log.d(">>>:Home", "extractUrlFromClipboard $value")
    return value
}

// Moshi 构建成本高，全局复用同一个实例；KotlinJsonAdapterFactory 必须用
// addLast 挂在链尾，放链首会遮蔽内建与 codegen 适配器。
private val moshi: Moshi by lazy {
    Moshi.Builder().addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
}
private val apiResponseAdapter = moshi.adapter(DouyinApiResponse::class.java)

suspend fun fetchVideoInfo(url: String, appKey: String): DouyinApiResponse? =
    withContext(Dispatchers.IO) {
        try {
            val apiUrl =
                "https://api.spapi.cn/get?appkey=$appKey&url=${URLEncoder.encode(url, "UTF-8")}"
            val request = Request.Builder().url(apiUrl).build()
            // use 确保 Response 连接被归还连接池；body 可为空（如空 200 响应），
            // 网络与解析异常统一按「解析失败」返回 null
            sharedOkHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                Log.d(">>>:fetchVideoInfo", "body: $body")
                if (!response.isSuccessful || body == null) {
                    return@use null
                }
                apiResponseAdapter.fromJson(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(">>>:fetchVideoInfo", "fetchVideoInfo failed", e)
            null
        }
    }

