package id.bram.lanjutplaylist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Taps the radio's decoded audio (fed by ExoPlayer's TeeAudioProcessor — no microphone
 * permission needed) and turns it into band levels for the visualizer.
 */
object Viz {
    private const val N = 1024
    private val ring = FloatArray(N)
    private var pos = 0
    private var sampleRate = 44100
    private var channels = 2
    private var pcm16 = true
    @Volatile var lastFeed = 0L; private set

    fun configure(rate: Int, ch: Int, encoding: Int) {
        sampleRate = if (rate > 0) rate else 44100
        channels = max(1, ch)
        pcm16 = encoding == C.ENCODING_PCM_16BIT
    }

    /** Called on the playback thread with PCM data. */
    fun feed(buf: ByteBuffer) {
        if (!pcm16) return
        val b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = 2 * channels
        synchronized(ring) {
            while (b.remaining() >= frameBytes) {
                var sum = 0
                for (c in 0 until channels) sum += b.short.toInt()
                ring[pos] = sum / (channels * 32768f)
                pos = (pos + 1) % N
            }
        }
        lastFeed = System.currentTimeMillis()
    }

    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }

    /** Fill `out` with band levels 0..1 (log-spaced 50 Hz–12 kHz). */
    fun bands(out: FloatArray) {
        synchronized(ring) {
            for (i in 0 until N) re[i] = ring[(pos + i) % N] * window[i]
        }
        java.util.Arrays.fill(im, 0f)
        fft(re, im)
        val nb = out.size
        val lo = 50.0; val hi = min(12000.0, sampleRate / 2.0)
        for (k in 0 until nb) {
            val f0 = lo * (hi / lo).pow(k.toDouble() / nb)
            val f1 = lo * (hi / lo).pow((k + 1).toDouble() / nb)
            val i0 = max(1, (f0 * N / sampleRate).toInt())
            val i1 = max(i0 + 1, (f1 * N / sampleRate).toInt())
            var m = 0f
            for (i in i0 until min(i1, N / 2)) m = max(m, sqrt(re[i] * re[i] + im[i] * im[i]))
            // to dB, map roughly -60..0 dB → 0..1, with a little treble lift
            val db = 20 * ln(max(m, 1e-6f).toDouble()) / ln(10.0) - 20
            out[k] = (((db + 60) / 60) * (1 + 0.35 * k / nb)).toFloat().coerceIn(0f, 1f)
        }
    }

    private fun fft(x: FloatArray, y: FloatArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = x[i]; x[i] = x[j]; x[j] = t; t = y[i]; y[i] = y[j]; y[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = x[i + k + len / 2] * cr - y[i + k + len / 2] * ci
                    val ai = x[i + k + len / 2] * ci + y[i + k + len / 2] * cr
                    x[i + k + len / 2] = x[i + k] - ar; y[i + k + len / 2] = y[i + k] - ai
                    x[i + k] += ar; y[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Cover image for Android Auto (it can't show animations): station name on a gradient. */
    private val artCache = HashMap<String, Bitmap>()

    fun stationArt(name: String, info: String): Bitmap = artCache.getOrPut("$name|$info") {
        val s = 512
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, s.toFloat(), s.toFloat(),
                Color.parseColor("#FB8C00"), Color.parseColor("#B71C1C"), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), bg)
        // decorative equalizer bars
        val bar = Paint().apply { color = Color.argb(70, 255, 255, 255) }
        val hs = floatArrayOf(.35f, .6f, .45f, .8f, .55f, .9f, .5f, .7f, .4f, .65f, .3f)
        val w = s / (hs.size * 1.6f)
        hs.forEachIndexed { i, h ->
            val x = 40 + i * w * 1.6f
            c.drawRoundRect(RectF(x, s - 40 - h * 200, x + w, s - 40f), 8f, 8f, bar)
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        }
        t.textSize = 120f; c.drawText("📻", s / 2f, 170f, t)
        var size = 72f
        t.textSize = size
        while (t.measureText(name) > s - 50 && size > 30) { size -= 4; t.textSize = size }
        c.drawText(name, s / 2f, 280f, t)
        t.typeface = Typeface.DEFAULT; t.textSize = 36f; t.color = Color.argb(230, 255, 255, 255)
        c.drawText(info, s / 2f, 335f, t)
        bmp
    }
}

/** Animated bar visualizer for the playing radio card. */
class VizView(context: Context) : View(context) {
    private val n = 28
    private val target = FloatArray(n)
    private val level = FloatArray(n)
    private val peak = FloatArray(n)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()
    var active = false

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        paint.shader = LinearGradient(0f, h.toFloat(), 0f, 0f,
            intArrayOf(Color.parseColor("#B71C1C"), Color.parseColor("#FB8C00"), Color.parseColor("#FFD54F")),
            null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val live = active && System.currentTimeMillis() - Viz.lastFeed < 600
        if (live) Viz.bands(target) else java.util.Arrays.fill(target, 0f)
        val w = width.toFloat(); val h = height.toFloat()
        val gap = w / n * 0.28f
        val bw = w / n - gap
        var moving = false
        for (i in 0 until n) {
            // fast attack, slower release
            level[i] = if (target[i] > level[i]) level[i] + (target[i] - level[i]) * 0.6f
            else level[i] - (level[i] - target[i]) * 0.18f
            peak[i] = if (level[i] > peak[i]) level[i] else max(0f, peak[i] - 0.012f)
            if (level[i] > 0.005f || peak[i] > 0.005f) moving = true
            val x = i * (bw + gap) + gap / 2
            val bh = max(h * 0.03f, level[i] * h * 0.92f)
            rect.set(x, h - bh, x + bw, h)
            c.drawRoundRect(rect, bw / 3, bw / 3, paint)
            if (peak[i] > 0.02f) {
                val py = h - peak[i] * h * 0.92f - h * 0.04f
                c.drawRect(x, py, x + bw, py + h * 0.025f, peakPaint)
            }
        }
        if (live || moving) postInvalidateOnAnimation()
    }

    fun kick() { postInvalidateOnAnimation() }
}
