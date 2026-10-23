package id.bram.lanjutplaylist

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

private val ORANGE = Color.parseColor("#FB8C00")
private val DIM = Color.parseColor("#8A8A8A")

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Context.pill(label: String, onClick: () -> Unit) = TextView(this).apply {
    text = label; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
    background = GradientDrawable().apply { setColor(Color.parseColor("#333333")); cornerRadius = dp(18).toFloat() }
    setOnClickListener { onClick() }
}

/** Compact lyrics (current line + next two) with sync buttons, for the radio card. */
class LyricsPanel(context: Context) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val rows = List(3) { TextView(context) }
    private val info = TextView(context)
    private var shownIdx = -2
    private var firstIdx = 0

    private val tick = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 250) }
    }
    private val listener: () -> Unit = { shownIdx = -2; update() }

    init {
        orientation = VERTICAL
        setPadding(0, context.dp(8), 0, 0)
        info.apply { textSize = 13f; setTextColor(DIM) }
        addView(info)
        rows.forEachIndexed { i, tv ->
            tv.setPadding(0, context.dp(2), 0, context.dp(2))
            tv.setOnClickListener { Lyrics.syncTo(firstIdx + i); shownIdx = -2; update() }
            addView(tv)
        }
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, context.dp(8), 0, 0) }
        bar.addView(context.pill("−1 dtk") { Lyrics.nudge(-1000) })
        bar.addView(context.pill("+1 dtk") { Lyrics.nudge(1000) }, LayoutParams(-2, -2).apply { leftMargin = context.dp(8) })
        bar.addView(View(context), LayoutParams(0, 1, 1f))
        bar.addView(context.pill("⛶ Karaoke") {
            context.startActivity(Intent(context, LyricsActivity::class.java))
        })
        addView(bar)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Lyrics.listeners.add(listener); handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        Lyrics.listeners.remove(listener); handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun update() {
        val l = Lyrics.lines
        if (l == null) {
            rows.forEach { it.visibility = GONE }
            info.text = Lyrics.status ?: (if (Lyrics.plain != null) "Lirik tanpa timing — buka ⛶ Karaoke"
                else if (Lyrics.isRadio) "Lirik muncul kalau radio mengirim judul lagu" else "Mencari lirik…")
            return
        }
        val idx = Lyrics.currentIndex()
        if (idx == shownIdx) return
        shownIdx = idx
        info.text = if (Lyrics.isRadio) "Tap baris yang sedang dinyanyikan untuk menyinkronkan" else "Lirik sinkron otomatis"
        firstIdx = maxOf(0, idx)
        rows.forEachIndexed { i, tv ->
            val li = firstIdx + i
            tv.visibility = VISIBLE
            tv.text = if (li < l.size) l[li].text else ""
            val cur = li == idx
            tv.textSize = if (cur) 19f else 15f
            tv.setTextColor(if (cur) Color.WHITE else DIM)
            tv.typeface = if (cur) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }
}

/** Full-screen karaoke view: big scrolling lyrics, current line highlighted. */
class LyricsActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scroll: ScrollView
    private lateinit var box: LinearLayout
    private lateinit var head: TextView
    private val views = ArrayList<TextView>()
    private var builtFor: Any? = null
    private var shownIdx = -2

    private val tick = object : Runnable {
        override fun run() { update(); handler.postDelayed(this, 200) }
    }
    private val listener: () -> Unit = { update() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(24), dp(20), dp(12))
        }
        head = TextView(this).apply { textSize = 16f; setTextColor(ORANGE); typeface = Typeface.DEFAULT_BOLD }
        root.addView(head)
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(200), 0, dp(300)) }
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        bar.addView(pill("−1 dtk") { Lyrics.nudge(-1000) })
        bar.addView(pill("+1 dtk") { Lyrics.nudge(1000) }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        bar.addView(TextView(this).apply {
            text = "Tap baris untuk sinkron"; textSize = 12f; setTextColor(DIM); setPadding(dp(12), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(pill("✕ Tutup") { finish() })
        root.addView(bar)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); Lyrics.listeners.add(listener); handler.post(tick) }
    override fun onPause() { super.onPause(); Lyrics.listeners.remove(listener); handler.removeCallbacks(tick) }

    private fun build() {
        box.removeAllViews(); views.clear(); shownIdx = -2
        val l = Lyrics.lines
        val src: Any? = l ?: Lyrics.plain ?: Lyrics.status
        builtFor = src
        when {
            l != null -> l.forEachIndexed { i, line ->
                val tv = TextView(this).apply {
                    text = line.text; textSize = 26f; setTextColor(DIM); gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { Lyrics.syncTo(i); shownIdx = -2; update() }
                }
                views.add(tv); box.addView(tv)
            }
            Lyrics.plain != null -> box.addView(TextView(this).apply {
                text = Lyrics.plain; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                setLineSpacing(0f, 1.3f)
            })
            else -> box.addView(TextView(this).apply {
                text = Lyrics.status ?: "Menunggu judul lagu dari radio…"; textSize = 20f; setTextColor(DIM); gravity = Gravity.CENTER
            })
        }
    }

    private fun update() {
        val st = if (Lyrics.isRadio) RadioClient.stationId?.let { RadioStore.get(this, it) } else null
        head.text = listOfNotNull(Lyrics.title, st?.name).joinToString("  ·  ").ifEmpty { "Lirik" }
        val src: Any? = Lyrics.lines ?: Lyrics.plain ?: Lyrics.status
        if (src !== builtFor) build()
        if (views.isEmpty()) return
        val idx = Lyrics.currentIndex()
        if (idx == shownIdx) return
        if (shownIdx in views.indices) views[shownIdx].apply { setTextColor(DIM); textSize = 26f; typeface = Typeface.DEFAULT }
        shownIdx = idx
        if (idx in views.indices) {
            val v = views[idx]
            v.setTextColor(Color.WHITE); v.textSize = 32f; v.typeface = Typeface.DEFAULT_BOLD
            v.post { scroll.smoothScrollTo(0, maxOf(0, v.top - scroll.height / 2 + v.height / 2)) }
        }
    }
}
