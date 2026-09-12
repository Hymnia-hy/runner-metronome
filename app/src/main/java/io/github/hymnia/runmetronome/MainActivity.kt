package io.github.hymnia.runmetronome

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// —— 设计规范（v2 简约稿）：深炭底 + 白字 + 青柠点缀，强调色只做小面积 ——
private val C_BG = Color(0xFF0A0C10)
private val C_PANEL = Color(0xFF16191F)
private val C_PANEL2 = Color(0xFF1F232C)
private val C_LINE = Color(0xFF272C37)
private val C_ACCENT = Color(0xFFC8FF3D)
private val C_TEXT = Color(0xFFFFFFFF)
private val C_TEXT2 = Color(0xFF868D9B)
private val C_DANGER = Color(0xFFFF6B5A)
private val C_ON_LIGHT = Color(0xFF0A0C10)

private val DarkColors = darkColorScheme(
    primary = C_ACCENT,
    onPrimary = C_ON_LIGHT,
    background = C_BG,
    onBackground = C_TEXT,
    surface = C_PANEL,
    onSurface = C_TEXT,
    surfaceVariant = C_PANEL2,
    onSurfaceVariant = C_TEXT2,
    outline = C_LINE,
    error = C_DANGER,
)

/**
 * 字重规范（全屏统一，避免某个元素"跳"出来）：
 * - 巨大读数（BPM 数字）：ExtraBold —— 全屏唯一的最重层级
 * - 主操作（按钮文字）：Bold
 * - 次级读数（参数值、面板读数、品牌、状态）：SemiBold
 * - 其余标签、刻度、说明：Normal
 */
private val W_DISPLAY = FontWeight.ExtraBold
private val W_ACTION = FontWeight.Bold
private val W_VALUE = FontWeight.SemiBold
private val W_LABEL = FontWeight.Normal

/** 等宽数字：数值变化时水平位置不抖动。 */
private const val TNUM = "tnum"

/** 步进器长按后开始连续步进的延迟与间隔。 */
private const val STEP_LONG_PRESS_MS = 380L
private const val STEP_REPEAT_MS = 70L

/** 运行态停止键的长按确认时长（防误触）。 */
private const val STOP_HOLD_MS = 400L

/** 连续调参合并窗口：把滑块/步进器的密集变更合并成一次下发。 */
private const val UPDATE_DEBOUNCE_MS = 250L

/** 参数条上可展开调节的三项。 */
private enum class Param { VOLUME, TIMER, TONE }

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
    var expanded by remember { mutableStateOf<Param?>(null) }
    var showGuide by remember { mutableStateOf(!settings.guideShown) }

    // 试听用的短音播放器（与节拍同一媒体通道，响度观感一致）
    val preview = remember { MetronomePlayer(context) }
    // 进界面就预热 SoundPool，避免第一次点音色卡片时样本还没解码完而无声
    LaunchedEffect(Unit) { preview.preload() }
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
            expanded = null
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

    // 步频的相对步进：lambda 捕获的是 State 委托，长按期间始终读到最新值
    val stepBpm: (Int) -> Unit = { delta ->
        bpm = (bpm + delta).coerceIn(BPM_MIN, BPM_MAX)
    }
    val commitBpm: () -> Unit = {
        settings.bpm = bpm
        pending = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(C_BG)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
    ) {
        TopBar(
            playback = playback,
            onSettings = { showGuide = true },
        )

        // 弹性 1：焦点带上方
        Spacer(Modifier.weight(1f))

        FocusSection(
            playback = playback,
            bpm = bpm,
        )

        // 弹性 2：焦点带与调节行之间
        Spacer(Modifier.weight(0.7f))

        AdjustRow(
            bpm = bpm,
            onStep = stepBpm,
            onSlide = { bpm = it },
            onCommit = commitBpm,
        )

        TickRow()

        // 弹性 3：调节行与参数条之间
        Spacer(Modifier.weight(0.5f))

        ParamStrip(
            volume = volume,
            timeoutMin = timeoutMin,
            tone = tone,
            playback = playback,
            expanded = expanded,
            onToggle = { expanded = if (expanded == it) null else it },
        )

        // 展开的调节面板：就地展开，不弹窗、不离开界面
        expanded?.let { p ->
            Spacer(Modifier.height(10.dp))
            ParamPanel(
                param = p,
                volume = volume,
                timeoutMin = timeoutMin,
                tone = tone,
                onVolume = {
                    volume = it
                    pending = true
                },
                onVolumeCommit = {
                    settings.volume = volume
                    // 音量只改音轨增益、不重建音轨，松手就立刻下发，
                    // 不必等 250ms 防抖窗口——拖完马上听到变化
                    pushUpdate(context, volume = volume)
                },
                onTimer = { timeoutMin = it },
                onTimerCommit = {
                    settings.timeoutMin = timeoutMin
                    pending = true
                },
                onTone = { t ->
                    tone = t
                    settings.tone = t
                    preview.preview(t, volume)
                    pending = true
                },
            )
        }

        // 弹性 4：参数条与底部操作区之间（留白稍大，让按钮有独立的呼吸空间）
        Spacer(Modifier.weight(1.1f))

        ActionRow(
            playback = playback,
            onPrimary = {
                when {
                    !playback.running -> {
                        settings.save(bpm, volume, tone, timeoutMin)
                        context.startForegroundService(startIntent(context, bpm, tone, volume, timeoutMin))
                    }
                    playback.paused -> sendCommand(context, MetronomeService.ACTION_RESUME)
                    else -> sendCommand(context, MetronomeService.ACTION_PAUSE)
                }
            },
            onStop = { sendCommand(context, MetronomeService.ACTION_STOP) },
        )
    }

    errorText?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            confirmButton = { TextButton(onClick = { errorText = null }) { Text("知道了") } },
            title = { Text("播放失败") },
            text = { Text(msg) },
            containerColor = C_PANEL,
            titleContentColor = C_TEXT,
            textContentColor = C_TEXT2,
        )
    }

    // 首次启动的后台保活引导（ColorOS 会把后台服务杀掉，导致节拍中断）
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
            containerColor = C_PANEL,
            titleContentColor = C_TEXT,
            textContentColor = C_TEXT2,
        )
    }
}

