package com.example.runmetronome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 用 AudioTrack(MODE_STATIC) 无缝循环播放"节拍长 PCM"。
 * 节拍点已在音频采样层面固定，播放时零调度抖动、间隔绝对一致。
 * 支持：响度增益放大、音量实时调整、暂停/继续。
 * USAGE_MEDIA 且不请求音频焦点 → 与音乐共存、不打断。
 */
class MetronomePlayer(private val context: Context) {

    private var track: AudioTrack? = null

    fun prepare(bpm: Double, tone: Tone, volume: Float) {
        release()
        val tonePcm = BeatPcmBuilder.loadRawPcm(context, tone.resId)
        if (tonePcm.isEmpty()) return
        val pcm0 = BeatPcmBuilder.build(bpm, tonePcm, seconds = 120)
        if (pcm0.isEmpty()) return
        val pcm = applyGain(pcm0, MASTER_GAIN_RATIO) // 响度放大一倍

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
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setVolume(volume.coerceIn(0f, 1f))
        t.setLoopPoints(0, pcm.size, -1)
        t.play()
        track = t
    }

    fun setVolume(v: Float) {
        track?.setVolume(v.coerceIn(0f, 1f))
    }

    fun pause() {
        try { track?.pause() } catch (_: Exception) {}
    }

    fun resume() {
        try { track?.play() } catch (_: Exception) {}
    }

    fun stop() {
        try {
            track?.stop()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }

    fun release() = stop()

    /** 一次性播放单音（用于报时/倒计时到点）。在后台线程调用，避免阻塞。 */
    fun playBeep(tone: Tone, volume: Float) {
        val tonePcm = BeatPcmBuilder.loadRawPcm(context, tone.resId)
        if (tonePcm.isEmpty()) return
        val pcm = applyGain(tonePcm, MASTER_GAIN_RATIO)
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
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(maxOf(minBuf, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setVolume(volume.coerceIn(0f, 1f))
        t.play()
        Thread.sleep((pcm.size * 1000L) / BeatPcmBuilder.SR)
        t.stop()
        t.release()
    }

    /** 响度增益（放大一倍），限幅防止溢出失真。 */
    private fun applyGain(pcm: ShortArray, gain: Float): ShortArray {
        if (gain <= 1f) return pcm
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val v = (pcm[i] * gain).toInt()
            out[i] = v.coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    companion object {
        private const val MASTER_GAIN_RATIO = 2.0f
    }
}
