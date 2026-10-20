package app.loop

import android.media.*
import androidx.compose.runtime.*
import kotlin.math.*

const val SR = 48000
const val CH = 480

class Track {
    @Volatile var data: FloatArray? = null
    @Volatile var s = 0
    @Volatile var e = 0
    var st by mutableIntStateOf(0) // 0 пусто, 1 ждёт начала цикла, 2 запись, 3 доводка до сетки, 4 играет
    var peaks by mutableStateOf<FloatArray?>(null)
    var vol by mutableFloatStateOf(.8f)
    var dMix by mutableFloatStateOf(0f)
    var dTime by mutableFloatStateOf(.3f)
    var rMix by mutableFloatStateOf(0f)
    var cut by mutableFloatStateOf(1f)
    var hp by mutableStateOf(false)
    var lp = 0f
    val dl = FloatArray(SR); var dw = 0
    val cb = Array(4) { FloatArray(intArrayOf(1557, 1617, 1491, 1422)[it]) }
    val ci = IntArray(4)
}

class Engine {
    val tracks = List(5) { Track() }
    var step by mutableFloatStateOf(1f)
    var lat by mutableFloatStateOf(0f)
    var aux by mutableFloatStateOf(0f)
    var info by mutableStateOf("запуск…")
    @Volatile var L = 0
    @Volatile var pos = 0
    @Volatile var cur = -1
    @Volatile var run = false
    @Volatile var cnt = 0
    @Volatile var tgt = 0
    var acc = FloatArray(0)

    fun stop() { run = false }

    fun idx(t: Track): Float {
        val l = L; val r = t.e - t.s
        if (l == 0 || r <= 0) return 0f
        return (t.s + Math.floorMod(pos % l - t.s, r)).toFloat()
    }

    fun rec(i: Int) {
        val t = tracks[i]
        if (cur == i) {
            if (L == 0 && t.st == 2) { val q = (step * SR).toInt(); tgt = max(1, (cnt + q - 1) / q) * q; t.st = 3 }
            else if (t.st == 1 || t.st == 2) { cur = -1; t.st = if (t.data != null) 4 else 0 }
            return
        }
        if (cur >= 0 || L < 0) return
        cnt = 0
        if (L == 0) { acc = FloatArray(SR * 30); tgt = 0; t.st = 2 } else { acc = FloatArray(L); tgt = L; t.st = 1 }
        cur = i
    }

    fun clear(i: Int) {
        val t = tracks[i]
        t.data = null; t.peaks = null; t.st = 0
        t.dl.fill(0f); t.cb.forEach { it.fill(0f) }
        if (cur == i) cur = -1
        if (tracks.none { it.data != null } && cur < 0) L = 0
    }

    private fun finish(t: Track) {
        val n = cnt
        val sh = (lat * SR / 1000f).toInt() % n // компенсация задержки: сдвиг по кругу
        val a = acc
        val d = FloatArray(n) { a[(it + sh) % n] }
        t.peaks = FloatArray(200) { k -> var m = 0f; for (j in k * n / 200 until (k + 1) * n / 200) m = max(m, abs(d[j])); m }
        t.s = 0; t.e = n; t.data = d
        if (L == 0) L = n
        t.st = 4; cur = -1
    }

    fun start(am: AudioManager) {
        if (run) return
        val wired = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)
        val inD = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in wired }
        val outD = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val ar = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(CH * 4 * 8).build()
        val ob = max(CH * 4 * 6, AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT))
        val at = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(ob).setTransferMode(AudioTrack.MODE_STREAM).build()
        if (inD != null) ar.setPreferredDevice(inD)
        if (outD != null) at.setPreferredDevice(outD)
        run = true
        Thread {
            val inb = FloatArray(CH); val out = FloatArray(CH)
            var shown = false
            ar.startRecording(); at.play()
            try {
                while (run) {
                    var n = 0
                    while (n < CH) { val r = ar.read(inb, n, CH - n, AudioRecord.READ_BLOCKING); if (r < 0) return@Thread; n += r }
                    val l0 = L; val p = pos
                    val c = cur
                    if (c >= 0) {
                        val t = tracks[c]; var from = -1
                        if (t.st == 1) { val off = l0 - p; if (off <= CH) { t.st = 2; from = off } }
                        else if (t.st == 2 || t.st == 3) from = 0
                        if (from >= 0) {
                            val cap = if (l0 > 0) l0 else if (tgt > 0) min(tgt, acc.size) else acc.size
                            val m = min(CH - from, cap - cnt)
                            if (m > 0) { System.arraycopy(inb, from, acc, cnt, m); cnt += m }
                            if (cnt >= cap) finish(t)
                        }
                    }
                    out.fill(0f)
                    if (l0 > 0) for (t in tracks) {
                        val d = t.data ?: continue
                        val rr = t.e - t.s; if (rr <= 0) continue
                        val a = (1 - exp(-2 * PI * 20.0 * 1000.0.pow(t.cut.toDouble()) / SR)).toFloat()
                        val dt = (t.dTime * SR).toInt().coerceIn(1, SR - 1)
                        for (i in 0 until CH) {
                            val q = (p + i) % l0
                            var x = d[t.s + Math.floorMod(q - t.s, rr)] * t.vol
                            t.lp += a * (x - t.lp); x = if (t.hp) x - t.lp else t.lp
                            val dly = t.dl[(t.dw - dt + SR) % SR]
                            t.dl[t.dw] = x + dly * .4f; t.dw = (t.dw + 1) % SR
                            var r = 0f
                            for (k in 0..3) { val b = t.cb[k]; val cv = b[t.ci[k]]; b[t.ci[k]] = x * .25f + cv * .84f; t.ci[k] = (t.ci[k] + 1) % b.size; r += cv }
                            out[i] += x + dly * t.dMix + r * .5f * t.rMix
                        }
                    }
                    val ax = aux
                    for (i in 0 until CH) out[i] = tanh(out[i] + inb[i] * ax)
                    at.write(out, 0, CH, AudioTrack.WRITE_BLOCKING)
                    pos = if (l0 == 0) 0 else (p + CH) % l0
                    if (!shown) {
                        shown = true
                        info = "Вход: ${ar.routedDevice?.productName ?: "?"} (тип ${ar.routedDevice?.type}) → Выход: ${at.routedDevice?.productName ?: "?"} (тип ${at.routedDevice?.type})"
                    }
                }
            } finally { ar.release(); at.release(); run = false }
        }.apply { priority = Thread.MAX_PRIORITY; start() }
    }
}
