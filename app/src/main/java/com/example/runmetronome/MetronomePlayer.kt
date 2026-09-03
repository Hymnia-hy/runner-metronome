package com.example.runmetronome

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * 节拍音播放器：用 SoundPool 播放 raw 里的柔和音效。
 * 重要：SoundPool.load() 返回"音效ID(soundID)"，play() 必须用该 soundID，而非资源ID(resId)。
 * 支持「轮换」：rotation=true 时按拍序在柔和音色间交替，降低长时间(马拉松)重复的单调/疲劳。
 * 用 USAGE_MEDIA 且不请求音频焦点 → 与音乐共存、不打断。
 */
class MetronomePlayer(private val context: Context) {

    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<Tone, Int>()
    private var volume = 0.8f
    private var currentTone: Tone = Tone.BUBBLE1
    private var rotation = false
    private val tones: List<Tone> = Tone.entries

    fun prepare(tone: Tone, volume: Float, rotation: Boolean) {
        this.volume = volume.coerceIn(0f, 1f)
        currentTone = tone
        this.rotation = rotation
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

    /** 触发一拍。rotation 时按拍序轮换音色，否则固定当前音色。 */
    fun click(beatIndex: Int) {
        val sp = soundPool ?: return
        val tone = if (rotation) tones[Math.floorMod(beatIndex, tones.size)] else currentTone
        val id = soundIds[tone] ?: return
        sp.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        soundIds.clear()
    }
}
