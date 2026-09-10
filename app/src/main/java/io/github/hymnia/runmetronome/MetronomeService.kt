package io.github.hymnia.runmetronome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.Executors

/**
 * 前台服务：保证锁屏 / 切后台时节拍不断。
 *
 * 职责：
 * - 节拍以"长 PCM 无缝循环"方式播放（见 [MetronomePlayer]），无逐拍调度抖动；
 * - 运行期支持实时改步频 / 音色 / 音量 / 倒计时（ACTION_UPDATE）；
 * - 倒计时暂停时同步停走，每 15 分钟报时，到点提示并发出可清除的结束通知；
 * - 状态通过 [PlaybackStore] 回传 UI，避免界面与实际播放脱节。
 */
class MetronomeService : Service() {

    companion object {
        private const val CHANNEL_ONGOING = "run_metronome"
        private const val CHANNEL_ALERT = "run_metronome_alert"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_DONE = 2
        private const val ANNOUNCE_INTERVAL_SEC = 15 * 60

        const val ACTION_START = "io.github.hymnia.runmetronome.START"
        const val ACTION_STOP = "io.github.hymnia.runmetronome.STOP"
        const val ACTION_PAUSE = "io.github.hymnia.runmetronome.PAUSE"
        const val ACTION_RESUME = "io.github.hymnia.runmetronome.RESUME"
        const val ACTION_UPDATE = "io.github.hymnia.runmetronome.UPDATE"
        const val EXTRA_BPM = "bpm"
        const val EXTRA_TONE = "tone"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_TIMEOUT_MIN = "timeout_min"

        /** 服务是否处于运行态；UI 据此决定用 startService 还是 startForegroundService。 */
        fun isRunning(): Boolean = PlaybackStore.state.running
    }

    private lateinit var player: MetronomePlayer
    private val mainHandler = Handler(Looper.getMainLooper())
    private val buildExecutor = Executors.newSingleThreadExecutor()

    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false

    /** 每次重建 PCM 自增，用于作废在途的旧构建结果，避免快速调参时竞态。 */
    private var buildGeneration = 0

