package id.bram.lanjutplaylist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One track of a playlist, as YouTube Data API reports it. */
data class Item(val videoId: String, val title: String, val channel: String)

/**
 * YouTube Data API v3 (API key only, no login). Used to look up the video id of the
 * last-played track, because YT Music doesn't expose ids through its media session.
 * Works for public and unlisted playlists; private ones (incl. Liked Music) don't.
 */
object YtApi {
    private const val BASE = "https://www.googleapis.com/youtube/v3/"

    class ApiError(msg: String) : Exception(msg)

    private fun get(path: String, key: String): JSONObject {
        val conn = URL(BASE + path + "&key=" + URLEncoder.encode(key, "UTF-8")).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = try {
                JSONObject(body).getJSONObject("error").optString("message")
            } catch (_: Exception) { body.take(200) }
            throw ApiError("HTTP $code: $msg")
        }
        return JSONObject(body)
    }

    /** Fetch all items of a playlist (max 1000). Throws ApiError on failure. */
    fun fetchItems(key: String, listId: String): List<Item> {
        val out = ArrayList<Item>()
        var page: String? = null
        do {
            val q = "playlistItems?part=snippet&maxResults=50&playlistId=$listId" +
                (page?.let { "&pageToken=$it" } ?: "")
            val o = get(q, key)
            val arr = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val sn = arr.getJSONObject(i).getJSONObject("snippet")
                val vid = sn.optJSONObject("resourceId")?.optString("videoId") ?: continue
                out.add(Item(vid, sn.optString("title"), sn.optString("videoOwnerChannelTitle")))
            }
            page = o.optString("nextPageToken").ifEmpty { null }
        } while (page != null && out.size < 1000)
        return out
    }

    /** One playlist search result. */
    data class Found(val listId: String, val title: String, val channel: String, val thumb: String?, var count: Int = -1)

    /** Search public playlists on YouTube (costs 100 of the 10 000 daily quota units). */
    fun search(key: String, query: String): List<Found> {
        val q = URLEncoder.encode(query, "UTF-8")
        val o = get("search?part=snippet&type=playlist&maxResults=25&q=$q", key)
        val arr = o.optJSONArray("items") ?: JSONArray()
        val out = ArrayList<Found>()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            val id = it.optJSONObject("id")?.optString("playlistId")?.ifEmpty { null } ?: continue
            val sn = it.getJSONObject("snippet")
            val th = sn.optJSONObject("thumbnails")
            val url = (th?.optJSONObject("medium") ?: th?.optJSONObject("default"))?.optString("url")
            out.add(Found(id, android.text.Html.fromHtml(sn.optString("title"), 0).toString(),
                sn.optString("channelTitle"), url))
        }
        // Track counts in one cheap call.
        if (out.isNotEmpty()) try {
            val ids = out.joinToString(",") { it.listId }
            val d = get("playlists?part=contentDetails&maxResults=50&id=$ids", key).optJSONArray("items") ?: JSONArray()
            val counts = HashMap<String, Int>()
            for (i in 0 until d.length()) {
                val x = d.getJSONObject(i)
                counts[x.optString("id")] = x.optJSONObject("contentDetails")?.optInt("itemCount", -1) ?: -1
            }
            out.forEach { f -> f.count = counts[f.listId] ?: -1 }
        } catch (_: Exception) {}
        return out
    }

    fun fetchTitle(key: String, listId: String): String? = try {
        get("playlists?part=snippet&id=$listId", key).optJSONArray("items")
            ?.optJSONObject(0)?.optJSONObject("snippet")?.optString("title")?.ifEmpty { null }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------ cache

    private fun prefs(c: Context) = c.getSharedPreferences("items", Context.MODE_PRIVATE)

    fun cached(c: Context, listId: String): List<Item>? {
        val raw = prefs(c).getString(listId, null) ?: return null
        val arr = JSONArray(raw)
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Item(o.getString("v"), o.optString("t"), o.optString("c"))
        }
    }

    fun saveCache(c: Context, listId: String, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("v", it.videoId).put("t", it.title).put("c", it.channel)) }
        prefs(c).edit().putString(listId, arr.toString()).apply()
    }

    /** Refresh the cache from the API. Returns null + error text on failure. */
    fun refresh(c: Context, listId: String): Pair<List<Item>?, String?> {
        val key = Store.apiKey(c) ?: return null to "API key belum diisi"
        return try {
            val items = fetchItems(key, listId)
            saveCache(c, listId, items)
            items to null
        } catch (e: Exception) {
            null to (e.message ?: e.toString())
        }
    }

    // ------------------------------------------------------------ matching

    private fun clean(s: String) = MediaWatcher.norm(
        s.replace(Regex("[\\(\\[][^\\)\\]]*(official|video|audio|lyric|mv|visualizer|hd|4k)[^\\)\\]]*[\\)\\]]",
            RegexOption.IGNORE_CASE), "")
    )

    /** Best-matching item for a YT Music title/artist, or null. */
    fun match(items: List<Item>, title: String, artist: String?): Item? {
        val t = clean(title)
        if (t.isEmpty()) return null
        val a = MediaWatcher.norm(artist).split(Regex("\\s*(,|&| x | feat\\.? | ft\\.? )\\s*")).firstOrNull()?.trim() ?: ""
        var best: Item? = null
        var bestScore = 0
        for (it in items) {
            val it_t = clean(it.title)
            val ch = MediaWatcher.norm(it.channel)
            var s = when {
                it_t == t -> 100
                it_t.endsWith(" - $t") || it_t.endsWith(" – $t") -> 90
                it_t.contains(t) && t.length >= 4 -> 60
                else -> 0
            }
            if (s == 0) continue
            if (a.isNotEmpty() && (ch.contains(a) || it_t.contains(a))) s += 20
            if (s > bestScore) { bestScore = s; best = it }
        }
        return best
    }
}
