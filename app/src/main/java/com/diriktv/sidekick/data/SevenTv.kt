package com.diriktv.sidekick.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** 7TV emotes (global plus the channel's set), as emote name -> image URL. */
object SevenTv {
    private var global: Map<String, String>? = null
    private val channels = HashMap<Int, Map<String, String>>()

    /** Emotes for the Kick user [kickUserId]; the channel's own set wins over globals with the same name. */
    suspend fun emotes(kickUserId: Int): Map<String, String> {
        val g = global ?: runCatching { parseSet(fetch("https://7tv.io/v3/emote-sets/global")) }.getOrNull()
            ?.also { global = it }.orEmpty()
        if (kickUserId <= 0) return g
        val own = channels[kickUserId] ?: runCatching {
            JSONObject(fetch("https://7tv.io/v3/users/kick/$kickUserId")).optJSONObject("emote_set")?.let { parseSet(it.toString()) }
        }.getOrNull().orEmpty().also { channels[kickUserId] = it }
        return g + own
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        KickApi.http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun parseSet(json: String): Map<String, String> {
        val arr = JSONObject(json).optJSONArray("emotes") ?: JSONArray()
        val out = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val name = e.optStringOrNull("name") ?: continue
            val host = e.optJSONObject("data")?.optJSONObject("host") ?: continue
            val base = host.optStringOrNull("url") ?: continue
            val files = host.optJSONArray("files") ?: JSONArray()
            val names = (0 until files.length()).mapNotNull { files.optJSONObject(it)?.optStringOrNull("name") }
            val file = listOf("2x.webp", "1x.webp", "2x.png").firstOrNull { it in names } ?: names.firstOrNull() ?: continue
            out[name] = "https:$base/$file"
        }
        return out
    }
}
