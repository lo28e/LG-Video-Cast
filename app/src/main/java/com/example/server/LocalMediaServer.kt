package com.example.server

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.model.VideoItem
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class LocalMediaServer(private val context: Context) {

    private val tag = "LocalMediaServer"
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val threadPool = Executors.newCachedThreadPool()

    @Volatile
    private var currentVideo: VideoItem? = null
    private var assignedPort: Int = 8855

    // Active sockets registry for instant cleanup on video change or reset
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val activeStreams = Collections.newSetFromMap(ConcurrentHashMap<InputStream, Boolean>())

    // Remote sync state for TV Web Receiver
    @Volatile
    var isTvPlaying: Boolean = true
    val tvCurrentPositionMs = AtomicLong(0L)
    @Volatile
    var lastCommand: String = "PLAY"
    @Volatile
    var seekTargetMs: Long = -1L

    fun isServerRunning(): Boolean = isRunning.get()

    fun getPort(): Int = assignedPort

    /**
     * Sets the active video and aborts all previous active connections immediately
     * so that the LG TV decoder and server buffers are freed for the new stream.
     */
    fun setVideo(video: VideoItem) {
        abortActiveConnections()
        currentVideo = video
        isTvPlaying = true
        tvCurrentPositionMs.set(0L)
        lastCommand = "LOAD"
        Log.i(tag, "LocalMediaServer active video set: ${video.title} (aborted old streams)")
    }

    /**
     * Closes and disconnects all currently open client sockets.
     * Crucial when changing videos or seeking to avoid socket buffer contention.
     */
    fun abortActiveConnections() {
        val sockets = activeSockets.toList()
        if (sockets.isNotEmpty()) {
            Log.d(tag, "Closing ${sockets.size} active client sockets...")
        }
        for (s in sockets) {
            try { s.shutdownInput() } catch (_: Exception) {}
            try { s.shutdownOutput() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
        }
        activeSockets.clear()

        val streams = activeStreams.toList()
        for (st in streams) {
            try { st.close() } catch (_: Exception) {}
        }
        activeStreams.clear()
    }

    fun getCurrentVideo(): VideoItem? = currentVideo

    fun start(port: Int = 8855): Boolean {
        if (isRunning.get()) return true

        try {
            serverSocket = try {
                ServerSocket(port).apply {
                    reuseAddress = true
                    receiveBufferSize = 256 * 1024
                }
            } catch (e: Exception) {
                Log.w(tag, "Port $port in use, allocating dynamic port: ${e.message}")
                ServerSocket(0).apply {
                    reuseAddress = true
                    receiveBufferSize = 256 * 1024
                }
            }

            assignedPort = serverSocket?.localPort ?: port
            isRunning.set(true)
            Log.i(tag, "LocalMediaServer successfully started on port $assignedPort")

            threadPool.execute {
                while (isRunning.get()) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        client.tcpNoDelay = true
                        client.sendBufferSize = 256 * 1024
                        activeSockets.add(client)

                        threadPool.execute {
                            try {
                                handleClient(client)
                            } finally {
                                activeSockets.remove(client)
                                try { client.close() } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(tag, "Error accepting client: ${e.message}")
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start server: ${e.message}", e)
            isRunning.set(false)
            return false
        }
    }

    fun stop() {
        isRunning.set(false)
        abortActiveConnections()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(tag, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        Log.i(tag, "LocalMediaServer stopped")
    }

    fun getStreamUrl(targetTvIp: String? = null): String {
        val ip = NetworkUtils.getLocalIpAddress(context, targetTvIp)
        return "http://$ip:$assignedPort/video"
    }

    fun getWebReceiverUrl(targetTvIp: String? = null): String {
        val ip = NetworkUtils.getLocalIpAddress(context, targetTvIp)
        return "http://$ip:$assignedPort/receiver"
    }

    private fun handleClient(socket: Socket) {
        var input: BufferedReader? = null
        var output: OutputStream? = null

        try {
            socket.soTimeout = 45000
            input = BufferedReader(InputStreamReader(socket.getInputStream()))
            output = BufferedOutputStream(socket.getOutputStream(), 128 * 1024)

            val requestLine = input.readLine() ?: return
            Log.d(tag, "Client request: $requestLine")

            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.US)
            val rawPath = parts[1]
            val path = rawPath.substringBefore("?")

            var rangeHeader: String? = null
            var line: String? = input.readLine()

            while (!line.isNullOrEmpty()) {
                val lower = line.lowercase(Locale.US)
                if (lower.startsWith("range:")) {
                    rangeHeader = line.substring(6).trim()
                }
                line = input.readLine()
            }

            // OPTIONS preflight
            if (method == "OPTIONS") {
                sendOptionsResponse(output)
                return
            }

            // Web Receiver
            if (path == "/" || path == "/receiver" || path == "/tv") {
                serveWebReceiverPage(output)
                return
            }

            // Sync API
            if (path == "/api/sync") {
                serveSyncApi(output)
                return
            }

            val video = currentVideo
            if (video == null) {
                sendNotFound(output)
                return
            }

            serveMedia(video, method, rangeHeader, output)
        } catch (e: SocketException) {
            // Expected when TV seeks to new offset
            Log.d(tag, "Socket closed by TV: ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "Client processing exception: ${e.message}")
        } finally {
            try { output?.flush() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            try { input?.close() } catch (_: Exception) {}
        }
    }

    private fun sendOptionsResponse(output: OutputStream) {
        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Range, Content-Type, Accept, User-Agent, X-Playback-Session-Id\r\n")
            append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n")
            append("Access-Control-Max-Age: 86400\r\n")
            append("Content-Length: 0\r\n")
            append("\r\n")
        }
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun serveSyncApi(output: OutputStream) {
        val video = currentVideo
        val seek = seekTargetMs
        if (seek >= 0) {
            seekTargetMs = -1L
        }

        val json = """
            {
               "hasVideo": ${video != null},
               "title": "${escapeJson(video?.title ?: "")}",
               "videoUrl": "/video",
               "isPlaying": $isTvPlaying,
               "seekTargetMs": $seek,
               "command": "$lastCommand"
            }
        """.trimIndent()

        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Content-Length: ${json.toByteArray(Charsets.UTF_8).size}\r\n")
            append("\r\n")
            append(json)
        }
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun serveWebReceiverPage(output: OutputStream) {
        val video = currentVideo
        val title = video?.title ?: "مشغل LG C5 Video Cast"

        val html = """
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
  <meta charset="utf-8">
  <title>$title - LG C5 OLED Player</title>
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      background-color: #000000;
      color: #F8FAFC;
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      overflow: hidden;
      width: 100vw;
      height: 100vh;
      display: flex;
      flex-direction: column;
      justify-content: center;
      align-items: center;
    }
    #videoContainer {
      position: relative;
      width: 100vw;
      height: 100vh;
      background: #000;
    }
    video {
      width: 100%;
      height: 100%;
      object-fit: contain;
    }
    #overlay {
      position: absolute;
      bottom: 40px;
      left: 40px;
      right: 40px;
      padding: 20px 30px;
      background: rgba(11, 14, 20, 0.88);
      border-radius: 16px;
      border: 1px solid #00E5FF;
      display: flex;
      justify-content: space-between;
      align-items: center;
      transition: opacity 0.5s;
      z-index: 10;
      opacity: 0;
    }
    #overlay.visible { opacity: 1; }
    .badge {
      background: #00E5FF;
      color: #00363D;
      padding: 4px 12px;
      border-radius: 6px;
      font-weight: bold;
      font-size: 14px;
    }
    .title {
      font-size: 24px;
      font-weight: bold;
      color: #fff;
      margin-right: 15px;
    }
  </style>
</head>
<body>
  <div id="videoContainer">
    <video id="player" controls autoplay playsinline preload="auto"></video>
    <div id="overlay">
      <div style="display: flex; align-items: center;">
        <span class="badge">LG C5 OLED 4K</span>
        <span class="title" id="videoTitle">$title</span>
      </div>
      <div id="statusText" style="color: #10B981; font-weight: bold; font-size: 16px;">جاهز للبث السريع</div>
    </div>
  </div>

  <script>
    const player = document.getElementById('player');
    const overlay = document.getElementById('overlay');
    const titleEl = document.getElementById('videoTitle');
    const statusText = document.getElementById('statusText');
    let hideTimer = null;
    let lastSrc = '';

    function showOverlay() {
      overlay.classList.add('visible');
      clearTimeout(hideTimer);
      hideTimer = setTimeout(() => overlay.classList.remove('visible'), 5000);
    }

    document.addEventListener('mousemove', showOverlay);
    document.addEventListener('keydown', showOverlay);

    player.addEventListener('play', () => { statusText.innerText = 'تشغيل بدقة فائقة'; showOverlay(); });
    player.addEventListener('pause', () => { statusText.innerText = 'متوقف مؤقتاً'; showOverlay(); });
    player.addEventListener('waiting', () => { statusText.innerText = 'جارٍ التخزين المؤقت...'; showOverlay(); });

    setInterval(async () => {
      try {
        const res = await fetch('/api/sync');
        if (!res.ok) return;
        const data = await res.json();
        if (data.hasVideo) {
          titleEl.innerText = data.title;
          if (lastSrc !== data.videoUrl) {
            lastSrc = data.videoUrl;
            player.src = data.videoUrl;
            player.play().catch(e => console.log('Autoplay prevented', e));
            showOverlay();
          }
          if (data.seekTargetMs >= 0) {
            player.currentTime = data.seekTargetMs / 1000;
          }
          if (data.isPlaying && player.paused) {
            player.play().catch(() => {});
          } else if (!data.isPlaying && !player.paused) {
            player.pause();
          }
        }
      } catch (err) {
        console.error(err);
      }
    }, 1000);
  </script>
</body>
</html>
        """.trimIndent()

        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n")
            append("\r\n")
            append(html)
        }
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun serveMedia(
        video: VideoItem,
        method: String,
        rangeHeader: String?,
        output: OutputStream
    ) {
        val uri = video.uri
        val totalLength = getFileSize(uri, video.sizeBytes)
        val mimeType = resolveMimeType(video)

        // DLNA features header for LG webOS
        val dlnaFeatures = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeValue = rangeHeader.removePrefix("bytes=").trim()

            val start: Long
            val end: Long

            if (rangeValue.startsWith("-")) {
                val suffix = rangeValue.removePrefix("-").toLongOrNull() ?: 0L
                start = (totalLength - suffix).coerceAtLeast(0L)
                end = (totalLength - 1).coerceAtLeast(start)
            } else {
                val parts = rangeValue.split("-")
                start = parts[0].toLongOrNull() ?: 0L
                end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].toLongOrNull() ?: (totalLength - 1)
                } else {
                    totalLength - 1
                }
            }

            val validStart = start.coerceIn(0L, (totalLength - 1).coerceAtLeast(0L))
            val validEnd = end.coerceIn(validStart, (totalLength - 1).coerceAtLeast(validStart))
            val contentLength = if (totalLength > 0) (validEnd - validStart + 1) else 0L

            val header = buildString {
                append("HTTP/1.1 206 Partial Content\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Range: bytes $validStart-$validEnd/$totalLength\r\n")
                append("Content-Length: $contentLength\r\n")
                append("Connection: keep-alive\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: $dlnaFeatures\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Range, Content-Type, Accept, User-Agent, X-Playback-Session-Id\r\n")
                append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n")
                append("\r\n")
            }

            output.write(header.toByteArray(Charsets.UTF_8))
            output.flush()

            if (method == "GET" && contentLength > 0) {
                streamByteRangeOptimized(uri, validStart, contentLength, output)
            }
        } else {
            // Full content
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: $mimeType\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $totalLength\r\n")
                append("Connection: keep-alive\r\n")
                append("transferMode.dlna.org: Streaming\r\n")
                append("contentFeatures.dlna.org: $dlnaFeatures\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Range, Content-Type, Accept, User-Agent, X-Playback-Session-Id\r\n")
                append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type\r\n")
                append("\r\n")
            }

            output.write(header.toByteArray(Charsets.UTF_8))
            output.flush()

            if (method == "GET" && totalLength > 0) {
                streamByteRangeOptimized(uri, 0L, totalLength, output)
            }
        }
    }

    /**
     * High-speed streaming using FileChannel position O(1) seek when ParcelFileDescriptor is available,
     * with fallback to InputStream.skip.
     */
    private fun streamByteRangeOptimized(
        uri: Uri,
        startOffset: Long,
        bytesToRead: Long,
        output: OutputStream
    ) {
        var pfd: ParcelFileDescriptor? = null
        var fileInputStream: FileInputStream? = null
        var genericStream: InputStream? = null

        try {
            // Method 1: Try ParcelFileDescriptor for instant O(1) seek
            pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (_: Exception) {
                null
            }

            if (pfd != null) {
                fileInputStream = FileInputStream(pfd.fileDescriptor)
                activeStreams.add(fileInputStream)
                val channel: FileChannel = fileInputStream.channel
                channel.position(startOffset)

                val buffer = ByteBuffer.allocateDirect(128 * 1024)
                val tempArray = ByteArray(128 * 1024)
                var bytesRemaining = bytesToRead

                while (bytesRemaining > 0 && isRunning.get()) {
                    buffer.clear()
                    val toRead = buffer.capacity().toLong().coerceAtMost(bytesRemaining).toInt()
                    buffer.limit(toRead)

                    val bytesRead = channel.read(buffer)
                    if (bytesRead <= 0) break

                    buffer.flip()
                    buffer.get(tempArray, 0, bytesRead)
                    output.write(tempArray, 0, bytesRead)
                    bytesRemaining -= bytesRead
                }
                output.flush()
                return
            }

            // Method 2: Generic InputStream fallback with fast skip
            genericStream = context.contentResolver.openInputStream(uri) ?: return
            activeStreams.add(genericStream)

            if (startOffset > 0) {
                var remainingToSkip = startOffset
                while (remainingToSkip > 0 && isRunning.get()) {
                    val skipped = genericStream.skip(remainingToSkip)
                    if (skipped <= 0) {
                        if (genericStream.read() == -1) break
                        remainingToSkip--
                    } else {
                        remainingToSkip -= skipped
                    }
                }
            }

            val buffer = ByteArray(128 * 1024)
            var bytesRemaining = bytesToRead

            while (bytesRemaining > 0 && isRunning.get()) {
                val toRead = buffer.size.toLong().coerceAtMost(bytesRemaining).toInt()
                val bytesRead = genericStream.read(buffer, 0, toRead)
                if (bytesRead == -1) break

                output.write(buffer, 0, bytesRead)
                bytesRemaining -= bytesRead
            }
            output.flush()
        } catch (e: SocketException) {
            // Expected when TV seeks
            Log.d(tag, "Client socket closed during streaming (seek): ${e.message}")
        } catch (e: Exception) {
            Log.w(tag, "Streaming error: ${e.message}")
        } finally {
            if (fileInputStream != null) {
                activeStreams.remove(fileInputStream)
                try { fileInputStream.close() } catch (_: Exception) {}
            }
            if (genericStream != null) {
                activeStreams.remove(genericStream)
                try { genericStream.close() } catch (_: Exception) {}
            }
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    fun resolveMimeType(video: VideoItem): String {
        val explicitMime = video.mimeType.trim()
        if (explicitMime.isNotBlank() && explicitMime != "application/octet-stream" && explicitMime != "video/*") {
            return explicitMime
        }

        val name = (video.title + " " + video.uri.toString()).lowercase(Locale.US)
        return when {
            name.contains(".mp4") || name.contains(".m4v") -> "video/mp4"
            name.contains(".mkv") -> "video/x-matroska"
            name.contains(".webm") -> "video/webm"
            name.contains(".mov") || name.contains(".qt") -> "video/quicktime"
            name.contains(".avi") -> "video/x-msvideo"
            name.contains(".ts") || name.contains(".m2ts") -> "video/mp2t"
            name.contains(".flv") -> "video/x-flv"
            name.contains(".3gp") -> "video/3gpp"
            name.contains(".wmv") -> "video/x-ms-wmv"
            name.contains(".m3u8") -> "application/x-mpegURL"
            name.contains(".mpd") -> "application/dash+xml"
            name.contains(".mp3") -> "audio/mpeg"
            name.contains(".aac") -> "audio/aac"
            name.contains(".m4a") -> "audio/mp4"
            name.contains(".wav") -> "audio/wav"
            name.contains(".flac") -> "audio/flac"
            name.contains(".ogg") || name.contains(".opus") -> "audio/ogg"
            name.contains(".ac3") -> "audio/ac3"
            name.contains(".eac3") -> "audio/eac3"
            else -> "video/mp4"
        }
    }

    private fun getFileSize(uri: Uri, fallbackSize: Long): Long {
        if (fallbackSize > 0) return fallbackSize
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            val length = pfd?.statSize ?: fallbackSize
            pfd?.close()
            if (length > 0) return length
        } catch (_: Exception) {}

        try {
            val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            val length = afd?.length ?: fallbackSize
            afd?.close()
            if (length > 0) return length
        } catch (_: Exception) {}

        return fallbackSize
    }

    private fun sendNotFound(output: OutputStream) {
        val resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        output.write(resp.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
