package com.example.runmetronome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 验证"节拍长 PCM"的精度：
 * ① 无频率偏移：长跑后最末拍位置 ≈ leadIn + n*period（偏差<1采样，平均速率精确匹配目标 BPM）。
 * ② 间隔几乎完全一致：相邻拍起始间隔 ∈ {period-1, period, period+1}（取整抖动 ≤1采样）。
 * ③ 首尾静音：循环点落在静音段，无缝无爆音。
 */
class BeatPcmBuilderTest {

    private fun positions(bpm: Double, tone: ShortArray, seconds: Int): List<Int> {
        val pcm = BeatPcmBuilder.build(bpm, tone, seconds)
        val lead = (BeatPcmBuilder.SR * 60.0 / bpm / 4.0).roundToLong().toInt()
        val list = mutableListOf<Int>()
        var i = 0
        while (i < pcm.size) {
            if (pcm[i].toInt() != 0) { list.add(i); i += tone.size } else i++
        }
        return list.map { it - lead }
    }

    @Test
    fun noFrequencyDrift_overLongRun() {
        val bpm = 170.0
        val period = BeatPcmBuilder.SR * 60.0 / bpm
        val tone = ShortArray(50) { 1000 }
        val n = 340 // 2 分钟 @170
        val pos = positions(bpm, tone, 120)
        assertTrue(pos.size >= n)
        val last = pos[n - 1]
        val expected = (n - 1) * period
        // 最后一拍相对理想位置偏差应 <1 采样 → 无累积频率偏移
        assertTrue("偏移=${last - expected}", abs(last - expected) < 1.0)
    }

    @Test
    fun beatsEvenlySpaced_noJitter() {
        val bpm = 180.0
        val period = (BeatPcmBuilder.SR * 60.0 / bpm).roundToLong().let { it.toInt() }
        val tone = ShortArray(40) { 2000 }
        val pos = positions(bpm, tone, 2)
        assertTrue(pos.size >= 6)
        var prev = -1
        for (p in pos) {
            if (prev >= 0) {
                val gap = p - prev
                assertTrue("gap=$gap", gap == period || gap == period - 1 || gap == period + 1)
            }
            prev = p
        }
    }

    @Test
    fun silenceAtStartEnd_forSeamlessLoop() {
        val bpm = 180.0
        val lead = (BeatPcmBuilder.SR * 60.0 / bpm / 4.0).roundToLong().toInt()
        val tone = ShortArray(50) { 1000 }
        val pcm = BeatPcmBuilder.build(bpm, tone, seconds = 1)
        for (i in 0 until lead) assertEquals(0, pcm[i].toInt())
        for (i in pcm.size - lead until pcm.size) assertEquals(0, pcm[i].toInt())
    }
}
