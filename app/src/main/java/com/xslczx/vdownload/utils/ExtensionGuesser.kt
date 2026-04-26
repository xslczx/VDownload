package com.xslczx.vdownload.utils

import java.io.File
import java.net.URL

import android.webkit.MimeTypeMap

/**
 * 推断后缀来源
 */
enum class ExtensionSource {
    CONTENT_DISPOSITION,
    URL_PATH,
    MIME_TYPE,
    FALLBACK
}

/**
 * 结果封装
 */
data class ExtensionResult(
    val extension: String, // 不带点，比如 "mp4"
    val source: ExtensionSource,
    val confidence: Double, // 0..1，越高越靠谱
    val originalCandidates: List<String> = emptyList(), // 用于调试/日志
    val conflictWithUrlExt: Boolean = false,
    val conflictWithMime: Boolean = false
)

/**
 * 更强的后缀推断器
 */
object ExtensionGuesser {

    // 你可以扩展这张表，用更全的 MIME -> ext 映射
    private val hardcodedMimeToExt = mapOf(
        "video/mp4" to "mp4",
        "video/webm" to "webm",
        "video/quicktime" to "mov",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/ogg" to "ogg",
        "audio/wav" to "wav",
        "audio/flac" to "flac",
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "application/octet-stream" to "bin"
    )

    /**
     * 主入口：推断最优扩展名（不带点）
     */
    fun guessBestExtension(
        urlString: String,
        contentType: String?,
        contentDisposition: String?
    ): ExtensionResult {
        val candidates = mutableListOf<ExtensionResult>()

        // 1. Content-Disposition
        contentDisposition?.let { cd ->
            val filenameRegex = "filename\\*?=([^;]+)".toRegex(RegexOption.IGNORE_CASE)
            val match = filenameRegex.find(cd) ?: run {
                // 兼容简单 filename=
                "filename=([^;]+)".toRegex(RegexOption.IGNORE_CASE).find(cd)
            }
            match?.groupValues?.getOrNull(1)?.trim()?.trim('"')?.let { rawName ->
                val ext = File(rawName).extension.lowercase()
                if (ext.isNotEmpty()) {
                    candidates += ExtensionResult(
                        extension = ext,
                        source = ExtensionSource.CONTENT_DISPOSITION,
                        confidence = 0.95,
                        originalCandidates = listOf(rawName)
                    )
                }
            }
        }

        // 2. URL path
        try {
            val path = URL(urlString).path
            val urlBase = File(path).name
            // Ensure we are extracting the extension correctly and that it's a valid extension
            val extFromUrl = File(urlBase).extension.lowercase()
            if (extFromUrl.isNotBlank() && extFromUrl.length <= 10) {
                candidates += ExtensionResult(
                    extension = extFromUrl,
                    source = ExtensionSource.URL_PATH,
                    confidence = 0.75,
                    originalCandidates = listOf(urlBase)
                )
            }
        } catch (_: Exception) {
            // Handle URL parsing error, you can log or debug here
        }

        // 3. Content-Type
        contentType?.let { ct ->
            val cleaned = ct.substringBefore(";").lowercase()
            val ext = hardcodedMimeToExt[cleaned] ?: run {
                // 试系统 mapper
                MimeTypeMap.getSingleton().getExtensionFromMimeType(cleaned)
            }
            if (!ext.isNullOrBlank()) {
                candidates += ExtensionResult(
                    extension = ext.lowercase(),
                    source = ExtensionSource.MIME_TYPE,
                    confidence = 0.85,
                    originalCandidates = listOf(cleaned)
                )
            }
        }

        // 4. 兜底
        if (candidates.isEmpty()) {
            candidates += ExtensionResult(
                extension = "bin",
                source = ExtensionSource.FALLBACK,
                confidence = 0.3,
                originalCandidates = emptyList()
            )
        }

        // 5. 选最高 confidence，如果多个可以按 source 优先级再细化
        val best = candidates.maxWithOrNull(compareBy<ExtensionResult> { it.confidence }
            .thenBy { priority(it.source) })!!

        // 6. 检查冲突：URL 后缀 vs MIME
        val urlExt = runCatching {
            URL(urlString).path.let { File(it).extension.lowercase() }
        }.getOrNull()?.takeIf { it.isNotBlank() }

        val conflictWithUrl = urlExt != null && urlExt != best.extension
        val mimeExt = contentType?.let {
            val cleaned = it.substringBefore(";").lowercase()
            hardcodedMimeToExt[cleaned] ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(cleaned)
        }?.lowercase()?.takeIf { it.isNotBlank() }

        val conflictWithMime = mimeExt != null && mimeExt != best.extension

        return best.copy(
            conflictWithUrlExt = conflictWithUrl,
            conflictWithMime = conflictWithMime,
            originalCandidates = candidates.flatMap { it.originalCandidates }.distinct()
        )
    }


    private fun priority(src: ExtensionSource): Int = when (src) {
        ExtensionSource.CONTENT_DISPOSITION -> 0
        ExtensionSource.MIME_TYPE -> 1
        ExtensionSource.URL_PATH -> 2
        ExtensionSource.FALLBACK -> 3
    }

    /**
     * 生成唯一文件名（避免重名）
     */
    fun uniqueFile(baseDir: File, baseName: String, ext: String): File {
        val safeBase = baseName.filter { it != File.separatorChar }
        var candidate = File(baseDir, "$safeBase.$ext")
        var idx = 1
        while (candidate.exists()) {
            candidate = File(baseDir, "$safeBase($idx).$ext")
            idx++
        }
        return candidate
    }

    /**
     * 判断是否应该用新的 extension 替换已有文件名后缀（例如 download.tmp.mp4，但 content-type 是 mp3）
     */
    fun needsReplacement(existing: File, guessed: ExtensionResult): Boolean {
        val currentExt = existing.extension.lowercase()
        return currentExt != guessed.extension
    }
}
