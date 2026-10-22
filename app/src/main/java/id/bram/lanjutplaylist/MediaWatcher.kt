package id.bram.lanjutplaylist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.widget.Toast
import id.bram.lanjutplaylist.Store.seekToPos

/**
 * Runs in the background (bound by the system once notification access is granted).
 * Watches YouTube Music's media session, remembers the last track + position for the
 * playlist that is "active", and performs the resume (jump to track + seek) after
 * we open a playlist.
 */
class MediaWatcher : NotificationListenerService() {

    companion object {
        const val YTM = "com.google.android.apps.youtube.music"
        var instance: MediaWatcher? = null
            private set

        private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

        fun norm(s: String?) = (s ?: "").lowercase().replace(Regex("\\s+"), " ").trim()

        /** Open a playlist in YT Music (works even if the watcher isn't running). */
        fun openInYtMusic(c: Context, p: Playlist, fromStart: Boolean) {
            val vid = if (fromStart) null else p.lastVideoId
            val url = if (vid != null)
                "https://music.youtube.com/watch?v=$vid&list=${p.listId}"
            else
                "https://music.youtube.com/watch?list=${p.listId}"
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                c.startActivity(Intent(i).setPackage(YTM))
            } catch (e: Exception) {
                try { c.startActivity(i) } catch (_: Exception) {
                    Toast.makeText(c, "YouTube Music tidak ditemukan", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private class Resume(
        val playlistId: String,
        val title: String,
        val artist: String,
        val pos: Long,
        val started: Long = SystemClock.elapsedRealtime(),
    ) {
        var skipped = false
        var doneAt = 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var pending: Resume? = null
    private var launchedAt = 0L

    /** Last thing we saw, for the UI. */
    var nowTitle: String? = null; private set
    var nowArtist: String? = null; private set
    var nowPlaying = false; private set

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(list ?: emptyList())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { tick() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { tick() }
        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) { tick() }
        override fun onSessionDestroyed() { detach() }
    }

    private val periodic = object : Runnable {
        override fun run() {
            record()
            handler.postDelayed(this, 5000)
        }
    }

    private val resumeLoop = object : Runnable {
        override fun run() {
            if (stepResume()) handler.postDelayed(this, 600)
        }
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
    }

    private fun tick() {
        val c = controller
        nowTitle = c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        nowArtist = c?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        nowPlaying = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        if (pending != null) stepResume() else record()
    }

    private fun currentPos(st: PlaybackState?): Long {
        if (st == null) return 0
        var p = st.position
        if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        return maxOf(0, p)
    }

    private fun videoIdOf(c: MediaController): String? {
        val md = c.metadata ?: return null
        md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.let { id ->
            if (VIDEO_ID.matches(id)) return id
            Regex("[?&]v=([A-Za-z0-9_-]{11})").find(id)?.let { return it.groupValues[1] }
        }
        val active = c.playbackState?.activeQueueItemId ?: -1
        val item = c.queue?.firstOrNull { it.queueId == active }
        item?.description?.mediaId?.let { if (VIDEO_ID.matches(it)) return it }
        return null
    }

    // ---------------------------------------------------------------- recording

    /** Save the current track/position into the active playlist. */
    fun record() {
        if (pending != null) return
        val c = controller ?: return
        val activeId = Store.activeId(this) ?: return
        val md = c.metadata ?: return
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return
        val st = c.playbackState ?: return
        if (st.state == PlaybackState.STATE_NONE || st.state == PlaybackState.STATE_ERROR) return

        val sinceLaunch = SystemClock.elapsedRealtime() - launchedAt
        val qt = c.queueTitle?.toString()?.trim()?.ifEmpty { null }

        // Detect a playlist switch made directly inside YT Music.
        val all = Store.all(this)
        val active = all.firstOrNull { it.id == activeId } ?: return
        if (qt != null && sinceLaunch > 10_000) {
            if (active.queueTitle == null) {
                Store.update(this, activeId) {
                    it.queueTitle = qt
                    if (it.nameAuto) { it.name = qt; it.nameAuto = false }
                }
            } else if (norm(active.queueTitle) != norm(qt)) {
                val other = all.firstOrNull { norm(it.queueTitle) == norm(qt) || norm(it.name) == norm(qt) }
                Store.setActive(this, other?.id)
                if (other == null) return
                saveInto(other.id, c, md, title, st)
                return
            }
        }
        saveInto(activeId, c, md, title, st)
    }

    private fun saveInto(id: String, c: MediaController, md: MediaMetadata, title: String, st: PlaybackState) {
        val pos = currentPos(st)
        val vid = videoIdOf(c)
        Store.update(this, id) {
            if (norm(it.lastTitle) != norm(title)) it.lastVideoId = null
            it.lastTitle = title
            it.lastArtist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            if (vid != null) it.lastVideoId = vid
            it.lastPos = pos
            it.lastDuration = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
            it.updatedAt = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------- resume

    /** Called from the UI when the user taps a playlist. */
    fun resume(p: Playlist, fromStart: Boolean) {
        record() // save the playlist we're leaving
        Store.setActive(this, p.id)
        launchedAt = SystemClock.elapsedRealtime()
        pending = if (!fromStart && p.lastTitle != null)
            Resume(p.id, p.lastTitle!!, p.lastArtist ?: "", if (seekToPos) p.lastPos else 0)
        else null
        openInYtMusic(this, p, fromStart)
        handler.removeCallbacks(resumeLoop)
        if (pending != null) handler.postDelayed(resumeLoop, 800)
    }

    /** One step of the resume state machine. Returns true to keep looping. */
    private fun stepResume(): Boolean {
        val r = pending ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - r.started > 30_000) {
            pending = null
            toast("Lagu terakhir tidak ketemu, diputar dari awal playlist")
            return false
        }
        if (r.doneAt > 0) {
            if (now - r.doneAt > 2500) { pending = null; return false }
            return true
        }
        val c = controller ?: return true
        val title = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val st = c.playbackState

        if (norm(title) == norm(r.title)) {
            // Right track. Wait until it's actually playing, then seek.
            if (st?.state != PlaybackState.STATE_PLAYING && now - r.started < 15_000) return true
            if (r.pos > 3000) {
                val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0
                val target = if (dur > 0 && r.pos > dur - 5000) maxOf(0, dur - 5000) else r.pos
                c.transportControls.seekTo(target)
            }
            r.doneAt = now
            return true
        }

        // Wrong track: give YT Music a moment, then look for the track in the queue.
        if (now - r.started > 3000 && !r.skipped) {
            val q = c.queue
            if (!q.isNullOrEmpty()) {
                val match = q.firstOrNull {
                    norm(it.description.title?.toString()) == norm(r.title) &&
                        (r.artist.isEmpty() || norm(it.description.subtitle?.toString()).contains(norm(r.artist)))
                } ?: q.firstOrNull { norm(it.description.title?.toString()) == norm(r.title) }
                if (match != null) {
                    c.transportControls.skipToQueueItem(match.queueId)
                    r.skipped = true
                }
            }
        }
        return true
    }

    private fun toast(msg: String) =
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // ---------------------------------------------------------------- diagnostics

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
        sb.append("actions: ").append(st?.actions?.let { java.lang.Long.toBinaryString(it) }).append('\n')
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
                .append(" | ").append(it.description.title).append(" | ").append(it.description.subtitle)
                .append(" | uri=").append(it.description.mediaUri).append('\n')
        }
        sb.append("\nvideoId terbaca: ").append(videoIdOf(c)).append('\n')
        return sb.toString()
    }
}