/** 顶部信息带：品牌 + 运行状态点；待机时右侧是后台保活设置的入口。 */
@Composable
private fun TopBar(playback: PlaybackState, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Runner Metronome",
            color = C_TEXT,
            fontSize = 14.5.sp,
            fontWeight = W_VALUE,
        )
        if (playback.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(if (playback.paused) C_DANGER else C_ACCENT, CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (playback.paused) "已暂停" else "跑步中",
                    color = if (playback.paused) C_DANGER else C_ACCENT,
                    fontSize = 12.sp,
                    fontWeight = W_VALUE,
                )
            }
        } else {
            Surface(
                onClick = onSettings,
                color = Color.Transparent,
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_settings),
                        contentDescription = "后台保活设置",
                        modifier = Modifier.size(20.dp),
                        tint = C_TEXT2,
                    )
                }
            }
        }
    }
}

/**
 * 中部焦点带：节拍脉冲 + 巨大步频数字 + 一行说明。
 * 数字用等宽数字特性（tnum），避免数值变化时水平位置抖动。
 */
@Composable
private fun FocusSection(playback: PlaybackState, bpm: Int) {
    val subtitle = when {
        playback.running && playback.remainingSec >= 0 ->
            "剩余 ${formatClock(playback.remainingSec)}"
        playback.running -> "已跑 ${formatClock(playback.elapsedSec)}"
        else -> "步频 · BPM"
    }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulseBars(
            active = playback.playing,
            bpm = bpm,
            modifier = Modifier.semantics {
                contentDescription = if (playback.playing) "节拍运行中" else "节拍未运行"
            },
        )
        Spacer(Modifier.height(22.dp))
        Text(
            text = "$bpm",
            style = TextStyle(
                fontSize = 150.sp,
                lineHeight = 158.sp,
                fontWeight = W_DISPLAY,
                letterSpacing = (-3).sp,
                fontFeatureSettings = TNUM,
            ),
            color = if (playback.playing) C_ACCENT else C_TEXT,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = TextStyle(
                fontSize = 12.5.sp,
                letterSpacing = 3.5.sp,
                fontFeatureSettings = TNUM,
            ),
            color = C_TEXT2,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 调节行：− 步进器 / 步频滑块 / ＋ 步进器，同一水平轴线。
 *
 * 旧版把步进器、滑块、大数字分三处重复展示同一个 BPM 值；
 * 这里把粗调（滑块）与精调（±1）合并成一行，数值只在大数字处出现一次。
 */
@Composable
private fun AdjustRow(
    bpm: Int,
    onStep: (Int) -> Unit,
    onSlide: (Int) -> Unit,
    onCommit: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StepButton(
            plus = false,
            contentDesc = "步频减一",
            onStep = { onStep(-1) },
            onCommit = onCommit,
        )
        BpmSlider(
            bpm = bpm,
            onSlide = onSlide,
            onCommit = onCommit,
            modifier = Modifier.weight(1f),
        )
        StepButton(
            plus = true,
            contentDesc = "步频加一",
            onStep = { onStep(1) },
            onCommit = onCommit,
        )
    }
}

/** 滑块下方的刻度行：与滑块两端严格对齐（左右各让出一个步进器宽度 + 间距）。 */
@Composable
private fun TickRow() {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(70.dp))
        Text(
            "$BPM_MIN",
            style = TextStyle(fontSize = 11.5.sp, fontFeatureSettings = TNUM),
            color = C_TEXT2,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "$BPM_MAX",
            style = TextStyle(fontSize = 11.5.sp, fontFeatureSettings = TNUM),
            color = C_TEXT2,
        )
        Spacer(Modifier.width(70.dp))
    }
}

