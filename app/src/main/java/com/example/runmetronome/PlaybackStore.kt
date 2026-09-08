package com.example.runmetronome

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 服务 → UI 的状态回传通道。
 *
 * Service 在主线程写入，Compose 直接读取（mutableStateOf 是 snapshot state，
 * 在 Compose 与非 Compose 代码之间读写都是安全的）。
 * 这样解决了旧实现的核心问题：UI 的播放状态是纯本地状态，
 * 倒计时自动结束 / 进程重建 / 服务被系统回收后，界面显示与实际播放完全脱节。
 */
object PlaybackStore {

    var state by mutableStateOf(PlaybackState())
        private set

    fun update(block: (PlaybackState) -> PlaybackState) {
        state = block(state)
    }
}

data class PlaybackState(
    /** 服务处于运行态（播放或暂停）。 */
    val running: Boolean = false,
    val paused: Boolean = false,
    /** 服务当前实际使用的参数（可能与 UI 编辑值短暂不一致）。 */
    val bpm: Int = 180,
    val tone: Tone = Tone.BUBBLE1,
    val timeoutMin: Int = 0,
    /** 本次训练已进行的秒数（暂停时不累加）。 */
    val elapsedSec: Int = 0,
    /** 倒计时剩余秒数，-1 表示不限时。 */
    val remainingSec: Int = -1,
    /** 音频初始化失败等错误提示，读取后由 UI 清除。 */
    val error: String? = null,
) {
    val active: Boolean get() = running
    val playing: Boolean get() = running && !paused
}

/** mm:ss 格式化，供通知与界面显示剩余时间。 */
fun formatClock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
