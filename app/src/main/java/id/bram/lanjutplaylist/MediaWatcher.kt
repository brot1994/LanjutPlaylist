package id.bram.lanjutplaylist

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import android.widget.Toast
import id.bram.lanjutplaylist.Store.seekToPos

/**
 * Background watcher (bound by the system once notification access is granted).
 * Watches the YouTube Music and Spotify media sessions, remembers the last track +
 * position for the active playlist, and performs the resume.
 *
 * YT Music: no media ids, no SKIP_TO_QUEUE_ITEM → resume by opening watch?v=ID&list=PL
 *           (id looked up via YouTube Data API), else press "next" until the title matches.
 * Spotify:  start the playlist (session playFromUri or spotify:…:play link), then press
 *           "next" until the track matches, then seek.
 */
class MediaWatcher : NotificationListenerService() {

    companion object {
        const val YTM = "com.google.android.apps.youtube.music"
        const val SPOTIFY = "com.spotify.music"
        var instance: MediaWatcher? = null
            private set

        private val main = Handler(Looper.getMainLooper())

        fun pkg(service: String) = if (service == "spotify") SPOTIFY else YTM
        fun appName(service: String) = if (service == "spotify") "Spotify" else "YouTube Music"

        fun norm(s: String?) = (s ?: "").lowercase().replace(Regex("\\s+"), " ").trim()

        fun url(listId: String, vid: String?) =
            if (vid != null) "https://music.youtube.com/watch?v=$vid&list=$listId"
            else "https://music.youtube.com/watch?list=$listId"

        /** Open YT Music on a playlist (optionally at a video). */
        fun open(c: Context, listId: String, vid: String?) =
            view(c, Uri.parse(url(listId, vid)), YTM)

        private fun view(c: Context, uri: Uri, pkg: String) {
            val i = Intent(Intent.ACTION_VIEW, uri)
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                c.startActivity(Intent(i).setPackage(pkg))
            } catch (e: Exception) {
                try { c.startActivity(i) } catch (_: Exception) {
                    Toast.makeText(c, "Aplikasi musik tidak bisa dibuka", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** Entry point from the phone UI. Must be called with the visible Activity. */
        fun play(act: Activity, p: Playlist, fromStart: Boolean, msg: (String) -> Unit) =
            start(act, p, fromStart, viaSession = false, msg = msg)

        /**
         * Start playlist p, resuming at its last track.
         * viaSession=false → open the music app (needs a visible Activity).
         * viaSession=true  → command the app's media session only (used from Android Auto).
         */
        fun start(c: Context, p: Playlist, fromStart: Boolean, viaSession: Boolean, msg: (String) -> Unit) {
            instance?.record()
            Store.setActive(c, p.id)
            val title = p.lastTitle
            val resumeWanted = !fromStart && title != null
            if (!resumeWanted) msg("Memutar ${p.name}…") else msg("Memutar ${p.name} — lanjut ke \"$title\"…")

            if (p.service == "spotify") {
                instance?.beginResume(if (resumeWanted) resumeFor(c, p, hasVid = false) else null)
                launchSpotify(c, p, viaSession, msg)
                return
            }
            if (!resumeWanted) {
                instance?.beginResume(null)
                launchYtm(c, p.listId, null, viaSession, msg)
                return
            }
            Thread {
                var vid = p.lastVideoId
                var note: String? = null
                if (vid == null) {
                    var m = YtApi.cached(c, p.listId)?.let { YtApi.match(it, title!!, p.lastArtist) }
                    if (m == null && Store.apiKey(c) != null) {
                        val (fresh, err) = YtApi.refresh(c, p.listId)
                        m = fresh?.let { YtApi.match(it, title!!, p.lastArtist) }
                        if (err != null) note = "Daftar lagu tidak bisa diambil ($err) — pakai cara lompat-lompat"
                    }
                    vid = m?.videoId
                }
                main.post {
                    note?.let(msg)
                    instance?.beginResume(resumeFor(c, p, hasVid = vid != null))
                    launchYtm(c, p.listId, vid, viaSession, msg)
                }
            }.start()
        }

        private fun resumeFor(c: Context, p: Playlist, hasVid: Boolean) = Resume(
            p.id, p.service, p.lastTitle!!, p.lastArtist ?: "", p.lastVideoId,
            if (c.seekToPos) p.lastPos else 0, hasVid,
            titleAtLaunch = instance?.controllerFor(p.service)?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        )

        private fun launchYtm(c: Context, listId: String, vid: String?, viaSession: Boolean, msg: (String) -> Unit) {
            if (!viaSession) { open(c, listId, vid); return }
            val w = instance ?: run { msg("Izin akses notifikasi belum aktif"); return }
            w.playViaSession(YTM, Uri.parse(url(listId, vid)), msg)
        }

        private fun launchSpotify(c: Context, p: Playlist, viaSession: Boolean, msg: (String) -> Unit) {
            val w = instance
            val uri = Uri.parse(p.listId)
            if (viaSession) {
                w?.playViaSession(SPOTIFY, uri, msg) ?: msg("Izin akses notifikasi belum aktif")
                return
            }
            // The ":play" suffix asks Spotify to start playing right away; if it doesn't,
            // push the playlist through Spotify's media session a few seconds later.
            view(c, Uri.parse(p.listId + ":play"), SPOTIFY)
            w?.ensureStarted(SPOTIFY, uri, w.controllerFor("spotify")?.metadata
                ?.getString(MediaMetadata.METADATA_KEY_TITLE))
        }
    }

    class Resume(
        val playlistId: String,
        val service: String,
        val title: String,
        val artist: String,
        val trackId: String?,
        val pos: Long,
        val hasVid: Boolean,
        val titleAtLaunch: String?,
        val started: Long = SystemClock.elapsedRealtime(),
    ) {
        var doneAt = 0L
        var seenChange = false
        var skips = 0
        var lastSkipAt = 0L
        var lastSkipTitle: String? = null
        var firstTitle: String? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private val controllers = HashMap<String, MediaController>()
    private var pending: Resume? = null
    private var launchedAt = 0L
    private var mismatches = 0

    var nowTitle: String? = null; private set
    var nowArtist: String? = null; private set
    var nowPlaying = false; private set
    var nowService: String? = null; private set
    val resuming get() = pending != null

    /** Listeners (e.g. AutoService) notified whenever a player's state changes. */
    val listeners = LinkedHashSet<() -> Unit>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { tick() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { tick() }
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) { tick() }
        override fun onSessionDestroyed() { refreshSessions() }
    }

    private val periodic = object : Runnable {
        override fun run() { record(); handler.postDelayed(this, 5000) }
    }

    private val resumeLoop = object : Runnable {
        override fun run() { if (stepResume()) handler.postDelayed(this, 500) }
    }

    override fun onListenerConnected() {
        instance = this
        val m = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm = m
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn(), handler)
        } catch (_: SecurityException) { }
        refreshSessions()
        handler.removeCallbacks(periodic)
        handler.post(periodic)
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        controllers.values.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun cn() = ComponentName(this, MediaWatcher::class.java)

    // ---------------------------------------------------------------- sessions

    private fun refreshSessions() {
        try { attach(msm?.getActiveSessions(cn()) ?: emptyList()) } catch (_: SecurityException) {}
    }

    private fun attach(list: List<MediaController>) {
        for (p in listOf(YTM, SPOTIFY)) {
            val fresh = list.firstOrNull { it.packageName == p }
            val old = controllers[p]
            if (fresh?.sessionToken == old?.sessionToken) continue
            old?.unregisterCallback(callback)
            if (fresh != null) { controllers[p] = fresh; fresh.registerCallback(callback, handler) }
            else controllers.remove(p)
        }
        tick()
    }

    fun controllerFor(service: String): MediaController? = controllers[pkg(service)]

    /** The player to show/control: whichever is playing, else the active playlist's app. */
    fun currentController(): MediaController? {
        controllers.values.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it }
        val active = Store.activeId(this)?.let { Store.get(this, it) }
        return active?.let { controllerFor(it.service) } ?: controllers[YTM] ?: controllers[SPOTIFY]
    }

    private fun tick() {
        val c = currentController()
        nowTitle = c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        nowArtist = c?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        nowPlaying = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        nowService = when (c?.packageName) { SPOTIFY -> "spotify"; YTM -> "ytm"; else -> null }
        if (pending != null) stepResume() else record()
        listeners.toList().forEach { try { it() } catch (_: Exception) {} }
    }

    private fun currentPos(st: PlaybackState?): Long {
        if (st == null) return 0
        var p = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return maxOf(0, p)
    }

    // ---------------------------------------------------------------- remote control

    /** Ask an app's session to play a URI; wake the app first if it has no session. */
    fun playViaSession(pkg: String, uri: Uri, msg: (String) -> Unit) {
        controllers[pkg]?.let { it.transportControls.playFromUri(uri, null); return }
        msg("Membangunkan ${if (pkg == SPOTIFY) "Spotify" else "YouTube Music"}…")
        wake(pkg)
        val t0 = SystemClock.elapsedRealtime()
        handler.post(object : Runnable {
            override fun run() {
                val c = controllers[pkg]
                when {
                    c != null -> handler.postDelayed({ c.transportControls.playFromUri(uri, null) }, 600)
                    SystemClock.elapsedRealtime() - t0 > 10_000 -> {
                        pending = null
                        msg("Aplikasi musik tidak merespons — buka sekali di HP, lalu coba lagi")
                    }
                    else -> handler.postDelayed(this, 400)
                }
            }
        })
    }

    /** After opening a ":play" link: if the track didn't change / nothing plays, push it via the session. */
    fun ensureStarted(pkg: String, uri: Uri, titleBefore: String?) {
        val t0 = SystemClock.elapsedRealtime()
        handler.postDelayed(object : Runnable {
            override fun run() {
                val c = controllers[pkg]
                val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
                val title = c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                if (playing && (titleBefore == null || norm(title) != norm(titleBefore))) return
                if (c != null) { c.transportControls.playFromUri(uri, null); return }
                if (SystemClock.elapsedRealtime() - t0 < 10_000) handler.postDelayed(this, 700)
            }
        }, 4000)
    }

    /** Press "play" on the app's media-button receiver so it creates its media session. */
    private fun wake(pkg: String) {
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            try {
                sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)))
            } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- recording

    private fun trackIdOf(c: MediaController): String? =
        c.metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { it.startsWith("spotify:") }

    /** Does this track belong to playlist p, as far as we know? null = don't know enough. */
    private fun belongs(p: Playlist, c: MediaController, title: String, artist: String?): Boolean? {
        if (p.service == "spotify") {
            val qt = c.queueTitle?.toString()?.trim()?.ifEmpty { null }
            if (p.queueTitle != null && qt != null) return norm(qt) == norm(p.queueTitle)
        } else {
            val items = YtApi.cached(this, p.listId)
            if (!items.isNullOrEmpty()) return YtApi.match(items, title, artist) != null
        }
        val seen = Store.seen(this, p.id)
        if (seen.size < 10) return null
        return norm(title) in seen
    }

    fun record() {
        if (pending != null) return
        val activeId = Store.activeId(this) ?: return
        val all = Store.all(this)
        val active = all.firstOrNull { it.id == activeId } ?: return
        val c = controllerFor(active.service) ?: return
        val md = c.metadata ?: return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val st = c.playbackState ?: return
        if (st.state == PlaybackState.STATE_NONE || st.state == PlaybackState.STATE_ERROR) return
        val playing = st.state == PlaybackState.STATE_PLAYING
        val sinceLaunch = SystemClock.elapsedRealtime() - launchedAt

        // The other app started playing → this playlist is no longer what's on.
        val otherPlaying = controllers.values.any {
            it !== c && it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (otherPlaying && !playing && sinceLaunch > 15_000) { Store.setActive(this, null); return }

        // Spotify: learn the playlist's queue title (usually its name) once it's playing.
        if (active.service == "spotify" && playing && sinceLaunch in 6_000..60_000 && active.queueTitle == null) {
            c.queueTitle?.toString()?.trim()?.ifEmpty { null }?.let { qt ->
                Store.update(this, active.id) {
                    it.queueTitle = qt
                    if (it.nameAuto) { it.name = qt; it.nameAuto = false }
                }
                return
            }
        }

        // Switched playlist inside the app (or autoplay radio after the end)?
        if (playing && sinceLaunch > 15_000 && belongs(active, c, title, artist) == false) {
            if (++mismatches < 2) return
            mismatches = 0
            val other = all.firstOrNull {
                it.id != activeId && it.service == active.service && belongs(it, c, title, artist) == true
            }
            Store.setActive(this, other?.id)
            if (other != null) saveInto(other, c, md, title, artist, st)
            return
        }
        mismatches = 0
        if (!playing && norm(active.lastTitle) != norm(title) && active.lastTitle != null) return
        saveInto(active, c, md, title, artist, st)
    }

    private fun saveInto(p: Playlist, c: MediaController, md: MediaMetadata, title: String, artist: String?, st: PlaybackState) {
        val pos = currentPos(st)
        val sameTrack = norm(p.lastTitle) == norm(title)
        val id = if (p.service == "spotify") trackIdOf(c)
        else if (sameTrack && p.lastVideoId != null) p.lastVideoId
        else YtApi.cached(this, p.listId)?.let { YtApi.match(it, title, artist) }?.videoId
        Store.update(this, p.id) {
            it.lastTitle = title
            it.lastArtist = artist
            it.lastVideoId = id
            it.lastPos = pos
            it.lastDuration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
            it.updatedAt = System.currentTimeMillis()
        }
        if (!sameTrack) Store.addSeen(this, p.id, listOf(norm(title)))
    }

    // ---------------------------------------------------------------- resume

    fun beginResume(r: Resume?) {
        launchedAt = SystemClock.elapsedRealtime()
        pending = r
        handler.removeCallbacks(resumeLoop)
        if (r != null) handler.postDelayed(resumeLoop, 800)
    }

    private fun finish(msg: String?) {
        pending = null
        msg?.let { toast(it) }
    }

    private fun isTarget(r: Resume, c: MediaController, title: String?): Boolean {
        if (r.trackId != null) trackIdOf(c)?.let { return it == r.trackId }
        return norm(title) == norm(r.title)
    }

    /** One step of the resume state machine. Returns true to keep looping. */
    private fun stepResume(): Boolean {
        val r = pending ?: return false
        val now = SystemClock.elapsedRealtime()
        val limit = if (r.hasVid) 30_000 else 240_000
        if (now - r.started > limit) { finish("Lagu terakhir tidak ketemu, diputar dari awal"); return false }
        if (r.doneAt > 0) {
            if (now - r.doneAt > 2500) { finish(null); return false }
            return true
        }
        val c = controllerFor(r.service) ?: return true
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING

        // Wait until the app has actually switched to the new playlist.
        if (!r.seenChange) {
            if (title != null && norm(title) != norm(r.titleAtLaunch)) r.seenChange = true
            else if (now - r.started > 7000) r.seenChange = true
            else return true
        }

        if (isTarget(r, c, title)) {
            if (!playing && now - r.started < 20_000) return true
            if (r.pos > 3000) {
                val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
                val target = if (dur > 0 && r.pos > dur - 5000) maxOf(0, dur - 5000) else r.pos
                c.transportControls.seekTo(target)
            }
            r.doneAt = now
            if (r.skips > 0) toast("Lanjut dari \"${r.title}\" (lompat ${r.skips} lagu)")
            return true
        }

        if (r.hasVid) return true // YT Music should land on it by itself; just wait.

        // Press "next" until the right track plays.
        if (!playing) return true
        if (r.firstTitle == null) r.firstTitle = title
        else if (r.skips > 2 && norm(title) == norm(r.firstTitle)) {
            finish("Lagu terakhir tidak ada di playlist ini"); return false
        }
        val changed = norm(title) != norm(r.lastSkipTitle)
        if ((changed && now - r.lastSkipAt > 400) || now - r.lastSkipAt > 4000) {
            if (r.skips >= 200) { finish("Lagu terakhir tidak ketemu"); return false }
            if (r.skips == 0) toast("Mencari \"${r.title}\"…")
            r.lastSkipTitle = title
            r.lastSkipAt = now
            r.skips++
            c.transportControls.skipToNext()
        }
        return true
    }

    private fun toast(msg: String) =
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // ---------------------------------------------------------------- diagnostics

    private val actionNames = listOf(
        "STOP", "PAUSE", "PLAY", "REWIND", "PREV", "NEXT", "FF", "RATING", "SEEK_TO", "PLAY_PAUSE",
        "PLAY_FROM_MEDIA_ID", "PLAY_FROM_SEARCH", "SKIP_TO_QUEUE_ITEM", "PLAY_FROM_URI", "PREPARE",
        "PREP_MEDIA_ID", "PREP_SEARCH", "PREP_URI", "SPEED"
    )

    private fun describe(c: MediaController, sb: StringBuilder) {
        sb.append("Paket: ").append(c.packageName).append('\n')
        sb.append("queueTitle: ").append(c.queueTitle).append('\n')
        val st = c.playbackState
        sb.append("state: ").append(st?.state).append("  pos: ").append(currentPos(st))
            .append("  activeQueueItemId: ").append(st?.activeQueueItemId).append('\n')
        val acts = st?.actions ?: 0
        sb.append("actions: ").append(actionNames.filterIndexed { i, _ -> acts and (1L shl i) != 0L }.joinToString(" "))
            .append('\n')
        sb.append("-- metadata --\n")
        c.metadata?.let { md ->
            for (k in md.keySet()) {
                val v: Any? = md.getText(k) ?: md.getLong(k).takeIf { it != 0L }
                if (v != null) sb.append(k.removePrefix("android.media.metadata.")).append(" = ").append(v).append('\n')
            }
        }
        val q = c.queue
        sb.append("-- queue (").append(q?.size ?: "null").append(") --\n")
        q?.take(5)?.forEach {
            sb.append("#").append(it.queueId).append(" id=").append(it.description.mediaId)
                .append(" | ").append(it.description.title).append(" | ").append(it.description.subtitle).append('\n')
        }
    }

    fun diagnostics(): String {
        val sb = StringBuilder()
        if (controllers.isEmpty()) {
            sb.append("Sesi YouTube Music / Spotify tidak aktif.\nPutar lagu dulu, lalu buka ini lagi.\n")
            try {
                val l = msm?.getActiveSessions(cn()) ?: emptyList()
                sb.append("Sesi aktif lain: ").append(l.joinToString { s -> s.packageName })
            } catch (_: Exception) {}
            return sb.toString()
        }
        for (p in listOf(YTM, SPOTIFY)) controllers[p]?.let { describe(it, sb); sb.append('\n') }
        val active = Store.activeId(this)?.let { Store.get(this, it) }
        sb.append("Playlist aktif: ").append(active?.name).append(" (").append(active?.service).append(")")
        active?.let {
            if (it.service == "ytm") sb.append("\nCache lagu: ").append(YtApi.cached(this, it.listId)?.size ?: "belum ada")
            else sb.append("\nqueueTitle tersimpan: ").append(it.queueTitle)
            sb.append("\nID lagu tersimpan: ").append(it.lastVideoId)
        }
        sb.append("\nAPI key: ").append(if (Store.apiKey(this) != null) "terisi" else "belum")
        return sb.toString()
    }
}
