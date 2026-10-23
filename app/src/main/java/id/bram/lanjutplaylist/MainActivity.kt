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
    private var activeSub: TextView? = null
    private var activeBtn: TextView? = null
    private var activeBadge: TextView? = null
    private var activeCard: Playlist? = null
    private var listSig = ""
    private lateinit var tabBar: LinearLayout
    private lateinit var addRow: LinearLayout
    private val musicOnly = ArrayList<View>()
    private val ytOnly = ArrayList<View>()
    private var radioSub: TextView? = null
    private var radioBtn: TextView? = null
    private var radioBadge: TextView? = null
    private var radioCardId: String? = null
    private val radioListener: () -> Unit = { handler.post { refreshLive() } }

    /** Current tab: "ytm", "spotify" or "radio". */
    private var tab: String
        get() = getSharedPreferences("lanjut", MODE_PRIVATE).getString("tab", "ytm") ?: "ytm"
        set(v) { getSharedPreferences("lanjut", MODE_PRIVATE).edit().putString("tab", v).apply() }
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refreshNow(); refreshLive(); handler.postDelayed(this, 1000) }
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
            text = "Lanjut dari lagu terakhir — YouTube Music, Spotify & Radio"
            textSize = 14f; setTextColor(dim); setPadding(0, dp(2), 0, dp(14))
        })

        permBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(permBox)

        nowText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(Color.parseColor("#2A1A1A"), 12)
            setOnClickListener { MediaWatcher.instance?.takeIf { it.resuming }?.cancelResume() }
        }
        root.addView(nowText, lp(bottom = 12))

        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(tabBar, lp(bottom = 12))

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)

        addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(addRow, lp(top = 6, bottom = 18))

        root.addView(CheckBox(this).apply {
            musicOnly.add(this)
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
        ytOnly.add(apiText)
        root.addView(apiText, lp(top = 10))
        root.addView(TextView(this).apply {
            text = "Tips: di YouTube Music / Spotify buka playlist → Bagikan → pilih \"Lanjut Playlist\" untuk menambah cepat."
            textSize = 13f; setTextColor(dim); setPadding(0, dp(10), 0, dp(10))
            musicOnly.add(this)
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
        RadioClient.listeners.add(radioListener)
        refreshPerm(); refreshTabs(); refreshList(); refreshApi()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        RadioClient.listeners.remove(radioListener)
        handler.removeCallbacks(refresher)
    }

    // ---------------------------------------------------------------- tabs

    private fun refreshTabs() {
        tabBar.removeAllViews()
        val tabs = listOf("ytm" to "YT Music", "spotify" to "Spotify", "radio" to "📻 Radio")
        val colors = mapOf("ytm" to red, "spotify" to Color.parseColor("#1DB954"), "radio" to Color.parseColor("#FB8C00"))
        tabs.forEachIndexed { i, (key, label) ->
            val sel = key == tab
            tabBar.addView(TextView(this).apply {
                text = label; textSize = 15f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (sel) Color.WHITE else dim)
                setPadding(dp(6), dp(12), dp(6), dp(12))
                background = rounded(if (sel) colors[key]!! else card, 12)
                setOnClickListener { if (tab != key) { tab = key; refreshTabs(); listSig = ""; refreshList() } }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i > 0) leftMargin = dp(4); if (i < tabs.size - 1) rightMargin = dp(4)
            })
        }
        musicOnly.forEach { it.visibility = if (tab == "radio") View.GONE else View.VISIBLE }
        ytOnly.forEach { it.visibility = if (tab == "ytm") View.VISIBLE else View.GONE }

        addRow.removeAllViews()
        val grey = Color.parseColor("#424242")
        val half = { left: Boolean -> LinearLayout.LayoutParams(0, -2, 1f).apply { if (left) rightMargin = dp(6) else leftMargin = dp(6) } }
        when (tab) {
            "ytm" -> {
                addRow.addView(button("🔍  Cari", red) { startActivity(Intent(this, SearchActivity::class.java)) }, half(true))
                addRow.addView(button("+  Link", grey) { showAddDialog("", null) }, half(false))
            }
            "spotify" -> addRow.addView(button("+  Link Spotify", grey) { showAddDialog("", null) },
                LinearLayout.LayoutParams(-1, -2))
            else -> {
                addRow.addView(button("🔍  Cari radio", Color.parseColor("#FB8C00")) {
                    startActivity(Intent(this, SearchActivity::class.java).putExtra("mode", "radio"))
                }, half(true))
                addRow.addView(button("+  Link stream", grey) { showAddRadioDialog() }, half(false))
            }
        }
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
        val rs = RadioClient.stationId?.let { RadioStore.get(this, it) }
        nowText.text = when {
            RadioClient.status != null && tab == "radio" -> "📻 ${RadioClient.status}"
            RadioClient.isPlaying || RadioClient.isBuffering ->
                "📻 ${rs?.name ?: "Radio"}" + (RadioClient.nowTitle?.let { "\n♪ $it" } ?: "") +
                    (if (RadioClient.isBuffering) "\nMenyambung…" else "")
            w == null -> "Pemantau belum berjalan (cek izin akses notifikasi)"
            w.nowTitle == null -> "YouTube Music / Spotify sedang tidak memutar apa-apa"
            w.resuming -> "⏩ Sedang mencari lagu terakhir… (${w.nowTitle})\nTap di sini untuk berhenti"
            else -> (if (w.nowPlaying) "▶ " else "❚❚ ") + (if (w.nowService == "spotify") "[Spotify] " else "") + "${w.nowTitle} – ${w.nowArtist ?: ""}" +
                "\nDicatat ke: " + (activeName ?: "— (tidak ada playlist aktif)")
        }
    }

    private fun refreshList() {
        activeSub = null; activeBtn = null; activeBadge = null; activeCard = null
        radioSub = null; radioBtn = null; radioBadge = null; radioCardId = null; vizView = null
        listBox.removeAllViews()
        if (tab == "radio") { refreshRadioList(); return }
        val list = Store.all(this).filter { it.service == tab }
        val active = Store.activeId(this)
        if (list.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = if (tab == "spotify") "Belum ada playlist Spotify. Di Spotify: buka playlist → Bagikan → Lanjut Playlist, atau tempel link lewat tombol di bawah."
                else "Belum ada playlist. Tambahkan dengan tombol di bawah atau lewat Bagikan dari YouTube Music."
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
            setOnClickListener { onButton(p) }
            setOnLongClickListener { showMenu(p); true }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = (if (isActive) "♫  " else "") + p.name
            textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        val badgeTv = TextView(this)
        box.addView(badgeTv.apply {
            val sp = p.service == "spotify"
            text = if (sp) "Spotify" else "YouTube Music"
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (sp) Color.parseColor("#1DB954") else Color.parseColor("#FF6E66"))
            setPadding(0, dp(2), 0, 0)
        })
        val sub = if (p.lastTitle == null) "Belum ada riwayat — mulai dari awal"
        else {
            val ago = DateUtils.getRelativeTimeSpanString(p.updatedAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS)
            "Terakhir: ${p.lastTitle}" + (p.lastArtist?.let { " – $it" } ?: "") +
                "\n${fmt(p.lastPos)}" + (if (p.lastDuration > 0) " / ${fmt(p.lastDuration)}" else "") + "  ·  $ago"
        }
        val subTv = TextView(this).apply {
            text = sub; textSize = 14f; setTextColor(dim); setPadding(0, dp(4), 0, 0)
        }
        box.addView(subTv)
        row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        val btn = TextView(this).apply {
            text = "▶"; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(red) }
            setOnClickListener { onButton(p) }
        }
        row.addView(btn, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(10) })
        if (isActive) { activeSub = subTv; activeBtn = btn; activeBadge = badgeTv; activeCard = p }
        return row
    }

    /** Is this playlist the active one AND its app is currently on its track? */
    private fun liveFor(p: Playlist): MediaWatcher.Live? {
        if (Store.activeId(this) != p.id) return null
        val w = MediaWatcher.instance ?: return null
        if (w.resuming) return null
        return w.live(p.service)?.takeIf { it.title != null }
    }

    /** Update the active card every second: live position, playing/paused, ▶ / ❚❚ button. */
    private fun refreshLive() {
        if (tab == "radio") { refreshRadioLive(); return }
        val w = MediaWatcher.instance
        // Rebuild the list when the active playlist or its track changes.
        val act = Store.activeId(this)
        val actP = act?.let { Store.get(this, it) }
        val sig = "$act|${actP?.let { w?.live(it.service)?.title }}"
        if (sig != listSig) { listSig = sig; refreshList(); return }

        val p = activeCard ?: return
        val l = liveFor(p)
        val sub = activeSub ?: return
        val btn = activeBtn ?: return
        val badge = activeBadge ?: return
        val sp = p.service == "spotify"
        val appLabel = if (sp) "Spotify" else "YouTube Music"
        if (l == null) {
            btn.text = "▶"
            badge.text = appLabel
            return
        }
        badge.text = appLabel + if (l.playing) "  ·  ▶ SEDANG DIPUTAR" else "  ·  ❚❚ DIJEDA"
        sub.text = "${l.title}" + (l.artist?.let { " – $it" } ?: "") +
            "\n${fmt(l.pos)}" + (if (l.dur > 0) " / ${fmt(l.dur)}" else "")
        sub.setTextColor(Color.WHITE)
        btn.text = if (l.playing) "❚❚" else "▶"
        btn.textSize = if (l.playing) 20f else 24f
    }

    private fun onButton(p: Playlist) {
        val l = liveFor(p)
        val w = MediaWatcher.instance
        when {
            l != null && l.playing -> w?.pause(p.service)
            l != null && !l.playing -> w?.resumePlay(p.service)
            else -> play(p, false)
        }
        handler.postDelayed({ refreshLive() }, 400)
    }

    // ---------------------------------------------------------------- radio

    private val orange = Color.parseColor("#FB8C00")

    private fun refreshRadioList() {
        val list = RadioStore.all(this)
        val cur = RadioClient.stationId
        if (list.isEmpty()) listBox.addView(TextView(this).apply {
            text = "Daftar radio kosong."; setTextColor(dim); textSize = 15f; setPadding(0, dp(8), 0, dp(16))
        })
        for (st in list) listBox.addView(radioCard(st, st.id == cur), lp(bottom = 10))
        refreshRadioLive(rebuild = false)
        refreshNow()
    }

    private var vizView: VizView? = null

    private fun radioCard(st: Station, isCur: Boolean): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(10), dp(12))
            background = rounded(card, 14).apply { if (isCur) setStroke(dp(2), orange) }
            isClickable = true
            setOnClickListener { onRadioButton(st) }
            setOnLongClickListener { showRadioMenu(st); true }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        outer.addView(row)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = (if (isCur) "📻  " else "") + st.name
            textSize = 19f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        val badge = TextView(this).apply {
            text = st.info; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(orange)
            setPadding(0, dp(2), 0, 0)
        }
        box.addView(badge)
        val sub = TextView(this).apply {
            textSize = 13f; setTextColor(dim); setPadding(0, dp(2), 0, 0)
            text = if (st.url == null) "Link stream dicari otomatis saat diputar" else ""
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        box.addView(sub)
        row.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        val btn = TextView(this).apply {
            text = "▶"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(orange) }
            setOnClickListener { onRadioButton(st) }
        }
        row.addView(btn, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(10) })
        if (isCur) {
            radioSub = sub; radioBtn = btn; radioBadge = badge; radioCardId = st.id
            val v = VizView(this)
            outer.addView(v, LinearLayout.LayoutParams(-1, dp(110)).apply { topMargin = dp(10); rightMargin = dp(6) })
            vizView = v
        }
        return outer
    }

    private fun refreshRadioLive(rebuild: Boolean = true) {
        if (rebuild && RadioClient.stationId != radioCardId && RadioClient.stationId?.let { RadioStore.get(this, it) } != null) {
            refreshList(); return
        }
        val st = radioCardId?.let { RadioStore.get(this, it) } ?: return
        val btn = radioBtn ?: return
        val badge = radioBadge ?: return
        val sub = radioSub ?: return
        val playing = RadioClient.isPlaying
        val buffering = RadioClient.isBuffering
        badge.text = st.info + when {
            buffering -> "  ·  ⏳ MENYAMBUNG"
            playing -> "  ·  ▶ SEDANG DIPUTAR"
            RadioClient.hasItem -> "  ·  ❚❚ DIJEDA"
            else -> ""
        }
        val t = RadioClient.status ?: RadioClient.nowTitle?.let { "♪ $it" }
        sub.text = t ?: ""
        sub.visibility = if (t == null) View.GONE else View.VISIBLE
        sub.setTextColor(if (RadioClient.status != null) Color.parseColor("#FFB74D") else Color.WHITE)
        btn.text = if (playing || buffering) "❚❚" else "▶"
        btn.textSize = if (playing || buffering) 18f else 22f
        vizView?.let { it.active = playing; it.kick() }
        refreshNow()
    }

    private fun onRadioButton(st: Station) {
        askNotifPermission()
        val cur = RadioClient.stationId == st.id
        when {
            cur && (RadioClient.isPlaying || RadioClient.isBuffering) -> RadioClient.pause()
            cur && RadioClient.hasItem && RadioClient.status == null -> RadioClient.resume(this)
            else -> RadioClient.play(this, RadioStore.get(this, st.id) ?: st)
        }
        handler.postDelayed({ refreshList() }, 300)
    }

    private fun askNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
        }
    }

    private fun showRadioMenu(st: Station) {
        val items = arrayOf("Ganti nama", "Ganti link stream", "Cari ulang link stream otomatis",
            "Pindah ke atas", "Pindah ke bawah", "Hapus dari daftar", "Kembalikan daftar radio default")
        AlertDialog.Builder(this).setTitle(st.name).setItems(items) { _, i ->
            when (i) {
                0 -> askText("Ganti nama", st.name) { t -> RadioStore.update(this, st.id) { it.name = t }; refreshList() }
                1 -> askText("Link stream (http…)", st.url ?: "") { t ->
                    if (!t.startsWith("http")) toast("Link stream harus diawali http")
                    else { RadioStore.update(this, st.id) { it.url = t; it.custom = true }; refreshList() }
                }
                2 -> { RadioStore.update(this, st.id) { it.url = null; it.custom = false }; toast("Link akan dicari ulang saat diputar"); refreshList() }
                3 -> { RadioStore.move(this, st.id, -1); refreshList() }
                4 -> { RadioStore.move(this, st.id, 1); refreshList() }
                5 -> AlertDialog.Builder(this).setMessage("Hapus \"${st.name}\"?")
                    .setPositiveButton("Hapus") { _, _ -> RadioStore.remove(this, st.id); refreshList() }
                    .setNegativeButton("Batal", null).show()
                6 -> AlertDialog.Builder(this).setMessage("Kembalikan daftar radio Jakarta bawaan? Radio yang kamu tambah sendiri tetap ada.")
                    .setPositiveButton("Ya") { _, _ -> RadioStore.resetDefaults(this); refreshList() }
                    .setNegativeButton("Batal", null).show()
            }
        }.show()
    }

    private fun showAddRadioDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val nameEt = EditText(this).apply { hint = "Nama radio" }
        val infoEt = EditText(this).apply { hint = "Keterangan (mis. 98.7 FM · Jakarta)" }
        val urlEt = EditText(this).apply { hint = "Link stream (http…)"; inputType = InputType.TYPE_TEXT_VARIATION_URI }
        box.addView(nameEt); box.addView(infoEt); box.addView(urlEt)
        AlertDialog.Builder(this).setTitle("Tambah radio").setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val u = urlEt.text.toString().trim()
                val n = nameEt.text.toString().trim().ifEmpty { "Radio" }
                if (!u.startsWith("http")) { toast("Link stream harus diawali http"); return@setPositiveButton }
                RadioStore.add(this, n, infoEt.text.toString().trim(), u); refreshList()
            }
            .setNegativeButton("Batal", null).show()
    }

    // ---------------------------------------------------------------- actions

    private fun play(p: Playlist, fromStart: Boolean) {
        if (!hasAccess()) toast("Izin akses notifikasi belum aktif — lagu terakhir tidak akan dicatat")
        MediaWatcher.play(this, p, fromStart) { toast(it) }
    }

    private fun showMenu(p: Playlist) {
        val items = arrayOf("Putar dari awal playlist", "Ganti nama", "Ganti link", "Pindah ke atas",
            "Pindah ke bawah", "Hapus riwayat lagu terakhir", "Hapus playlist", "Baca ulang daftar lagu (API)",
            "Tes mode Android Auto (tanpa buka aplikasi musik)")
        AlertDialog.Builder(this).setTitle(p.name).setItems(items) { _, i ->
            when (i) {
                0 -> play(p, true)
                1 -> askText("Ganti nama", p.name) { t ->
                    Store.update(this, p.id) { it.name = t; it.nameAuto = false }; refreshList()
                }
                2 -> askText("Link / ID playlist",
                    if (p.service == "spotify") p.listId else "https://music.youtube.com/playlist?list=${p.listId}") { t ->
                    val link = Store.parseLink(t)
                    if (link == null) toast("Link playlist tidak dikenali")
                    else {
                        Store.update(this, p.id) { it.service = link.first; it.listId = link.second; it.queueTitle = null }
                        refreshList()
                    }
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
                7 -> if (p.service != "ytm") toast("Khusus playlist YouTube Music")
                    else if (Store.apiKey(this) == null) askApiKey() else prefetch(p, verbose = true)
                8 -> MediaWatcher.start(this, p, fromStart = false, viaSession = true) { toast(it) }
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
            hint = "Link playlist YouTube Music / Spotify"; setText(link)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        box.addView(nameEt); box.addView(linkEt)
        AlertDialog.Builder(this).setTitle("Tambah playlist").setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val link = Store.parseLink(linkEt.text.toString())
                if (link == null) { toast("Link playlist tidak dikenali"); return@setPositiveButton }
                val (service, id) = link
                val n = nameEt.text.toString().trim()
                val auto = n.isEmpty()
                val added = Store.add(this, if (auto) defaultName(id) else n, id, auto, service)
                if (tab != service) { tab = service; refreshTabs() }
                refreshList()
                prefetch(added)
            }
            .setNegativeButton("Batal", null).show()
    }

    /** Grab the track list (and name, if left blank) in the background when an API key is set. */
    private fun prefetch(p: Playlist, verbose: Boolean = false) {
        if (p.service != "ytm") return
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
        id.startsWith("spotify:") -> "Playlist Spotify ${Store.all(this).size + 1}"
        else -> "Playlist ${Store.all(this).size + 1}"
    }

    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)
        setIntent(Intent())
        if (Store.parseLink(text) == null) {
            toast("Yang dibagikan bukan link playlist YouTube Music / Spotify")
            return
        }
        // Text before the link is often the title.
        var before = text.substringBefore("http").trim().trimEnd(':', '-', '–').trim()
        // Spotify prefixes shares with e.g. "Give this playlist a listen: " / "Dengarkan playlist ini: ".
        if (before.contains(": ")) before = before.substringAfter(": ").trim()
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
