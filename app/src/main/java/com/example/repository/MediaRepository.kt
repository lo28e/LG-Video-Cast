package com.example.repository

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.example.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MediaRepository(private val context: Context) {

    private val tag = "MediaRepository"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * Queries local phone videos from MediaStore.
     */
    suspend fun getLocalVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val videoList = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimeCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val widthCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val dateCol = it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val name = it.getString(nameCol) ?: "فيديو بدون عنوان"
                    val duration = it.getLong(durationCol)
                    val size = it.getLong(sizeCol)
                    val mime = it.getString(mimeCol) ?: "video/mp4"
                    val width = it.getInt(widthCol)
                    val height = it.getInt(heightCol)
                    val dateAdded = it.getLong(dateCol)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    videoList.add(
                        VideoItem(
                            id = id.toString(),
                            title = name,
                            uri = contentUri,
                            durationMs = duration,
                            sizeBytes = size,
                            mimeType = mime,
                            width = width,
                            height = height,
                            dateAdded = dateAdded,
                            isWeb = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Error querying MediaStore: ${e.message}")
        }

        videoList
    }

    /**
     * Resolves a video picked through SAF picker into a complete VideoItem.
     */
    suspend fun resolvePickedUri(uri: Uri): VideoItem = withContext(Dispatchers.IO) {
        var name = "فيديو مختار"
        var size = 0L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Error reading cursor for picked uri: ${e.message}")
        }

        var duration = 0L
        var width = 0
        var height = 0
        var mime = context.contentResolver.getType(uri) ?: "video/mp4"

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val mStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)

            durStr?.toLongOrNull()?.let { duration = it }
            wStr?.toIntOrNull()?.let { width = it }
            hStr?.toIntOrNull()?.let { height = it }
            mStr?.let { mime = it }
            retriever.release()
        } catch (e: Exception) {
            Log.w(tag, "Retriever metadata extraction: ${e.message}")
        }

        VideoItem(
            id = "picked-${System.currentTimeMillis()}",
            title = name,
            uri = uri,
            durationMs = duration,
            sizeBytes = size,
            mimeType = mime,
            width = width,
            height = height,
            dateAdded = System.currentTimeMillis() / 1000,
            isWeb = false
        )
    }

    /**
     * Probes a remote web video URL (checking content-type, size, range support).
     */
    suspend fun probeWebUrl(url: String): VideoItem = withContext(Dispatchers.IO) {
        var mime = "video/mp4"
        var size = 0L
        val cleanTitle = url.substringAfterLast("/").substringBefore("?").ifEmpty { "مقطع ويب" }

        try {
            val req = Request.Builder().url(url).head().build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                resp.header("Content-Type")?.let { mime = it.substringBefore(";") }
                resp.header("Content-Length")?.toLongOrNull()?.let { size = it }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to probe web URL headers: ${e.message}")
        }

        VideoItem(
            id = "web-${url.hashCode()}",
            title = cleanTitle,
            uri = Uri.parse(url),
            durationMs = 0L,
            sizeBytes = size,
            mimeType = mime,
            isWeb = true,
            webUrl = url
        )
    }

    /**
     * Built-in 4K / High Definition demo streams tested for LG OLED TVs.
     */
    fun getPresetDemoStreams(): List<VideoItem> {
        return listOf(
            VideoItem(
                id = "demo-4k-nature",
                title = "LG OLED C5 - عرض تجريبي 4K HDR الألوان العميقة",
                uri = Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"),
                durationMs = 596000L,
                sizeBytes = 158008374L,
                mimeType = "video/mp4",
                width = 3840,
                height = 2160,
                isWeb = true,
                webUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
            ),
            VideoItem(
                id = "demo-oled-contrast",
                title = "تجربة التباين الفائق واللون الأسود المطلق (OLED Contrast Demo)",
                uri = Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"),
                durationMs = 653000L,
                sizeBytes = 140000000L,
                mimeType = "video/mp4",
                width = 1920,
                height = 1080,
                isWeb = true,
                webUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4"
            ),
            VideoItem(
                id = "demo-action-60fps",
                title = "مقطع أكشن وسرعة حركة سلسة 60fps (Motion Pro)",
                uri = Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"),
                durationMs = 734000L,
                sizeBytes = 220000000L,
                mimeType = "video/mp4",
                width = 3840,
                height = 2160,
                isWeb = true,
                webUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
            )
        )
    }
}
