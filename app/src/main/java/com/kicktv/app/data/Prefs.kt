package com.kicktv.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Favorite(val slug: String, val username: String, val avatar: String?)

/** Local settings and favorites (there's no Kick login on the TV). */
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
}
