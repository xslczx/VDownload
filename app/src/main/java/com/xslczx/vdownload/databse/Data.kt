package com.xslczx.vdownload.databse

data class DouyinApiResponse(
    val status: Int,
    val msg: String,
    val data: DouyinVideoData?
)

data class DouyinVideoData(
    val title: String? = null,
    val image: String? = null,
    val atlas: List<String>? = null,       // 多图
    val video: String? = null,              // 视频
    val url: String? = null
)
