package com.example.runmetronome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证"节拍长 PCM"的采样级均匀性：
 * 每个节拍音的起始位置必须严格相差 periodSamples，确保间隔完全一致（无"一对一对"抖动）。
 */
class BeatPcmBuilderTest {

    @Test
    fun build_beatPositionsEvenlySpaced_at180() {
        val bpm = 180.0
        val period = ((BeatPcmBuilder.SR * 60.0 / bpm)).toInt() // 16000
        val tone = ShortArray(50) { 1000 } // 非零采样
        val pcm = BeatPcmBuilder.build(bpm, tone, seconds = 2)
        val starts = findBeatStarts(pcm, tone.size)
        assertTrue("应有多个节拍", starts.size >= 6)
        // 相邻起始间隔应恒等于 period
        for (i in 1 until starts.size) {
            assertEquals(period, starts[i] - starts[i - 1])
        }
    }

    @Test
    fun build_everyBeatSingleTone() {
        val bpm = 200.0
        val period = ((BeatPcmBuilder.SR * 60.0 / bpm)).toInt() // 14400
        val tone = ShortArray(40) { 2000 }
        val pcm = BeatPcmBuilder.build(bpm, tone, seconds = 1)
        val starts = findBeatStarts(pcm, tone.size)
        for (i in 1 until starts.size) {
            assertEquals(period, starts[i] - starts[i - 1])
        }
    }

    @Test
    fun build_endsWithSilence_forSeamlessLoop() {
        val bpm = 180.0
        val leadIn = ((BeatPcmBuilder.SR * 60.0 / bpm)).toInt() / 4
        val tone = ShortArray(50) { 1000 }
        val pcm = BeatPcmBuilder.build(bpm, tone, seconds = 1)
        // 开头(0..leadIn)与结尾(last quarter)应为静音，保证循环点落在静音段
        for (i in 0 until leadIn) assertEquals(0, pcm[i].toInt())
        val tailStart = pcm.size - leadIn
        for (i in tailStart until pcm.size) assertEquals(0, pcm[i].toInt())
    }

    /** 找到每个节拍音的起始位置（非零区段的起点） */
    private fun findBeatStarts(pcm: ShortArray, toneLen: Int): List<Int> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i < pcm.size) {
            if (pcm[i].toInt() != 0) {
                starts.add(i)
                i += toneLen
            } else i++
        }
        return starts
    }
}
