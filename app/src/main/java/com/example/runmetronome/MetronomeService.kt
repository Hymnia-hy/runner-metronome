package com.example.runmetronome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * 前台服务：保证锁屏 / 切后台时节拍不断。
 * 节拍以"长 PCM 无缝循环"方式播放（见 MetronomePlayer），无逐拍调度抖动、间隔绝对一致。
 */
class MetronomeService : Service() {

    companion object {
        private const val CHANNEL_ID = "run_metronome"
        private const val NOTIF_ID = 1
        const val ACTION_START = "com.example.runmetronome.START"
        const val ACTION_STOP = "com.example.runmetronome.STOP"
        const val EXTRA_BPM = "bpm"
        const val EXTRA_TONE = "tone"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_TIMEOUT_MIN = "timeout_min"
    }

    private lateinit var player: MetronomePlayer
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var timeoutRunnable: Runnable? = null
    private var tone: Tone = Tone.BUBBLE1

    override fun onCreate() {
        super.onCreate()
        player = MetronomePlayer(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> startMetronome(intent)
            else -> {
                // 系统重建导致的重启，保持运行基数
            }
        }
        return START_STICKY
    }

    private fun startMetronome(intent: Intent) {
        val bpm = intent.getDoubleExtra(EXTRA_BPM, 180.0)
        val volume = intent.getFloatExtra(EXTRA_VOLUME, 0.8f)
        val timeoutMin = intent.getIntExtra(EXTRA_TIMEOUT_MIN, 0)
        val toneName = intent.getStringExtra(EXTRA_TONE) ?: Tone.BUBBLE1.name

        startForeground(NOTIF_ID, buildNotification("节拍进行中 · ${bpm.toInt()} BPM"))

        tone = runCatching { Tone.valueOf(toneName) }.getOrDefault(Tone.BUBBLE1)
        // 生成该 bpm 的"节拍长文件"并开始无缝循环播放（节拍点固定、零抖动）
        player.prepare(bpm, tone, volume)
        acquireWakeLock()

        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        if (timeoutMin > 0) {
            val r = Runnable {
                stopEverything()
                notifyFinished()
                stopSelf()
            }
            timeoutRunnable = r
            mainHandler.postDelayed(r, timeoutMin * 60_000L)
        }
    }

    private fun stopEverything() {
        player.release()
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunMetronome::beat").apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notifyFinished() {
        // 到点提示音（一次性柔和音）
        runCatching {
            val tmp = MetronomePlayer(this)
            tmp.playBeep(Tone.BUBBLE1, 1f)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification("训练结束"))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "节拍器", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            nm.createNotificationChannel(channel)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Runner Metronome")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
