package com.tinn.picture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/** Plays the audio track of the selected video after the Activity is backgrounded/locked. */
class BackgroundAudioService : Service() {

    companion object {
        const val ACTION_SHOW_VIDEO = "com.tinn.picture.action.SHOW_VIDEO"
        const val ACTION_START_BACKGROUND_AUDIO = "com.tinn.picture.action.START_BACKGROUND_AUDIO"
        const val ACTION_STOP = "com.tinn.picture.action.STOP_BACKGROUND_AUDIO"
        const val EXTRA_URI = "extra_media_uri"
        const val EXTRA_TITLE = "extra_media_title"

        private const val CHANNEL_ID = "photos_by_tinn_playback"
        private const val NOTIFICATION_ID = 4102
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentTitle: String = "Video"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        currentTitle = intent?.getStringExtra(EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() } ?: currentTitle

        when (intent?.action) {
            ACTION_SHOW_VIDEO -> showAsForeground("Đang phát video")
            ACTION_START_BACKGROUND_AUDIO -> {
                val uriString = intent.getStringExtra(EXTRA_URI)
                if (uriString.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                showAsForeground("Đang phát âm thanh nền")
                startAudio(Uri.parse(uriString))
            }
            ACTION_STOP -> stopPlaybackAndService()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAudio(uri: Uri) {
        releasePlayer()
        try {
            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Keeps the audio decoder running when the display turns off.
            player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setDataSource(applicationContext, uri)
            player.setOnPreparedListener { preparedPlayer ->
                if (mediaPlayer === preparedPlayer) {
                    preparedPlayer.isLooping = true
                    preparedPlayer.start()
                }
            }
            player.setOnCompletionListener {
                stopPlaybackAndService()
            }
            player.setOnErrorListener { _, _, _ ->
                stopPlaybackAndService()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            stopPlaybackAndService()
        }
    }

    private fun showAsForeground(description: String) {
        val notification = buildNotification(description)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(description: String): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = openAppIntent?.let {
            PendingIntent.getActivity(
                this,
                4103,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val stopIntent = Intent(this, BackgroundAudioService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            4104,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Đang phát: $currentTitle")
            .setContentText("Photos by Tinn · $description")
            .setContentIntent(contentPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Dừng", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Đang phát media",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Thông báo video đang phát và âm thanh nền"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun releasePlayer() {
        try {
            mediaPlayer?.setOnPreparedListener(null)
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.setOnErrorListener(null)
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    private fun stopPlaybackAndService() {
        releasePlayer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
