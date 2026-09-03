package com.example.runmetronome

import android.content.Context
import kotlin.math.roundToLong

/**
 * 生成"节拍长 PCM"：把节拍点按采样率精确烘焙进一个长音频（如 2 分钟）。
 * 手机只需连续循环播放该文件，节拍点已固定，彻底没有逐拍触发的调度抖动。
 * 每拍间隔 = 48000*60/bpm 采样（固定步进），采样级严格均匀。
 */
object BeatPcmBuilder {

    const val SR = 48000

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
        val n = dataLen / 2
        val out = ShortArray(n)
        for (k in 0 until n) {
            val b0 = bytes[dataStart + k * 2].toInt() and 0xff
            val b1 = (bytes[dataStart + k * 2 + 1].toInt() and 0xff) shl 8
            out[k] = (b0 or b1).toShort()
        }
        return out
    }

    /**
     * 按 bpm 生成节拍序列 PCM。
     * 每拍位置用"浮点精确累积后取整"：平均速率精确匹配目标 BPM（无频率偏移），
     * 单拍取整抖动 ≤1 采样(≈0.02ms，人耳不可闻)；首尾各留静音便于无缝循环。
     */
    fun build(bpm: Double, tonePcm: ShortArray, seconds: Int = 120): ShortArray {
        val period = SR * 60.0 / bpm
        val leadIn = (period / 4.0).roundToLong().toInt()
        val total = SR * seconds
        val pcm = ShortArray(total)
        var beatIndex = 0
        while (true) {
            val pos = leadIn + (beatIndex * period).roundToLong().toInt()
            if (pos + tonePcm.size > total) break
            for (j in tonePcm.indices) {
                val sum = pcm[pos + j].toInt() + tonePcm[j].toInt()
                pcm[pos + j] = sum.coerceIn(-32767, 32767).toShort()
            }
            beatIndex++
        }
        return pcm
    }
}
