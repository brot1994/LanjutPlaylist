package id.bram.lanjutplaylist

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import id.bram.lanjutplaylist.Store.seekToPos

class MainActivity : AppCompatActivity() {

    private lateinit var root: LinearLayout
    private lateinit var permBox: LinearLayout
    private lateinit var nowText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var apiText: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refreshNow(); handler.postDelayed(this, 2000) }
    }

    private val red = Color.parseColor("#E53935")
    private val card = Color.parseColor("#1E1E1E")
    private val dim = Color.parseColor("#9E9E9E")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Lanjut Playlist"
            textSize = 28f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Tap playlist untuk lanjut dari lagu terakhir di YouTube Music"
            textSize = 14f; setTextColor(dim); setPadding(0, dp(2), 0, dp(14))
        })

        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(permBox)

        nowText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.parseColor("#2A1A1A"), 12)
        }
        root.addView(nowText, lp(bottom = 14))

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        root.addView(button("+  Tambah playlist", red) { showAddDialog("", null) }, lp(top = 6, bottom = 18))

        root.addView(CheckBox(this).apply {
            text = "Lanjut dari detik terakhir (bukan dari awal lagu)"
            setTextColor(Color.WHITE); isChecked = seekToPos
            buttonTintList = android.content.res.ColorStateList.valueOf(red)
            setOnCheckedChangeListener { _, b -> seekToPos = b }
        })
        apiText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(card, 12)
            setOnClickListener { askApiKey() }
        }
        root.addView(apiText, lp(top = 10))
        root.addView(TextView(this).apply {
            text = "Tips: di YouTube Music buka playlist → ⋮ / Bagikan → pilih \"Lanjut Playlist\" untuk menambah cepat."
            textSize = 13f; setTextColor(dim); setPadding(0, dp(10), 0, dp(10))
        })
        root.addView(TextView(this).apply {
            text = "Info sesi (diagnostik)"
            textSize = 13f; setTextColor(Color.parseColor("#FF8A80")); setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { showDiagnostics() }
        })

        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshPerm(); refreshList(); refreshApi()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    // ---------------------------------------------------------------- views

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 18f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER; setPadding(dp(12), dp(16), dp(12), dp(16))
        background = rounded(color, 14)
        setOnClickListener { onClick() }
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun hasAccess() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun refreshPerm() {
        permBox.removeAllViews()
        if (hasAccess()) return
        permBox.addView(TextView(this).apply {
            text = "⚠ Izin akses notifikasi belum aktif.\nTanpa izin ini aplikasi tidak bisa mencatat lagu terakhir.\n\n" +
                "Android 13+: kalau tombolnya abu-abu/terkunci, buka Info Aplikasi → ⋮ (pojok kanan atas) → " +
                "\"Izinkan setelan terbatas\", lalu coba lagi."
            textSize = 14f; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.parseColor("#4A2B00"), 12)
        }, lp(bottom = 8))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Buka izin", red) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        row.addView(button("Info aplikasi", Color.parseColor("#424242")) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        permBox.addView(row, lp(bottom = 14))
    }

    private fun refreshApi() {
        apiText.text = if (Store.apiKey(this) != null)
            "🔑 API key YouTube: terisi  (tap untuk ubah / baca ulang semua playlist)"
        else "🔑 API key YouTube: belum diisi\nTanpa ini, lanjut lagu memakai cara \"next\" berulang-ulang (lebih lambat). Tap untuk isi."
    }

    private fun askApiKey() {
        val et = EditText(this).apply { hint = "AIza..."; setText(Store.apiKey(this@MainActivity) ?: "") }
        val pad = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this).setTitle("API key YouTube Data v3").setView(pad)
            .setPositiveButton("Simpan & baca playlist") { _, _ ->
                Store.setApiKey(this, et.text.toString())
                refreshApi()
                Store.all(this).forEach { prefetch(it, verbose = true) }
            }
            .setNeutralButton("Hapus") { _, _ -> Store.setApiKey(this, null); refreshApi() }
            .setNegativeButton("Batal", null).show()
    }

    private fun refreshNow() {
        val w = MediaWatcher.instance
        val activeName = Store.activeId(this)?.let { Store.get(this, it)?.name }
        nowText.text = when {
            w == null -> "Pemantau belum berjalan (cek izin akses notifikasi)"
            w.nowTitle == null -> "YouTube Music sedang tidak memutar apa-apa"
            w.resuming -> "⏩ Sedang mencari lagu terakhir… (${w.nowTitle})"
            else -> (if (w.nowPlaying) "▶ " else "❚❚ ") + "${w.nowTitle} – ${w.nowArtist ?: ""}" +
                "\nDicatat ke: " + (activeName ?: "— (tidak ada playlist aktif)")
        }
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val list = Store.all(this)
        val active = Store.activeId(this)
        if (list.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "Belum ada playlist. Tambahkan dengan tombol di bawah atau lewat Bagikan dari YouTube Music."
                setTextColor(dim); textSize = 15f; setPadding(0, dp(8), 0, dp(16))
            })
        }
        for (p in list) listBox.addView(playlistCard(p, p.id == active), lp(bottom = 10))
        refreshNow()
    }

    private fun playlistCard(p: Playlist, isActive: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(10), dp(14))
            background = rounded(card, 14).apply { if (isActive) setStroke(dp(2), red) }
            isClickable = true
            setOnClickListener { play(p, false) }
            setOnLongClickListener { showMenu(p); true }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = (if (isActive) "♫  " else "") + p.name
            textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        val sub = if (p.lastTitle == null) "Belum ada riwayat — mulai dari awal"
        else {
            val ago = DateUtils.getRelativeTimeSpanString(p.updatedAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS)
            "Terakhir: ${p.lastTitle}" + (p.lastArtist?.let { " – $it" } ?: "") +
                "\n${fmt(p.lastPos)}" + (if (p.lastDuration > 0) " / ${fmt(p.lastDuration)}" else "") + "  ·  $ago"
        }
        box.addView(TextView(this).apply {
            text = sub; textSize = 14f; setTextColor(dim); setPadding(0, dp(4), 0, 0)
        })
        row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "▶"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(red) }
            setOnClickListener { play(p, false) }
        }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(10) })
        return row
    }

    // ---------------------------------------------------------------- actions

    private fun play(p: Playlist, fromStart: Boolean) {
        if (!hasAccess()) toast("Izin akses notifikasi belum aktif — lagu terakhir tidak akan dicatat")
        MediaWatcher.play(this, p, fromStart) { toast(it) }
    }

    private fun showMenu(p: Playlist) {
        val items = arrayOf("Putar dari awal playlist", "Ganti nama", "Ganti link", "Pindah ke atas",
            "Pindah ke bawah", "Hapus riwayat lagu terakhir", "Hapus playlist", "Baca ulang daftar lagu (API)")
        AlertDialog.Builder(this).setTitle(p.name).setItems(items) { _, i ->
            when (i) {
                0 -> play(p, true)
                1 -> askText("Ganti nama", p.name) { t ->
                    Store.update(this, p.id) { it.name = t; it.nameAuto = false }; refreshList()
                }
                2 -> askText("Link / ID playlist", "https://music.youtube.com/playlist?list=${p.listId}") { t ->
                    val id = Store.parseListId(t)
                    if (id == null) toast("Link playlist tidak dikenali")
                    else { Store.update(this, p.id) { it.listId = id; it.queueTitle = null }; refreshList() }
                }
                3 -> { Store.move(this, p.id, -1); refreshList() }
                4 -> { Store.move(this, p.id, 1); refreshList() }
                5 -> {
                    Store.update(this, p.id) {
                        it.lastTitle = null; it.lastArtist = null; it.lastVideoId = null; it.lastPos = 0
                    }; refreshList()
                }
                6 -> AlertDialog.Builder(this).setMessage("Hapus \"${p.name}\"?")
                    .setPositiveButton("Hapus") { _, _ -> Store.remove(this, p.id); refreshList() }
                    .setNegativeButton("Batal", null).show()
                7 -> if (Store.apiKey(this) == null) askApiKey() else prefetch(p, verbose = true)
            }
        }.show()
    }

    private fun askText(title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(this).apply { setText(initial); setSelection(initial.length) }
        val pad = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        AlertDialog.Builder(this).setTitle(title).setView(pad)
            .setPositiveButton("OK") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Batal", null).show()
    }

    private fun showAddDialog(link: String, name: String?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
        }
        val nameEt = EditText(this).apply { hint = "Nama (kosongkan = otomatis)"; setText(name ?: "") }
        val linkEt = EditText(this).apply {
            hint = "Link playlist YouTube Music"; setText(link)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(nameEt); box.addView(linkEt)
        AlertDialog.Builder(this).setTitle("Tambah playlist").setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val id = Store.parseListId(linkEt.text.toString())
                if (id == null) { toast("Link playlist tidak dikenali (harus ada list=...)"); return@setPositiveButton }
                val n = nameEt.text.toString().trim()
                val auto = n.isEmpty()
                val added = Store.add(this, if (auto) defaultName(id) else n, id, auto)
                refreshList()
                prefetch(added)
            }
            .setNegativeButton("Batal", null).show()
    }

    /** Grab the track list (and name, if left blank) in the background when an API key is set. */
    private fun prefetch(p: Playlist, verbose: Boolean = false) {
        val key = Store.apiKey(this) ?: return
        Thread {
            val (items, err) = YtApi.refresh(this, p.listId)
            val title = if (p.nameAuto) YtApi.fetchTitle(key, p.listId) else null
            if (title != null) Store.update(this, p.id) { it.name = title; it.nameAuto = false }
            runOnUiThread {
                refreshList()
                if (verbose || err != null) toast(
                    if (items != null) "${p.name}: ${items.size} lagu terbaca"
                    else "${p.name}: gagal ambil daftar lagu ($err)"
                )
            }
        }.start()
    }

    private fun defaultName(id: String) = when {
        id == "LM" -> "Lagu yang disukai"
        else -> "Playlist ${Store.all(this).size + 1}"
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)
        setIntent(Intent())
        if (Store.parseListId(text) == null) {
            toast("Yang dibagikan bukan playlist (tidak ada list=...)")
            return
        }
        // Text before the link is often the title.
        val before = text.substringBefore("http").trim().trimEnd(':', '-', '–').trim()
        showAddDialog(text, subject ?: before.ifEmpty { null })
    }

    private fun showDiagnostics() {
        val w = MediaWatcher.instance
        val txt = w?.diagnostics() ?: "Pemantau belum berjalan. Aktifkan izin akses notifikasi dulu."
        val tv = TextView(this).apply {
            text = txt; textSize = 12f; setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE; setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Info sesi").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Salin") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("info", txt))
                toast("Disalin")
            }
            .setNegativeButton("Tutup", null).show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
