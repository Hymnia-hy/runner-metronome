package com.example.runmetronome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 音频播放层。
 * - 节拍：MODE_STATIC 长 PCM 无缝循环，节拍点已在采样层面固定，零调度抖动。
 * - 试听/报时/到点提示：SoundPool 短音，避免每次新建 AudioTrack 的开销。
 * - 所有音色先做峰值归一化再统一增益，响度一致且不削波。
 * - USAGE_MEDIA 且不请求音频焦点 → 与 QQ音乐 / 喜马拉雅 共存，互不打断。
 */
class MetronomePlayer(private val context: Context) {

    private var track: AudioTrack? = null
    private var soundPool: SoundPool? = null
    private val beepIds = mutableMapOf<Tone, Int>()
    private val handler = Handler(Looper.getMainLooper())

    // —— 节拍音轨 ——

    /**
     * 构建可循环播放的音轨。**必须在后台线程调用**
     * （解码 + 烘焙 + 写入 MB 级 PCM，主线程执行会掉帧）。
     * 失败返回 null：设备不支持该采样率、缓冲分配失败等，由调用方提示用户。
     */
    fun buildTrack(bpm: Int, tone: Tone, volume: Float): AudioTrack? {
        val raw = runCatching { BeatPcmBuilder.loadRawPcm(context, tone.resId) }.getOrNull()
        if (raw == null || raw.isEmpty()) {
            Log.e(TAG, "音色资源为空: $tone")
            return null
        }
        val pcm = applyGain(
            BeatPcmBuilder.build(bpm.toDouble(), normalize(raw, NORM_PEAK)),
            MASTER_GAIN_RATIO,
        )
        if (pcm.isEmpty()) return null

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
        )
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack 创建失败", e)
            return null
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack 未初始化，state=${t.state}")
            runCatching { t.release() }
            return null
        }
        val written = t.write(pcm, 0, pcm.size)
        if (written <= 0) {
            Log.e(TAG, "AudioTrack.write 失败: $written")
            runCatching { t.release() }
            return null
        }
        t.setVolume(volume.coerceIn(0f, 1f))
        t.setLoopPoints(0, pcm.size, -1)
        return t
    }

    /** 主线程：切换到新音轨并开始播放（旧音轨先停后释，避免两轨重叠出现"双响"）。 */
    fun play(newTrack: AudioTrack, volume: Float) {
        val old = track
        track = newTrack
        runCatching {
            old?.stop()
            old?.release()
        }
        newTrack.setVolume(volume.coerceIn(0f, 1f))
        runCatching { newTrack.play() }
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

    /** 停止并释放当前节拍音轨（SoundPool 保持可用，便于紧接着播提示音）。 */
    fun stopTrack() {
        val t = track
        track = null
        runCatching {
            t?.stop()
            t?.release()
        }
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
    }
}
