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
 * Remembers the last track + position for the active playlist and performs the resume.
 *
 * Findings from the device: YT Music exposes NO media/video ids and does NOT support
 * SKIP_TO_QUEUE_ITEM. So to resume we either
 *   1) know the video id (looked up via YouTube Data API) → open watch?v=ID&list=PL, or
 *   2) open the playlist from the top and press "next" until the right title plays.
 */
class MediaWatcher : NotificationListenerService() {

    companion object {
        const val YTM = "com.google.android.apps.youtube.music"
        var instance: MediaWatcher? = null
            private set

        private val main = Handler(Looper.getMainLooper())

        fun norm(s: String?) = (s ?: "").lowercase().replace(Regex("\\s+"), " ").trim()

        fun open(c: Context, listId: String, vid: String?) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url(listId, vid)))
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                c.startActivity(Intent(i).setPackage(YTM))
            } catch (e: Exception) {
                try { c.startActivity(i) } catch (_: Exception) {
                    Toast.makeText(c, "YouTube Music tidak bisa dibuka", Toast.LENGTH_LONG).show()
                }
            }
        }

        fun url(listId: String, vid: String?) =
            if (vid != null) "https://music.youtube.com/watch?v=$vid&list=$listId"
            else "https://music.youtube.com/watch?list=$listId"

        /** Entry point from the phone UI. Must be called with the visible Activity. */
        fun play(act: Activity, p: Playlist, fromStart: Boolean, msg: (String) -> Unit) =
            start(act, p, fromStart, viaSession = false, msg = msg)

        /**
         * Start playlist p, resuming at its last track.
         * viaSession=false → open YT Music with a link (needs a visible Activity).
         * viaSession=true  → tell YT Music's media session to play the link, without
         *                    showing anything (used from Android Auto).
         */
        fun start(c: Context, p: Playlist, fromStart: Boolean, viaSession: Boolean, msg: (String) -> Unit) {
            instance?.record()
            Store.setActive(c, p.id)
            val title = p.lastTitle
            if (fromStart || title == null) {
                instance?.beginResume(null)
                msg("Memutar ${p.name}…")
                launch(c, p.listId, null, viaSession, msg)
                return
            }
            msg("Memutar ${p.name} — lanjut ke \"$title\"…")
            Thread {
                var vid = p.lastVideoId
                var note: String? = null
                if (vid == null) {
                    var m = YtApi.cached(c, p.listId)?.let { YtApi.match(it, title, p.lastArtist) }
                    if (m == null && Store.apiKey(c) != null) {
                        val (fresh, err) = YtApi.refresh(c, p.listId)
                        m = fresh?.let { YtApi.match(it, title, p.lastArtist) }
                        if (err != null) note = "Daftar lagu tidak bisa diambil ($err) — pakai cara lompat-lompat"
                    }
                    vid = m?.videoId
                }
                main.post {
                    note?.let(msg)
                    instance?.beginResume(
                        Resume(p.id, title, p.lastArtist ?: "",
                            if (c.seekToPos) p.lastPos else 0, hasVid = vid != null,
                            titleAtLaunch = instance?.nowTitle)
                    )
                    launch(c, p.listId, vid, viaSession, msg)
                }
            }.start()
        }

        private fun launch(c: Context, listId: String, vid: String?, viaSession: Boolean, msg: (String) -> Unit) {
            if (!viaSession) { open(c, listId, vid); return }
            val w = instance
            if (w == null) { msg("Izin akses notifikasi belum aktif"); return }
            w.playViaSession(Uri.parse(url(listId, vid)), msg)
        }
    }

    class Resume(
        val playlistId: String,
        val title: String,
        val artist: String,
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
    private var controller: MediaController? = null
    private var pending: Resume? = null
    private var launchedAt = 0L
    private var mismatches = 0

    var nowTitle: String? = null; private set
    var nowArtist: String? = null; private set
    var nowPlaying = false; private set
    val resuming get() = pending != null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { tick() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { tick() }
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) { tick() }
        override fun onSessionDestroyed() { detach() }
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
        val cn = ComponentName(this, MediaWatcher::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, handler)
            attach(m.getActiveSessions(cn))
        } catch (_: SecurityException) { }
        handler.removeCallbacks(periodic)
        handler.post(periodic)
    }

    override fun onListenerDisconnected() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        detach()
        instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- remote control (Android Auto)

    /** Listeners (e.g. AutoService) notified whenever YT Music's state changes. */
    val listeners = LinkedHashSet<() -> Unit>()

    fun ytController(): MediaController? = controller

    /** Ask YT Music's session to play a link; wake YT Music first if it has no session. */
    fun playViaSession(uri: Uri, msg: (String) -> Unit) {
        controller?.let { it.transportControls.playFromUri(uri, null); return }
        msg("Membangunkan YouTube Music…")
        wakeYtMusic()
        val t0 = SystemClock.elapsedRealtime()
        handler.post(object : Runnable {
            override fun run() {
                val c = controller
                when {
                    c != null -> handler.postDelayed({ c.transportControls.playFromUri(uri, null) }, 600)
                    SystemClock.elapsedRealtime() - t0 > 10_000 -> {
                        pending = null
                        msg("YouTube Music tidak merespons — buka YT Music sekali di HP, lalu coba lagi")
                    }
                    else -> handler.postDelayed(this, 400)
                }
            }
        })
    }

    /** Press "play" on YT Music's media-button receiver so it creates its media session. */
    private fun wakeYtMusic() {
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            try {
                sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(YTM)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)))
            } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- sessions

    private fun attach(list: List<MediaController>) {
        val yt = list.firstOrNull { it.packageName == YTM }
        if (yt?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = yt
        yt?.registerCallback(callback, handler)
        tick()
    }

    private fun detach() {
        controller?.unregisterCallback(callback)
        controller = null
        nowPlaying = false
        listeners.toList().forEach { try { it() } catch (_: Exception) {} }
    }

    private fun tick() {
        val c = controller
        nowTitle = c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        nowArtist = c?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        nowPlaying = c?.playbackState?.state == PlaybackState.STATE_PLAYING
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

    // ---------------------------------------------------------------- recording

    /** Does this title belong to playlist p, as far as we know? null = don't know enough. */
    private fun belongs(p: Playlist, title: String, artist: String?): Boolean? {
        val items = YtApi.cached(this, p.listId)
        if (!items.isNullOrEmpty()) return YtApi.match(items, title, artist) != null
        val seen = Store.seen(this, p.id)
        if (seen.size < 10) return null
        return norm(title) in seen
    }

    fun record() {
        if (pending != null) return
        val c = controller ?: return
        val activeId = Store.activeId(this) ?: return
        val md = c.metadata ?: return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val st = c.playbackState ?: return
        if (st.state == PlaybackState.STATE_NONE || st.state == PlaybackState.STATE_ERROR) return

        val all = Store.all(this)
        val active = all.firstOrNull { it.id == activeId } ?: return

        // Switched playlist inside YT Music (or autoplay radio after the end)?
        val playing = st.state == PlaybackState.STATE_PLAYING
        if (playing && SystemClock.elapsedRealtime() - launchedAt > 15_000 &&
            belongs(active, title, artist) == false) {
            // Require it twice in a row (~5 s apart) before letting go of the playlist.
            if (++mismatches < 2) return
            mismatches = 0
            val other = all.firstOrNull { it.id != activeId && belongs(it, title, artist) == true }
            Store.setActive(this, other?.id)
            if (other != null) saveInto(other, md, title, artist, st)
            return
        }
        mismatches = 0
        if (!playing && norm(active.lastTitle) != norm(title) && active.lastTitle != null) return
        saveInto(active, md, title, artist, st)
    }

    private fun saveInto(p: Playlist, md: MediaMetadata, title: String, artist: String?, st: PlaybackState) {
        val pos = currentPos(st)
        val sameTrack = norm(p.lastTitle) == norm(title)
        val vid = if (sameTrack && p.lastVideoId != null) p.lastVideoId
        else YtApi.cached(this, p.listId)?.let { YtApi.match(it, title, artist) }?.videoId
        Store.update(this, p.id) {
            it.lastTitle = title
            it.lastArtist = artist
            it.lastVideoId = vid
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
        val c = controller ?: return true
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING

        // Wait until YT Music has actually switched to the new playlist.
        if (!r.seenChange) {
            if (title != null && norm(title) != norm(r.titleAtLaunch)) r.seenChange = true
            else if (now - r.started > 7000) r.seenChange = true
            else return true
        }

        if (norm(title) == norm(r.title)) {
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

        // Fallback: press "next" until the right track plays.
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

    fun diagnostics(): String {
        val sb = StringBuilder()
        val c = controller
        if (c == null) {
            sb.append("Sesi YouTube Music tidak aktif.\nPutar lagu di YT Music dulu, lalu buka ini lagi.\n")
            msm?.let {
                try {
                    val l = it.getActiveSessions(ComponentName(this, MediaWatcher::class.java))
                    sb.append("Sesi aktif lain: ").append(l.joinToString { s -> s.packageName })
                } catch (_: Exception) {}
            }
            return sb.toString()
        }
        sb.append("Paket: ").append(c.packageName).append('\n')
        sb.append("queueTitle: ").append(c.queueTitle).append('\n')
        val st = c.playbackState
        sb.append("state: ").append(st?.state).append("  pos: ").append(currentPos(st))
            .append("  activeQueueItemId: ").append(st?.activeQueueItemId).append('\n')
        val acts = st?.actions ?: 0
        sb.append("actions: ").append(actionNames.filterIndexed { i, _ -> acts and (1L shl i) != 0L }.joinToString(" "))
            .append('\n')
        sb.append("\n-- metadata --\n")
        c.metadata?.let { md ->
            for (k in md.keySet()) {
                val v: Any? = md.getText(k) ?: md.getLong(k).takeIf { it != 0L }
                if (v != null) sb.append(k.removePrefix("android.media.metadata.")).append(" = ").append(v).append('\n')
            }
        }
        val q = c.queue
        sb.append("\n-- queue (").append(q?.size ?: "null").append(") --\n")
        q?.take(5)?.forEach {
            sb.append("#").append(it.queueId).append(" id=").append(it.description.mediaId)
                .append(" | ").append(it.description.title).append(" | ").append(it.description.subtitle).append('\n')
        }
        val active = Store.activeId(this)?.let { Store.get(this, it) }
        sb.append("\nPlaylist aktif: ").append(active?.name)
        active?.let {
            sb.append("\nCache lagu: ").append(YtApi.cached(this, it.listId)?.size ?: "belum ada")
            sb.append("\nvideoId tersimpan: ").append(it.lastVideoId)
        }
        sb.append("\nAPI key: ").append(if (Store.apiKey(this) != null) "terisi" else "belum")
        return sb.toString()
    }
}
