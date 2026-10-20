package app.loop

import android.Manifest
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.*
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private val eng = Engine()
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val rq = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it[Manifest.permission.RECORD_AUDIO] == true) eng.start(getSystemService(AudioManager::class.java))
            else eng.info = "Нет разрешения на микрофон"
        }
        rq.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT))
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen(eng) } }
    }
    override fun onDestroy() { eng.stop(); super.onDestroy() }
}

@Composable fun Lbl(n: String, v: Float, r: ClosedFloatingPointRange<Float>, f: (Float) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(n, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.width(84.dp))
        Slider(v, f, valueRange = r, modifier = Modifier.weight(1f))
    }

@Composable fun Screen(e: Engine) {
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { tick.longValue = it } }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0F1115)).systemBarsPadding().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(e.info, color = Color.Gray, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1f, .5f, .25f).forEach { s -> FilterChip(e.step == s, { e.step = s }, label = { Text("$s с") }) }
        }
        Lbl("Aux вход", e.aux, 0f..1f) { e.aux = it }
        Lbl("Задержка ${e.lat.toInt()} мс", e.lat, 0f..500f) { e.lat = it }
        for (i in 0 until 5) TrackView(e, i, tick)
    }
}

@Composable fun TrackView(e: Engine, i: Int, tick: State<Long>) {
    val t = e.tracks[i]
    var fx by remember { mutableStateOf(false) }
    val col = when (t.st) { 1 -> Color(0xFFFFB020); 2, 3 -> Color(0xFFFF4D5E); 4 -> Color(0xFF3DDC97); else -> Color(0xFF2A2F3A) }
    Column(Modifier.fillMaxWidth().background(Color(0xFF1A1D24), RoundedCornerShape(14.dp)).border(2.dp, col, RoundedCornerShape(14.dp)).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", color = Color.Gray, modifier = Modifier.width(18.dp))
            Slider(t.vol, { t.vol = it }, modifier = Modifier.weight(1f))
            TextButton({ fx = !fx }) { Text("FX") }
            TextButton({ e.clear(i) }) { Text("✕") }
            Button({ e.rec(i) }, colors = ButtonDefaults.buttonColors(containerColor = if (t.st in 1..3) col else Color(0xFF2A2F3A))) {
                Text(if (t.st in 1..3) "■" else "●")
            }
        }
        Bar(e, t, tick)
        if (fx) Column {
            Lbl("Delay время", t.dTime, .05f..1f) { t.dTime = it }
            Lbl("Delay микс", t.dMix, 0f..1f) { t.dMix = it }
            Lbl("Reverb микс", t.rMix, 0f..1f) { t.rMix = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ t.hp = !t.hp; t.cut = if (t.hp) 0f else 1f }) { Text(if (t.hp) "HP" else "LP") }
                Slider(t.cut, { t.cut = it }, modifier = Modifier.weight(1f))
            }
        }
    }
}

// Полоса дорожки: тянем за белые края, чтобы выбрать повторяемый участок (с привязкой к сетке).
@Composable fun Bar(e: Engine, t: Track, tick: State<Long>) {
    var drag by remember { mutableIntStateOf(0) }
    Canvas(
        Modifier.fillMaxWidth().height(56.dp).pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { o ->
                    val l = e.L
                    if (l > 0 && t.data != null) { val x = o.x / size.width * l; drag = if (abs(x - t.s) < abs(x - t.e)) 1 else 2 }
                },
                onDragEnd = { drag = 0 }, onDragCancel = { drag = 0 },
                onHorizontalDrag = { c, _ ->
                    val l = e.L
                    if (drag != 0 && l > 0) {
                        val q = (e.step * SR).toInt()
                        val x = ((c.position.x / size.width * l / q).roundToInt() * q).coerceIn(0, l)
                        if (drag == 1) t.s = min(x, t.e - q).coerceAtLeast(0) else t.e = max(x, t.s + q).coerceAtMost(l)
                    }
                }
            )
        }
    ) {
        tick.value
        val w = size.width; val h = size.height; val l = e.L
        drawRect(Color(0xFF2A2F3A))
        if (t.data != null && l > 0) {
            val a = t.s.toFloat() / l * w; val b = t.e.toFloat() / l * w
            drawRect(Color(0x334DA3FF), Offset(a, 0f), Size(b - a, h))
            t.peaks?.let { pk ->
                pk.forEachIndexed { k, v ->
                    val x = (k + .5f) / pk.size * w
                    val c = if (x in a..b) Color(0xFF4DA3FF) else Color(0xFF555B6B)
                    drawLine(c, Offset(x, h / 2 - v * h / 2), Offset(x, h / 2 + v * h / 2), 2f)
                }
            }
            val px = e.idx(t) / l * w
            drawRect(Color(0xFF3DDC97), Offset(a, h - 6f), Size(max(0f, px - a), 6f))
            drawLine(Color(0xFF3DDC97), Offset(px, 0f), Offset(px, h), 4f)
            drawRect(Color.White, Offset(a - 3f, 0f), Size(6f, h))
            drawRect(Color.White, Offset(b - 3f, 0f), Size(6f, h))
        }
    }
}
