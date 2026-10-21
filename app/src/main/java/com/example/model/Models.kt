package com.example.model

import android.net.Uri

data class VideoItem(
    val id: String,
    val title: String,
    val uri: Uri,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String = "video/mp4",
    val width: Int = 0,
    val height: Int = 0,
    val dateAdded: Long = 0L,
    val isWeb: Boolean = false,
    val webUrl: String? = null
) {
    val formattedDuration: String
        get() {
            if (durationMs <= 0) return "--:--"
            val totalSeconds = durationMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format("%02d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return ""
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                else -> String.format("%.0f KB", kb)
            }
        }

    val resolutionLabel: String
        get() {
            return when {
                width >= 3840 || height >= 2160 -> "4K UHD"
                width >= 2560 || height >= 1440 -> "2K QHD"
                width >= 1920 || height >= 1080 -> "1080p FHD"
                width >= 1280 || height >= 720 -> "720p HD"
                width > 0 && height > 0 -> "${width}x${height}"
                else -> "فيديو"
            }
        }
}

enum class CastProtocol(val titleArabic: String, val subtitleArabic: String) {
    DLNA("DLNA AVTransport (الموصى به لـ LG)", "مشغل الوسائط الرسمي لشاشات LG webOS - استجابة ممتازة وجودة فائقة"),
    CHROMECAST("Google Cast (Chromecast)", "مدمج في شاشات LG C4/C5 الحديثة"),
    WEB_RECEIVER("مستعرض الويب (LG Web Receiver)", "مشغل 4K مباشر يعمل عبر متصفح الشاشة")
}

data class CastDevice(
    val id: String,
    val name: String,
    val ipAddress: String,
    val port: Int = 1422,
    val modelName: String = "LG OLED TV",
    val manufacturer: String = "LG Electronics",
    val locationXmlUrl: String? = null,
    val controlUrl: String = "",
    val renderingControlUrl: String = "",
    val isLgC5: Boolean = false,
    val isOnline: Boolean = true,
    val hasChromecast: Boolean = true,
    val hasDlna: Boolean = true,
    val preferredProtocol: CastProtocol = CastProtocol.DLNA
)

enum class PlayState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    ERROR
}

data class PlaybackStatus(
    val videoTitle: String = "",
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playState: PlayState = PlayState.IDLE,
    val volume: Int = 20,
    val isMuted: Boolean = false,
    val activeDevice: CastDevice? = null,
    val currentStreamUrl: String = "",
    val activeProtocol: CastProtocol = CastProtocol.DLNA,
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (durationMs > 0) {
            (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

    val formattedPosition: String
        get() = formatMs(currentPositionMs)

    val formattedDuration: String
        get() = formatMs(durationMs)

    private fun formatMs(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }
}
