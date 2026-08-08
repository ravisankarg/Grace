package com.ravi.grace

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.LocalTime
import kotlin.math.ceil

data class MeditationStatus(
    val active: Boolean = false,
    val paused: Boolean = false,
    val secondsLeft: Int = 0,
    val minutes: Int = 0,
    val soundName: String = ""
)

/** The one source of truth for meditation, shared by the notification and the in-app timer. */
object MeditationSession {
    const val ACTION_START = "com.ravi.grace.meditation.START"
    const val ACTION_PAUSE = "com.ravi.grace.meditation.PAUSE"
    const val ACTION_RESUME = "com.ravi.grace.meditation.RESUME"
    const val ACTION_END = "com.ravi.grace.meditation.END"
    const val ACTION_STATUS = "com.ravi.grace.meditation.STATUS"
    private const val PREFS = "grace-meditation"

    fun status(context: Context): MeditationStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("active", false)) return MeditationStatus()
        val paused = prefs.getBoolean("paused", false)
        val seconds = if (paused) prefs.getInt("paused-seconds", 0) else
            ceil((prefs.getLong("end-at", 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L) / 1000.0).toInt()
        return MeditationStatus(true, paused, seconds, prefs.getInt("minutes", 0), prefs.getString("sound-name", "Silence") ?: "Silence")
    }

    fun start(context: Context, minutes: Int, soundAsset: String?, soundName: String) {
        ContextCompat.startForegroundService(context, Intent(context, MeditationService::class.java).apply {
            action = ACTION_START
            putExtra("minutes", minutes); putExtra("asset", soundAsset); putExtra("sound-name", soundName)
        })
    }

    fun send(context: Context, action: String) = context.startService(Intent(context, MeditationService::class.java).setAction(action))
}

class MeditationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var nextPlayer: MediaPlayer? = null
    private var loopAsset: String? = null
    private val tick = object : Runnable { override fun run() { publish(); if (MeditationSession.status(this@MeditationService).secondsLeft <= 0) finishPractice() else handler.postDelayed(this, 1000L) } }
    private val prefs by lazy { getSharedPreferences("grace-meditation", Context.MODE_PRIVATE) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MeditationSession.ACTION_START -> start(intent)
            MeditationSession.ACTION_PAUSE -> pause()
            MeditationSession.ACTION_RESUME -> resume()
            MeditationSession.ACTION_END -> stopPractice()
            else -> if (MeditationSession.status(this).active) resumeRunningSession()
        }
        return START_STICKY
    }

    private fun start(intent: Intent) {
        val minutes = intent.getIntExtra("minutes", 10)
        val asset = intent.getStringExtra("asset")
        prefs.edit().clear().putBoolean("active", true).putBoolean("paused", false)
            .putInt("minutes", minutes).putString("asset", asset).putString("sound-name", intent.getStringExtra("sound-name") ?: "Silence")
            .putLong("end-at", SystemClock.elapsedRealtime() + minutes * 60_000L).apply()
        openAudio(asset); startForeground(903, notification()); handler.removeCallbacks(tick); handler.post(tick)
    }

    private fun pause() {
        val status = MeditationSession.status(this); if (!status.active || status.paused) return
        prefs.edit().putBoolean("paused", true).putInt("paused-seconds", status.secondsLeft).apply()
        player?.pause(); handler.removeCallbacks(tick); publish()
    }

    private fun resume() {
        val status = MeditationSession.status(this); if (!status.active || !status.paused) return
        prefs.edit().putBoolean("paused", false).putLong("end-at", SystemClock.elapsedRealtime() + status.secondsLeft * 1000L).apply()
        if (player == null) openAudio(prefs.getString("asset", null)) else player?.start()
        handler.removeCallbacks(tick); handler.post(tick)
    }

    private fun resumeRunningSession() {
        val status = MeditationSession.status(this)
        if (!status.active) return
        if (status.paused) { startForeground(903, notification()); publish() } else {
            openAudio(prefs.getString("asset", null)); startForeground(903, notification()); handler.post(tick)
        }
    }

    private fun openAudio(asset: String?) {
        releaseAudio()
        asset ?: return
        val first = createPlayer(asset)
        val second = createPlayer(asset)

        // MediaPlayer.isLooping tears down and recreates the decoder at the
        // boundary. Keeping a prepared successor lets Android hand off to the
        // next copy directly, avoiding the audible rebuffer gap. The successor
        // is re-armed on every completion; setNextMediaPlayer is one-shot and
        // must not be wired as a finite two-player cycle.
        first.setNextMediaPlayer(second)
        first.setOnCompletionListener { completed -> advanceLoop(completed) }
        second.setOnCompletionListener { completed -> advanceLoop(completed) }
        loopAsset = asset
        player = first
        nextPlayer = second
        first.start()
    }

    private fun createPlayer(asset: String): MediaPlayer = MediaPlayer().also { mediaPlayer ->
        assets.openFd(asset).use { afd ->
            mediaPlayer.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        }
        mediaPlayer.prepare()
    }

    private fun advanceLoop(completed: MediaPlayer) {
        if (player !== completed) return
        val successor = nextPlayer ?: return
        val asset = loopAsset ?: return
        runCatching {
            // The successor is already playing. Prepare a new successor and
            // attach it before the current successor reaches its end.
            val replacement = createPlayer(asset)
            successor.setNextMediaPlayer(replacement)
            player = successor
            nextPlayer = replacement
            completed.release()
        }.onFailure { error ->
            // Keep the ambient sound alive if a rare decoder allocation fails;
            // this fallback may have a boundary gap but must not go silent.
            Log.e("GraceMeditation", "Could not prepare next ambience loop", error)
            successor.isLooping = true
            player = successor
            nextPlayer = null
        }
    }

    private fun releaseAudio() {
        val current = player
        val successor = nextPlayer
        player = null
        nextPlayer = null
        loopAsset = null
        current?.release()
        if (successor !== current) successor?.release()
    }

    private fun finishPractice() {
        val status = MeditationSession.status(this)
        if (status.minutes > 0) {
            val store = GraceStore(this); val day = store.day()
            store.save(if (LocalTime.now().hour >= 16) day.copy(eveningMeditationMinutes = day.eveningMeditationMinutes + status.minutes) else day.copy(morningMeditationMinutes = day.morningMeditationMinutes + status.minutes))
        }
        stopPractice()
    }

    private fun stopPractice() {
        handler.removeCallbacks(tick); releaseAudio(); prefs.edit().clear().apply(); notifyStatus()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun publish() { startForeground(903, notification()); notifyStatus() }
    private fun notifyStatus() { sendBroadcast(Intent(MeditationSession.ACTION_STATUS).setPackage(packageName)) }

    private fun notification(): android.app.Notification {
        val channel = "grace-meditation"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, "Meditation in progress", NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC })
        val status = MeditationSession.status(this)
        val display = if (status.paused) "Paused • ${formatTime(status.secondsLeft)} left" else "${formatTime(status.secondsLeft)} remaining • ${status.soundName}"
        val open = PendingIntent.getActivity(this, 903, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val controlAction = if (status.paused) MeditationSession.ACTION_RESUME else MeditationSession.ACTION_PAUSE
        val controlLabel = if (status.paused) "Resume" else "Pause"
        fun command(code: Int, action: String) = PendingIntent.getService(this, code, Intent(this, MeditationService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channel).setSmallIcon(R.drawable.ic_grace_launcher).setContentTitle("Grace meditation").setContentText(display)
            .setContentIntent(open).setOngoing(true).setCategory(NotificationCompat.CATEGORY_TRANSPORT).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, controlLabel, command(904, controlAction)).addAction(0, "End", command(905, MeditationSession.ACTION_END)).build()
    }

    private fun formatTime(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { handler.removeCallbacks(tick); releaseAudio(); super.onDestroy() }
}
