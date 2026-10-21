package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CastDevice
import com.example.model.CastProtocol
import com.example.model.PlayState
import com.example.model.PlaybackStatus
import com.example.ui.theme.NeonAmber
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonRed
import com.example.ui.theme.OledBackground
import com.example.ui.theme.OledSurfaceCard
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@Composable
fun RemoteScreen(
    status: PlaybackStatus,
    selectedDevice: CastDevice?,
    activeProtocol: CastProtocol,
    webReceiverUrl: String,
    onTogglePlayPause: () -> Unit,
    onSkipBy: (Int) -> Unit,
    onSeekTo: (Long) -> Unit,
    onStop: () -> Unit,
    onSetVolume: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onSwitchProtocol: (CastProtocol) -> Unit,
    onOpenCastDialog: () -> Unit,
    onNavigateToVideos: () -> Unit
) {
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }

    val currentSliderPosition = if (isDraggingSlider) {
        sliderValue
    } else {
        status.progressFraction
    }

    val isPlaying = status.playState == PlayState.PLAYING

    val playButtonBg by animateColorAsState(
        targetValue = if (isPlaying) NeonCyan else NeonPurple,
        animationSpec = tween(300),
        label = "playButtonBg"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledBackground)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
            .testTag("remote_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Device Connection Header Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onOpenCastDialog)
                .border(1.dp, Color(0xFF263248), RoundedCornerShape(18.dp)),
            colors = CardDefaults.cardColors(containerColor = OledSurfaceCard)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(NeonCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (activeProtocol) {
                                CastProtocol.CHROMECAST -> Icons.Default.CastConnected
                                CastProtocol.DLNA -> Icons.Default.Tv
                                CastProtocol.WEB_RECEIVER -> Icons.Default.Language
                            },
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = selectedDevice?.name ?: "شاشة LG C5 OLED",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (status.playState != PlayState.IDLE) NeonGreen else NeonAmber)
                            )
                        }
                        Text(
                            text = "${activeProtocol.titleArabic} • ${selectedDevice?.ipAddress ?: "الشبكة المحلية"}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = NeonCyan,
                                fontSize = 11.sp
                            )
                        )
                    }
                }

                Button(
                    onClick = onOpenCastDialog,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B2433)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("تغيير", fontSize = 12.sp, color = NeonCyan)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Protocol Switcher Bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A26)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222B3D))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CastProtocol.values().forEach { protocol ->
                    val isSelected = activeProtocol == protocol
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) NeonCyan.copy(alpha = 0.2f) else Color.Transparent)
                            .border(
                                width = if (isSelected) 1.dp else 0.dp,
                                color = if (isSelected) NeonCyan else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onSwitchProtocol(protocol) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when (protocol) {
                                CastProtocol.CHROMECAST -> "Google Cast"
                                CastProtocol.DLNA -> "DLNA"
                                CastProtocol.WEB_RECEIVER -> "مستعرض LG"
                            },
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) NeonCyan else TextSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (status.videoTitle.isBlank() && status.playState == PlayState.IDLE) {
            // Idle State View
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = OledSurfaceCard)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF192130)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "لا يوجد فيديو يعمل حالياً على التلفزيون",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "اختر فيديو من جوالك أو الصق رابط ويب لبدء البث والتحكم الكامل بالريموت.",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onNavigateToVideos,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                    ) {
                        Text("اختر فيديو للبث الآن", color = Color(0xFF00363D), fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // Active Media Controller
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = OledSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.25f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Video Title
                    Text(
                        text = status.videoTitle.ifEmpty { "فيديو بدون اسم" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = when (status.playState) {
                            PlayState.PLAYING -> "يعمل الآن عبر ${activeProtocol.titleArabic}"
                            PlayState.PAUSED -> "متوقف مؤقتاً"
                            PlayState.BUFFERING -> "جارٍ التحميل والتخزين المؤقت..."
                            PlayState.STOPPED -> "متوقف"
                            PlayState.ERROR -> "خطأ في التشغيل"
                            PlayState.IDLE -> "جاهز"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (status.playState == PlayState.PLAYING) NeonGreen else NeonCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Progress Slider
                    Slider(
                        value = currentSliderPosition,
                        onValueChange = {
                            isDraggingSlider = true
                            sliderValue = it
                        },
                        onValueChangeFinished = {
                            isDraggingSlider = false
                            val targetMs = (sliderValue * status.durationMs).toLong()
                            onSeekTo(targetMs)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("remote_progress_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = NeonCyan,
                            activeTrackColor = NeonCyan,
                            inactiveTrackColor = Color(0xFF222C3E)
                        )
                    )

                    // Time display row
                    val displayedPositionText = if (isDraggingSlider) {
                        val seekMs = (sliderValue * status.durationMs).toLong()
                        val totalSec = (seekMs / 1000).coerceAtLeast(0)
                        val h = totalSec / 3600
                        val m = (totalSec % 3600) / 60
                        val s = totalSec % 60
                        if (h > 0) String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
                        else String.format(java.util.Locale.US, "%02d:%02d", m, s)
                    } else {
                        status.formattedPosition
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = displayedPositionText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = NeonCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                        Text(
                            text = status.formattedDuration,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Big Controls Cluster
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rewind 15s
                        IconButton(
                            onClick = { onSkipBy(-15) },
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1A2232))
                                .testTag("skip_backward_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "ترجيع 15 ثانية",
                                tint = TextPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        // Play/Pause Big Button
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(playButtonBg)
                                .clickable(onClick = onTogglePlayPause)
                                .border(3.dp, Color.White.copy(alpha = 0.2f), CircleShape)
                                .testTag("remote_play_pause_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                                tint = Color(0xFF00363D),
                                modifier = Modifier.size(42.dp)
                            )
                        }

                        // Forward 15s
                        IconButton(
                            onClick = { onSkipBy(15) },
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1A2232))
                                .testTag("skip_forward_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "تقديم 15 ثانية",
                                tint = TextPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Stop & Replay buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonRed.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("remote_stop_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "إيقاف",
                                tint = NeonRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("إيقاف البث", color = NeonRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = { onSeekTo(0L) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B2433)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.testTag("remote_restart_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay,
                                contentDescription = "من البداية",
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("من البداية", color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Web Receiver direct suggestion card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121926)),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonGreen.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = NeonGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "رابط مستعرض LG TV السريع (حل بديل فوري بدون انقطاع):",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = NeonGreen
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "إذا لم يستجب التلفزيون لـ Google Cast أو DLNA، افتح متصفح شاشة LG C5 واكتب:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = webReceiverUrl,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = NeonCyan
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Volume Control Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = OledSurfaceCard),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF263248))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (status.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                                contentDescription = null,
                                tint = if (status.isMuted) NeonRed else NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "مستوى صوت تلفزيون LG C5",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                        }

                        Text(
                            text = if (status.isMuted) "كتم الصوت" else "${status.volume}%",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = if (status.isMuted) NeonRed else NeonCyan
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onToggleMute,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (status.isMuted) NeonRed.copy(alpha = 0.2f) else Color(0xFF1A2232))
                                .testTag("mute_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (status.isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeMute,
                                contentDescription = "كتم",
                                tint = if (status.isMuted) NeonRed else TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Slider(
                            value = status.volume.toFloat(),
                            onValueChange = { onSetVolume(it.toInt()) },
                            valueRange = 0f..100f,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("volume_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = NeonCyan,
                                activeTrackColor = NeonCyan,
                                inactiveTrackColor = Color(0xFF222C3E)
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}
