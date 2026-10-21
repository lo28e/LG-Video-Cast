package com.example.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

class CastStreamingService : Service() {

    companion object {
        private const val TAG = "CastStreamingService"
        private const val NOTIFICATION_ID = 4554
        private const val CHANNEL_ID = "lg_cast_streaming_channel"

        const val ACTION_START_STREAMING = "com.example.action.START_STREAMING"
        const val ACTION_STOP_STREAMING = "com.example.action.STOP_STREAMING"

        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_DEVICE_NAME = "extra_device_name"

        fun start(context: Context, videoTitle: String, deviceName: String = "LG OLED C5") {
            val intent = Intent(context, CastStreamingService::class.java).apply {
                action = ACTION_START_STREAMING
                putExtra(EXTRA_VIDEO_TITLE, videoTitle)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CastStreamingService::class.java).apply {
                action = ACTION_STOP_STREAMING
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send stop to service: ${e.message}")
            }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_STREAMING) {
            Log.i(TAG, "Received ACTION_STOP_STREAMING, stopping foreground service")
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val videoTitle = intent?.getStringExtra(EXTRA_VIDEO_TITLE) ?: "مقطع فيديو"
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME) ?: "LG OLED C5"

        // Ensure locks are held
        acquireLocks()

        val notification = buildNotification(videoTitle, deviceName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null || wakeLock?.isHeld == false) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LGVideoCast::StreamingWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "PowerManager PARTIAL_WAKE_LOCK acquired successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock: ${e.message}")
        }

        try {
            if (wifiLock == null || wifiLock?.isHeld == false) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wifiManager?.createWifiLock(lockMode, "LGVideoCast::StreamingWifiLock")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                Log.i(TAG, "WifiManager High-Perf Lock acquired successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.i(TAG, "WakeLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.i(TAG, "WifiLock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WifiLock: ${e.message}")
        }
        wifiLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "بث الفيديو إلى شاشة LG",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار استمرار بث الفيديو في الخلفية أثناء إطفاء شاشة الجوال"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(videoTitle: String, deviceName: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CastStreamingService::class.java).apply {
            action = ACTION_STOP_STREAMING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("جارٍ البث إلى $deviceName")
            .setContentText(videoTitle)
            .setSubText("خادم البث نشط بدون انقطاع")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "إيقاف البث",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        Log.i(TAG, "CastStreamingService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
