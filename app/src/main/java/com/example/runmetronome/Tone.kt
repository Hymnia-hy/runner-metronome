package com.example.runmetronome

/**
 * 音效种类：真实音频采样（16bit PCM，内置 res/raw），用 SoundPool 播放。
 * 前 4 款来自开源节拍器 Kr0oked/Metronome(GPL-3.0)；后 4 款来自 de.moekadu.metronome(thetwom/toc2, GPL-3.0)。
 */
enum class Tone(val display: String, val desc: String, val resId: Int) {
    PLUCK("拨弦·清脆", "清脆明亮，近木琴/吉他", R.raw.tone_pluck_strong),
    SINE("正弦·柔和", "圆润柔和纯音", R.raw.tone_sine_strong),
    SQUARE("方波·电子", "电子感强", R.raw.tone_square_strong),
    RISSET("鼓点", "鼓点感强", R.raw.tone_risset_strong),
    CLAVES("响板·咔哒", "清脆响板", R.raw.tone_claves),
    WOODBLOCK("木鱼", "清脆木鱼", R.raw.tone_woodblock),
    STICKS("鼓棒", "清脆棒击", R.raw.tone_sticks),
    BASE("底鼓", "低频底鼓", R.raw.tone_base),
}
