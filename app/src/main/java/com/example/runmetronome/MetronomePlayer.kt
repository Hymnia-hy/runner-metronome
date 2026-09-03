package com.example.runmetronome

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * 节拍音播放器：用 SoundPool 播放 raw 里的真实音效。
 * 重要：SoundPool.load() 返回的是"音效ID(soundID)"，play() 必须用该 soundID，而不是资源ID(resId)。
 * 关键：AudioAttributes 用 USAGE_MEDIA（媒体流），且【不请求音频焦点】，与音乐共存互不打断。
 */
class MetronomePlayer(private val context: Context) {

    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<Tone, Int>()
    private var volume = 0.8f
    private var currentTone: Tone = Tone.BUBBLE1

    fun prepare(tone: Tone, volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        currentTone = tone
        if (soundPool == null) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val sp = SoundPool.Builder()
                .setAudioAttributes(attrs)
                .setMaxStreams(3)
                .build()
            Tone.entries.forEach { soundIds[it] = sp.load(context, it.resId, 1) }
            soundPool = sp
        }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    /** 触发一拍（均匀：所有拍同一音色）。用已加载的 soundID 播放。 */
    fun click() {
        val sp = soundPool ?: return
        val id = soundIds[currentTone] ?: return
        sp.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        soundIds.clear()
    }
}
