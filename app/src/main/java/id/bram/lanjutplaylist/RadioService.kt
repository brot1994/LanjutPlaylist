package id.bram.lanjutplaylist

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import id.bram.lanjutplaylist.RadioStore.lastStation

/**
 * Plays radio streams in the background (ExoPlayer + media notification).
 * Audio focus is handled, so starting the radio pauses YT Music / Spotify and vice versa.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class RadioService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Tap the decoded audio for the on-screen visualizer.
        val tee = TeeAudioProcessor(object : TeeAudioProcessor.AudioBufferSink {
            override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) =
                Viz.configure(sampleRateHz, channelCount, encoding)
            override fun handleBuffer(buffer: ByteBuffer) = Viz.feed(buffer)
        })
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(tee)).build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/** App-side handle on the radio player (UI + Android Auto talk to this). */
object RadioClient {
    private val main = Handler(Looper.getMainLooper())
    private var connecting = false
    private val waiting = ArrayList<(MediaController) -> Unit>()

    var controller: MediaController? = null; private set
    var stationId: String? = null; private set
    /** Short status text for the UI ("Mencari stream…", errors). */
    var status: String? = null; private set

    val listeners = LinkedHashSet<() -> Unit>()
    private fun changed() {
        // Radio drives the lyrics only while it is the thing playing.
        if (isPlaying || isBuffering) Lyrics.onTitle(nowTitle)
        listeners.toList().forEach { try { it() } catch (_: Exception) {} }
    }

    val isPlaying get() = controller?.isPlaying == true
    val isBuffering get() = controller?.let { it.playWhenReady && it.playbackState == Player.STATE_BUFFERING } == true
    val hasItem get() = (controller?.mediaItemCount ?: 0) > 0

    /** "Artist - Song" from the stream (ICY), if the station sends it. */
    val nowTitle: String?
        get() {
            val c = controller ?: return null
            val t = c.mediaMetadata.title?.toString()?.trim()
            val st = c.currentMediaItem?.mediaMetadata?.title?.toString()
            return t?.takeIf { it.isNotEmpty() && it != st }
        }

    private fun connect(c: Context, then: (MediaController) -> Unit) {
        controller?.takeIf { it.isConnected }?.let { then(it); return }
        waiting.add(then)
        if (connecting) return
        connecting = true
        val app = c.applicationContext
        val f = MediaController.Builder(app, SessionToken(app, ComponentName(app, RadioService::class.java))).buildAsync()
        f.addListener({
            connecting = false
            try {
                val mc = f.get()
                controller = mc
                mc.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { changed() }
                    override fun onPlayerError(error: PlaybackException) {
                        status = "Stream gagal diputar (${error.errorCodeName}). Coba lagi / ganti link."
                        // A seeded station's link may have changed: look it up again next time.
                        stationId?.let { id ->
                            RadioStore.update(app, id) { s -> if (!s.custom) s.url = null }
                        }
                        changed()
                    }
                })
                val l = waiting.toList(); waiting.clear()
                l.forEach { it(mc) }
            } catch (e: Exception) {
                waiting.clear()
                status = "Pemutar radio gagal dimulai: ${e.message}"
                changed()
            }
        }, ContextCompat.getMainExecutor(app))
    }

    fun play(c: Context, s: Station) {
        stationId = s.id
        c.lastStation = s.id
        val url = s.url
        if (url == null) {
            status = "Mencari stream ${s.name}…"
            changed()
            Thread {
                val u = try { RadioApi.resolve(s) } catch (e: Exception) { null }
                main.post {
                    if (stationId != s.id) return@post
                    if (u == null) {
                        status = "Stream ${s.name} tidak ketemu — tekan lama kartunya untuk isi link manual"
                        changed()
                    } else {
                        RadioStore.update(c, s.id) { it.url = u }
                        start(c, s, u)
                    }
                }
            }.start()
            return
        }
        start(c, s, url)
    }

    private fun start(c: Context, s: Station, url: String) {
        status = null
        changed()
        connect(c) { mc ->
            val md = MediaMetadata.Builder().setTitle(s.name).setArtist(s.info).setStation(s.name).build()
            mc.setMediaItem(MediaItem.Builder().setUri(url).setMediaId(s.id).setMediaMetadata(md).build())
            mc.prepare()
            mc.play()
        }
    }

    fun pause() { controller?.pause() }

    fun resume(c: Context) {
        val mc = controller
        when {
            mc != null && mc.mediaItemCount > 0 -> {
                // Live radio: jump back to "now" instead of continuing a stale buffer.
                if (mc.playbackState == Player.STATE_IDLE || mc.playbackState == Player.STATE_ENDED) mc.prepare()
                mc.seekToDefaultPosition(); mc.play()
            }
            else -> (stationId ?: c.lastStation)?.let { RadioStore.get(c, it) }?.let { play(c, it) }
        }
    }

    fun stop() { controller?.stop(); controller?.clearMediaItems(); changed() }

    /** Next / previous station in the list (used by Android Auto & steering-wheel buttons). */
    fun step(c: Context, delta: Int) {
        val l = RadioStore.all(c)
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.id == (stationId ?: c.lastStation) }
        play(c, l[((if (i < 0) 0 else i) + delta + l.size) % l.size])
    }
}
