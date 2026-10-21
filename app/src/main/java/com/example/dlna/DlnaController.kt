package com.example.dlna

import android.util.Log
import com.example.model.CastDevice
import com.example.model.PlayState
import com.example.model.PlaybackStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.TimeUnit

class DlnaController {

    private val tag = "DlnaController"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val _playbackStatus = MutableStateFlow(PlaybackStatus())
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    /**
     * Casts video to LG webOS DLNA renderer with explicit Session Reset Sequence:
     * 1. Stop old session to release the TV's media decoder.
     * 2. Short pause (400ms) for pipeline settlement.
     * 3. SetAVTransportURI for the new stream.
     * 4. Short pause (400ms) for pipeline transition.
     * 5. Send Play (with automatic retries).
     */
    suspend fun castVideo(
        device: CastDevice,
        streamUrl: String,
        videoTitle: String,
        mimeType: String = "video/mp4",
        durationMs: Long = 0L
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            _playbackStatus.value = _playbackStatus.value.copy(
                activeDevice = device,
                videoTitle = videoTitle,
                currentStreamUrl = streamUrl,
                playState = PlayState.BUFFERING,
                durationMs = durationMs,
                currentPositionMs = 0L,
                errorMessage = null
            )

            // Step 1: Session Reset Sequence (إلغاء الجلسة السابقة وتحرير المشغل)
            Log.i(tag, "Session Reset: Stopping previous playback on ${device.ipAddress}...")
            try {
                val stopSoapBody = "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                        "<InstanceID>0</InstanceID>" +
                        "</u:Stop>"
                sendSoapAction(device.controlUrl, "AVTransport:1", "Stop", stopSoapBody)
            } catch (_: Exception) {}

            // Step 2: Delay 400ms to let webOS clear decoder pipeline
            delay(400)

            // Step 3: SetAVTransportURI
            Log.i(tag, "Sending SetAVTransportURI: $streamUrl")
            val setUriSuccess = setAvTransportUriWithFallback(device, streamUrl, videoTitle, mimeType)
            if (!setUriSuccess) {
                Log.w(tag, "SetAVTransportURI failed on ${device.ipAddress}")
                _playbackStatus.value = _playbackStatus.value.copy(
                    playState = PlayState.ERROR,
                    errorMessage = "تعذر تعيين رابط الفيديو على شاشة LG C5."
                )
                return@withContext false
            }

            // Step 4: Delay 400ms to allow LG TV to transition from TRANSITIONING to READY
            delay(400)

            // Step 5: Send Play with up to 3 retries
            var playSuccess = false
            for (attempt in 1..3) {
                playSuccess = play(device)
                if (playSuccess) break
                delay(350)
            }

            if (playSuccess) {
                _playbackStatus.value = _playbackStatus.value.copy(playState = PlayState.PLAYING)
                return@withContext true
            } else {
                _playbackStatus.value = _playbackStatus.value.copy(
                    playState = PlayState.ERROR,
                    errorMessage = "تم إرسال الفيديو لكن شاشة LG C5 لم تبدأ التشغيل تلقائياً."
                )
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(tag, "DLNA Cast error: ${e.message}", e)
            _playbackStatus.value = _playbackStatus.value.copy(
                playState = PlayState.ERROR,
                errorMessage = "خطأ أثناء البث: ${e.message}"
            )
            return@withContext false
        }
    }

