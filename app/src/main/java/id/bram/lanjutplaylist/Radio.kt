package id.bram.lanjutplaylist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** A radio station. url == null → not resolved yet (looked up via Radio Browser on first play). */
data class Station(
    val id: String,
    var name: String,
    var info: String,          // e.g. "102.2 FM · Jakarta"
    var query: String,         // name to search in Radio Browser
    var prefer: String = "",   // word that should appear in the match (e.g. city)
    var url: String? = null,
    var custom: Boolean = false,
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("info", info).put("query", query)
        .put("prefer", prefer).put("url", url ?: JSONObject.NULL).put("custom", custom)

    companion object {
        fun fromJson(o: JSONObject) = Station(
            o.getString("id"), o.optString("name"), o.optString("info"), o.optString("query"),
            o.optString("prefer"), if (o.isNull("url")) null else o.optString("url").ifEmpty { null },
            o.optBoolean("custom"),
        )
    }
}

object RadioStore {
    private fun prefs(c: Context) = c.getSharedPreferences("radio", Context.MODE_PRIVATE)

    /** Default list: the 4 favourites first, then Jakarta FM stations by frequency. */
    private fun seed(): List<Station> {
        val l = ArrayList<Station>()
        fun s(name: String, freq: String, city: String, query: String = name, prefer: String = "") =
            l.add(Station("seed:" + query.lowercase().replace(Regex("[^a-z0-9]+"), "-"),
                name, "$freq FM · $city", query, prefer))
        s("Prambors FM", "102.2", "Jakarta", "Prambors", "jakarta")
        s("Jak FM", "101.0", "Jakarta", "Jak FM", "101")
        s("Sonora FM", "92.0", "Jakarta", "Sonora", "jakarta")
        s("Suara Surabaya", "100.0", "Surabaya", "Suara Surabaya")
        // Jakarta, by frequency
        s("Mustang 88 FM", "88.0", "Jakarta", "Mustang 88")
        s("Global Radio", "88.4", "Jakarta", "Global Radio", "jakarta")
        s("RRI Pro 3", "88.8", "Jakarta", "RRI Pro 3", "pro 3")
        s("RDA Radio Dangdut Asia", "89.8", "Jakarta", "Dangdut Asia")
        s("Elshinta", "90.0", "Jakarta", "Elshinta")
        s("OZ Radio", "90.8", "Jakarta", "OZ Radio", "jakarta")
        s("RRI Pro 1 Jakarta", "91.2", "Jakarta", "RRI Pro 1", "jakarta")
        s("PAS FM", "92.4", "Jakarta", "PAS FM")
        s("RRI Pro 4 Jakarta", "92.8", "Jakarta", "RRI Pro 4", "jakarta")
        s("Hot FM", "93.2", "Jakarta", "Hot 93.2")
        s("Good Radio", "94.3", "Jakarta", "Good Radio")
        s("MG Radio", "94.7", "Jakarta", "MG Radio")
        s("Kis FM", "95.1", "Jakarta", "Kis FM")
        s("Smart FM", "95.9", "Jakarta", "Smart FM", "jakarta")
        s("RPK FM", "96.3", "Jakarta", "RPK")
        s("Hitz FM", "96.7", "Jakarta", "Hitz 96.7")
        s("RDI", "97.1", "Jakarta", "RDI", "97")
        s("Motion Radio", "97.5", "Jakarta", "Motion Radio")
        s("Female Radio", "97.9", "Jakarta", "Female Radio")
        s("Mandarin Station", "98.3", "Jakarta", "Mandarin Station")
        s("Gen FM", "98.7", "Jakarta", "Gen 98.7")
        s("Delta FM", "99.1", "Jakarta", "Delta FM", "jakarta")
        s("Z 99.9 FM", "99.9", "Jakarta", "Z 99.9")
        s("I-Radio", "89.6", "Jakarta", "I-Radio", "jakarta")
        s("Bahana FM", "101.8", "Jakarta", "Bahana FM")
        s("Pop FM", "103.0", "Jakarta", "Pop FM", "jakarta")
        s("DFM", "103.4", "Jakarta", "DFM", "103")
        s("X Channel", "103.8", "Jakarta", "X Channel")
        s("MS Tri FM", "104.2", "Jakarta", "MS Tri")
        s("MNC Trijaya FM", "104.6", "Jakarta", "Trijaya", "jakarta")
        s("RRI Pro 2 Jakarta", "105.0", "Jakarta", "RRI Pro 2", "jakarta")
        s("MOST Radio", "105.8", "Jakarta", "MOST Radio")
        s("Bens Radio", "106.2", "Jakarta", "Bens Radio")
        s("V Radio", "106.6", "Jakarta", "V Radio", "jakarta")
        return l
    }

