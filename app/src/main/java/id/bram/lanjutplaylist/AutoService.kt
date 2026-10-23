package id.bram.lanjutplaylist

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

/**
 * Makes Lanjut Playlist show up in Android Auto as a media app.
 * It plays no audio itself: picking a playlist tells YouTube Music / Spotify (through its media
 * session) to play it from the last track, and this app's player screen mirrors that app
 * and forwards play/pause/next/prev/seek to it.
 */
class AutoService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private val handler = Handler(Looper.getMainLooper())
    private var errorText: String? = null
    private var watcherHooked: MediaWatcher? = null
    /** What the car screen shows/controls: "music" (YT Music/Spotify) or "radio". */
    private var lastSource = "music"

    private val ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_STOP

    private val mirror: () -> Unit = { handler.post { pushState() } }

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "LanjutPlaylist")
        session.setCallback(callback)
        sessionToken = session.sessionToken
        session.isActive = true
        RadioClient.listeners.add(mirror)
        hookWatcher()
        pushState()
    }

    override fun onDestroy() {
        watcherHooked?.listeners?.remove(mirror)
        RadioClient.listeners.remove(mirror)
        handler.removeCallbacksAndMessages(null)
        session.release()
        super.onDestroy()
    }

    /** The watcher may (re)start after us; keep trying to hook into it. */
    private fun hookWatcher() {
        val w = MediaWatcher.instance
        if (w !== watcherHooked) {
            watcherHooked?.listeners?.remove(mirror)
            w?.listeners?.add(mirror)
            watcherHooked = w
            pushState()
        }
        handler.postDelayed({ hookWatcher() }, 3000)
    }

    // ------------------------------------------------------------ browse tree

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) =
        BrowserRoot("root", null)

    private fun folder(id: String, title: String, sub: String) = MediaBrowserCompat.MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(),
        MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
    )

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val items = ArrayList<MediaBrowserCompat.MediaItem>()
        val all = Store.all(this)
        when (parentId) {
            "root" -> {
                items.add(folder("ytm", "YouTube Music", "${all.count { it.service == "ytm" }} playlist"))
                items.add(folder("spotify", "Spotify", "${all.count { it.service == "spotify" }} playlist"))
                items.add(folder("radio", "Radio", "${RadioStore.all(this).size} stasiun"))
            }
            "ytm", "spotify" -> {
                val active = Store.activeId(this)
                for (p in all.filter { it.service == parentId }) {
                    val sub = p.lastTitle?.let { "Lanjut: $it" + (p.lastArtist?.let { a -> " – $a" } ?: "") } ?: "Mulai dari awal"
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId(p.id)
                        .setTitle((if (p.id == active) "♫ " else "") + p.name)
                        .setSubtitle(sub)
                        .build()
                    items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                }
            }
            "radio" -> {
                val cur = RadioClient.stationId
                for (st in RadioStore.all(this)) {
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId("radio:" + st.id)
                        .setTitle((if (st.id == cur) "📻 " else "") + st.name)
                        .setSubtitle(st.info)
                        .build()
                    items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                }
            }
        }
        result.sendResult(items)
    }

    // ------------------------------------------------------------ controls

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId?.startsWith("radio:") == true) {
                val st = RadioStore.get(this@AutoService, mediaId.removePrefix("radio:")) ?: return
                lastSource = "radio"
                errorText = null
                RadioClient.play(this@AutoService, st)
                handler.postDelayed({ notifyChildrenChanged("radio") }, 1500)
                return
            }
            val p = mediaId?.let { Store.get(this@AutoService, it) } ?: return
            lastSource = "music"
            if (RadioClient.isPlaying || RadioClient.isBuffering) RadioClient.pause()
            errorText = null
            showMessage("Memutar ${p.name}…")
            MediaWatcher.start(this@AutoService, p, fromStart = false, viaSession = true) { m ->
                handler.post { showMessage(m) }
            }
            handler.postDelayed({ notifyChildrenChanged(p.service) }, 8000)
        }

        override fun onPlay() {
            if (radio()) RadioClient.resume(this@AutoService) else yt()?.play() ?: resumeActive()
        }
        override fun onPause() { if (radio()) RadioClient.pause() else yt()?.pause() }
        override fun onStop() { if (radio()) RadioClient.pause() else yt()?.pause() }
        override fun onSkipToNext() { if (radio()) RadioClient.step(this@AutoService, 1) else yt()?.skipToNext() }
        override fun onSkipToPrevious() { if (radio()) RadioClient.step(this@AutoService, -1) else yt()?.skipToPrevious() }
        override fun onSeekTo(pos: Long) { if (!radio()) yt()?.seekTo(pos) }
    }

    private fun yt() = MediaWatcher.instance?.currentController()?.transportControls

    /** Is the radio what's on (or was last chosen)? */
    private fun radio(): Boolean {
        if (RadioClient.isPlaying || RadioClient.isBuffering) { lastSource = "radio"; return true }
        val musicPlaying = MediaWatcher.instance?.currentController()?.playbackState?.state ==
            android.media.session.PlaybackState.STATE_PLAYING
        if (musicPlaying) { lastSource = "music"; return false }
        return lastSource == "radio"
    }

    /** "Play" with YT Music not running → resume the active (or first) playlist. */
    private fun resumeActive() {
        val p = Store.activeId(this)?.let { Store.get(this, it) } ?: Store.all(this).firstOrNull() ?: return
        callback.onPlayFromMediaId(p.id, null)
    }

    // ------------------------------------------------------------ mirroring

    private fun showMessage(m: String) {
        errorText = m
        pushState()
        handler.postDelayed({ if (errorText == m) { errorText = null; pushState() } }, 6000)
    }

    private fun pushRadioState() {
        val st = RadioClient.stationId?.let { RadioStore.get(this, it) }
        val mb = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, RadioClient.nowTitle ?: st?.name ?: "Radio")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, (st?.name ?: "") + (st?.info?.let { " · $it" } ?: ""))
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, "Radio")
        session.setMetadata(mb.build())
        val state = when {
            RadioClient.isBuffering -> PlaybackStateCompat.STATE_BUFFERING
            RadioClient.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val sb = PlaybackStateCompat.Builder()
            .setActions(ACTIONS and PlaybackStateCompat.ACTION_SEEK_TO.inv())
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f, SystemClock.elapsedRealtime())
        RadioClient.status?.let { sb.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, it) }
        session.setPlaybackState(sb.build())
    }

    private fun pushState() {
        if (radio()) { pushRadioState(); return }
        val c = MediaWatcher.instance?.currentController()
        val md = c?.metadata
        val st = c?.playbackState

        val mb = MediaMetadataCompat.Builder()
        if (md != null) {
            mb.putString(MediaMetadataCompat.METADATA_KEY_TITLE, md.getString(MediaMetadata.METADATA_KEY_TITLE))
            mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, md.getString(MediaMetadata.METADATA_KEY_ARTIST))
            mb.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, md.getString(MediaMetadata.METADATA_KEY_ALBUM))
            mb.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, md.getLong(MediaMetadata.METADATA_KEY_DURATION))
            (md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART))
                ?.let { mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        } else {
            mb.putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Lanjut Playlist")
            mb.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Pilih playlist dari daftar")
        }
        val active = Store.activeId(this)?.let { Store.get(this, it) }
        active?.let { mb.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, it.name) }
        session.setMetadata(mb.build())

        val sb = PlaybackStateCompat.Builder().setActions(ACTIONS)
        val err = errorText
        if (st != null) {
            sb.setState(st.state, st.position, st.playbackSpeed, st.lastPositionUpdateTime)
        } else {
            sb.setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f, SystemClock.elapsedRealtime())
        }
        if (err != null) {
            // Shown by Android Auto as a short message while keeping controls usable.
            sb.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, err)
        }
        session.setPlaybackState(sb.build())
    }
}