    private suspend fun setAvTransportUriWithFallback(
        device: CastDevice,
        streamUrl: String,
        title: String,
        mimeType: String
    ): Boolean {
        val escapedTitle = escapeXml(title)
        val dlnaFlags = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val protocolInfo = "http-get:*:$mimeType:$dlnaFlags"

        // Attempt 1: Full DIDL-Lite metadata tailored for LG webOS
        val didlMetadata = "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;" +
                "&lt;item id=\"1\" parentID=\"0\" restricted=\"1\"&gt;" +
                "&lt;dc:title&gt;$escapedTitle&lt;/dc:title&gt;" +
                "&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;" +
                "&lt;res protocolInfo=\"$protocolInfo\"&gt;$streamUrl&lt;/res&gt;" +
                "&lt;/item&gt;" +
                "&lt;/DIDL-Lite&gt;"

        val soapBody1 = "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<CurrentURI>$streamUrl</CurrentURI>" +
                "<CurrentURIMetaData>$didlMetadata</CurrentURIMetaData>" +
                "</u:SetAVTransportURI>"

        val ok1 = sendSoapAction(device.controlUrl, "AVTransport:1", "SetAVTransportURI", soapBody1)
        if (ok1) return true

        Log.w(tag, "Attempt 1 failed. Trying Attempt 2 with empty metadata for LG TV compatibility...")
        delay(150)

        // Attempt 2: Empty metadata (supported universally by all LG webOS versions)
        val soapBody2 = "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<CurrentURI>$streamUrl</CurrentURI>" +
                "<CurrentURIMetaData></CurrentURIMetaData>" +
                "</u:SetAVTransportURI>"

        val ok2 = sendSoapAction(device.controlUrl, "AVTransport:1", "SetAVTransportURI", soapBody2)
        if (ok2) return true

        // Attempt 3: Simple DIDL without flags
        val simpleDidl = "&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
                "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;" +
                "&lt;item id=\"0\" parentID=\"-1\" restricted=\"1\"&gt;" +
                "&lt;dc:title&gt;$escapedTitle&lt;/dc:title&gt;" +
                "&lt;res protocolInfo=\"http-get:*:$mimeType:*\"&gt;$streamUrl&lt;/res&gt;" +
                "&lt;/item&gt;" +
                "&lt;/DIDL-Lite&gt;"

        val soapBody3 = "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<CurrentURI>$streamUrl</CurrentURI>" +
                "<CurrentURIMetaData>$simpleDidl</CurrentURIMetaData>" +
                "</u:SetAVTransportURI>"

        return sendSoapAction(device.controlUrl, "AVTransport:1", "SetAVTransportURI", soapBody3)
    }

    suspend fun play(device: CastDevice? = _playbackStatus.value.activeDevice): Boolean = withContext(Dispatchers.IO) {
        val targetDevice = device ?: return@withContext false
        val soapBody = "<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<Speed>1</Speed>" +
                "</u:Play>"

        val success = sendSoapAction(targetDevice.controlUrl, "AVTransport:1", "Play", soapBody)
        if (success) {
            _playbackStatus.value = _playbackStatus.value.copy(playState = PlayState.PLAYING)
        }
        success
    }

    suspend fun pause(device: CastDevice? = _playbackStatus.value.activeDevice): Boolean = withContext(Dispatchers.IO) {
        val targetDevice = device ?: return@withContext false
        val soapBody = "<u:Pause xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "</u:Pause>"

        val success = sendSoapAction(targetDevice.controlUrl, "AVTransport:1", "Pause", soapBody)
        if (success) {
            _playbackStatus.value = _playbackStatus.value.copy(playState = PlayState.PAUSED)
        }
        success
    }

    suspend fun stop(device: CastDevice? = _playbackStatus.value.activeDevice): Boolean = withContext(Dispatchers.IO) {
        val targetDevice = device ?: return@withContext false
        val soapBody = "<u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "</u:Stop>"

        val success = sendSoapAction(targetDevice.controlUrl, "AVTransport:1", "Stop", soapBody)
        if (success) {
            _playbackStatus.value = _playbackStatus.value.copy(
                playState = PlayState.STOPPED,
                currentPositionMs = 0L
            )
        }
        success
    }

    /**
     * High-precision seeking using the standard REL_TIME format (e.g. 01:23:45)
     * strictly formatted with US ASCII digits to guarantee webOS parser compatibility.
     */
    suspend fun seek(targetMs: Long, device: CastDevice? = _playbackStatus.value.activeDevice): Boolean = withContext(Dispatchers.IO) {
        val targetDevice = device ?: return@withContext false
        val timeString = formatToTimeString(targetMs)
        Log.i(tag, "Executing Seek REL_TIME: $timeString ($targetMs ms) on ${targetDevice.ipAddress}")

        val soapBody = "<u:Seek xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<Unit>REL_TIME</Unit>" +
                "<Target>$timeString</Target>" +
                "</u:Seek>"

        val success = sendSoapAction(targetDevice.controlUrl, "AVTransport:1", "Seek", soapBody)
        if (success) {
            _playbackStatus.value = _playbackStatus.value.copy(currentPositionMs = targetMs)
        }
        success
    }

    suspend fun setVolume(volume: Int, device: CastDevice? = _playbackStatus.value.activeDevice): Boolean = withContext(Dispatchers.IO) {
        val targetDevice = device ?: return@withContext false
        val clamped = volume.coerceIn(0, 100)
        val soapBody = "<u:SetVolume xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<Channel>Master</Channel>" +
                "<DesiredVolume>$clamped</DesiredVolume>" +
                "</u:SetVolume>"

        val success = sendSoapAction(targetDevice.renderingControlUrl, "RenderingControl:1", "SetVolume", soapBody)
        if (success) {
            _playbackStatus.value = _playbackStatus.value.copy(volume = clamped)
        }
        success
    }