@Composable
private fun BpmSlider(
    bpm: Int,
    onSlide: (Int) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val span = (BPM_MAX - BPM_MIN).toFloat()
    TrackSlider(
        fraction = (bpm - BPM_MIN) / span,
        onFractionChange = { fr ->
            onSlide((BPM_MIN + fr * span).roundToInt().coerceIn(BPM_MIN, BPM_MAX))
        },
        onCommit = onCommit,
        contentDescription = "步频 $bpm BPM",
        modifier = modifier,
        steps = BPM_MAX - BPM_MIN - 1,
        trackHeight = 12.dp,
        thumbSize = 28.dp,
    )
}

/**
 * 自绘轨道滑块。
 *
 * 不用 Material3 的 Slider：它在圆钮与已选轨道之间强制留有空隙（M3 规范的一部分），
 * 在深色窄屏上就是一道突兀的黑缝，圆钮看着像"浮"在轨道外。
 * 这里改为直接绘制：已选段一直延伸到圆钮圆心，圆钮再盖在上面，两者无缝相接。
 *
 * 轨道两端各留出一个圆钮半径，保证圆钮始终完整落在轨道范围内而不探出边缘。
 */
@Composable
private fun TrackSlider(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    onCommit: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    trackHeight: Dp = 12.dp,
    thumbSize: Dp = 28.dp,
) {
    val f = fraction.coerceIn(0f, 1f)
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { (thumbSize / 2).toPx() }
    // 圆钮圆心可移动的行程：轨道宽度去掉两端各一个半径
    val travelPx = (widthPx - 2f * thumbRadiusPx).coerceAtLeast(0f)

    Box(
        modifier
            .height(56.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(travelPx) {
                if (travelPx <= 0f) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onFractionChange(
                        ((down.position.x - thumbRadiusPx) / travelPx).coerceIn(0f, 1f)
                    )
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        onFractionChange(
                            ((change.position.x - thumbRadiusPx) / travelPx).coerceIn(0f, 1f)
                        )
                        change.consume()
                    }
                    onCommit()
                }
            }
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f, steps)
                setProgress { target ->
                    onFractionChange((target / 100f).coerceIn(0f, 1f))
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(thumbSize)) {
            val cy = size.height / 2f
            val th = trackHeight.toPx()
            val r = th / 2f
            val trackLeft = thumbRadiusPx
            val trackW = (size.width - 2f * thumbRadiusPx).coerceAtLeast(0f)
            val knobX = trackLeft + trackW * f

            drawRoundRect(
                color = C_PANEL2,
                topLeft = Offset(trackLeft, cy - r),
                size = Size(trackW, th),
                cornerRadius = CornerRadius(r, r),
            )
            if (knobX > trackLeft) {
                drawRoundRect(
                    color = C_ACCENT,
                    topLeft = Offset(trackLeft, cy - r),
                    // 端点用半径兜底，避免极短时圆角塌陷
                    size = Size((knobX - trackLeft).coerceAtLeast(r), th),
                    cornerRadius = CornerRadius(r, r),
                )
            }
            drawCircle(
                color = C_TEXT,
                radius = thumbSize.toPx() / 2f,
                center = Offset(knobX, cy),
            )
        }
    }
}

/**
 * 步进器：单击 ±1，长按连续步进（旧实现从 110 调到 230 要点 120 次）。
 * 长按期间只改本地数值，松手后才通过 onCommit 下发一次，避免节拍被反复重建。
 */
@Composable
private fun StepButton(
    plus: Boolean,
    contentDesc: String,
    onStep: () -> Unit,
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
                onStep()
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
                onStep()
                onCommit()
            }
        },
        interactionSource = interactionSource,
        shape = CircleShape,
        color = if (pressed) C_PANEL2 else C_PANEL,
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = contentDesc },
    ) {
        Box(contentAlignment = Alignment.Center) {
            // 用两个色块拼出 − / ＋，避免为两种符号各配一套 drawable
            Box(
                Modifier
                    .width(17.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(C_TEXT)
            )
            if (plus) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(17.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(C_TEXT)
                )
            }
        }
    }
}

