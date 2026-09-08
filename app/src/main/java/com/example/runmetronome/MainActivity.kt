package com.example.runmetronome

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// —— 设计规范配色（runner-metronome-design/design-guidelines.html） ——
private val C_BG = Color(0xFF0A1426)
private val C_SURFACE = Color(0xFF111D33)
private val C_SURFACE2 = Color(0xFF182845)
private val C_LINE = Color(0xFF22304D)
private val C_ACCENT = Color(0xFF3DFF88)
private val C_WARN = Color(0xFFFF6B35)
private val C_TEXT = Color(0xFFF5F7FA)
private val C_TEXT2 = Color(0xFF8A94A6)
private val C_ON_ACCENT = Color(0xFF06281A)
private val C_TONE_ON = Color(0xFF14352A)

private val DarkColors = darkColorScheme(
    primary = C_ACCENT,
    onPrimary = C_ON_ACCENT,
    background = C_BG,
    onBackground = C_TEXT,
    surface = C_SURFACE,
    onSurface = C_TEXT,
    surfaceVariant = C_SURFACE2,
    onSurfaceVariant = C_TEXT2,
    outline = C_LINE,
    error = C_WARN,
)

/** 步进器长按后开始连续步进的延迟与间隔。 */
private const val STEP_LONG_PRESS_MS = 380L
private const val STEP_REPEAT_MS = 70L

/** 运行态停止键的长按确认时长（防误触）。 */
private const val STOP_HOLD_MS = 600L

/** 连续调参合并窗口：把滑块/步进器的密集变更合并成一次下发。 */
private const val UPDATE_DEBOUNCE_MS = 250L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        setContent {
            MaterialTheme(colorScheme = DarkColors) { AppScreen() }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}

