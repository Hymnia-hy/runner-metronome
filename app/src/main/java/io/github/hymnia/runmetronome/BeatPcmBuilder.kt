package io.github.hymnia.runmetronome

import android.content.Context
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * 生成"节拍长 PCM"：把节拍点按采样率精确烘焙进一段长音频。
 * 手机只需连续循环播放该段 PCM，节拍点已在采样层面固定，彻底没有逐拍触发的调度抖动。
 *
 * 两条决定听感是否均匀的关键约束：
 * ① 循环长度必须是"整数拍"，否则循环回绕处会出现一次不均匀间隔；
 * ② 音效尾部若超出循环末尾，必须"环绕"写回开头，而不是丢弃整拍。
 *    （旧实现直接 break，导致 BPM ≥ 174 的气泡音色每轮循环少响一拍，
 *      表现为每 10 秒出现一次双倍间隔的"空拍"。）
 */
object BeatPcmBuilder {

    const val SR = 48000

    /** 目标循环时长（秒）。只要覆盖若干整拍即可，越短越省内存与构建时间。 */
    private const val TARGET_LOOP_SECONDS = 10.0

    /** 从 raw 资源读 wav 并解析为 16bit PCM（单声道） */
    fun loadRawPcm(context: Context, resId: Int): ShortArray {
        val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
        return parseWav16(bytes)
    }

    private fun parseWav16(bytes: ByteArray): ShortArray {
        var i = 12
        var dataStart = -1
        var dataLen = 0
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4)
            val sz = (bytes[i + 4].toInt() and 0xff) or
                ((bytes[i + 5].toInt() and 0xff) shl 8) or
                ((bytes[i + 6].toInt() and 0xff) shl 16) or
                ((bytes[i + 7].toInt() and 0xff) shl 24)
            if (id == "data") {
                dataStart = i + 8
                dataLen = sz
                break
            }
            i += 8 + sz
        }
        if (dataStart < 0) return ShortArray(0)
        val n = (dataLen / 2).coerceAtMost((bytes.size - dataStart) / 2)
        val out = ShortArray(n)
        for (k in 0 until n) {
            val b0 = bytes[dataStart + k * 2].toInt() and 0xff
            val b1 = (bytes[dataStart + k * 2 + 1].toInt() and 0xff) shl 8
            out[k] = (b0 or b1).toShort()
        }
        return out
    }

    /**
     * 循环长度（采样数）：向上取整到 targetSeconds 对应的整数拍数。
     * 必须是周期整数倍，循环回绕后第一拍才能精确落在 leadIn 处。
     */
    fun loopSamples(bpm: Double, targetSeconds: Double = TARGET_LOOP_SECONDS): Int {
        val period = SR * 60.0 / bpm
        val beats = ceil(targetSeconds * bpm / 60.0).toInt().coerceAtLeast(1)
        return (beats * period).roundToLong().toInt().coerceAtLeast(1)
    }

    /**
     * 按 bpm 生成节拍序列 PCM。
     * 每拍位置用"浮点精确累积后取整"：平均速率精确匹配目标 BPM（无频率偏移），
     * 单拍取整抖动 ≤1 采样（≈0.02ms，人耳不可闻）。
     * 循环首尾各留 1/4 拍的静音，且尾音环绕写入，保证每一拍都不丢、循环点连续无缝。
     */
    fun build(
        bpm: Double,
        tonePcm: ShortArray,
        targetSeconds: Double = TARGET_LOOP_SECONDS,
    ): ShortArray {
        val period = SR * 60.0 / bpm
        val total = loopSamples(bpm, targetSeconds)
        val leadIn = (period / 4.0).roundToLong().toInt().coerceIn(0, total - 1)
        val pcm = ShortArray(total)
        var beatIndex = 0
        while (true) {
            val pos = leadIn + (beatIndex * period).roundToLong().toInt()
            if (pos >= total) break
            // 尾部环绕写入：循环播放时末尾尾音与开头静音区本就连续，取模即正确拼接
            for (j in 0 until minOf(tonePcm.size, total)) {
                val idx = (pos + j) % total
                val sum = pcm[idx].toInt() + tonePcm[j].toInt()
                pcm[idx] = sum.coerceIn(-32767, 32767).toShort()
            }
            beatIndex++
        }
        return pcm
    }
}
