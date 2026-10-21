package com.example.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.model.CastDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

class SsdpDiscovery(private val context: Context) {

    private val tag = "SsdpDiscovery"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val foundDevicesMap = mutableMapOf<String, CastDevice>()

    suspend fun discoverDevices(timeoutSeconds: Int = 5) = withContext(Dispatchers.IO) {
        if (_isScanning.value) return@withContext
        _isScanning.value = true

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("lg_cast_ssdp_lock")
        multicastLock?.setReferenceCounted(true)

        var socket: DatagramSocket? = null
        try {
            multicastLock?.acquire()
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 1200

            val ssdpGroup = InetAddress.getByName("239.255.255.250")
            val port = 1900

            // Search specifically for MediaRenderer and UPnP root devices
            val searchQueries = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "ssdp:all"
            )

            for (target in searchQueries) {
                val query = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: $target\r\n\r\n"

                val sendData = query.toByteArray(Charsets.UTF_8)
                val sendPacket = DatagramPacket(sendData, sendData.size, ssdpGroup, port)
                socket.send(sendPacket)
            }

            val buffer = ByteArray(4096)
            val startTime = System.currentTimeMillis()
            val maxTime = timeoutSeconds * 1000L

            while (System.currentTimeMillis() - startTime < maxTime) {
                try {
                    val receivePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(receivePacket)

                    val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                    val senderIp = receivePacket.address.hostAddress ?: continue
                    parseSsdpPacket(response, senderIp)
                } catch (e: SocketTimeoutException) {
                    // Check if time remaining
                    continue
                } catch (e: Exception) {
                    Log.w(tag, "Receive packet error: ${e.message}")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Discovery error: ${e.message}")
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {}
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {}
            _isScanning.value = false
        }
    }

    private suspend fun parseSsdpPacket(packet: String, senderIp: String) {
        val lines = packet.split("\r\n")
        var locationHeader: String? = null
        var serverHeader: String? = null

        for (line in lines) {
            val lower = line.lowercase()
            if (lower.startsWith("location:")) {
                locationHeader = line.substring(9).trim()
            } else if (lower.startsWith("server:")) {
                serverHeader = line.substring(7).trim()
            }
        }

        if (!locationHeader.isNullOrBlank()) {
            fetchDeviceDescription(locationHeader, senderIp, serverHeader)
        }
    }

    private suspend fun fetchDeviceDescription(
        locationUrl: String,
        fallbackIp: String,
        serverHeader: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(locationUrl).build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext

            val xmlBody = resp.body?.string() ?: return@withContext
            val uri = URI(locationUrl)
            val host = uri.host ?: fallbackIp
            val port = if (uri.port > 0) uri.port else 1422

            parseDeviceXml(xmlBody, host, port, locationUrl, serverHeader)
        } catch (e: Exception) {
            Log.w(tag, "Failed to fetch description from $locationUrl: ${e.message}")
        }
    }

    private fun parseDeviceXml(
        xml: String,
        host: String,
        port: Int,
        locationUrl: String,
        serverHeader: String?
    ) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var friendlyName: String? = null
            var modelName: String? = null
            var manufacturer: String? = null
            var udn: String? = null

            var currentTag = ""
            var currentServiceType: String? = null
            var currentControlUrl: String? = null
            var avTransportControlUrl: String? = null
            var renderingControlUrl: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        if (currentTag.equals("service", ignoreCase = true)) {
                            currentServiceType = null
                            currentControlUrl = null
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            when (currentTag.lowercase()) {
                                "friendlyname" -> if (friendlyName == null) friendlyName = text
                                "modelname" -> if (modelName == null) modelName = text
                                "manufacturer" -> if (manufacturer == null) manufacturer = text
                                "udn" -> if (udn == null) udn = text
                                "servicetype" -> currentServiceType = text
                                "controlurl" -> currentControlUrl = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("service", ignoreCase = true)) {
                            if (currentServiceType?.contains("AVTransport", ignoreCase = true) == true) {
                                avTransportControlUrl = currentControlUrl
                            } else if (currentServiceType?.contains("RenderingControl", ignoreCase = true) == true) {
                                renderingControlUrl = currentControlUrl
                            }
                        }
                    }
                }
                eventType = parser.next()
            }

            if (friendlyName != null || avTransportControlUrl != null) {
                val fullAvUrl = resolveControlUrl(locationUrl, avTransportControlUrl ?: "/upnp/control/AVTransport1")
                val fullRenderingUrl = resolveControlUrl(locationUrl, renderingControlUrl ?: "/upnp/control/RenderingControl1")

                val finalName = friendlyName ?: "شاشة LG Smart TV"
                val finalManufacturer = manufacturer ?: "LG Electronics"
                val finalModel = modelName ?: "LG OLED C5"

                val isLg = finalManufacturer.contains("LG", ignoreCase = true) ||
                        finalName.contains("LG", ignoreCase = true) ||
                        finalName.contains("webOS", ignoreCase = true) ||
                        serverHeader?.contains("LG", ignoreCase = true) == true ||
                        serverHeader?.contains("webOS", ignoreCase = true) == true

                val isC5 = isLg && (finalName.contains("C5", ignoreCase = true) ||
                        finalModel.contains("C5", ignoreCase = true) ||
                        finalModel.contains("OLED", ignoreCase = true))

                val id = udn ?: "device-$host-$port"
                val device = CastDevice(
                    id = id,
                    name = finalName,
                    ipAddress = host,
                    port = port,
                    modelName = finalModel,
                    manufacturer = finalManufacturer,
                    locationXmlUrl = locationUrl,
                    controlUrl = fullAvUrl,
                    renderingControlUrl = fullRenderingUrl,
                    isLgC5 = isC5
                )

                foundDevicesMap[device.ipAddress] = device
                _devices.value = foundDevicesMap.values.toList()
                Log.i(tag, "Found device: ${device.name} at ${device.ipAddress}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing XML from $host: ${e.message}")
        }
    }

    private fun resolveControlUrl(locationUrl: String, controlUrl: String): String {
        return if (controlUrl.startsWith("http://") || controlUrl.startsWith("https://")) {
            controlUrl
        } else {
            val uri = URI(locationUrl)
            val base = "${uri.scheme}://${uri.host}:${uri.port}"
            if (controlUrl.startsWith("/")) "$base$controlUrl" else "$base/$controlUrl"
        }
    }

    /**
     * Adds an LG C5 TV manually by IP address (useful when Wi-Fi router blocks multicast).
     */
    suspend fun addDirectIpDevice(ip: String, customPort: Int = 1422, customName: String = "شاشة LG C5 (اتصال مباشر)"): CastDevice {
        val device = CastDevice(
            id = "manual-$ip-$customPort",
            name = customName,
            ipAddress = ip,
            port = customPort,
            modelName = "LG OLED C5 (webOS)",
            manufacturer = "LG Electronics",
            controlUrl = "http://$ip:$customPort/upnp/control/AVTransport1",
            renderingControlUrl = "http://$ip:$customPort/upnp/control/RenderingControl1",
            isLgC5 = true
        )
        foundDevicesMap[ip] = device
        _devices.value = foundDevicesMap.values.toList()
        return device
    }

    fun clearDevices() {
        foundDevicesMap.clear()
        _devices.value = emptyList()
    }
}
