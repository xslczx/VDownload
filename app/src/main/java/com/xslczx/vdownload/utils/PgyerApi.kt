package com.xslczx.vdownload.utils

import org.json.JSONObject

/**
 * 蒲公英应用分发平台版本检测（apiv2）。
 * app/check：_api_key（账号 API Key）+ appKey（应用 App Key）→ 线上最新版本。
 * 注意：downloadURL 含签名参数，属敏感链接，对外只展示安装单页 appURl。
 */
object PgyerApi {

    private const val CHECK_URL = "https://www.pgyer.com/apiv2/app/check"
    private const val API_KEY = "be2706ce59798d0b4ed57bd19b12cde5"
    private const val APP_KEY = "0b61c13b3978118c9c1ddbabbc9ddf1e"

    data class LatestRelease(
        val versionName: String, // buildVersion，如 2.4.0
        val versionCode: Int, // buildVersionNo
        val description: String, // buildUpdateDescription
        val pageUrl: String, // appURl 安装单页
        val downloadUrl: String, // downloadURL 直链（含签名参数，仅用于应用内下载，不对外展示）
    )

    /** 查询线上最新发布版本；code != 0 抛出异常，由调用方决定如何提示 */
    suspend fun latest(): LatestRelease {
        val text = Http.postForm(
            CHECK_URL,
            mapOf("_api_key" to API_KEY, "appKey" to APP_KEY),
        )
        val root = JSONObject(text)
        val code = root.optInt("code", -1)
        if (code != 0) throw RuntimeException(root.optString("message", "蒲公英接口错误 code=$code"))
        val data = root.optJSONObject("data") ?: throw RuntimeException("蒲公英响应缺少 data")
        return LatestRelease(
            versionName = data.optString("buildVersion"),
            versionCode = data.optString("buildVersionNo").toIntOrNull() ?: 0,
            description = data.optString("buildUpdateDescription"),
            pageUrl = data.optString("appURl"),
            downloadUrl = data.optString("downloadURL"),
        )
    }
}
