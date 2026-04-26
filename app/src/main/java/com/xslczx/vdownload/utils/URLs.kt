package com.xslczx.vdownload.utils

import android.util.Log
import com.squareup.moshi.Moshi
import com.xslczx.vdownload.databse.DouyinApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

suspend fun fetchVideoInfo(url: String, appKey: String): DouyinApiResponse? =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val apiUrl =
            "https://api.spapi.cn/get?appkey=$appKey&url=${URLEncoder.encode(url, "UTF-8")}"
        val request = Request.Builder().url(apiUrl).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string()
        Log.d(">>>:fetchVideoInfo", "body: $body")
        if (response.isSuccessful) {
            val fromJson =
                Moshi.Builder().add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                    .build()
                    .adapter(DouyinApiResponse::class.java)
                    .fromJson(body!!)
            return@withContext fromJson
        }
        null
    }

