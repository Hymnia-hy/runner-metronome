package com.example.runmetronome

/**
 * 音效种类：柔和、耐听的音色，适合长时间（如马拉松 4 小时）持续听、不厌烦。
 * 素材：BigSoundBank(CC0 公有领域) 的水泡破裂与各种落地声，已提取单瞬态、转 16bit PCM。
 * "碳板·落地"为硬木地板鞋落地声（较清脆硬板感，贴近碳板跑鞋落地）。
 */
enum class Tone(val display: String, val desc: String, val resId: Int) {
    BUBBLE1("气泡·柔", "柔和气泡破裂", R.raw.tone_bubble1),
    BUBBLE2("气泡·轻", "轻柔气泡", R.raw.tone_bubble2),
    BUBBLE3("气泡·缓", "舒缓气泡", R.raw.tone_bubble3),
    FOOTSTEP("脚步·沉", "跑步脚下触地", R.raw.tone_footstep),
    CARBON("碳板·落地", "硬板落地脆响", R.raw.tone_footstep_elite),
}