    fun all(c: Context): MutableList<Station> {
        val raw = prefs(c).getString("stations", null)
        if (raw == null) { val s = seed(); saveAll(c, s); return s.toMutableList() }
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { Station.fromJson(arr.getJSONObject(it)) }
    }

    fun saveAll(c: Context, list: List<Station>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("stations", arr.toString()).apply()
    }

    fun get(c: Context, id: String) = all(c).firstOrNull { it.id == id }

    fun update(c: Context, id: String, block: (Station) -> Unit) {
        val l = all(c); l.firstOrNull { it.id == id }?.let { block(it); saveAll(c, l) }
    }

    fun add(c: Context, name: String, info: String, url: String): Station {
        val l = all(c)
        l.firstOrNull { it.url == url }?.let { return it }
        val s = Station(UUID.randomUUID().toString(), name, info, name, url = url, custom = true)
        l.add(s); saveAll(c, l); return s
    }

    fun remove(c: Context, id: String) = saveAll(c, all(c).filterNot { it.id == id })

    fun move(c: Context, id: String, delta: Int) {
        val l = all(c); val i = l.indexOfFirst { it.id == id }; val j = i + delta
        if (i < 0 || j < 0 || j >= l.size) return
        l.add(j, l.removeAt(i)); saveAll(c, l)
    }

    fun resetDefaults(c: Context) {
        val custom = all(c).filter { it.custom }
        saveAll(c, seed() + custom)
    }

    var Context.lastStation: String?
        get() = prefs(this).getString("last", null)
        set(v) = prefs(this).edit().putString("last", v).apply()
}

/** Radio Browser (radio-browser.info) — free public directory of internet radio streams. */
object RadioApi {
    private val servers = listOf(
        "https://de1.api.radio-browser.info", "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info", "https://de2.api.radio-browser.info"
    )

    data class Hit(val name: String, val url: String, val city: String, val codec: String,
                   val bitrate: Int, val votes: Int, val ok: Boolean)

    private fun get(path: String): JSONArray {
        var last: Exception? = null
        for (s in servers) {
            try {
                val conn = URL(s + path).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 12000
                conn.setRequestProperty("User-Agent", "LanjutPlaylist/1.7")
                if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
                return JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            } catch (e: Exception) { last = e }
        }
        throw last ?: Exception("Radio Browser tidak bisa dihubungi")
    }

    fun search(query: String, indonesiaOnly: Boolean = true, limit: Int = 40): List<Hit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val arr = get("/json/stations/search?name=$q" + (if (indonesiaOnly) "&countrycode=ID" else "") +
            "&hidebroken=true&order=clickcount&reverse=true&limit=$limit")
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Hit(o.optString("name").trim(), o.optString("url_resolved").ifEmpty { o.optString("url") },
                o.optString("state").ifEmpty { o.optString("tags") }, o.optString("codec"),
                o.optInt("bitrate"), o.optInt("clickcount"), o.optInt("lastcheckok") == 1)
        }.filter { it.url.startsWith("http") }
    }

    /** Find the best stream for a station from our list. Returns null if nothing matches. */
    fun resolve(s: Station): String? {
        val hits = search(s.query)
        if (hits.isEmpty()) return null
        val q = s.query.lowercase()
        val pref = s.prefer.lowercase()
        return hits.maxByOrNull { h ->
            val n = (h.name + " " + h.city).lowercase()
            var sc = 0
            if (n.contains(q)) sc += 50
            if (pref.isNotEmpty() && n.contains(pref)) sc += 40
            if (h.ok) sc += 20
            sc += minOf(h.votes, 2000) / 100
            if (h.codec.equals("AAC", true) || h.codec.equals("MP3", true)) sc += 3
            sc
        }?.url
    }
}
