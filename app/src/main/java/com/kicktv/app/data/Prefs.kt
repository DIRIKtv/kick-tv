package com.kicktv.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Favorite(
    val slug: String,
    val username: String,
    val avatar: String?,
    /** Live state from the account's follow list; null when unknown (favorites saved on the TV). */
    val live: Boolean? = null,
)

/** Local settings, favorites and watch history. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("kicktv", Context.MODE_PRIVATE)

    var language: String
        get() = sp.getString("language", "en") ?: "en"
        set(v) = sp.edit().putString("language", v).apply()

    /** Preferred max video height; 0 = automatic. */
    var preferredQuality: Int
        get() = sp.getInt("quality", 0)
        set(v) = sp.edit().putInt("quality", v).apply()

    var chatEnabled: Boolean
        get() = sp.getBoolean("chat", true)
        set(v) = sp.edit().putBoolean("chat", v).apply()

    /** "side" shrinks the video next to chat, "overlay" floats chat on top. */
    var chatMode: String
        get() = sp.getString("chat_mode", "side") ?: "side"
        set(v) = sp.edit().putString("chat_mode", v).apply()

    var chatTextSize: Int
        get() = sp.getInt("chat_text", 15)
        set(v) = sp.edit().putInt("chat_text", v).apply()

    var lowLatency: Boolean
        get() = sp.getBoolean("low_latency_v2", false)
        set(v) = sp.edit().putBoolean("low_latency_v2", v).apply()

    /** kick.com session token from signing in; sent as a Bearer token. */
    var sessionToken: String?
        get() = sp.getString("session_token", null)
        set(v) = sp.edit().putString("session_token", v).apply()

    var accountName: String?
        get() = sp.getString("account_name", null)
        set(v) = sp.edit().putString("account_name", v).apply()

    var accountAvatar: String?
        get() = sp.getString("account_avatar", null)
        set(v) = sp.edit().putString("account_avatar", v).apply()

    /** Hide messages from chat bots (bot badge or a well-known bot name). */
    var chatHideBots: Boolean
        get() = sp.getBoolean("chat_hide_bots", false)
        set(v) = sp.edit().putBoolean("chat_hide_bots", v).apply()

    /** Hide "!command" messages. */
    var chatHideCommands: Boolean
        get() = sp.getBoolean("chat_hide_commands", false)
        set(v) = sp.edit().putBoolean("chat_hide_commands", v).apply()

    /** Pace busy chats so messages stay readable, skipping some when chat runs far ahead. */
    var chatCalm: Boolean
        get() = sp.getBoolean("chat_calm", false)
        set(v) = sp.edit().putBoolean("chat_calm", v).apply()

    var sevenTvEmotes: Boolean
        get() = sp.getBoolean("seventv", true)
        set(v) = sp.edit().putBoolean("seventv", v).apply()

    /** Pop up a notice when a followed channel goes live while the app is open. */
    var liveAlerts: Boolean
        get() = sp.getBoolean("live_alerts", true)
        set(v) = sp.edit().putBoolean("live_alerts", v).apply()

    /** Even out volume between streams and between quiet and loud moments. */
    var loudnessEq: Boolean
        get() = sp.getBoolean("loudness_eq", false)
        set(v) = sp.edit().putBoolean("loudness_eq", v).apply()

    /** Shrink the player into a corner window when leaving it with Home. */
    var autoPip: Boolean
        get() = sp.getBoolean("auto_pip", true)
        set(v) = sp.edit().putBoolean("auto_pip", v).apply()

    var seekStepSeconds: Int
        get() = sp.getInt("seek_step", 10)
        set(v) = sp.edit().putInt("seek_step", v).apply()

    var favorites: List<Favorite>
        get() {
            val arr = JSONArray(sp.getString("favorites", "[]"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Favorite(o.getString("slug"), o.optString("username"), o.optStringOrNull("avatar"))
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("slug", it.slug).put("username", it.username).put("avatar", it.avatar)) }
            sp.edit().putString("favorites", arr.toString()).apply()
        }

    fun isFavorite(slug: String) = favorites.any { it.slug.equals(slug, true) }

    /** Returns the new favorite state. */
    fun toggleFavorite(slug: String, username: String, avatar: String?): Boolean {
        val list = favorites.toMutableList()
        val existing = list.indexOfFirst { it.slug.equals(slug, true) }
        return if (existing >= 0) {
            list.removeAt(existing); favorites = list; false
        } else {
            list.add(0, Favorite(slug, username, avatar)); favorites = list; true
        }
    }

    fun vodPosition(uuid: String): Long = sp.getLong("vod_$uuid", 0L)

    fun saveVodPosition(uuid: String, pos: Long) {
        sp.edit().putLong("vod_$uuid", pos).apply()
    }

    /** Most recent first. */
    var history: List<HistoryEntry>
        get() = runCatching {
            val arr = JSONArray(sp.getString("history", "[]"))
            (0 until arr.length()).map { HistoryEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            sp.edit().putString("history", arr.toString()).apply()
        }

    fun recordHistory(entry: HistoryEntry) {
        history = (listOf(entry) + history.filter { it.key != entry.key }).take(60)
    }

    fun clearHistory() {
        val editor = sp.edit().remove("history")
        sp.all.keys.filter { it.startsWith("vod_") }.forEach { editor.remove(it) }
        editor.apply()
    }
}

/** Something watched: a live stream, a past broadcast or a clip. */
data class HistoryEntry(
    val kind: String,
    val slug: String,
    val username: String,
    val avatar: String?,
    val title: String,
    val thumbnail: String?,
    /** VOD uuid or clip id; null for live. */
    val videoId: String? = null,
    val source: String? = null,
    val durationMs: Long = 0,
    val createdAt: String? = null,
    val category: String? = null,
    val watchedAt: Long = System.currentTimeMillis(),
) {
    val key get() = "$kind:${videoId ?: slug.lowercase()}"

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind).put("slug", slug).put("username", username).put("avatar", avatar)
        .put("title", title).put("thumbnail", thumbnail).put("videoId", videoId).put("source", source)
        .put("durationMs", durationMs).put("createdAt", createdAt).put("category", category)
        .put("watchedAt", watchedAt)

    companion object {
        const val LIVE = "live"
        const val VOD = "vod"
        const val CLIP = "clip"

        fun fromJson(o: JSONObject) = HistoryEntry(
            kind = o.optString("kind", LIVE),
            slug = o.getString("slug"),
            username = o.optString("username"),
            avatar = o.optStringOrNull("avatar"),
            title = o.optString("title"),
            thumbnail = o.optStringOrNull("thumbnail"),
            videoId = o.optStringOrNull("videoId"),
            source = o.optStringOrNull("source"),
            durationMs = o.optLong("durationMs"),
            createdAt = o.optStringOrNull("createdAt"),
            category = o.optStringOrNull("category"),
            watchedAt = o.optLong("watchedAt"),
        )
    }
}
