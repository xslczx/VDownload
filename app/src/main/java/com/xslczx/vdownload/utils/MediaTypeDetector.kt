package com.xslczx.vdownload.utils

import java.io.File

object MediaTypeDetector {

    private data class Signature(
        val bytes: ByteArray,
        val offset: Int = 0,
        val category: MediaCategory,
        val subtype: String,
        val extraCheck: ((header: ByteArray) -> Boolean)? = null
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Signature

            if (offset != other.offset) return false
            if (!bytes.contentEquals(other.bytes)) return false
            if (category != other.category) return false
            if (subtype != other.subtype) return false
            if (extraCheck != other.extraCheck) return false

            return true
        }

        override fun hashCode(): Int {
            var result = offset
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + category.hashCode()
            result = 31 * result + subtype.hashCode()
            result = 31 * result + (extraCheck?.hashCode() ?: 0)
            return result
        }
    }

    // 辅助方法：用 Int 写十六进制，自动转 Byte
    private fun sig(vararg ints: Int) = ByteArray(ints.size) { i -> ints[i].toByte() }

    private val signatures = listOf(
        // 图片
        Signature(sig(0xFF, 0xD8, 0xFF), category = MediaCategory.IMAGE, subtype = "jpeg"), // JPEG
        Signature(sig(0x89, 0x50, 0x4E, 0x47), category = MediaCategory.IMAGE, subtype = "png"), // PNG
        Signature("GIF8".toByteArray(), category = MediaCategory.IMAGE, subtype = "gif"), // GIF
        Signature(sig(0x42, 0x4D), category = MediaCategory.IMAGE, subtype = "bmp"), // BMP
        Signature(sig(0x49, 0x49, 0x2A, 0x00), category = MediaCategory.IMAGE, subtype = "tiff"), // TIFF little
        Signature(sig(0x4D, 0x4D, 0x00, 0x2A), category = MediaCategory.IMAGE, subtype = "tiff"), // TIFF big
        Signature("RIFF".toByteArray(), category = MediaCategory.IMAGE, subtype = "webp", extraCheck = { h ->
            h.size >= 12 && h.copyOfRange(8, 12).contentEquals("WEBP".toByteArray())
        }),
        Signature(sig(0x00, 0x00, 0x00), category = MediaCategory.IMAGE, subtype = "heif", extraCheck = { h ->
            if (h.size >= 12) {
                val ftyp = String(h.copyOfRange(4, 8))
                if (ftyp != "ftyp") return@Signature false
                val major = String(h.copyOfRange(8, 12))
                listOf("heic", "heix", "hevc", "mif1", "avif", "avis").any { it == major }
            } else false
        }),

        // 音频
        Signature("fLaC".toByteArray(), category = MediaCategory.AUDIO, subtype = "flac"),
        Signature("ID3".toByteArray(), category = MediaCategory.AUDIO, subtype = "mp3"),
        Signature(sig(0xFF, 0xE0), category = MediaCategory.AUDIO, subtype = "mp3", extraCheck = { h ->
            h.size >= 2 && (h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xE0) == 0xE0
        }),
        Signature(sig(0xFF, 0xF1), category = MediaCategory.AUDIO, subtype = "aac"),
        Signature(sig(0xFF, 0xF9), category = MediaCategory.AUDIO, subtype = "aac"),
        Signature("RIFF".toByteArray(), category = MediaCategory.AUDIO, subtype = "wav", extraCheck = { h ->
            h.size >= 12 && h.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
        }),
        Signature("FORM".toByteArray(), category = MediaCategory.AUDIO, subtype = "aiff", extraCheck = { h ->
            h.size >= 12 && h.copyOfRange(8, 12).contentEquals("AIFF".toByteArray())
        }),
        Signature("OggS".toByteArray(), category = MediaCategory.OTHER, subtype = "ogg"), // 后面细分

        // 视频
        Signature(sig(0x00, 0x00, 0x00), category = MediaCategory.VIDEO, subtype = "mp4", extraCheck = { h ->
            if (h.size >= 12) {
                val box = String(h.copyOfRange(4, 8))
                if (box != "ftyp") return@Signature false
                val major = String(h.copyOfRange(8, 12))
                listOf("isom", "iso2", "mp41", "mp42", "avc1", "M4V ", "M4A ", "qt  ").any { it == major }
            } else false
        }),
        Signature(sig(0x1A, 0x45, 0xDF, 0xA3), category = MediaCategory.VIDEO, subtype = "mkv"),
        Signature("RIFF".toByteArray(), category = MediaCategory.VIDEO, subtype = "avi", extraCheck = { h ->
            h.size >= 12 && h.copyOfRange(8, 12).contentEquals("AVI ".toByteArray())
        })
    )

    private val mimeMap = mapOf(
        "video/mp4" to DetectedMedia(MediaCategory.VIDEO, "mp4"),
        "video/quicktime" to DetectedMedia(MediaCategory.VIDEO, "mov"),
        "video/webm" to DetectedMedia(MediaCategory.VIDEO, "webm"),
        "video/x-matroska" to DetectedMedia(MediaCategory.VIDEO, "mkv"),
        "audio/mpeg" to DetectedMedia(MediaCategory.AUDIO, "mp3"),
        "audio/mp4" to DetectedMedia(MediaCategory.AUDIO, "m4a"),
        "audio/ogg" to DetectedMedia(MediaCategory.AUDIO, "ogg"),
        "audio/flac" to DetectedMedia(MediaCategory.AUDIO, "flac"),
        "image/jpeg" to DetectedMedia(MediaCategory.IMAGE, "jpeg"),
        "image/png" to DetectedMedia(MediaCategory.IMAGE, "png"),
        "image/gif" to DetectedMedia(MediaCategory.IMAGE, "gif"),
        "image/webp" to DetectedMedia(MediaCategory.IMAGE, "webp")
    )

    fun detect(contentType: String?=null, file: File? = null): DetectedMedia {
        file?.takeIf { it.exists() && it.canRead() }?.let {
            val header = ByteArray(64)
            it.inputStream().use { stream -> stream.read(header) }

            // Ogg 细分
            if (header.startsWith("OggS".toByteArray())) {
                val inner = String(header, 0, header.size.coerceAtMost(64))
                return when {
                    inner.contains("OpusHead") -> DetectedMedia(MediaCategory.AUDIO, "opus")
                    inner.contains("vorbis", ignoreCase = true) -> DetectedMedia(MediaCategory.AUDIO, "vorbis")
                    inner.contains("Theora", ignoreCase = true) -> DetectedMedia(MediaCategory.VIDEO, "theora")
                    else -> DetectedMedia(MediaCategory.OTHER, "ogg")
                }
            }

            for (sig in signatures) {
                if (header.matchesAt(sig.bytes, sig.offset)) {
                    if (sig.extraCheck?.invoke(header) != false) {
                        return DetectedMedia(sig.category, sig.subtype)
                    }
                }
            }
        }

        contentType?.let {
            val base = contentType.substringBefore(";").lowercase()
            mimeMap[base]?.let { return it }
            when {
                base.startsWith("image/") -> return DetectedMedia(MediaCategory.IMAGE, base.substringAfter("/"))
                base.startsWith("video/") -> return DetectedMedia(MediaCategory.VIDEO, base.substringAfter("/"))
                base.startsWith("audio/") -> return DetectedMedia(MediaCategory.AUDIO, base.substringAfter("/"))
                else -> return DetectedMedia(MediaCategory.OTHER)
            }
        }

        file?.let {
            val ext = it.extension.lowercase()
            return when (ext) {
                "jpg", "jpeg" -> DetectedMedia(MediaCategory.IMAGE, "jpeg")
                "png" -> DetectedMedia(MediaCategory.IMAGE, "png")
                "gif" -> DetectedMedia(MediaCategory.IMAGE, "gif")
                "webp" -> DetectedMedia(MediaCategory.IMAGE, "webp")
                "mp4", "m4v" -> DetectedMedia(MediaCategory.VIDEO, "mp4")
                "mov" -> DetectedMedia(MediaCategory.VIDEO, "mov")
                "mkv" -> DetectedMedia(MediaCategory.VIDEO, "mkv")
                "avi" -> DetectedMedia(MediaCategory.VIDEO, "avi")
                "mp3" -> DetectedMedia(MediaCategory.AUDIO, "mp3")
                "flac" -> DetectedMedia(MediaCategory.AUDIO, "flac")
                "wav" -> DetectedMedia(MediaCategory.AUDIO, "wav")
                "aac" -> DetectedMedia(MediaCategory.AUDIO, "aac")
                "opus" -> DetectedMedia(MediaCategory.AUDIO, "opus")
                else -> DetectedMedia(MediaCategory.OTHER, ext)
            }
        }

        return DetectedMedia(MediaCategory.OTHER, null)
    }

    private fun ByteArray.matchesAt(prefix: ByteArray, offset: Int): Boolean {
        if (this.size < offset + prefix.size) return false
        for (i in prefix.indices) {
            if (this[offset + i] != prefix[i]) return false
        }
        return true
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = matchesAt(prefix, 0)
}
