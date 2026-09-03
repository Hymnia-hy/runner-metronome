package com.example.runmetronome

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * 节拍引擎。
 * 关键：不依赖周期性 postDelayed 的累积，而是基于绝对时钟(SystemClock.elapsedRealtime())
 * 计算每一拍的“目标时间”，长期不会漂移。短时抖动由系统调度决定，可接受。
 */
class MetronomeEngine(
    private val onBeat: (beatIndex: Int, isAccent: Boolean) -> Unit
) {

    @Volatile private var running = false
    @Volatile private var bpm = 180.0
    @Volatile private var accentEvery = 4

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var startBase = 0L
    private var targetMs = 0L
    private var beatIndex = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            // 兜底：若 session 卡顿导致目标时间已过很远，跳到当前，避免爆发式追拍
            if (targetMs < now - 200) targetMs = now
            onBeat(beatIndex, accentEvery > 0 && beatIndex % accentEvery == 0)
            beatIndex++
            targetMs = TempoMath.beatTimeMs(startBase, beatIndex, bpm)
            val delay = (targetMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            handler?.postDelayed(this, delay)
        }
    }

    fun start() {
        if (running) return
        running = true
        beatIndex = 0
        val t = HandlerThread("metronome")
        t.start()
        thread = t
        handler = Handler(t.looper)
        startBase = SystemClock.elapsedRealtime()
        targetMs = startBase
        handler?.post(ticker)
    }

    fun stop() {
        running = false
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun setBpm(value: Double) {
        bpm = value.coerceIn(30.0, 300.0)
    }

    fun setAccentEvery(value: Int) {
        accentEvery = value
    }

    val isRunning: Boolean get() = running
}
