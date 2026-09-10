package io.github.hymnia.runmetronome

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户设置持久化：步频 / 音量 / 音色 / 倒计时。
 * 旧实现每次冷启动都回到默认 180 BPM，跑者每次都得重新调。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("run_metronome_settings", Context.MODE_PRIVATE)

    var bpm: Int
        get() = prefs.getInt(KEY_BPM, DEFAULT_BPM).coerceIn(BPM_MIN, BPM_MAX)
        set(value) = prefs.edit().putInt(KEY_BPM, value.coerceIn(BPM_MIN, BPM_MAX)).apply()

    var volume: Float
        get() = prefs.getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_VOLUME, value.coerceIn(0f, 1f)).apply()

    var timeoutMin: Int
        get() = prefs.getInt(KEY_TIMEOUT, 0).coerceIn(0, TIMEOUT_MAX)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT, value.coerceIn(0, TIMEOUT_MAX)).apply()

    var tone: Tone
        get() = runCatching { Tone.valueOf(prefs.getString(KEY_TONE, null) ?: Tone.BUBBLE1.name) }
            .getOrDefault(Tone.BUBBLE1)
        set(value) = prefs.edit().putString(KEY_TONE, value.name).apply()

    /** 是否已展示过首次启动的后台保活引导。 */
    var guideShown: Boolean
        get() = prefs.getBoolean(KEY_GUIDE, false)
        set(value) = prefs.edit().putBoolean(KEY_GUIDE, value).apply()

    fun save(bpm: Int, volume: Float, tone: Tone, timeoutMin: Int) {
        prefs.edit()
            .putInt(KEY_BPM, bpm.coerceIn(BPM_MIN, BPM_MAX))
            .putFloat(KEY_VOLUME, volume.coerceIn(0f, 1f))
            .putString(KEY_TONE, tone.name)
            .putInt(KEY_TIMEOUT, timeoutMin.coerceIn(0, TIMEOUT_MAX))
            .apply()
    }

    private companion object {
        const val KEY_BPM = "bpm"
        const val KEY_VOLUME = "volume"
        const val KEY_TONE = "tone"
        const val KEY_TIMEOUT = "timeout_min"
        const val KEY_GUIDE = "guide_shown"
    }
}

// —— 全局参数范围（UI 与服务共用，避免两处写死后不一致） ——
const val BPM_MIN = 110
const val BPM_MAX = 230
const val DEFAULT_BPM = 180
const val TIMEOUT_MAX = 300
