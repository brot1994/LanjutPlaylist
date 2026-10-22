package id.bram.lanjutplaylist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Playlist(
    val id: String,
    var name: String,
    var listId: String,
    var nameAuto: Boolean = false,
    var queueTitle: String? = null,
    var lastTitle: String? = null,
    var lastArtist: String? = null,
    var lastVideoId: String? = null,
    var lastPos: Long = 0,
    var lastDuration: Long = 0,
    var updatedAt: Long = 0,
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("listId", listId); put("nameAuto", nameAuto)
        put("queueTitle", queueTitle ?: JSONObject.NULL)
        put("lastTitle", lastTitle ?: JSONObject.NULL)
        put("lastArtist", lastArtist ?: JSONObject.NULL)
        put("lastVideoId", lastVideoId ?: JSONObject.NULL)
        put("lastPos", lastPos); put("lastDuration", lastDuration); put("updatedAt", updatedAt)
    }

    companion object {
        private fun JSONObject.str(k: String): String? =
            if (isNull(k)) null else optString(k).ifEmpty { null }

        fun fromJson(o: JSONObject) = Playlist(
            id = o.getString("id"),
            name = o.optString("name"),
            listId = o.optString("listId"),
            nameAuto = o.optBoolean("nameAuto"),
            queueTitle = o.str("queueTitle"),
            lastTitle = o.str("lastTitle"),
            lastArtist = o.str("lastArtist"),
            lastVideoId = o.str("lastVideoId"),
            lastPos = o.optLong("lastPos"),
            lastDuration = o.optLong("lastDuration"),
            updatedAt = o.optLong("updatedAt"),
        )
    }
}

object Store {
    private const val PREF = "lanjut"

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun all(c: Context): MutableList<Playlist> {
        val raw = prefs(c).getString("playlists", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return MutableList(arr.length()) { Playlist.fromJson(arr.getJSONObject(it)) }
    }

    fun saveAll(c: Context, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("playlists", arr.toString()).apply()
    }

    fun get(c: Context, id: String) = all(c).firstOrNull { it.id == id }

    fun update(c: Context, id: String, block: (Playlist) -> Unit) {
        val list = all(c)
        list.firstOrNull { it.id == id }?.let { block(it); saveAll(c, list) }
    }

    fun add(c: Context, name: String, listId: String, nameAuto: Boolean): Playlist {
        val list = all(c)
        list.firstOrNull { it.listId == listId }?.let {
            if (!nameAuto) { it.name = name; it.nameAuto = false }
            saveAll(c, list); return it
        }
        val p = Playlist(UUID.randomUUID().toString(), name, listId, nameAuto)
        list.add(p); saveAll(c, list)
        return p
    }

    fun remove(c: Context, id: String) {
        saveAll(c, all(c).filterNot { it.id == id })
        if (activeId(c) == id) setActive(c, null)
    }

    fun move(c: Context, id: String, delta: Int) {
        val list = all(c)
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        val p = list.removeAt(i); list.add(j, p); saveAll(c, list)
    }

    fun activeId(c: Context): String? = prefs(c).getString("active", null)
    fun setActive(c: Context, id: String?) = prefs(c).edit().putString("active", id).apply()

    var Context.seekToPos: Boolean
        get() = prefs(this).getBoolean("seek", true)
        set(v) = prefs(this).edit().putBoolean("seek", v).apply()

    /** Pull a YouTube playlist id (list=...) out of any shared text / link. */
    fun parseListId(text: String): String? {
        Regex("[?&]list=([A-Za-z0-9_-]+)").find(text)?.let { return it.groupValues[1] }
        val t = text.trim()
        if (Regex("^(PL|OL|RD|LM|VL|UU|FL)[A-Za-z0-9_-]*$").matches(t)) return t.removePrefix("VL")
        return null
    }
}
