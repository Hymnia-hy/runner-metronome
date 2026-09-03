package com.example.runmetronome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoMathTest {

    @Test
    fun periodMs_at180() {
        assertEquals(333.333, TempoMath.periodMs(180.0), 0.001)
    }

    @Test
    fun periodMs_at170_and_200() {
        assertEquals(60000.0 / 170, TempoMath.periodMs(170.0), 0.001)
        assertEquals(60000.0 / 200, TempoMath.periodMs(200.0), 0.001)
    }

    /** 长跑 10 分钟 @180 BPM（1800 拍），验证长期无漂移：一次乘法公式 vs 连续时间期望 */
    @Test
    fun beatTimesNoDrift_forLongRun() {
        val bpm = 180.0
        val start = 1_000_000L
        val totalBeats = 1800
        val expectedMs = start + (totalBeats * (60000.0 / bpm))
        val actualMs = TempoMath.beatTimeMs(start, totalBeats, bpm)
        assertEquals(expectedMs, actualMs.toDouble(), 0.5)
    }

    /** 逐拍验证：与“一次性期望时间”在每拍上一致（无逐拍累积偏差） */
    @Test
    fun everyBeatMatchesContinuousTime() {
        val bpm = 180.0
        val start = 0L
        for (i in 0..1800) {
            val expected = i * (60000.0 / bpm)
            val actual = TempoMath.beatTimeMs(start, i, bpm)
            assertEquals(expected, actual.toDouble(), 0.5)
        }
    }

    @Test
    fun periodIsPositive_inRange() {
        for (bpm in 120..220) {
            val p = TempoMath.periodMs(bpm.toDouble())
            assertTrue(p > 0 && p < 1000)
        }
    }

    @Test
    fun accentLogic() {
        assertTrue(TempoMath.isAccent(0, 4))
        assertTrue(TempoMath.isAccent(4, 4))
        assertTrue(TempoMath.isAccent(8, 4))
        assertTrue(!TempoMath.isAccent(1, 4))
        assertTrue(!TempoMath.isAccent(2, 4))
        assertTrue(!TempoMath.isAccent(3, 4))
        assertTrue(!TempoMath.isAccent(1, 0))
    }
}