@Composable
fun AppScreen() {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context) }
    val playback = PlaybackStore.state

    var bpm by remember { mutableIntStateOf(settings.bpm) }
    var volume by remember { mutableFloatStateOf(settings.volume) }
    var timeoutMin by remember { mutableIntStateOf(settings.timeoutMin) }
    var tone by remember { mutableStateOf(settings.tone) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 试听用的短音播放器（与节拍同一媒体通道，响度观感一致）
    val preview = remember { MetronomePlayer(context) }
    DisposableEffect(Unit) { onDispose { preview.release() } }

    // 服务报错回传 → 弹窗提示
    LaunchedEffect(playback.error) {
        playback.error?.let {
            errorText = it
            PlaybackStore.update { s -> s.copy(error = null) }
        }
    }

    // 服务重新进入运行态时，以服务实际参数为准回填界面
    LaunchedEffect(playback.running) {
        if (playback.running) {
            bpm = playback.bpm
            tone = playback.tone
            timeoutMin = playback.timeoutMin
        }
    }

    // 运行中改参数：合并 250ms 内的密集变更后一次性下发
    // （步频/音色变更会重建音轨，密集下发会让节奏反复重置）
    var pending by remember { mutableStateOf(false) }
    LaunchedEffect(pending) {
        if (!pending) return@LaunchedEffect
        delay(UPDATE_DEBOUNCE_MS)
        pushUpdate(context, bpm = bpm, tone = tone, volume = volume, timeoutMin = timeoutMin)
        pending = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(C_BG)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        TopBar(playback)

        Spacer(Modifier.height(12.dp))
        Text(
            "CADENCE",
            color = C_TEXT2, fontSize = 12.sp, letterSpacing = 3.sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
            Text(
                "$bpm",
                fontSize = 108.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
                color = if (playback.playing) C_ACCENT else C_TEXT,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "BPM", color = C_ACCENT, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 18.dp)
            )
        }

        // 节拍柱：随拍点脉冲，跑步中余光即可确认节奏在走
        PulseBars(
            active = playback.playing,
            bpm = bpm,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .semantics { contentDescription = if (playback.playing) "节拍运行中" else "节拍未运行" },
        )

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            StepperBtn(
                label = "−",
                contentDesc = "步频减一",
                onTick = { bpm = (bpm - 1).coerceIn(BPM_MIN, BPM_MAX) },
                onCommit = {
                    settings.bpm = bpm
                    pending = true
                },
            )
            Spacer(Modifier.width(12.dp))
            StepperBtn(
                label = "＋",
                contentDesc = "步频加一",
                onTick = { bpm = (bpm + 1).coerceIn(BPM_MIN, BPM_MAX) },
                onCommit = {
                    settings.bpm = bpm
                    pending = true
                },
            )
        }

        SliderCard(
            title = "步频",
            value = "$bpm",
            unit = "BPM",
            iconRes = R.drawable.ic_cadence,
        ) {
            DesignSlider(
                value = bpm.toFloat(),
                onValueChange = { bpm = it.roundToInt().coerceIn(BPM_MIN, BPM_MAX) },
                valueRange = BPM_MIN.toFloat()..BPM_MAX.toFloat(),
                steps = BPM_MAX - BPM_MIN - 1,
                contentDescription = "步频 $bpm BPM",
                onValueChangeFinished = {
                    settings.bpm = bpm
                    pending = true
                },
                modifier = Modifier.height(36.dp),
            )
        }

        val remaining = playback.remainingSec
        SliderCard(
            title = "训练倒计时",
            value = when {
                timeoutMin == 0 -> "不限"
                playback.running && remaining >= 0 -> "剩 ${formatClock(remaining)}"
                else -> "$timeoutMin"
            },
            unit = if (timeoutMin == 0 || (playback.running && remaining >= 0)) "" else "min",
            iconRes = R.drawable.ic_timer,
        ) {
            DesignSlider(
                value = timeoutMin.toFloat(),
                onValueChange = { timeoutMin = it.roundToInt().coerceIn(0, TIMEOUT_MAX) },
                valueRange = 0f..TIMEOUT_MAX.toFloat(),
                steps = TIMEOUT_MAX - 1,
                contentDescription = if (timeoutMin == 0) "倒计时不限时" else "倒计时 $timeoutMin 分钟",
                onValueChangeFinished = {
                    settings.timeoutMin = timeoutMin
                    pending = true
                },
                modifier = Modifier.height(36.dp),
            )
        }

        SliderCard(
            title = "节拍音量",
            value = "${(volume * 100).roundToInt()}",
            unit = "%",
            iconRes = R.drawable.ic_volume,
        ) {
            DesignSlider(
                value = volume,
                onValueChange = {
                    volume = it
                    pending = true
                },
                valueRange = 0f..1f,
                steps = 0,
                contentDescription = "节拍音量 ${(volume * 100).roundToInt()}%",
                onValueChangeFinished = {
                    settings.volume = volume
                    pushUpdate(context, volume = volume)
                },
                modifier = Modifier.height(36.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Tone.entries) { t ->
                ToneCard(t, selected = tone == t) {
                    tone = t
                    settings.tone = t
                    preview.preview(t, volume)
                    pending = true
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    when {
                        !playback.running -> {
                            settings.save(bpm, volume, tone, timeoutMin)
                            context.startForegroundService(startIntent(context, bpm, tone, volume, timeoutMin))
                        }
                        playback.paused -> sendCommand(context, MetronomeService.ACTION_RESUME)
                        else -> sendCommand(context, MetronomeService.ACTION_PAUSE)
                    }
                },
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (playback.playing) C_SURFACE2 else C_ACCENT,
                    contentColor = if (playback.playing) C_TEXT else C_ON_ACCENT
                )
            ) {
                Icon(
                    painterResource(if (playback.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (playback.playing) C_TEXT else C_ON_ACCENT
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        !playback.running -> "开始"
                        playback.paused -> "继续"
                        else -> "暂停"
                    },
                    fontSize = 19.sp, fontWeight = FontWeight.ExtraBold
                )
            }
            StopButton(
                enabled = playback.running,
                onStop = { sendCommand(context, MetronomeService.ACTION_STOP) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    errorText?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            confirmButton = { TextButton(onClick = { errorText = null }) { Text("知道了") } },
            title = { Text("播放失败") },
            text = { Text(msg) },
            containerColor = C_SURFACE,
            titleContentColor = C_TEXT,
            textContentColor = C_TEXT2,
        )
    }

    // 首次启动的后台保活引导（ColorOS 会把后台服务杀掉，导致节拍中断）
    var showGuide by remember { mutableStateOf(!settings.guideShown) }
    if (showGuide) {
        val dismiss = {
            showGuide = false
            settings.guideShown = true
        }
        AlertDialog(
            onDismissRequest = dismiss,
            confirmButton = { TextButton(onClick = dismiss) { Text("知道了") } },
            title = { Text("让节拍在后台不断") },
            text = {
                Text(
                    "为避免锁屏 / 切后台被系统省电策略杀掉，请在系统设置里：\n\n" +
                        "1. 应用管理 → 跑者节拍器 → 允许后台运行\n" +
                        "2. 同一页开启「自启动」\n" +
                        "3. 电池 → 选择「无限制」"
                )
            },
            containerColor = C_SURFACE,
            titleContentColor = C_TEXT,
            textContentColor = C_TEXT2,
        )
    }
}

@Composable
private fun TopBar(playback: PlaybackState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).background(C_SURFACE, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Box(Modifier.size(9.dp).background(C_ACCENT, RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.width(8.dp))
            Text("Runner Metronome", color = C_TEXT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (playback.running) {
                Box(
                    Modifier.size(7.dp).background(
                        if (playback.paused) C_WARN else C_ACCENT,
                        RoundedCornerShape(4.dp)
                    )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (playback.paused) "已暂停" else "跑步中",
                    color = if (playback.paused) C_WARN else C_ACCENT,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(10.dp))
            }
            // 核心卖点：不打断音乐
            Text("♪ 与音乐共存", color = C_TEXT2, fontSize = 11.sp)
        }
    }
}

/**
 * 节拍柱：5 根高度不同的柱子按当前 BPM 的相位做指数衰减脉冲，
 * 用 graphicsLayer 只触发重绘（不触发布局），跑步中余光即可确认节奏。
 */
@Composable
private fun PulseBars(active: Boolean, bpm: Int, modifier: Modifier = Modifier) {
    val baseHeights = remember { listOf(8f, 14f, 22f, 14f, 8f) }
    var phase by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(active, bpm) {
        if (!active) {
            phase = 0f
            return@LaunchedEffect
        }
        val periodNanos = (60_000_000_000.0 / bpm).toLong().coerceAtLeast(1L)
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                phase = ((now - start) % periodNanos).toFloat() / periodNanos
            }
        }
    }

    Row(
        modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        baseHeights.forEachIndexed { i, h ->
            Box(
                Modifier
                    .padding(horizontal = 2.5.dp)
                    .width(5.dp)
                    .height(h.dp)
                    .graphicsLayer {
                        val d = ((phase - i * 0.05f) % 1f + 1f) % 1f
                        val pulse = exp(-d * 5.0).toFloat()
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        scaleY = 1f + (12f / h) * pulse
                        alpha = if (active) 0.45f + 0.55f * pulse else 0.3f
                    }
                    .background(C_ACCENT, RoundedCornerShape(3.dp))
            )
        }
    }
}

