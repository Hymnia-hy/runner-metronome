package com.example.runmetronome

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

// —— 设计规范配色 ——
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

    var bpm by remember { mutableFloatStateOf(180f) }
    var volume by remember { mutableFloatStateOf(1.0f) }
    var timeoutMin by remember { mutableIntStateOf(0) }
    var tone by remember { mutableStateOf(Tone.BUBBLE1) }
    var status by remember { mutableStateOf(RunState.IDLE) }
    var showVolume by remember { mutableStateOf(false) }

    val previewState = remember {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setAudioAttributes(attrs).setMaxStreams(1).build()
        val ids = mutableMapOf<Tone, Int>()
        Tone.entries.forEach { ids[it] = sp.load(context, it.resId, 1) }
        sp to ids
    }
    DisposableEffect(Unit) { onDispose { previewState.first.release() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(C_BG)
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 顶栏：品牌 + 右上角音量键
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(C_SURFACE, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(10.dp).background(C_ACCENT, RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.width(10.dp))
                Text("Runner Metronome", color = C_TEXT, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            // 音量键：点击弹出音量调节
            Surface(
                onClick = { showVolume = true },
                shape = RoundedCornerShape(12.dp),
                color = C_SURFACE,
                border = BorderStroke(1.dp, C_LINE),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("🔊", fontSize = 18.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        // BPM 大数字
        Text("CADENCE", color = C_TEXT2, fontSize = 13.sp, letterSpacing = 3.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
            Text(
                "${bpm.toInt()}",
                fontSize = 128.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                color = if (status == RunState.PLAYING) C_ACCENT else C_TEXT
            )
            Spacer(Modifier.width(8.dp))
            Text("BPM", color = C_ACCENT, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 22.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            StepperBtn("−") { bpm = (bpm - 1f).coerceIn(110f, 230f) }
            Spacer(Modifier.width(14.dp))
            StepperBtn("＋") { bpm = (bpm + 1f).coerceIn(110f, 230f) }
        }

        Spacer(Modifier.height(16.dp))
        SliderCard(title = "步频范围", value = "${bpm.toInt()}", unit = "BPM") {
            Slider(value = bpm, onValueChange = { bpm = it }, valueRange = 110f..230f, steps = 119)
        }
        SliderCard(title = "训练倒计时", value = if (timeoutMin > 0) "$timeoutMin" else "不限", unit = if (timeoutMin > 0) "min" else "") {
            Slider(value = timeoutMin.toFloat(), onValueChange = { timeoutMin = it.toInt() }, valueRange = 0f..300f, steps = 300)
        }

        // 音色卡（横向滑动，无描述小字）
        Spacer(Modifier.height(16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(Tone.entries) { t ->
                ToneCard(t, selected = tone == t, onClick = {
                    tone = t
                    val sid = previewState.second[t]
                    if (sid != null) previewState.first.play(sid, 0.7f, 0.7f, 1, 0, 1f)
                })
            }
        }

        Spacer(Modifier.height(24.dp))
        // 主按钮 + 停止，同一行
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Button(
                onClick = {
                    when (status) {
                        RunState.IDLE -> { sendStart(context, bpm, tone, volume, timeoutMin); status = RunState.PLAYING }
                        RunState.PLAYING -> { sendAction(context, MetronomeService.ACTION_PAUSE); status = RunState.PAUSED }
                        RunState.PAUSED -> { sendAction(context, MetronomeService.ACTION_RESUME); status = RunState.PLAYING }
                    }
                },
                modifier = Modifier.weight(1f).height(64.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (status == RunState.PLAYING) C_SURFACE2 else C_ACCENT,
                    contentColor = if (status == RunState.PLAYING) C_TEXT else C_ON_ACCENT
                )
            ) {
                Text(if (status == RunState.PLAYING) "暂停" else "开始", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            }
            OutlinedButton(
                onClick = {
                    if (status != RunState.IDLE) sendAction(context, MetronomeService.ACTION_STOP)
                    status = RunState.IDLE
                },
                modifier = Modifier.width(96.dp).height(64.dp),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, C_LINE),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = C_WARN)
            ) {
                Text("停止", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // 音量弹窗（右上角音量键唤起）
    if (showVolume) {
        AlertDialog(
            onDismissRequest = { showVolume = false },
            confirmButton = { TextButton(onClick = { showVolume = false }) { Text("完成", color = C_ACCENT) } },
            containerColor = C_SURFACE,
            titleContentColor = C_TEXT,
            title = { Text("节拍音量") },
            text = {
                Slider(value = volume, onValueChange = {
                    volume = it
                    if (status != RunState.IDLE) {
                        val vi = Intent(context, MetronomeService::class.java).apply {
                            action = MetronomeService.ACTION_VOLUME
                            putExtra(MetronomeService.EXTRA_VOLUME, it)
                        }
                        ContextCompat.startForegroundService(context, vi)
                    }
                }, valueRange = 0f..1f)
            }
        )
    }
}

@Composable
private fun StepperBtn(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = C_SURFACE,
        border = BorderStroke(1.dp, C_LINE),
        modifier = Modifier.size(56.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = C_TEXT, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SliderCard(title: String, value: String, unit: String, slider: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = C_SURFACE),
        border = BorderStroke(1.dp, C_LINE)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = C_TEXT2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(if (unit.isEmpty()) value else "$value $unit", color = C_TEXT, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            slider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToneCard(t: Tone, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) C_TONE_ON else C_SURFACE,
        border = BorderStroke(1.5.dp, if (selected) C_ACCENT else C_LINE),
        modifier = Modifier.width(96.dp)
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(48.dp).background(if (selected) C_ACCENT else C_SURFACE2, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(t.display.first().toString(), color = if (selected) C_ON_ACCENT else C_TEXT, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(t.display, color = C_TEXT, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

enum class RunState { IDLE, PLAYING, PAUSED }

private fun sendAction(context: Context, action: String) {
    val i = Intent(context, MetronomeService::class.java).apply { this.action = action }
    ContextCompat.startForegroundService(context, i)
}

private fun sendStart(context: Context, bpm: Float, tone: Tone, volume: Float, timeoutMin: Int) {
    val i = Intent(context, MetronomeService::class.java).apply {
        action = MetronomeService.ACTION_START
        putExtra(MetronomeService.EXTRA_BPM, bpm.toDouble())
        putExtra(MetronomeService.EXTRA_TONE, tone.name)
        putExtra(MetronomeService.EXTRA_VOLUME, volume)
        putExtra(MetronomeService.EXTRA_TIMEOUT_MIN, timeoutMin)
    }
    ContextCompat.startForegroundService(context, i)
}
