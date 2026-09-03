package com.example.runmetronome

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * 节拍音播放器：用 SoundPool 播放 raw 里的真实音效。
 * 关键：AudioAttributes 用 USAGE_MEDIA（媒体流），且【不请求音频焦点】，
 * 这样节拍器与 QQ音乐 / 喜马拉雅处于同一混音环境，两者同时发声、互不打断。
 * 采用均匀节拍（每拍同一音色，无重音）。
 */
class MetronomePlayer(private val context: Context) {

    private var soundPool: SoundPool? = null
    private var loaded = false
    private var volume = 0.8f
    private var currentTone: Tone = Tone.PLUCK

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
            sp.setOnLoadCompleteListener { _, _, status -> if (status == 0) loaded = true }
            Tone.entries.forEach { sp.load(context, it.resId, 1) }
            soundPool = sp
        }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
    }

    /** 触发一拍（均匀：所有拍同一音色）。 */
    fun click() {
        val sp = soundPool ?: return
        // 首次可能尚未加载完，等待后续拍即可
        sp.play(currentTone.resId, volume, volume, 1, 0, 1f)
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        loaded = false
    }
}
