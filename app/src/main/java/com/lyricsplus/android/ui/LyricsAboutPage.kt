package com.lyricsplus.android.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsplus.android.BuildConfig
import com.lyricsplus.android.MainViewModel
import kotlin.math.*

private data class AboutBurst(val id: Long, val origin: Offset, val symbol: String, val born: Long, val strength: Float)

@Composable
fun LyricsAboutPage(viewModel: MainViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val density = LocalDensity.current
    var bursts by remember { mutableStateOf(emptyList<AboutBurst>()) }
    var now by remember { mutableLongStateOf(0L) }
    var nextId by remember { mutableLongStateOf(0L) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var combo by remember { mutableIntStateOf(0) }
    LaunchedEffect(bursts.isNotEmpty()) {
        var previousFrame: Long? = null
        while (bursts.isNotEmpty()) {
            withFrameMillis { frame ->
                now += previousFrame?.let { (frame - it).coerceIn(0L, 250L) } ?: 16L
                previousFrame = frame
            }
            bursts = bursts.filter { now - it.born < 1100 }
        }
    }
    Box(modifier.background(Color(0xFF101010)).pointerInput(Unit) {
        // Observe genuine taps without consuming link/back clicks or treating a swipe as a tap.
        awaitPointerEventScope {
            var start: Offset? = null
            var downTime = 0L
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) moved = true
                val change = event.changes.firstOrNull() ?: continue
                if (change.pressed && !change.previousPressed) {
                    start = change.position
                    downTime = SystemClock.elapsedRealtime()
                    moved = false
                }
                if (start != null && (change.position - start!!).getDistance() > viewConfiguration.touchSlop) moved = true
                if (change.previousPressed && !change.pressed) {
                    val time = SystemClock.elapsedRealtime()
                    if (start != null && !moved && time - downTime < 400) {
                        combo = if (time - lastTap < 650) (combo + 1).coerceAtMost(8) else 0
                        lastTap = time
                        val symbols = listOf("★", "♥", "👍")
                        bursts = (bursts + AboutBurst(nextId, change.position, symbols[(nextId % 3).toInt()], now,
                            1f + combo * .17f)).takeLast(12)
                        nextId++
                    }
                    start = null
                }
            }
        }
    }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("关于项目", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onBack) { Text("✕", color = Color.White, fontSize = 20.sp) }
            }
            Spacer(Modifier.height(32.dp))
            Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(Color(0xFFD3A5FF), Color(0xFF8865E8))), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center) {
                Text("L", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("lyrics", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text(BuildConfig.VERSION_NAME, color = Color(0xFF8D9490), fontSize = 13.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 28.dp))
            AboutLink("项目地址", "GitHub") { uri.openUri("https://github.com/Artriai/lyrics") }
            Spacer(Modifier.height(12.dp))
            AboutLink("检查更新", BuildConfig.VERSION_NAME) { viewModel.checkForUpdates() }
        }
        Canvas(Modifier.fillMaxSize()) {
            bursts.forEach { burst ->
                val t = ((now - burst.born).coerceAtLeast(0) / 1100f).coerceIn(0f, 1f)
                val fade = (1f - t).pow(2)
                val radius = (18.dp.toPx() + 65.dp.toPx() * (1f - (1f - t).pow(3))) * burst.strength
                drawCircle(Color(0xFFD3A5FF).copy(alpha = fade * .25f), radius, burst.origin, style = Stroke(1.dp.toPx()))
                repeat(12) { i ->
                    val angle = (i * PI / 6 + burst.id * .37).toFloat()
                    val direction = Offset(cos(angle), sin(angle))
                    val color = listOf(Color(0xFFD3A5FF), Color(0xFF4AD295), Color(0xFFFFD578))[i % 3].copy(alpha = fade)
                    val tip = burst.origin + direction * radius
                    if (i % 3 == 0) {
                        val star = Path()
                        repeat(10) { point ->
                            val a = -PI / 2 + point * PI / 5
                            val r = (if (point % 2 == 0) 4.dp.toPx() else 1.7.dp.toPx()) * burst.strength * (1f - t)
                            val x = tip.x + cos(a).toFloat() * r
                            val y = tip.y + sin(a).toFloat() * r
                            if (point == 0) star.moveTo(x, y) else star.lineTo(x, y)
                        }
                        star.close()
                        drawPath(star, color)
                    } else {
                        val end = tip + direction * (8.dp.toPx() * burst.strength * (1f - t))
                        drawLine(color.copy(alpha = fade * .12f), tip, end, 5.dp.toPx())
                        drawLine(color, tip, end, 1.5.dp.toPx())
                    }
                }
            }
        }
        bursts.forEach { burst ->
            key(burst.id) {
                val t = ((now - burst.born).coerceAtLeast(0) / 1100f).coerceIn(0f, 1f)
                val pop = (1f - exp(-t * 11f) * cos(t * 23f)) * burst.strength
                Box(Modifier.offset {
                    with(density) { IntOffset((burst.origin.x - 24.dp.toPx()).roundToInt(),
                        (burst.origin.y - 24.dp.toPx() - 30.dp.toPx() * t * t).roundToInt()) }
                }.size(48.dp).graphicsLayer {
                    alpha = ((1f - t) / .45f).coerceIn(0f, 1f)
                    scaleX = pop; scaleY = pop
                    rotationZ = sin(t * 16f) * (1f - t) * if (burst.id % 2 == 0L) 12f else -12f
                }, contentAlignment = Alignment.Center) {
                    Text(burst.symbol, color = if (burst.symbol == "★") Color(0xFFFFD578) else Color(0xFFFF96C1), fontSize = 30.sp)
                }
            }
        }
    }
}

@Composable
private fun AboutLink(title: String, detail: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color(0xFF181A19), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0x1FFFFFFF)), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(detail, color = Color(0xFF8D9490), fontSize = 12.sp)
        }
    }
}
