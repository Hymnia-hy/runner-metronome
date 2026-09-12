package io.github.hymnia.runmetronome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * 音频播放层。
 *
 * 节拍用 MODE_STREAM + 常驻写入线程把 PCM 首尾相接连续喂给音轨：
 * - MODE_STATIC 需要一次性分配整段 PCM 的共享缓冲，Android 上存在约 1MB 的实现上限
 *   （部分设备更低），10 秒循环即 960KB，实测在该限制边缘会创建失败；
 * - 流式写入没有这个限制（缓冲只需几十 KB），且数据首尾相接即天然无缝循环。
 *
 * 试听 / 报时 / 到点提示走 SoundPool，与节拍同一媒体通道，响度观感一致。
 * 所有音色先做峰值归一化再统一增益，响度一致且不削波。
 * USAGE_MEDIA 且不请求音频焦点 → 与 QQ音乐 / 喜马拉雅 共存，互不打断。
 */
class MetronomePlayer(private val context: Context) {

    private var track: AudioTrack? = null

    @Volatile
    private var streaming = false
    private var writer: Thread? = null

    private var soundPool: SoundPool? = null
    private val beepIds = mutableMapOf<Tone, Int>()
    private val handler = Handler(Looper.getMainLooper())

    // —— 节拍 ——

    /** 后台线程构建循环 PCM（解码 + 逐采样烘焙），失败返回 null。 */
    fun buildPcm(bpm: Int, tone: Tone): ShortArray? {
        val raw = runCatching { BeatPcmBuilder.loadRawPcm(context, tone.resId) }.getOrNull()
        if (raw == null || raw.isEmpty()) {
            Log.e(TAG, "音色资源为空: $tone")
            return null
        }
        val pcm = applyGain(
            BeatPcmBuilder.build(bpm.toDouble(), normalize(raw, NORM_PEAK)),
            MASTER_GAIN_RATIO,
        )
        return if (pcm.isEmpty()) null else pcm
    }

    /**
     * 主线程：开始流式循环播放，返回错误描述（null 表示成功）。
     * 调用方把错误直接展示给用户，避免"点了没反应也说不清原因"。
     */
    fun play(pcm: ShortArray, volume: Float): String? {
        stopTrack()

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(BeatPcmBuilder.SR)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(
            BeatPcmBuilder.SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).let { if (it > 0) it else BeatPcmBuilder.SR }
        val bufferBytes = maxOf(minBuf * 4, MIN_BUFFER_BYTES)

        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack 创建失败", e)
            return "创建音轨失败：${e.message ?: e.javaClass.simpleName}"
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack 未初始化 state=${t.state}")
            runCatching { t.release() }
            return "音轨未初始化（state=${t.state}）"
        }
        runCatching { t.setVolume(volume.coerceIn(0f, 1f)) }
        runCatching { t.play() }
        track = t
        startWriter(t, pcm)
        return null
    }

    /** 常驻写入线程：循环喂 PCM。write 在缓冲满时阻塞，暂停时自然挂起。 */
    private fun startWriter(t: AudioTrack, pcm: ShortArray) {
        streaming = true
        writer = Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            var offset = 0
            while (streaming) {
                val count = minOf(WRITE_CHUNK_FRAMES, pcm.size - offset)
                val written = try {
                    t.write(pcm, offset, count)
                } catch (e: Exception) {
                    -1
                }
                if (written < 0) break
                if (written == 0) {
                    runCatching { Thread.sleep(5) }
                    continue
                }
                offset += written
                if (offset >= pcm.size) offset = 0
            }
        }, "metronome-writer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun setVolume(v: Float) {
        runCatching { track?.setVolume(v.coerceIn(0f, 1f)) }
    }

    fun pause() {
        runCatching { track?.pause() }
    }

    fun resume() {
        runCatching { track?.play() }
    }

    /** 停止并释放节拍音轨（SoundPool 保持可用，便于紧接着播提示音）。 */
    fun stopTrack() {
        streaming = false
        val t = track
        track = null
        // stop() 会唤醒阻塞中的 write()，让写入线程尽快退出
        runCatching { t?.pause() }
        runCatching { t?.stop() }
        writer?.let { runCatching { it.join(500) } }
        writer = null
        runCatching { t?.flush() }
        runCatching { t?.release() }
    }

    /** 释放全部资源（含 SoundPool），并取消未执行的提示音。 */
    fun release() {
        stopTrack()
        handler.removeCallbacksAndMessages(null)
        val sp = soundPool
        soundPool = null
        beepIds.clear()
        runCatching { sp?.release() }
    }

    // —— 短音（试听 / 提示） ——

    /**
     * 预热 SoundPool。SoundPool.load() 是异步解码，而它是懒加载的：
     * 不预热的话，用户第一次点音色卡片时样本往往还没就绪，表现为"第一次点没声音"。
     * 界面进入时就调用，正常操作节奏下早已加载完毕。
     */
    fun preload() {
        soundPool()
    }

    /** 音色卡片试听：与节拍同一媒体通道，响度观感一致。 */
    fun preview(tone: Tone, volume: Float) = playShort(tone, volume)

    /** 报时 / 到点提示音，可重复若干次以在跑步中更容易被注意到。 */
    fun beep(tone: Tone, volume: Float, repeats: Int = 1, intervalMs: Long = 320) {
        repeat(repeats.coerceIn(1, 5)) { i ->
            if (i == 0) playShort(tone, volume)
            else handler.postDelayed({ playShort(tone, volume) }, i * intervalMs)
        }
    }

    private fun playShort(tone: Tone, volume: Float) {
        val sp = soundPool ?: return
        val id = beepIds[tone] ?: return
        val v = volume.coerceIn(0f, 1f)
        runCatching { sp.play(id, v, v, 1, 0, 1f) }
    }

    private fun soundPool(): SoundPool? {
        soundPool?.let { return it }
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val sp = SoundPool.Builder().setAudioAttributes(attrs).setMaxStreams(3).build()
            Tone.entries.forEach { beepIds[it] = sp.load(context, it.resId, 1) }
            soundPool = sp
            sp
        } catch (e: Exception) {
            Log.e(TAG, "SoundPool 创建失败", e)
            null
        }
    }

    // —— 电平处理 ——

    /** 峰值归一化到目标电平，使各音色响度一致，避免放大后削波。 */
    private fun normalize(pcm: ShortArray, targetPeak: Int): ShortArray {
        val peak = (pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0).coerceAtLeast(1)
        val scale = targetPeak.toDouble() / peak
        if (scale >= 0.99 && scale <= 1.01) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i] * scale).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    /** 响度增益（放大一倍），限幅防止溢出失真。 */
    private fun applyGain(pcm: ShortArray, gain: Float): ShortArray {
        if (gain <= 1f) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i] * gain).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    companion object {
        private const val TAG = "MetronomePlayer"
        private const val MASTER_GAIN_RATIO = 2.0f
        private const val NORM_PEAK = 13000

        /** 单次写入帧数（≈85ms @48kHz）。 */
        private const val WRITE_CHUNK_FRAMES = 4096

        /** 流式缓冲下限，约 341ms，足以吸收线程调度抖动避免 underrun。 */
        private const val MIN_BUFFER_BYTES = 32 * 1024
    }
}
