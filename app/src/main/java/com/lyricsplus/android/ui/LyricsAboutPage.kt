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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsplus.android.BuildConfig
import com.lyricsplus.android.MainViewModel
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun LyricsAboutPage(viewModel: MainViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
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
            bursts = bursts.filter { now - it.born < 1400 }
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
                        val shapes = AboutShape.values()
                        bursts = (bursts + AboutBurst(nextId, change.position, now,
                            1f + combo * .17f, shapes[(nextId % shapes.size).toInt()])).takeLast(12)
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
            Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(Color(0xFFFF8B86), Color(0xFFE64055))), RoundedCornerShape(14.dp)),
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
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = "3D点击特效"
            stateDescription = "立体图形：${bursts.size}"
        }) {
            drawAboutTap3D(bursts, now)
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
