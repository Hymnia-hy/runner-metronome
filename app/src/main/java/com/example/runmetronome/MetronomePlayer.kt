package com.example.runmetronome

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 用 AudioTrack(MODE_STATIC) 无缝循环播放"节拍长 PCM"。
 * 节拍点已在音频采样层面固定，播放时零调度抖动、间隔绝对一致。
 * USAGE_MEDIA 且不请求音频焦点 → 与音乐共存、不打断。
 */
class MetronomePlayer(private val context: Context) {

    private var track: AudioTrack? = null

    /** 生成节拍长文件并开始无缝循环播放。 */
    fun prepare(bpm: Double, tone: Tone, volume: Float) {
        release()
        val tonePcm = BeatPcmBuilder.loadRawPcm(context, tone.resId)
        if (tonePcm.isEmpty()) return
        val pcm = BeatPcmBuilder.build(bpm, tonePcm, seconds = 120)
        if (pcm.isEmpty()) return

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
        // 无尽循环；循环点(0 与 pcm.size)落在静音段，两端衔接无爆音
        t.setLoopPoints(0, pcm.size, -1)
        t.play()
        track = t
    }

    fun setVolume(v: Float) {
        track?.setVolume(v.coerceIn(0f, 1f))
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

    /** 一次性播放单音（用于"倒计时到点提示"等）。 */
    fun playBeep(tone: Tone, volume: Float) {
        val tonePcm = BeatPcmBuilder.loadRawPcm(context, tone.resId)
        if (tonePcm.isEmpty()) return
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
            .setBufferSizeInBytes(maxOf(minBuf, tonePcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(tonePcm, 0, tonePcm.size)
        t.setVolume(volume.coerceIn(0f, 1f))
        t.play()
        Thread.sleep((tonePcm.size * 1000L) / BeatPcmBuilder.SR)
        t.stop()
        t.release()
    }
}
