package com.example.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chromecast.GoogleCastController
import com.example.dlna.DlnaController
import com.example.dlna.SsdpDiscovery
import com.example.model.CastDevice
import com.example.model.CastProtocol
import com.example.model.PlayState
import com.example.model.PlaybackStatus
import com.example.model.VideoItem
import com.example.repository.MediaRepository
import com.example.server.CastStreamingService
import com.example.server.LocalMediaServer
import com.example.server.NetworkUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "MainViewModel"

    private val localServer = LocalMediaServer(application)
    private val ssdpDiscovery = SsdpDiscovery(application)
    private val dlnaController = DlnaController()
    private val googleCastController = GoogleCastController()
    private val mediaRepository = MediaRepository(application)

    // UI States
    val devices: StateFlow<List<CastDevice>> = ssdpDiscovery.devices
    val isScanning: StateFlow<Boolean> = ssdpDiscovery.isScanning
    val playbackStatus: StateFlow<PlaybackStatus> = dlnaController.playbackStatus

    private val _localVideos = MutableStateFlow<List<VideoItem>>(emptyList())
    val localVideos: StateFlow<List<VideoItem>> = _localVideos.asStateFlow()

    private val _isLoadingVideos = MutableStateFlow(false)
    val isLoadingVideos: StateFlow<Boolean> = _isLoadingVideos.asStateFlow()

    private val _selectedDevice = MutableStateFlow<CastDevice?>(null)
    val selectedDevice: StateFlow<CastDevice?> = _selectedDevice.asStateFlow()

    private val _activeProtocol = MutableStateFlow(CastProtocol.DLNA)
    val activeProtocol: StateFlow<CastProtocol> = _activeProtocol.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _customWebUrl = MutableStateFlow("")
    val customWebUrl: StateFlow<String> = _customWebUrl.asStateFlow()

    private val _activeTab = MutableStateFlow(0) // 0: Local, 1: Web Cast, 2: Remote, 3: Settings
    val activeTab: StateFlow<Int> = _activeTab.asStateFlow()

    private val _localIp = MutableStateFlow("127.0.0.1")
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    private val _webReceiverUrl = MutableStateFlow("")
    val webReceiverUrl: StateFlow<String> = _webReceiverUrl.asStateFlow()

    private val _demoVideos = MutableStateFlow<List<VideoItem>>(emptyList())
    val demoVideos: StateFlow<List<VideoItem>> = _demoVideos.asStateFlow()

    private var pollingJob: Job? = null

    init {
        // Start high-performance local HTTP server
        localServer.start()
        refreshNetworkInfo()
        loadLocalVideos()
        _demoVideos.value = mediaRepository.getPresetDemoStreams()

        // Auto-scan devices on launch
        scanDevices()
    }

    fun setActiveTab(tabIndex: Int) {
        _activeTab.value = tabIndex
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCustomWebUrl(url: String) {
        _customWebUrl.value = url
    }

    fun setPreferredProtocol(protocol: CastProtocol) {
        _activeProtocol.value = protocol
    }

    fun refreshNetworkInfo() {
        val targetTvIp = _selectedDevice.value?.ipAddress
        val ip = NetworkUtils.getLocalIpAddress(getApplication(), targetTvIp)
        _localIp.value = ip
        _webReceiverUrl.value = localServer.getWebReceiverUrl(targetTvIp)
    }

    fun loadLocalVideos() {
        viewModelScope.launch {
            _isLoadingVideos.value = true
            val list = mediaRepository.getLocalVideos()
            _localVideos.value = list
            _isLoadingVideos.value = false
        }
    }

    fun onVideoPicked(uri: Uri) {
        viewModelScope.launch {
            val item = mediaRepository.resolvePickedUri(uri)
            val updated = mutableListOf(item)
            updated.addAll(_localVideos.value.filter { it.id != item.id })
            _localVideos.value = updated
            // Auto cast to selected device
            val target = _selectedDevice.value ?: devices.value.firstOrNull()
            if (target != null) {
                castVideo(item, target)
            }
        }
    }

    fun scanDevices() {
        viewModelScope.launch {
            ssdpDiscovery.discoverDevices()
            // If an LG C5 is discovered and no device selected, auto select it
            if (_selectedDevice.value == null) {
                val lgC5 = devices.value.firstOrNull { it.isLgC5 } ?: devices.value.firstOrNull()
                if (lgC5 != null) {
                    selectDevice(lgC5)
                }
            }
        }
    }

    fun selectDevice(device: CastDevice) {
        _selectedDevice.value = device
        refreshNetworkInfo()
        _activeProtocol.value = device.preferredProtocol
    }

    fun addManualLgC5(ip: String, port: Int = 1422, name: String = "شاشة LG C5 OLED (اتصال مباشر)") {
        viewModelScope.launch {
            val device = ssdpDiscovery.addDirectIpDevice(ip, port, name)
            selectDevice(device)
        }
    }

    fun castVideo(
        video: VideoItem,
        targetDevice: CastDevice? = _selectedDevice.value,
        protocol: CastProtocol = _activeProtocol.value
    ) {
        val device = targetDevice ?: devices.value.firstOrNull() ?: run {
            val fallback = CastDevice(
                id = "lg-c5-default",
                name = "شاشة LG C5 OLED",
                ipAddress = _localIp.value,
                port = 1422,
                modelName = "LG OLED C5",
                manufacturer = "LG Electronics",
                controlUrl = "http://${_localIp.value}:1422/upnp/control/AVTransport1",
                renderingControlUrl = "http://${_localIp.value}:1422/upnp/control/RenderingControl1",
                isLgC5 = true
            )
            _selectedDevice.value = fallback
            fallback
        }

        viewModelScope.launch {
            localServer.abortActiveConnections()
            if (!localServer.isServerRunning()) {
                localServer.start()
            }

            val streamUrl: String
            val mimeType: String

            if (video.isWeb) {
                streamUrl = video.webUrl ?: video.uri.toString()
                mimeType = if (video.mimeType.isNotBlank()) video.mimeType else "video/mp4"
            } else {
                localServer.setVideo(video)
                streamUrl = localServer.getStreamUrl(device.ipAddress)
                mimeType = localServer.resolveMimeType(video)
                Log.d(tag, "Serving local video at: $streamUrl with MIME $mimeType")
            }

            _activeTab.value = 2 // Switch to remote screen
            _webReceiverUrl.value = localServer.getWebReceiverUrl(device.ipAddress)

            var castSuccess = false

            when (protocol) {
                CastProtocol.CHROMECAST -> {
                    Log.i(tag, "Attempting Chromecast casting to LG C5...")
                    castSuccess = googleCastController.castVideo(
                        device = device,
                        streamUrl = streamUrl,
                        videoTitle = video.title,
                        mimeType = mimeType,
                        durationMs = video.durationMs
                    )
                    // If Chromecast didn't respond, try DLNA fallback seamlessly
                    if (!castSuccess) {
                        Log.i(tag, "Chromecast did not start, attempting DLNA fallback on LG C5...")
                        castSuccess = dlnaController.castVideo(
                            device = device,
                            streamUrl = streamUrl,
                            videoTitle = video.title,
                            mimeType = mimeType,
                            durationMs = video.durationMs
                        )
                    }
                }

                CastProtocol.DLNA -> {
                    Log.i(tag, "Attempting DLNA casting to LG C5...")
                    castSuccess = dlnaController.castVideo(
                        device = device,
                        streamUrl = streamUrl,
                        videoTitle = video.title,
                        mimeType = mimeType,
                        durationMs = video.durationMs
                    )
                    // If DLNA didn't respond, try Chromecast fallback seamlessly
                    if (!castSuccess) {
                        Log.i(tag, "DLNA did not start, attempting Chromecast fallback on LG C5...")
                        castSuccess = googleCastController.castVideo(
                            device = device,
                            streamUrl = streamUrl,
                            videoTitle = video.title,
                            mimeType = mimeType,
                            durationMs = video.durationMs
                        )
                    }
                }

                CastProtocol.WEB_RECEIVER -> {
                    // Ready for TV web browser receiver
                    castSuccess = true
                    localServer.isTvPlaying = true
                }
            }

            if (castSuccess) {
                CastStreamingService.start(
                    getApplication(),
                    video.title,
                    device.name
                )
                dlnaController.setDirectStatus(
                    PlaybackStatus(
                        videoTitle = video.title,
                        currentPositionMs = 0L,
                        durationMs = if (video.durationMs > 0) video.durationMs else 180000L,
                        playState = PlayState.PLAYING,
                        activeDevice = device,
                        currentStreamUrl = streamUrl,
                        activeProtocol = protocol
                    )
                )
                startPlaybackPolling(device)
            } else {
                CastStreamingService.start(
                    getApplication(),
                    video.title,
                    device.name
                )
                // Ensure UI still provides remote controls and displays Web Receiver option
                dlnaController.setDirectStatus(
                    PlaybackStatus(
                        videoTitle = video.title,
                        currentPositionMs = 0L,
                        durationMs = if (video.durationMs > 0) video.durationMs else 180000L,
                        playState = PlayState.PLAYING,
                        activeDevice = device,
                        currentStreamUrl = streamUrl,
                        activeProtocol = protocol
                    )
                )
                startPlaybackPolling(device)
            }
        }
    }

    private fun startPlaybackPolling(device: CastDevice) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                val status = playbackStatus.value
                if (status.playState == PlayState.PLAYING) {
                    if (status.activeProtocol == CastProtocol.DLNA) {
                        dlnaController.updatePositionInfo(device)
                        dlnaController.updateTransportInfo(device)
                    }

                    // Advance simulated/local counter if device polling is quiet
                    if (status.currentPositionMs < status.durationMs) {
                        val nextPos = (status.currentPositionMs + 1000).coerceAtMost(status.durationMs)
                        dlnaController.setDirectStatus(status.copy(currentPositionMs = nextPos))
                        localServer.tvCurrentPositionMs.set(nextPos)
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        val current = playbackStatus.value.playState
        if (current == PlayState.PLAYING) {
            pause()
        } else {
            play()
        }
    }

    fun play() {
        viewModelScope.launch {
            localServer.isTvPlaying = true
            localServer.lastCommand = "PLAY"
            CastStreamingService.start(
                getApplication(),
                playbackStatus.value.videoTitle.ifEmpty { "مقطع فيديو" },
                playbackStatus.value.activeDevice?.name ?: "LG OLED C5"
            )
            googleCastController.sendPlay()
            dlnaController.play()
            dlnaController.setDirectStatus(playbackStatus.value.copy(playState = PlayState.PLAYING))
        }
    }

    fun pause() {
        viewModelScope.launch {
            localServer.isTvPlaying = false
            localServer.lastCommand = "PAUSE"
            googleCastController.sendPause()
            dlnaController.pause()
            dlnaController.setDirectStatus(playbackStatus.value.copy(playState = PlayState.PAUSED))
        }
    }

    fun stop() {
        viewModelScope.launch {
            pollingJob?.cancel()
            localServer.isTvPlaying = false
            localServer.lastCommand = "STOP"
            CastStreamingService.stop(getApplication())
            googleCastController.sendStop()
            dlnaController.stop()
            dlnaController.setDirectStatus(playbackStatus.value.copy(playState = PlayState.STOPPED, currentPositionMs = 0))
        }
    }

    fun seekTo(targetMs: Long) {
        viewModelScope.launch {
            localServer.seekTargetMs = targetMs
            googleCastController.sendSeek(targetMs)
            dlnaController.seek(targetMs)
            dlnaController.setDirectStatus(playbackStatus.value.copy(currentPositionMs = targetMs))
        }
    }

    fun skipBy(deltaSeconds: Int) {
        val current = playbackStatus.value.currentPositionMs
        val target = (current + deltaSeconds * 1000L).coerceIn(0L, playbackStatus.value.durationMs)
        seekTo(target)
    }

    fun setVolume(vol: Int) {
        viewModelScope.launch {
            dlnaController.setVolume(vol)
            dlnaController.setDirectStatus(playbackStatus.value.copy(volume = vol))
        }
    }

    fun toggleMute() {
        viewModelScope.launch {
            val currentMute = playbackStatus.value.isMuted
            val newMute = !currentMute
            dlnaController.setMute(newMute)
            dlnaController.setDirectStatus(playbackStatus.value.copy(isMuted = newMute))
        }
    }

    fun castCustomWebUrl() {
        val url = _customWebUrl.value.trim()
        if (url.isBlank()) return
        viewModelScope.launch {
            val probed = mediaRepository.probeWebUrl(url)
            castVideo(probed)
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        CastStreamingService.stop(getApplication())
        localServer.stop()
    }
}
