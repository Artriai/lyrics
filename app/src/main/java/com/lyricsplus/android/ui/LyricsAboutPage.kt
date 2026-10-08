package com.lyricsplus.android.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsplus.android.BuildConfig
import com.lyricsplus.android.MainViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private data class AboutParticle(val id: Long, val origin: Offset, val symbol: String, val born: Long)

@Composable
fun LyricsAboutPage(viewModel: MainViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    var particles by remember { mutableStateOf(emptyList<AboutParticle>()) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var nextId by remember { mutableLongStateOf(0L) }
    LaunchedEffect(particles.isNotEmpty()) {
        while (particles.isNotEmpty()) {
            now = SystemClock.elapsedRealtime()
            particles = particles.filter { now - it.born < 1000 }
            delay(16)
        }
    }
    Box(modifier.background(Color(0xFF101010)).pointerInput(Unit) {
        // Observe taps without consuming them: project/update buttons still work.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.firstOrNull { it.previousPressed && !it.pressed }?.let { up ->
                    val time = SystemClock.elapsedRealtime()
                    val symbols = listOf("♥", "★", "👍")
                    particles = (particles + AboutParticle(nextId++, up.position, symbols[(nextId % 3).toInt()], time)).takeLast(18)
                }
            }
        }
    }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("关于项目", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onBack) { Text("✕", color = Color.White, fontSize = 20.sp) }
            }
            Spacer(Modifier.height(40.dp))
            Box(Modifier.size(64.dp).background(Brush.linearGradient(listOf(Color(0xFFD3A5FF), Color(0xFF8865E8))), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center) {
                Text("L", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("lyrics", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 18.dp))
            Text("${BuildConfig.VERSION_NAME} · 独立歌词提词板", color = Color(0xFF8D9490), fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 32.dp))
            AboutLink("项目地址", "GitHub") { uri.openUri("https://github.com/Artriai/lyrics-plus-android/tree/lyrics") }
            Spacer(Modifier.height(12.dp))
            AboutLink("检查更新", "${BuildConfig.VERSION_NAME}") { viewModel.checkForUpdates() }
            Spacer(Modifier.weight(1f))
            Text("Made with love. ♥", color = Color(0x668D9490), fontSize = 12.sp, modifier = Modifier.padding(bottom = 24.dp))
        }
        particles.forEach { particle ->
            key(particle.id) {
                val progress = ((now - particle.born).coerceAtLeast(0) / 1000f).coerceIn(0f, 1f)
                Text(particle.symbol, color = if (particle.symbol == "★") Color(0xFFFFD578) else Color(0xFFD3A5FF),
                    fontSize = 28.sp,
                    modifier = Modifier.offset { IntOffset((particle.origin.x - 20).roundToInt(), (particle.origin.y - 30 - progress * 150).roundToInt()) }
                        .graphicsLayer { alpha = 1f - progress; scaleX = .8f + progress * .5f; scaleY = scaleX; rotationZ = progress * if (particle.id % 2 == 0L) 15f else -15f })
            }
        }
    }
}

@Composable
private fun AboutLink(title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color(0xFF181A19), shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(detail, color = Color(0xFF8D9490), fontSize = 13.sp)
        }
    }
}
