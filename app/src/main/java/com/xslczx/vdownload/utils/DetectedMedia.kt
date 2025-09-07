package com.xslczx.vdownload.utils

data class DetectedMedia(
    val category: MediaCategory,
    val subtype: String? = null // e.g., "mp4", "jpeg", "opus"
)