/**
 * 步进器：单击 ±1，长按连续步进（旧实现从 110 调到 230 要点 120 次）。
 * 长按期间只改本地数值，松手后才通过 onCommit 下发一次，避免节拍被反复重建。
 */
@Composable
private fun StepperBtn(
    label: String,
    contentDesc: String,
    onTick: () -> Unit,
    onCommit: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var repeating by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (pressed) {
            delay(STEP_LONG_PRESS_MS)
            repeating = true
            while (true) {
                onTick()
                delay(STEP_REPEAT_MS)
            }
        } else if (repeating) {
            repeating = false
            onCommit()
        }
    }

    Surface(
        onClick = {
            if (!repeating) {
                onTick()
                onCommit()
            }
        },
        interactionSource = interactionSource,
        shape = RoundedCornerShape(16.dp),
        color = if (pressed) C_SURFACE2 else C_SURFACE,
        border = BorderStroke(1.dp, if (pressed) C_ACCENT else C_LINE),
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = contentDesc },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = C_TEXT, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 停止键：运行态需长按 600ms 才生效，避免跑步中误触。 */
@Composable
private fun StopButton(enabled: Boolean, onStop: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var fired by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) {
            fired = false
            return@LaunchedEffect
        }
        delay(STOP_HOLD_MS)
        fired = true
        onStop()
    }

    OutlinedButton(
        onClick = { /* 由长按触发，避免误触 */ },
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier.width(96.dp).height(64.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (pressed) C_WARN else C_LINE),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = C_WARN),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_stop), null, Modifier.size(18.dp), tint = C_WARN)
            Spacer(Modifier.height(2.dp))
            Text(
                if (pressed && enabled) "按住…" else "停止",
                fontSize = 15.sp, fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SliderCard(
    title: String,
    value: String,
    unit: String,
    iconRes: Int,
    slider: @Composable () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = C_SURFACE),
        border = BorderStroke(1.dp, C_LINE)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(iconRes), null, Modifier.size(16.dp), tint = C_TEXT2)
                    Spacer(Modifier.width(6.dp))
                    Text(title, color = C_TEXT2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(if (unit.isEmpty()) value else "$value $unit", color = C_TEXT, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(2.dp))
            slider()
        }
    }
}

