package com.example

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.PlayState
import com.example.ui.MainViewModel
import com.example.ui.components.CastDeviceDialog
import com.example.ui.components.MiniPlayerBar
import com.example.ui.screens.LocalVideosScreen
import com.example.ui.screens.RemoteScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WebCastScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.OledBackground
import com.example.ui.theme.OledSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainAppScreen(viewModel: MainViewModel) {
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val localVideos by viewModel.localVideos.collectAsStateWithLifecycle()
    val demoVideos by viewModel.demoVideos.collectAsStateWithLifecycle()
    val isLoadingVideos by viewModel.isLoadingVideos.collectAsStateWithLifecycle()
    val selectedDevice by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val playbackStatus by viewModel.playbackStatus.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val customWebUrl by viewModel.customWebUrl.collectAsStateWithLifecycle()
    val localIp by viewModel.localIp.collectAsStateWithLifecycle()
    val activeProtocol by viewModel.activeProtocol.collectAsStateWithLifecycle()
    val webReceiverUrl by viewModel.webReceiverUrl.collectAsStateWithLifecycle()

    var showCastDialog by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* granted or denied */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Handle back button when navigating tabs
    BackHandler(enabled = activeTab != 0) {
        viewModel.setActiveTab(0)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBackground),
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                color = OledSurface,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(NeonCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "LG Video Cast",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(NeonCyan.copy(alpha = 0.2f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "OLED C5",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeonCyan
                                )
                            }
                        }
                        Text(
                            text = if (selectedDevice != null) "متصل بـ: ${selectedDevice?.name}" else "بث فوري بدون تقطيع",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (selectedDevice != null) NeonGreen else TextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }

                    IconButton(
                        onClick = { viewModel.scanDevices() },
                        modifier = Modifier.testTag("app_bar_refresh_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "تحديث الشبكة",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Cast device icon with status badge
                    IconButton(
                        onClick = { showCastDialog = true },
                        modifier = Modifier.testTag("app_bar_cast_dialog_btn")
                    ) {
                        BadgedBox(
                            badge = {
                                if (selectedDevice != null) {
                                    Badge(
                                        containerColor = NeonGreen,
                                        modifier = Modifier.size(8.dp)
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (selectedDevice != null) Icons.Default.CastConnected else Icons.Default.Cast,
                                contentDescription = "أجهزة البث",
                                tint = if (selectedDevice != null) NeonCyan else TextPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                // Persistent Mini Player Bar when video is playing/paused
                if (activeTab != 2 && playbackStatus.playState != PlayState.IDLE && playbackStatus.videoTitle.isNotBlank()) {
                    MiniPlayerBar(
                        status = playbackStatus,
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onClick = { viewModel.setActiveTab(2) }
                    )
                }

                NavigationBar(
                    containerColor = OledSurface,
                    tonalElevation = 8.dp,
                    modifier = Modifier.testTag("main_navigation_bar")
                ) {
                    NavigationBarItem(
                        selected = activeTab == 0,
                        onClick = { viewModel.setActiveTab(0) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.VideoLibrary,
                                contentDescription = stringResource(R.string.tab_local_videos)
                            )
                        },
                        label = { Text(stringResource(R.string.tab_local_videos), fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00363D),
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("nav_tab_local")
                    )

                    NavigationBarItem(
                        selected = activeTab == 1,
                        onClick = { viewModel.setActiveTab(1) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = stringResource(R.string.tab_web_cast)
                            )
                        },
                        label = { Text(stringResource(R.string.tab_web_cast), fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00363D),
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("nav_tab_web")
                    )

                    NavigationBarItem(
                        selected = activeTab == 2,
                        onClick = { viewModel.setActiveTab(2) },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (playbackStatus.playState == PlayState.PLAYING) {
                                        Badge(containerColor = NeonGreen, modifier = Modifier.size(6.dp))
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = stringResource(R.string.tab_remote)
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.tab_remote), fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00363D),
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("nav_tab_remote")
                    )

                    NavigationBarItem(
                        selected = activeTab == 3,
                        onClick = { viewModel.setActiveTab(3) },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.tab_settings)
                            )
                        },
                        label = { Text(stringResource(R.string.tab_settings), fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF00363D),
                            selectedTextColor = NeonCyan,
                            indicatorColor = NeonCyan,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        ),
                        modifier = Modifier.testTag("nav_tab_settings")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                0 -> LocalVideosScreen(
                    videos = localVideos,
                    demoVideos = demoVideos,
                    selectedDevice = selectedDevice,
                    searchQuery = searchQuery,
                    isLoading = isLoadingVideos,
                    onSearchChange = { viewModel.setSearchQuery(it) },
                    onVideoSelected = { video ->
                        viewModel.castVideo(video)
                    },
                    onVideoPicked = { uri ->
                        viewModel.onVideoPicked(uri)
                    },
                    onOpenCastDialog = { showCastDialog = true }
                )
                1 -> WebCastScreen(
                    customUrl = customWebUrl,
                    demoVideos = demoVideos,
                    selectedDevice = selectedDevice,
                    onUrlChange = { viewModel.setCustomWebUrl(it) },
                    onCastUrl = { viewModel.castCustomWebUrl() },
                    onCastDemoVideo = { video -> viewModel.castVideo(video) },
                    onOpenCastDialog = { showCastDialog = true }
                )
                2 -> RemoteScreen(
                    status = playbackStatus,
                    selectedDevice = selectedDevice,
                    activeProtocol = activeProtocol,
                    webReceiverUrl = webReceiverUrl,
                    onTogglePlayPause = { viewModel.togglePlayPause() },
                    onSkipBy = { delta -> viewModel.skipBy(delta) },
                    onSeekTo = { targetMs -> viewModel.seekTo(targetMs) },
                    onStop = { viewModel.stop() },
                    onSetVolume = { vol -> viewModel.setVolume(vol) },
                    onToggleMute = { viewModel.toggleMute() },
                    onSwitchProtocol = { protocol ->
                        viewModel.setPreferredProtocol(protocol)
                        playbackStatus.activeDevice?.let { dev ->
                            val currentVid = viewModel.localVideos.value.firstOrNull { it.title == playbackStatus.videoTitle }
                                ?: com.example.model.VideoItem("current", playbackStatus.videoTitle, android.net.Uri.parse(playbackStatus.currentStreamUrl))
                            viewModel.castVideo(currentVid, dev, protocol)
                        }
                    },
                    onOpenCastDialog = { showCastDialog = true },
                    onNavigateToVideos = { viewModel.setActiveTab(0) }
                )
                3 -> SettingsScreen(
                    localIp = localIp,
                    onRefreshNetwork = { viewModel.refreshNetworkInfo() },
                    onOpenCastDialog = { showCastDialog = true }
                )
            }
        }
    }

    if (showCastDialog) {
        CastDeviceDialog(
            devices = devices,
            selectedDevice = selectedDevice,
            activeProtocol = activeProtocol,
            webReceiverUrl = webReceiverUrl,
            isScanning = isScanning,
            onDeviceSelected = { device ->
                viewModel.selectDevice(device)
            },
            onProtocolSelected = { protocol ->
                viewModel.setPreferredProtocol(protocol)
            },
            onScanRequested = {
                viewModel.scanDevices()
            },
            onManualConnect = { ip, port ->
                viewModel.addManualLgC5(ip, port)
            },
            onDismiss = { showCastDialog = false }
        )
    }
}
