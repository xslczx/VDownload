package com.xslczx.vdownload.utils

import java.io.File

data class DownloadResult(
    val file: File? = null,
    val exception: Throwable? = null,
    var media : MediaCategory = MediaCategory.OTHER,
)