/**
 * 按设计规范定制的滑块：荧光绿轨道 + 28dp 拇指（默认 Material3 配色与设计稿不符）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesignSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        thumbColor = C_TEXT,
        activeTrackColor = C_ACCENT,
        inactiveTrackColor = C_SURFACE2,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        enabled = true,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interactionSource,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                colors = colors,
                enabled = true,
                thumbSize = DpSize(28.dp, 28.dp),
            )
        },
    )
}

@Composable
private fun ToneCard(t: Tone, selected: Boolean, onClick: () -> Unit) {
    val iconRes = when (t) {
        Tone.FOOTSTEP -> R.drawable.ic_tone_foot
        else -> R.drawable.ic_tone_bubble
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) C_TONE_ON else C_SURFACE,
        border = BorderStroke(1.5.dp, if (selected) C_ACCENT else C_LINE),
        modifier = Modifier
            .width(96.dp)
            .semantics { contentDescription = "音色 ${t.display}" }
    ) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(40.dp).background(if (selected) C_ACCENT else C_SURFACE2, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(iconRes), null, Modifier.size(22.dp), tint = if (selected) C_ON_ACCENT else C_TEXT)
            }
            Spacer(Modifier.height(6.dp))
            Text(t.display, color = C_TEXT, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

// —— 与服务通信 ——

/**
 * 下发运行中参数变更。只带真正变化的字段，服务端据此决定是否重建音轨。
 * 服务未运行时不发送：既无意义，也会因 startForegroundService 超时规则带来崩溃风险。
 */
private fun pushUpdate(
    context: Context,
    bpm: Int? = null,
    tone: Tone? = null,
    volume: Float? = null,
    timeoutMin: Int? = null,
) {
    if (!MetronomeService.isRunning()) return
    val i = Intent(context, MetronomeService::class.java).apply {
        action = MetronomeService.ACTION_UPDATE
        bpm?.let { putExtra(MetronomeService.EXTRA_BPM, it) }
        tone?.let { putExtra(MetronomeService.EXTRA_TONE, it.name) }
        volume?.let { putExtra(MetronomeService.EXTRA_VOLUME, it) }
        timeoutMin?.let { putExtra(MetronomeService.EXTRA_TIMEOUT_MIN, it) }
    }
    runCatching { context.startService(i) }
}

/**
 * 下发控制命令。服务已在运行时用 startService（此时 App 有前台服务，允许后台启动服务），
 * 避免 startForegroundService 拉起后未及时 startForeground 导致的平台异常。
 */
private fun sendCommand(context: Context, action: String) {
    if (!MetronomeService.isRunning()) return
    runCatching {
        context.startService(Intent(context, MetronomeService::class.java).setAction(action))
    }
}

private fun startIntent(context: Context, bpm: Int, tone: Tone, volume: Float, timeoutMin: Int): Intent =
    Intent(context, MetronomeService::class.java).apply {
        action = MetronomeService.ACTION_START
        putExtra(MetronomeService.EXTRA_BPM, bpm)
        putExtra(MetronomeService.EXTRA_TONE, tone.name)
        putExtra(MetronomeService.EXTRA_VOLUME, volume)
        putExtra(MetronomeService.EXTRA_TIMEOUT_MIN, timeoutMin)
    }