    suspend fun setMute(mute: Boolean, device: CastDevice? = _playbackStatus.value.activeDevice): Boolean = withContext(Dispatchers.IO) {
        val targetDevice = device ?: return@withContext false
        val muteVal = if (mute) "1" else "0"
        val soapBody = "<u:SetMute xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">" +
                "<InstanceID>0</InstanceID>" +
                "<Channel>Master</Channel>" +
                "<DesiredMute>$muteVal</DesiredMute>" +
                "</u:SetMute>"

        val success = sendSoapAction(targetDevice.renderingControlUrl, "RenderingControl:1", "SetMute", soapBody)
        if (success) {
            _playbackStatus.value = _playbackStatus.value.copy(isMuted = mute)
        }
        success
    }

    suspend fun updatePositionInfo(device: CastDevice? = _playbackStatus.value.activeDevice) = withContext(Dispatchers.IO) {
        val targetDevice = device ?: return@withContext
        val soapBody = "<u:GetPositionInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "</u:GetPositionInfo>"

        val xmlResponse = executeSoapAction(targetDevice.controlUrl, "AVTransport:1", "GetPositionInfo", soapBody)
        if (xmlResponse != null) {
            val relTimeStr = extractTagValue(xmlResponse, "RelTime")
            val trackDurationStr = extractTagValue(xmlResponse, "TrackDuration")

            val currentMs = parseTimeStringToMs(relTimeStr)
            val durMs = parseTimeStringToMs(trackDurationStr)

            if (currentMs >= 0) {
                _playbackStatus.value = _playbackStatus.value.copy(
                    currentPositionMs = currentMs,
                    durationMs = if (durMs > 0) durMs else _playbackStatus.value.durationMs
                )
            }
        }
    }

    suspend fun updateTransportInfo(device: CastDevice? = _playbackStatus.value.activeDevice) = withContext(Dispatchers.IO) {
        val targetDevice = device ?: return@withContext
        val soapBody = "<u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
                "<InstanceID>0</InstanceID>" +
                "</u:GetTransportInfo>"

        val xmlResponse = executeSoapAction(targetDevice.controlUrl, "AVTransport:1", "GetTransportInfo", soapBody)
        if (xmlResponse != null) {
            val stateStr = extractTagValue(xmlResponse, "CurrentTransportState")
            val newState = when (stateStr?.uppercase(Locale.US)) {
                "PLAYING" -> PlayState.PLAYING
                "PAUSED_PLAYBACK" -> PlayState.PAUSED
                "STOPPED" -> PlayState.STOPPED
                "TRANSITIONING" -> PlayState.BUFFERING
                else -> _playbackStatus.value.playState
            }
            _playbackStatus.value = _playbackStatus.value.copy(playState = newState)
        }
    }

    private fun sendSoapAction(controlUrl: String, serviceType: String, action: String, soapBody: String): Boolean {
        return executeSoapAction(controlUrl, serviceType, action, soapBody) != null
    }

    private fun executeSoapAction(controlUrl: String, serviceType: String, action: String, soapBody: String): String? {
        return try {
            val envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                    "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                    "<s:Body>$soapBody</s:Body>" +
                    "</s:Envelope>"

            val mediaType = "text/xml; charset=\"utf-8\"".toMediaType()
            val requestBody = envelope.toRequestBody(mediaType)
            val soapActionHeader = "\"urn:schemas-upnp-org:service:$serviceType#$action\""

            val request = Request.Builder()
                .url(controlUrl)
                .addHeader("SOAPAction", soapActionHeader)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful) {
                responseBody
            } else {
                Log.w(tag, "SOAP Action $action failed (${response.code}): $responseBody")
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "SOAP Action error ($action): ${e.message}")
            null
        }
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun extractTagValue(xml: String, tagName: String): String? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals(tagName, ignoreCase = true)) {
                    return parser.nextText()
                }
                eventType = parser.next()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts milliseconds to standard REL_TIME (HH:MM:SS) using Locale.US
     * to avoid localized non-ASCII numerals.
     */
    private fun formatToTimeString(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun parseTimeStringToMs(timeStr: String?): Long {
        if (timeStr.isNullOrBlank() || timeStr == "NOT_IMPLEMENTED") return -1L
        return try {
            val parts = timeStr.trim().split(":")
            if (parts.size == 3) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val s = parts[2].substringBefore(".").toLong()
                ((h * 3600) + (m * 60) + s) * 1000L
            } else if (parts.size == 2) {
                val m = parts[0].toLong()
                val s = parts[1].substringBefore(".").toLong()
                ((m * 60) + s) * 1000L
            } else -1L
        } catch (_: Exception) {
            -1L
        }
    }

    fun setDirectStatus(status: PlaybackStatus) {
        _playbackStatus.value = status
    }
}