/**
 * 参数条：把原先「步频 / 倒计时 / 音量」三张等权卡片压成一条三等分窄条。
 * 点击任意一格就地展开该参数的调节面板——主界面因此保持极简。
 */
@Composable
private fun ParamStrip(
    volume: Float,
    timeoutMin: Int,
    tone: Tone,
    playback: PlaybackState,
    expanded: Param?,
    onToggle: (Param) -> Unit,
) {
    val timerValue = when {
        timeoutMin == 0 -> "不限"
        playback.running && playback.remainingSec >= 0 -> formatClock(playback.remainingSec)
        else -> "$timeoutMin min"
    }

    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = C_PANEL,
    ) {
        Row(
            Modifier.height(84.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ParamCell(
                iconRes = R.drawable.ic_volume,
                label = "音量",
                value = "${(volume * 100).roundToInt()}%",
                selected = expanded == Param.VOLUME,
                onClick = { onToggle(Param.VOLUME) },
                modifier = Modifier.weight(1f),
            )
            CellDivider()
            ParamCell(
                iconRes = R.drawable.ic_timer,
                label = "倒计时",
                value = timerValue,
                selected = expanded == Param.TIMER,
                onClick = { onToggle(Param.TIMER) },
                modifier = Modifier.weight(1f),
            )
            CellDivider()
            ParamCell(
                iconRes = if (tone == Tone.FOOTSTEP) R.drawable.ic_tone_foot else R.drawable.ic_tone_bubble,
                label = "音色",
                value = tone.display,
                selected = expanded == Param.TONE,
                onClick = { onToggle(Param.TONE) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun CellDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(52.dp)
            .background(C_LINE)
    )
}

@Composable
private fun ParamCell(
    iconRes: Int,
    label: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = if (selected) C_PANEL2 else Color.Transparent,
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = if (selected) C_ACCENT else C_TEXT2,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                label,
                color = C_TEXT2,
                fontSize = 11.sp,
                fontWeight = W_LABEL,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                value,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = W_VALUE,
                    fontFeatureSettings = TNUM,
                ),
                color = C_TEXT,
                maxLines = 1,
            )
        }
    }
}

/**
 * 展开面板的固定高度：按滑块版（标签行 + 56dp 滑块触摸区）的自然高度取。
 * 音色版内容更矮，在同一高度内居中。
 */
private val PARAM_PANEL_HEIGHT = 104.dp

/** 就地展开的参数调节面板：音量 / 倒计时用滑块，音色用三选一。 */
@Composable
private fun ParamPanel(
    param: Param,
    volume: Float,
    timeoutMin: Int,
    tone: Tone,
    onVolume: (Float) -> Unit,
    onVolumeCommit: () -> Unit,
    onTimer: (Int) -> Unit,
    onTimerCommit: () -> Unit,
    onTone: (Tone) -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = C_PANEL,
    ) {
        // 高度必须锁死：两种内容的自然高度不同（滑块版更高），
        // 否则切换标签时整个主界面会跟着上下跳。
        Column(
            Modifier
                .fillMaxWidth()
                .height(PARAM_PANEL_HEIGHT)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            when (param) {
                Param.VOLUME, Param.TIMER -> {
                    val isVolume = param == Param.VOLUME
                    val title = if (isVolume) "节拍音量" else "训练倒计时"
                    val readout = if (isVolume) {
                        "${(volume * 100).roundToInt()}%"
                    } else if (timeoutMin == 0) {
                        "不限"
                    } else {
                        "$timeoutMin min"
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, color = C_TEXT2, fontSize = 12.sp, fontWeight = W_LABEL)
                        Text(
                            readout,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = W_VALUE,
                                fontFeatureSettings = TNUM,
                            ),
                            color = C_TEXT,
                        )
                    }
                    TrackSlider(
                        fraction = if (isVolume) volume else timeoutMin.toFloat() / TIMEOUT_MAX.toFloat(),
                        onFractionChange = { fr ->
                            if (isVolume) {
                                onVolume(fr)
                            } else {
                                onTimer((fr * TIMEOUT_MAX).roundToInt().coerceIn(0, TIMEOUT_MAX))
                            }
                        },
                        onCommit = { if (isVolume) onVolumeCommit() else onTimerCommit() },
                        contentDescription = if (isVolume) {
                            "节拍音量 ${(volume * 100).roundToInt()}%"
                        } else if (timeoutMin == 0) {
                            "倒计时不限时"
                        } else {
                            "倒计时 $timeoutMin 分钟"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        steps = if (isVolume) 0 else TIMEOUT_MAX - 1,
                        trackHeight = 10.dp,
                        thumbSize = 26.dp,
                    )
                }

                Param.TONE -> {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Tone.entries.forEach { t ->
                            ToneChip(
                                tone = t,
                                selected = tone == t,
                                onClick = { onTone(t) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 音色三选一：点击即切换并试听。选中态靠颜色与描边区分，字重保持一致。 */
@Composable
private fun ToneChip(
    tone: Tone,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) C_PANEL2 else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) C_ACCENT else C_LINE),
        modifier = modifier
            .height(58.dp)
            .semantics { contentDescription = "音色 ${tone.display}" },
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painterResource(
                    if (tone == Tone.FOOTSTEP) R.drawable.ic_tone_foot else R.drawable.ic_tone_bubble
                ),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) C_ACCENT else C_TEXT2,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                tone.display,
                color = if (selected) C_TEXT else C_TEXT2,
                fontSize = 11.5.sp,
                fontWeight = W_VALUE,
                maxLines = 1,
            )
        }
    }
}

