package com.example.runmetronome

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 验证"节拍长 PCM"的精度与循环正确性：
 * ① 不漏拍：音效尾部超出循环末尾时必须环绕写入，而不是丢弃整拍；
 * ② 循环长度是整数拍，回绕处的间隔恰好等于一个周期；
 * ③ 无频率偏移、相邻拍间隔抖动 ≤1 采样；
 * ④ 循环长度足够长（≥8 秒），避免过短的循环带来可闻的重复感。
 */
class BeatPcmBuilderTest {

    /** res/raw/tone_bubble*.wav 的采样数：决定"漏拍" bug 的 BPM 边界（≈174）。 */
    private val bubbleLen = 12480

    private fun periodOf(bpm: Double): Double = BeatPcmBuilder.SR * 60.0 / bpm

    private fun leadInOf(bpm: Double): Int = (periodOf(bpm) / 4.0).roundToLong().toInt()

    /**
     * 回归测试：旧实现在 `pos + tonePcm.size > total` 时 break，
     * 导致 BPM ≥ 174 的气泡音色每轮循环少响一拍（默认 180 BPM 即中招）。
     */
    @Test
    fun noDroppedBeat_whenToneTailOverflowsLoop() {
        val tone = ShortArray(bubbleLen) { 1000 }
        for (bpm in intArrayOf(174, 180, 190, 200, 220, 230)) {
            val period = periodOf(bpm.toDouble())
            val pcm = BeatPcmBuilder.build(bpm.toDouble(), tone, 10.0)
            val total = pcm.size
            val leadIn = leadInOf(bpm.toDouble())
            val beats = (total / period).roundToInt()
            for (k in 0 until beats) {
                val pos = (leadIn + (k * period).roundToLong().toInt()) % total
                assertTrue("BPM=$bpm 第 $k 拍缺失（pos=$pos）", pcm[pos].toInt() != 0)
            }
        }
    }

    @Test
    fun loopLengthIsIntegerBeats_andLongEnough() {
        for (bpm in intArrayOf(110, 150, 170, 180, 200, 230)) {
            val period = periodOf(bpm.toDouble())
            val total = BeatPcmBuilder.loopSamples(bpm.toDouble(), 10.0)
            val beats = total / period
            assertTrue("BPM=$bpm 循环长度不是整数拍: $beats", abs(beats - beats.roundToLong()) < 0.01)
            assertTrue("BPM=$bpm 循环过短: $total", total >= BeatPcmBuilder.SR * 8)
        }
    }

    @Test
    fun wrapGapEqualsOnePeriod() {
        val tone = ShortArray(bubbleLen) { 1000 }
        for (bpm in intArrayOf(174, 180, 200, 230)) {
            val period = periodOf(bpm.toDouble())
            val pcm = BeatPcmBuilder.build(bpm.toDouble(), tone, 10.0)
            val total = pcm.size
            val leadIn = leadInOf(bpm.toDouble())
            val beats = (total / period).roundToInt()
            val lastPos = leadIn + ((beats - 1) * period).roundToLong().toInt()
            val gap = total - lastPos + leadIn
            assertTrue("BPM=$bpm 回绕间隔=$gap，期望≈$period", abs(gap - period) <= 1.0)
        }
    }

    @Test
    fun beatsEvenlySpaced_noJitter() {
        val bpm = 180.0
        val period = periodOf(bpm).roundToLong()
        val tone = ShortArray(8) { 3000 }
        val pcm = BeatPcmBuilder.build(bpm, tone, 2.0)
        val positions = scanBeats(pcm, tone.size)
        assertTrue("拍数太少: ${positions.size}", positions.size >= 5)
        for (i in 1 until positions.size) {
            val gap = positions[i] - positions[i - 1]
            assertTrue("gap=$gap 期望=$period", abs(gap - period) <= 1)
        }
    }

    @Test
    fun noFrequencyDrift_overLongRun() {
        val bpm = 170.0
        val period = periodOf(bpm)
        val tone = ShortArray(8) { 3000 }
        val pcm = BeatPcmBuilder.build(bpm, tone, 120.0)
        val positions = scanBeats(pcm, tone.size)
        assertTrue("拍数太少: ${positions.size}", positions.size >= 300)
        val last = positions.last()
        val expected = leadInOf(bpm) + (positions.size - 1) * period
        assertTrue("偏移=${last - expected}", abs(last - expected) < 1.0)
    }

    /** 扫描非零采样段起点作为拍点（要求音效很短，避免环绕尾音干扰）。 */
    private fun scanBeats(pcm: ShortArray, toneLen: Int): List<Int> {
        val list = mutableListOf<Int>()
        var i = 0
        while (i < pcm.size) {
            if (pcm[i].toInt() != 0) {
                list.add(i)
                i += toneLen
            } else {
                i++
            }
        }
        return list
    }
}
