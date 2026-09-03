package com.example.runmetronome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        setContent {
            MaterialTheme {
                AppScreen()
            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppScreen() {
    val context = LocalContext.current

    var bpm by remember { mutableFloatStateOf(180f) }
    var volume by remember { mutableFloatStateOf(0.8f) }
    var timeoutMin by remember { mutableIntStateOf(0) }
    var tone by remember { mutableStateOf(Tone.BUBBLE1) }
    var running by remember { mutableStateOf(false) }

    // 选音色即试听（独立提示音 SoundPool，不干扰节拍器）。注意：play 要用 load 返回的 soundID，而非 resId。
    val previewState = remember {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder()
            .setAudioAttributes(attrs)
            .setMaxStreams(1)
            .build()
        val ids = mutableMapOf<Tone, Int>()
        Tone.entries.forEach { ids[it] = sp.load(context, it.resId, 1) }
        sp to ids
    }
    DisposableEffect(Unit) { onDispose { previewState.first.release() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("跑者节拍器", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "${bpm.toInt()}",
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text("BPM", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))

        SectionLabel("步频")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = bpm,
                onValueChange = { bpm = it },
                valueRange = 120f..220f,
                steps = 99,
                modifier = Modifier.weight(1f)
            )
            Text("${bpm.toInt()}", modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }

        SectionLabel("节拍音量")
        Slider(value = volume, onValueChange = { volume = it }, modifier = Modifier.fillMaxWidth())

        SectionLabel("倒计时（分钟，0 = 不限时）")
        Slider(
            value = timeoutMin.toFloat(),
            onValueChange = { timeoutMin = it.toInt() },
            valueRange = 0f..90f,
            steps = 90,
            modifier = Modifier.fillMaxWidth()
        )
        Text(if (timeoutMin > 0) "${timeoutMin} 分钟" else "不限时", style = MaterialTheme.typography.bodyMedium)

        SectionLabel("音色")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tone.entries.forEach { t ->
                FilterChip(
                    selected = tone == t,
                    onClick = {
                        tone = t
                        val sid = previewState.second[t]
                        if (sid != null) previewState.first.play(sid, 0.7f, 0.7f, 1, 0, 1f)
                    },
                    label = { Text(t.display) }
                )
            }
        }

        Spacer(Modifier.width(1.dp))
        Button(
            onClick = {
                val intent = Intent(context, MetronomeService::class.java).apply {
                    action = if (running) MetronomeService.ACTION_STOP else MetronomeService.ACTION_START
                    putExtra(MetronomeService.EXTRA_BPM, bpm.toDouble())
                    putExtra(MetronomeService.EXTRA_TONE, tone.name)
                    putExtra(MetronomeService.EXTRA_VOLUME, volume)
                    putExtra(MetronomeService.EXTRA_TIMEOUT_MIN, timeoutMin)
                    putExtra(MetronomeService.EXTRA_ACCENT, 4)
                }
                ContextCompat.startForegroundService(context, intent)
                running = !running
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            Text(if (running) "停止" else "开始", fontSize = 20.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}
