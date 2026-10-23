package id.bram.lanjutplaylist

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Synced lyrics for the song the radio is playing, from LRCLIB (free, no key).
 * The radio only tells us when the title changes, so the start time is approximate;
 * the user can nudge it (±1 s) or tap the line that is being sung right now.
 */
object Lyrics {
    data class Line(val t: Long, val text: String)

    private val main = Handler(Looper.getMainLooper())

    /** The ICY title we're showing lyrics for. */
    var title: String? = null; private set
    var artist: String? = null; private set
    var song: String? = null; private set
    var lines: List<Line>? = null; private set
    var plain: String? = null; private set
    var status: String? = null; private set
    /** elapsedRealtime when the title changed (≈ song start). */
    private var startedAt = 0L
    /** User correction, ms. */
    var offset = 0L; private set
    /** For YT Music / Spotify: exact playback position from the app. null → radio (clock since title change). */
    private var posProvider: (() -> Long)? = null
    val isRadio get() = posProvider == null
    private var duration = 0L

    val listeners = LinkedHashSet<() -> Unit>()
    private fun changed() = main.post { listeners.toList().forEach { try { it() } catch (_: Exception) {} } }

    private val cache = HashMap<String, Pair<List<Line>?, String?>>()

    /** Called whenever the radio's "now playing" title may have changed. */
    fun onTitle(t: String?) {
        if (t == title && posProvider == null) return
        posProvider = null; duration = 0
        val (a, s) = split(t)
        load(t, a, s)
    }

    /** Called for YT Music / Spotify with the exact track info and a live position source. */
    fun onMusic(title: String?, artist: String?, durMs: Long, pos: () -> Long) {
        val t = title?.let { if (artist.isNullOrBlank()) it else "$artist - $it" }
        posProvider = pos
        if (t == this.title) return
        duration = durMs
        load(t, artist?.trim()?.ifEmpty { null }, title?.let { clean(it) })
    }

    private fun load(t: String?, a: String?, s: String?) {
        title = t
        startedAt = SystemClock.elapsedRealtime()
        offset = 0
        lines = null; plain = null
        artist = a; song = s
        if (s == null) { status = if (t == null) null else "Info lagu dari radio tidak lengkap"; changed(); return }
        val key = "${a ?: ""}|$s".lowercase()
        cache[key]?.let { (l, p) -> lines = l; plain = p; status = if (l == null && p == null) "Lirik tidak ditemukan" else null; changed(); return }
        status = "Mencari lirik…"
        changed()
        Thread {
            val res = try { fetch(a, s, duration) } catch (e: Exception) { null to null }
            main.post {
                cache[key] = res
                if (title != t) return@post
                lines = res.first; plain = res.second
                status = if (res.first == null && res.second == null) "Lirik tidak ditemukan" else null
                changed()
            }
        }.start()
    }

    /** "Artist - Title" → (artist, title). */
    private fun split(t: String?): Pair<String?, String?> {
        val x = t?.trim()?.ifEmpty { null } ?: return null to null
        val i = x.indexOf(" - ")
        return if (i > 0) x.substring(0, i).trim() to clean(x.substring(i + 3)) else null to clean(x)
    }

    private fun clean(s: String) = s
        .replace(Regex("\\s+-\\s+[^-]*(edit|remaster|version|live|mono|stereo)[^-]*$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*[\\(\\[][^)\\]]*(radio edit|edit|remaster|live|version|feat\\.?)[^)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE), "")
        .trim().ifEmpty { null }

    // ------------------------------------------------------------ timing

    fun position(): Long = (posProvider?.invoke() ?: (SystemClock.elapsedRealtime() - startedAt)) + offset

    fun nudge(ms: Long) { offset += ms; changed() }

    /** The user says line i is being sung right now. */
    fun syncTo(i: Int) {
        val l = lines ?: return
        if (i !in l.indices) return
        offset = l[i].t - (posProvider?.invoke() ?: (SystemClock.elapsedRealtime() - startedAt))
        changed()
    }

    fun currentIndex(): Int {
        val l = lines ?: return -1
        val p = position()
        var idx = -1
        for (i in l.indices) { if (l[i].t <= p) idx = i else break }
        return idx
    }

    // ------------------------------------------------------------ LRCLIB

    private fun http(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "LanjutPlaylist/1.8 (https://github.com/brot1994/LanjutPlaylist)")
        if (c.responseCode !in 200..299) return null
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun fetch(artist: String?, song: String, durMs: Long = 0): Pair<List<Line>?, String?> {
        if (artist != null) {
            // Spotify lists "A, B" and YT Music "A & B": the first artist matches best.
            val first = artist.split(Regex("\\s*(,|&| x | feat\\.? | ft\\.? )\\s*")).first().trim()
            val dur = if (durMs > 0) "&duration=${durMs / 1000}" else ""
            for (a in listOf(artist, first).distinct()) {
                http("https://lrclib.net/api/get?artist_name=${enc(a)}&track_name=${enc(song)}$dur")?.let {
                    val r = from(JSONObject(it))
                    if (r.first != null) return r
                }
            }
            http("https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(song)}")?.let {
                val o = JSONObject(it)
                val r = from(o)
                if (r.first != null || r.second != null) return r
            }
        }
        val q = listOfNotNull(artist, song).joinToString(" ")
        val arr = http("https://lrclib.net/api/search?q=${enc(q)}")?.let { JSONArray(it) } ?: return null to null
        var plainFallback: String? = null
        for (i in 0 until arr.length()) {
            val r = from(arr.getJSONObject(i))
            if (r.first != null) return r
            if (plainFallback == null) plainFallback = r.second
        }
        return null to plainFallback
    }

    private fun from(o: JSONObject): Pair<List<Line>?, String?> {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return synced?.let { parse(it) }?.takeIf { it.isNotEmpty() } to plain
    }

    private val TAG = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)]")

    private fun parse(lrc: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in lrc.lines()) {
            val tags = TAG.findAll(raw).toList()
            if (tags.isEmpty()) continue
            val text = raw.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val ms = m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].toDouble() * 1000).toLong()
                out.add(Line(ms, text.ifEmpty { "♪" }))
            }
        }
        return out.sortedBy { it.t }
    }
}
