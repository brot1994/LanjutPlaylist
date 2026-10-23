package id.bram.lanjutplaylist

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.net.URL
import java.util.concurrent.Executors

/** Search public YouTube playlists and save them to the list. */
class SearchActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private val pool = Executors.newFixedThreadPool(4)
    private val thumbCache = HashMap<String, Bitmap>()

    private val red = Color.parseColor("#E53935")
    private val card = Color.parseColor("#1E1E1E")
    private val dim = Color.parseColor("#9E9E9E")
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    companion object { private const val REQ_VOICE = 7 }

    private val radioMode get() = intent.getStringExtra("mode") == "radio"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), 0)
        }
        setContentView(root)

        root.addView(TextView(this).apply {
            text = if (radioMode) "Cari radio" else "Cari playlist"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(8))
        }
        input = EditText(this).apply {
            hint = if (radioMode) "mis. Prambors, dangdut, Bandung" else "mis. lagu 90an indonesia"; setTextColor(Color.WHITE); setHintTextColor(dim)
            textSize = 17f; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(card, 12)
            setOnEditorActionListener { _, a, e ->
                if (a == EditorInfo.IME_ACTION_SEARCH || e?.keyCode == KeyEvent.KEYCODE_ENTER) { doSearch(); true } else false
            }
        }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(circle("🎤") { voice() }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(8) })
        bar.addView(circle("🔍") { doSearch() }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { leftMargin = dp(8) })
        root.addView(bar)

        status = TextView(this).apply { textSize = 13f; setTextColor(dim); setPadding(0, 0, 0, dp(8)) }
        root.addView(status)

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        root.addView(ScrollView(this).apply { addView(results) }, LinearLayout.LayoutParams(-1, 0, 1f))

        if (radioMode) status.text = "Cari nama radio, kota, atau genre (dari direktori Radio Browser)."
        else if (Store.apiKey(this) == null) status.text = "API key belum diisi — isi dulu di layar utama."
        else status.text = "Hasil dari playlist publik YouTube / YouTube Music."
        input.requestFocus()
    }

    override fun onDestroy() { pool.shutdownNow(); super.onDestroy() }

    private fun circle(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 20f; gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(red) }
        setOnClickListener { onClick() }
    }

    private fun voice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, if (radioMode) "Sebutkan radio yang dicari" else "Sebutkan playlist yang dicari")
        try {
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_VOICE)
        } catch (_: Exception) {
            Toast.makeText(this, "Pengenal suara tidak tersedia", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            input.setText(t); input.setSelection(t.length)
            doSearch()
        }
    }

    private fun doSearch() {
        val q = input.text.toString().trim()
        if (q.isEmpty()) return
        if (radioMode) { doRadioSearch(q); return }
        val key = Store.apiKey(this) ?: run { status.text = "API key belum diisi — isi dulu di layar utama."; return }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        status.text = "Mencari \"$q\"…"
        results.removeAllViews()
        Thread {
            val res = try { Result.success(YtApi.search(key, q)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                res.onSuccess { list ->
                    status.text = if (list.isEmpty()) "Tidak ada hasil untuk \"$q\"" else "${list.size} playlist ditemukan"
                    list.forEach { results.addView(row(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
                }.onFailure { status.text = "Gagal mencari: ${it.message}" }
            }
        }.start()
    }

    private fun doRadioSearch(q: String) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        status.text = "Mencari radio \"$q\"…"
        results.removeAllViews()
        Thread {
            val res = try {
                var l = RadioApi.search(q, indonesiaOnly = true)
                if (l.isEmpty()) l = RadioApi.search(q, indonesiaOnly = false)
                Result.success(l)
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                res.onSuccess { list ->
                    status.text = if (list.isEmpty()) "Tidak ada radio untuk \"$q\"" else "${list.size} radio ditemukan"
                    list.forEach { results.addView(radioRow(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) }
                }.onFailure { status.text = "Gagal mencari: ${it.message}" }
            }
        }.start()
    }

    private fun radioRow(h: RadioApi.Hit): View {
        val orange = Color.parseColor("#FB8C00")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10)); background = rounded(card, 12)
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(8), 0) }
        texts.addView(TextView(this).apply {
            text = h.name; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 2
        })
        texts.addView(TextView(this).apply {
            text = listOf(h.city, h.codec + (if (h.bitrate > 0) " ${h.bitrate}k" else ""), if (h.ok) "" else "⚠ kadang mati")
                .filter { it.isNotBlank() }.joinToString("  ·  ")
            textSize = 13f; setTextColor(dim); maxLines = 1
        })
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val save = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        fun mark(done: Boolean) {
            save.text = if (done) "✓ Tersimpan" else "+ Simpan"
            save.background = rounded(if (done) Color.parseColor("#2E7D32") else orange, 10)
            save.isEnabled = !done
        }
        mark(RadioStore.all(this).any { it.url == h.url })
        save.setOnClickListener {
            RadioStore.add(this, h.name, h.city, h.url)
            mark(true)
            Toast.makeText(this, "\"${h.name}\" disimpan", Toast.LENGTH_SHORT).show()
        }
        btns.addView(save)
        btns.addView(TextView(this).apply {
            text = "▶ Coba"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                RadioClient.play(this@SearchActivity, Station("preview", h.name, h.city, h.name, url = h.url, custom = true))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        box.addView(btns)
        return box
    }

    private fun row(f: YtApi.Found): View {
        val saved = Store.all(this).any { it.listId == f.listId }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10)); background = rounded(card, 12)
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#333333"))
        }
        box.addView(img, LinearLayout.LayoutParams(dp(84), dp(56)))
        f.thumb?.let { loadThumb(it, img) }

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        texts.addView(TextView(this).apply {
            text = f.title; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; maxLines = 2
        })
        texts.addView(TextView(this).apply {
            text = f.channel + (if (f.count >= 0) "  ·  ${f.count} lagu" else "")
            textSize = 13f; setTextColor(dim); maxLines = 1
        })
        box.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val save = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        fun mark(done: Boolean) {
            save.text = if (done) "✓ Tersimpan" else "+ Simpan"
            save.background = rounded(if (done) Color.parseColor("#2E7D32") else red, 10)
            save.isEnabled = !done
        }
        mark(saved)
        save.setOnClickListener {
            val p = Store.add(this, f.title, f.listId, nameAuto = false)
            mark(true)
            Toast.makeText(this, "\"${f.title}\" disimpan", Toast.LENGTH_SHORT).show()
            Thread { YtApi.refresh(this, p.listId) }.start()
        }
        btns.addView(save)
        btns.addView(TextView(this).apply {
            text = "▶ Coba"; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                Store.setActive(this@SearchActivity, null) // a preview shouldn't overwrite any saved playlist
                MediaWatcher.open(this@SearchActivity, f.listId, null)
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        box.addView(btns)
        return box
    }

    private fun loadThumb(url: String, iv: ImageView) {
        thumbCache[url]?.let { iv.setImageBitmap(it); return }
        pool.execute {
            try {
                val bmp = URL(url).openStream().use { BitmapFactory.decodeStream(it) } ?: return@execute
                runOnUiThread { thumbCache[url] = bmp; iv.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }
    }
}