    private var running = false
    private var paused = false
    private var bpm = DEFAULT_BPM
    private var tone = Tone.BUBBLE1
    private var volume = 1f
    private var timeoutMin = 0
    private var elapsedSec = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!running || paused) return
            elapsedSec++
            val remaining = if (timeoutMin > 0) timeoutMin * 60 - elapsedSec else -1
            if (timeoutMin > 0 && remaining <= 0) {
                finishTraining()
                return
            }
            if (timeoutMin > 0 && remaining > 0 && remaining % ANNOUNCE_INTERVAL_SEC == 0) {
                player.beep(Tone.BUBBLE3, volume, repeats = 2)
            }
            if (elapsedSec % 60 == 0) updateOngoingNotification()
            PlaybackStore.update { it.copy(elapsedSec = elapsedSec, remainingSec = remaining) }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = MetronomePlayer(this)
        ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTraining(intent)
            ACTION_STOP -> if (alive()) stopTraining(notifyDone = false) else stopSelf()
            ACTION_PAUSE -> if (alive()) pauseTraining() else stopSelf()
            ACTION_RESUME -> if (alive()) resumeTraining() else stopSelf()
            ACTION_UPDATE -> if (alive()) applyUpdate(intent) else stopSelf()
            else -> if (!alive()) stopSelf()
        }
        return START_NOT_STICKY
    }

    /**
     * 是否处于可执行命令的运行态。
     * 非 START 命令若服务其实没在运行，必须立刻停掉自己：一旦被
     * startForegroundService() 拉起却不在 5 秒内调用 startForeground()，
     * Android 8+ 会抛 ForegroundServiceDidNotStartInTimeException 直接崩溃。
     */
    private fun alive(): Boolean = running && foregroundStarted

    // —— 训练生命周期 ——

    private fun startTraining(intent: Intent) {
        bpm = intent.getIntExtra(EXTRA_BPM, DEFAULT_BPM).coerceIn(BPM_MIN, BPM_MAX)
        tone = parseTone(intent.getStringExtra(EXTRA_TONE), Tone.BUBBLE1)
        volume = intent.getFloatExtra(EXTRA_VOLUME, 1f).coerceIn(0f, 1f)
        timeoutMin = intent.getIntExtra(EXTRA_TIMEOUT_MIN, 0).coerceIn(0, TIMEOUT_MAX)
        elapsedSec = 0
        paused = false
        running = true

        startForeground(NOTIF_ONGOING, buildOngoingNotification())
        foregroundStarted = true
        acquireWakeLock()
        rebuildTrack(restartTimer = true)
    }

    /**
     * 后台构建 PCM，回到主线程起音轨并热切换。
     * 构建 10 秒级 PCM 需要解码 + 逐采样烘焙，放在主线程会造成明显掉帧。
     */
    private fun rebuildTrack(restartTimer: Boolean) {
        val gen = ++buildGeneration
        val reqBpm = bpm
        val reqTone = tone
        val reqVolume = volume
        buildExecutor.execute {
            val pcm = runCatching { player.buildPcm(reqBpm, reqTone) }.getOrNull()
            mainHandler.post {
                if (gen != buildGeneration) return@post
                if (pcm == null) {
                    PlaybackStore.update { it.copy(error = "音频数据构建失败，请重试或换一个音色") }
                    stopTraining(notifyDone = false)
                    return@post
                }
                val failure = player.play(pcm, reqVolume)
                if (failure != null) {
                    PlaybackStore.update { it.copy(error = failure) }
                    stopTraining(notifyDone = false)
                    return@post
                }
                if (paused) player.pause() else if (restartTimer) startTicker()
                PlaybackStore.update {
                    it.copy(
                        running = true,
                        paused = paused,
                        bpm = reqBpm,
                        tone = reqTone,
                        timeoutMin = timeoutMin,
                        elapsedSec = elapsedSec,
                        remainingSec = remainingSec(),
                        error = null,
                    )
                }
                updateOngoingNotification()
            }
        }
    }

    private fun pauseTraining() {
        if (paused) return
        paused = true
        player.pause()
        mainHandler.removeCallbacks(ticker)
        releaseWakeLock()
        PlaybackStore.update { it.copy(paused = true) }
        updateOngoingNotification()
    }

    private fun resumeTraining() {
        if (!paused) return
        paused = false
        player.resume()
        acquireWakeLock()
        startTicker()
        PlaybackStore.update { it.copy(paused = false) }
        updateOngoingNotification()
    }

    /**
     * 运行中热更新参数。只处理真正带上的字段：
     * 音量即时生效（只改音轨增益），步频/音色才重建音轨，倒计时按已跑时长重算剩余。
     */
    private fun applyUpdate(intent: Intent) {
        var needRebuild = false

        if (intent.hasExtra(EXTRA_BPM)) {
            val newBpm = intent.getIntExtra(EXTRA_BPM, bpm).coerceIn(BPM_MIN, BPM_MAX)
            if (newBpm != bpm) {
                bpm = newBpm
                needRebuild = true
            }
        }
        if (intent.hasExtra(EXTRA_TONE)) {
            val newTone = parseTone(intent.getStringExtra(EXTRA_TONE), tone)
            if (newTone != tone) {
                tone = newTone
                needRebuild = true
            }
        }
        if (intent.hasExtra(EXTRA_VOLUME)) {
            val newVolume = intent.getFloatExtra(EXTRA_VOLUME, volume).coerceIn(0f, 1f)
            if (newVolume != volume) {
                volume = newVolume
                player.setVolume(volume)
            }
        }
        if (intent.hasExtra(EXTRA_TIMEOUT_MIN)) {
            val newTimeout = intent.getIntExtra(EXTRA_TIMEOUT_MIN, timeoutMin).coerceIn(0, TIMEOUT_MAX)
            if (newTimeout != timeoutMin) {
                timeoutMin = newTimeout
                PlaybackStore.update { it.copy(timeoutMin = timeoutMin, remainingSec = remainingSec()) }
            }
        }

        if (needRebuild) rebuildTrack(restartTimer = false) else updateOngoingNotification()
    }

    private fun stopTraining(notifyDone: Boolean) {
        running = false
        paused = false
        buildGeneration++
        mainHandler.removeCallbacks(ticker)
        player.stopTrack()
        releaseWakeLock()
        elapsedSec = 0
        PlaybackStore.update {
            it.copy(running = false, paused = false, elapsedSec = 0, remainingSec = -1)
        }
        if (notifyDone) notifyFinished()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    /** 倒计时到点：先提示音（走 SoundPool，不受音轨释放影响），再收尾并发出结束通知。 */
    private fun finishTraining() {
        mainHandler.removeCallbacks(ticker)
        player.stopTrack()
        player.beep(Tone.BUBBLE1, volume, repeats = 3)
        running = false
        paused = false
        buildGeneration++
        releaseWakeLock()
        elapsedSec = 0
        PlaybackStore.update {
            it.copy(running = false, paused = false, elapsedSec = 0, remainingSec = -1)
        }
        notifyFinished()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.postDelayed(ticker, 1000)
    }

    private fun remainingSec(): Int =
        if (timeoutMin > 0) (timeoutMin * 60 - elapsedSec).coerceAtLeast(0) else -1

    private fun parseTone(name: String?, fallback: Tone): Tone =
        if (name == null) fallback else runCatching { Tone.valueOf(name) }.getOrDefault(fallback)

    // —— 唤醒锁 ——

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RunMetronome::beat").apply {
            setReferenceCounted(false)
            runCatching { acquire(6 * 60 * 60 * 1000L) }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    // —— 通知 ——

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "节拍器运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "锁屏 / 后台播放时的常驻通知"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "训练提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "倒计时结束等提醒"
            }
        )
    }

    private fun buildOngoingNotification(): Notification {
        val prefix = if (paused) "已暂停" else "节拍进行中"
        val text = if (timeoutMin > 0) {
            "$prefix · $bpm BPM · 剩余 ${formatClock(remainingSec())}"
        } else {
            "$prefix · $bpm BPM"
        }
        return Notification.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("Runner Metronome")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(activityPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(
                notifAction(
                    if (paused) "继续" else "暂停",
                    if (paused) ACTION_RESUME else ACTION_PAUSE,
                    1
                )
            )
            .addAction(notifAction("停止", ACTION_STOP, 2))
            .build()
    }

    private fun notifAction(title: String, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            null as android.graphics.drawable.Icon?,
            title,
            servicePendingIntent(action, requestCode)
        ).build()

    private fun updateOngoingNotification() {
        if (!foregroundStarted) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ONGOING, buildOngoingNotification())
        }
    }

    /** 训练结束通知：独立渠道 + 可划除，避免和常驻通知同 ID 被一起移除而看不到。 */
    private fun notifyFinished() {
        val n = Notification.Builder(this, CHANNEL_ALERT)
            .setContentTitle("训练结束")
            .setContentText("倒计时已完成，节拍已停止")
            .setSmallIcon(R.drawable.ic_stop)
            .setContentIntent(activityPendingIntent())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n) }
    }

    private fun activityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, MetronomeService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        player.stopTrack()
        releaseWakeLock()
        buildExecutor.shutdownNow()
        // 延迟释放 SoundPool，让到点提示音能完整播完
        mainHandler.postDelayed({ player.release() }, 5000)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
