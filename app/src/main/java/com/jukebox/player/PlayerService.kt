package com.jukebox.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.net.HttpURLConnection
import java.net.URL

// Foreground "mediaPlayback" service + MediaSession + wake/wifi locks keep the
// process alive and show lock-screen controls while the screen is off.
class PlayerService : Service() {
    private var session: MediaSession? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val main = Handler(Looper.getMainLooper())
    private var base = ""
    private var title = "Jukebox"
    private var paused = false

    companion object {
        @Volatile var instance: PlayerService? = null
        const val CH = "jukebox"
        const val ID = 42
        const val A_TOGGLE = "com.jukebox.player.TOGGLE"
        const val A_NEXT = "com.jukebox.player.NEXT"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        instance = this
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Jukebox playback", NotificationManager.IMPORTANCE_LOW))

        session = MediaSession(this, "Jukebox").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { if (paused) post("/pause") }
                override fun onPause() { if (!paused) post("/pause") }
                override fun onSkipToNext() { post("/next") }
            })
            isActive = true
        }

        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jukebox:play").also { it.acquire() }
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "jukebox:wifi").also { it.acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_TOGGLE -> post("/pause")
            A_NEXT -> post("/next")
        }
        update()
        return START_NOT_STICKY
    }

    fun setState(base: String, title: String, paused: Boolean) {
        main.post {
            this.base = base
            this.title = title
            this.paused = paused
            update()
        }
    }

    private fun post(path: String) {
        val b = base
        if (b.isEmpty()) return
        Thread {
            try {
                val c = URL(b + path).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.outputStream.use { it.write("{}".toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun update() {
        val s = session ?: return
        s.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build())
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT
                )
                .setState(
                    if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
                ).build()
        )

        fun svc(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, PlayerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIcon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(if (paused) "Paused" else "Playing")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, toggleIcon), if (paused) "Play" else "Pause", svc(A_TOGGLE, 1)).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_next), "Next", svc(A_NEXT, 2)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1))
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(ID, n)
        }
    }

    override fun onDestroy() {
        instance = null
        session?.release()
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