/** 底部操作区：白色主按钮（开始 / 继续 / 暂停）+ 长按停止。 */
@Composable
private fun ActionRow(
    playback: PlaybackState,
    onPrimary: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onPrimary,
            modifier = Modifier.weight(1f).height(62.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = C_TEXT,
                contentColor = C_ON_LIGHT,
            ),
        ) {
            Icon(
                painterResource(
                    if (playback.playing) R.drawable.ic_pause else R.drawable.ic_play
                ),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = C_ON_LIGHT,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !playback.running -> "开始"
                    playback.paused -> "继续"
                    else -> "暂停"
                },
                fontSize = 20.sp,
                fontWeight = W_ACTION,
            )
        }
        StopButton(
            enabled = playback.running,
            onStop = onStop,
        )
    }
}

/**
 * 停止键：长按 0.4s 停止（防误触）。
 *
 * 提示必须**常驻**在按钮上：按下之后的任何提示都会被指尖盖住，所以"要长按"这件事
 * 得在按下之前就看到。长按到位时再补一次触觉反馈，不依赖眼睛确认。
 */
@Composable
private fun StopButton(enabled: Boolean, onStop: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    var holdFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) {
            holdFraction = 0f
            return@LaunchedEffect
        }
        val start = withFrameNanos { it }
        while (true) {
            val reached = withFrameNanos { now ->
                holdFraction = ((now - start) / 1_000_000f / STOP_HOLD_MS).coerceIn(0f, 1f)
                holdFraction >= 1f
            }
            if (reached) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onStop()
                break
            }
        }
    }

    Surface(
        onClick = { /* 由长按触发，避免误触 */ },
        enabled = enabled,
        interactionSource = interactionSource,
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (pressed && enabled) C_DANGER else C_LINE),
        modifier = Modifier
            .width(92.dp)
            .height(62.dp)
            .clip(RoundedCornerShape(20.dp))
            .drawWithContent {
                drawContent()
                // 长按进度：从底部向上填充（手指可能盖住文字，边缘仍可见）
                if (holdFraction > 0f) {
                    drawRect(
                        color = C_DANGER.copy(alpha = 0.28f),
                        topLeft = Offset(0f, size.height * (1f - holdFraction)),
                        size = Size(size.width, size.height * holdFraction),
                    )
                }
            },
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_stop),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (enabled) C_DANGER else C_TEXT2,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "长按停止",
                color = if (enabled) C_DANGER else C_TEXT2,
                fontSize = 11.5.sp,
                fontWeight = W_VALUE,
                maxLines = 1,
            )
        }
    }
}

/**
 * 节拍柱：7 根高度不同的柱子按当前 BPM 的相位做指数衰减脉冲，
 * 用 graphicsLayer 只触发重绘（不触发布局），跑步中余光即可确认节奏。
 */
@Composable
private fun PulseBars(active: Boolean, bpm: Int, modifier: Modifier = Modifier) {
    val baseHeights = remember { listOf(12f, 20f, 28f, 36f, 28f, 20f, 12f) }
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

    Box(modifier.height(36.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
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
                            scaleY = 1f + (10f / h) * pulse
                            alpha = if (active) 0.45f + 0.55f * pulse else 0.3f
                        }
                        .background(C_ACCENT, RoundedCornerShape(3.dp))
                )
            }
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
