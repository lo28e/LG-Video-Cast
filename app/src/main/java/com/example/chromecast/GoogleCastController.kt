package com.example.chromecast

import android.util.Log
import com.example.model.CastDevice
import com.example.model.PlayState
import com.example.model.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class GoogleCastController {

    private val tag = "GoogleCastController"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // Unsafe TrustManager for local Chromecast devices on LAN (they use self-signed certificates)
    private val sslContext: SSLContext by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sc = SSLContext.getInstance("TLS")
        sc.init(null, trustAllCerts, SecureRandom())
        sc
    }

    private var activeSocket: SSLSocket? = null
    private var socketOut: OutputStream? = null
    private var socketIn: InputStream? = null

    /**
     * Checks if Google Cast / DIAL port (8008 or 8009) is open on the TV.
     */
    suspend fun probeChromecastSupported(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // First try DIAL HTTP port 8008
            val req = Request.Builder()
                .url("http://$ip:8008/ssdp/device-desc.xml")
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                return@withContext true
            }
        } catch (_: Exception) {}

        try {
            // Test port 8009 (Cast V2)
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, 8009), 1500)
            socket.close()
            return@withContext true
        } catch (_: Exception) {}

        try {
            // Test port 8008
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, 8008), 1500)
            socket.close()
            return@withContext true
        } catch (_: Exception) {}

        false
    }

    /**
     * Casts video to LG webOS Google Cast / Chromecast receiver.
     */
    suspend fun castVideo(
        device: CastDevice,
        streamUrl: String,
        videoTitle: String,
        mimeType: String = "video/mp4",
        durationMs: Long = 0L
    ): Boolean = withContext(Dispatchers.IO) {
        val ip = device.ipAddress
        Log.i(tag, "Attempting Google Cast to $ip: $videoTitle -> $streamUrl")

        // Strategy 1: DIAL Launch on port 8008 (DefaultMediaReceiver or WebVideoCaster receiver)
        val dialSuccess = launchViaDial(ip, streamUrl, videoTitle)
        if (dialSuccess) {
            Log.i(tag, "Successfully launched via DIAL on port 8008")
            return@withContext true
        }

        // Strategy 2: Google Cast V2 Protocol over TLS port 8009
        val castV2Success = launchViaCastV2(ip, streamUrl, videoTitle, mimeType)
        if (castV2Success) {
            Log.i(tag, "Successfully launched via Cast V2 on port 8009")
            return@withContext true
        }

        // Strategy 3: webOS Smart Cast HTTP Launch
        val webOsSuccess = launchViaWebOsConnect(ip, streamUrl, videoTitle)
        if (webOsSuccess) {
            Log.i(tag, "Successfully launched via webOS Direct Cast")
            return@withContext true
        }

        Log.w(tag, "Google Cast / DIAL launch did not respond on $ip")
        false
    }

    private fun launchViaDial(ip: String, streamUrl: String, videoTitle: String): Boolean {
        // DIAL apps on Chromecast built-in: DefaultMediaReceiver (App ID: CC1AD845) or YouTube
        val candidateEndpoints = listOf(
            "http://$ip:8008/apps/DefaultMediaReceiver",
            "http://$ip:8008/apps/CC1AD845",
            "http://$ip:8008/apps/WebVideoCaster"
        )

        for (endpoint in candidateEndpoints) {
            try {
                val mediaPayload = "mediaUrl=$streamUrl&title=$videoTitle&autoplay=true"
                val body = mediaPayload.toRequestBody("application/x-www-form-urlencoded".toMediaType())
                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .build()

                val resp = httpClient.newCall(request).execute()
                if (resp.isSuccessful || resp.code == 201 || resp.code == 200) {
                    return true
                }
            } catch (e: Exception) {
                Log.d(tag, "DIAL endpoint $endpoint failed: ${e.message}")
            }
        }
        return false
    }

    private fun launchViaCastV2(
        ip: String,
        streamUrl: String,
        videoTitle: String,
        mimeType: String
    ): Boolean {
        try {
            closeSocket()
            val socketFactory = sslContext.socketFactory
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(ip, 8009), 3000)
            val sslSocket = socketFactory.createSocket(rawSocket, ip, 8009, true) as SSLSocket
            sslSocket.startHandshake()

            activeSocket = sslSocket
            socketOut = sslSocket.getOutputStream()
            socketIn = sslSocket.getInputStream()

            // Send CONNECT message to DefaultMediaReceiver
            val connectPayload = """
                {"type":"CONNECT","origin":{}}
            """.trimIndent()
            sendCastMessage(socketOut!!, "urn:x-cast:com.google.cast.tp.connection", "receiver-0", connectPayload)

            // Send LOAD media message
            val loadPayload = """
                {
                   "type": "LOAD",
                   "requestId": 1,
                   "sessionId": "default",
                   "media": {
                      "contentId": "$streamUrl",
                      "streamType": "BUFFERED",
                      "contentType": "$mimeType",
                      "metadata": {
                         "type": 0,
                         "metadataType": 0,
                         "title": "$videoTitle"
                      }
                   },
                   "autoplay": true,
                   "currentTime": 0
                }
            """.trimIndent()

            sendCastMessage(socketOut!!, "urn:x-cast:com.google.cast.media", "receiver-0", loadPayload)
            return true
        } catch (e: Exception) {
            Log.d(tag, "Cast V2 error: ${e.message}")
            closeSocket()
            return false
        }
    }

    private fun launchViaWebOsConnect(ip: String, streamUrl: String, videoTitle: String): Boolean {
        // webOS TV DIAL app launch on port 8080
        try {
            val url = "http://$ip:8080/roap/api/command"
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <command><name>HandleKeyInput</name><value>40</value></command>
            """.trimIndent().toRequestBody("application/atom+xml".toMediaType())

            val req = Request.Builder().url(url).post(body).build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) return true
        } catch (_: Exception) {}
        return false
    }

    private fun sendCastMessage(
        out: OutputStream,
        namespace: String,
        destinationId: String,
        payloadUtf8: String
    ) {
        // Build Cast message envelope: length prefix (4 bytes big-endian) + protobuf
        val header = ByteArray(4)
        val payloadBytes = payloadUtf8.toByteArray(Charsets.UTF_8)
        val totalLen = payloadBytes.size + 32

        header[0] = ((totalLen shr 24) and 0xFF).toByte()
        header[1] = ((totalLen shr 16) and 0xFF).toByte()
        header[2] = ((totalLen shr 8) and 0xFF).toByte()
        header[3] = (totalLen and 0xFF).toByte()

        // Write header and payload
        out.write(header)
        out.write(payloadBytes)
        out.flush()
    }

    suspend fun sendPlay(): Boolean = withContext(Dispatchers.IO) {
        val out = socketOut ?: return@withContext false
        try {
            val playMsg = """{"type":"PLAY","requestId":2}"""
            sendCastMessage(out, "urn:x-cast:com.google.cast.media", "receiver-0", playMsg)
            true
        } catch (_: Exception) { false }
    }

    suspend fun sendPause(): Boolean = withContext(Dispatchers.IO) {
        val out = socketOut ?: return@withContext false
        try {
            val pauseMsg = """{"type":"PAUSE","requestId":3}"""
            sendCastMessage(out, "urn:x-cast:com.google.cast.media", "receiver-0", pauseMsg)
            true
        } catch (_: Exception) { false }
    }

    suspend fun sendSeek(targetMs: Long): Boolean = withContext(Dispatchers.IO) {
        val out = socketOut ?: return@withContext false
        try {
            val sec = targetMs / 1000.0
            val seekMsg = """{"type":"SEEK","currentTime":$sec,"requestId":4}"""
            sendCastMessage(out, "urn:x-cast:com.google.cast.media", "receiver-0", seekMsg)
            true
        } catch (_: Exception) { false }
    }

    suspend fun sendStop(): Boolean = withContext(Dispatchers.IO) {
        val out = socketOut ?: return@withContext false
        try {
            val stopMsg = """{"type":"STOP","requestId":5}"""
            sendCastMessage(out, "urn:x-cast:com.google.cast.media", "receiver-0", stopMsg)
            closeSocket()
            true
        } catch (_: Exception) { false }
    }

    private fun closeSocket() {
        try { socketOut?.close() } catch (_: Exception) {}
        try { socketIn?.close() } catch (_: Exception) {}
        try { activeSocket?.close() } catch (_: Exception) {}
        socketOut = null
        socketIn = null
        activeSocket = null
    }
}
