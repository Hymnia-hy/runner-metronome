package com.example.runmetronome

import kotlin.math.roundToLong

/**
 * 纯数学节拍时基计算。与 Android 无关，可在 JVM 单测中精确验证。
 * 关键设计：每一拍的目标时间 = startBase + beatIndex * period，
 * 采用“一次性乘法+单次取整”，而不是逐拍累加，从而杜绝长期舍入漂移。
 */
object TempoMath {

    /** 每拍毫秒数 */
    fun periodMs(bpm: Double): Double = 60000.0 / bpm

    /** 第 beatIndex 拍（从 0 开始）相对 startBase 的目标绝对时间(ms) */
    fun beatTimeMs(startBase: Long, beatIndex: Int, bpm: Double): Long =
        startBase + (beatIndex * periodMs(bpm)).roundToLong()

    /** 一拍是否是重音（accentEvery 的倍数拍，第 1 拍为重音起点） */
    fun isAccent(beatIndex: Int, accentEvery: Int): Boolean {
        if (accentEvery <= 0) return false
        return beatIndex % accentEvery == 0
    }
}
