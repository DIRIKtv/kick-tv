package com.kicktv.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Thin client over Kick's public web endpoints. */
object KickApi {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    // Fallback if the realtime connection endpoint stops answering.
    const val DEFAULT_PUSHER_KEY = "34bf7a0ff419a2a775b9"
    const val DEFAULT_PUSHER_CLUSTER = "us2"

    /** Set when signed in; added to kick.com API calls. */
    @Volatile var authToken: String? = null

    val http: OkHttpClient = OkHttpClient.Builder()
        // Images and playlists come from a few hosts; the default of 5 per host queues thumbnails.
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder().header("User-Agent", USER_AGENT)
            if (req.url.host.endsWith("kick.com") && req.url.encodedPath.startsWith("/api/")) {
                if (req.header("Accept") == null) b.header("Accept", "application/json")
                authToken?.let { if (req.header("Authorization") == null) b.header("Authorization", "Bearer $it") }
            }
            chain.proceed(b.build())
        }
        .build()

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${resp.request.url.encodedPath}")
            body
        }
    }

    private suspend fun post(url: String, json: JSONObject, method: String = "POST"): String = withContext(Dispatchers.IO) {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(url).method(method, body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            text
        }
    }

    /** Live streams sorted by viewers. [language] null means all languages. */
    suspend fun livestreams(
        cursor: String? = null,
        categoryId: Int? = null,
        language: String? = null,
        limit: Int = 24,
    ): Page<LiveStream> {
        val url = "https://web.kick.com/api/v1/livestreams".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", "viewer_count_desc")
            .apply {
                cursor?.let { addQueryParameter("after", it) }
                categoryId?.let { addQueryParameter("category_id", it.toString()) }
                language?.let { addQueryParameter("language", it) }
            }.build().toString()
        val data = JSONObject(get(url)).getJSONObject("data")
        val arr = data.optJSONArray("livestreams") ?: JSONArray()
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ch = o.optJSONObject("channel") ?: JSONObject()
            LiveStream(
                slug = ch.optString("slug"),
                username = ch.optString("username", ch.optString("slug")),
                title = o.optString("title"),
                viewers = o.optInt("viewer_count"),
                thumbnail = o.optJSONObject("thumbnail")?.optStringOrNull("src"),
                avatar = ch.optStringOrNull("profile_pic"),
                category = o.optJSONObject("category")?.optStringOrNull("name"),
                language = o.optStringOrNull("language"),
                startTime = o.optStringOrNull("start_time"),
            )
        }.filter { it.slug.isNotEmpty() }
        val next = data.optJSONObject("pagination")?.optStringOrNull("next_cursor")
        return Page(items, if (items.isEmpty()) null else next)
    }

    suspend fun categories(page: Int = 1, limit: Int = 32): Page<Category> {
        val json = JSONObject(get("https://kick.com/api/v1/subcategories?page=$page&limit=$limit"))
        val arr = json.optJSONArray("data") ?: JSONArray()
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Category(
                id = o.optInt("id"),
                name = o.optString("name"),
                slug = o.optString("slug"),
                viewers = o.optInt("viewers"),
                banner = o.optJSONObject("banner")?.let { b ->
                    com.kicktv.app.ui.Img.fromSrcset(b.optStringOrNull("responsive"), 340) ?: b.optStringOrNull("url")
                },
            )
        }
        val next = if (json.optStringOrNull("next_page_url") != null) (page + 1).toString() else null
        return Page(items, next)
    }

    suspend fun channel(slug: String): ChannelInfo {
        val o = JSONObject(get("https://kick.com/api/v2/channels/${slug.lowercase()}"))
        val user = o.optJSONObject("user") ?: JSONObject()
        val live = o.optJSONObject("livestream")
        val liveCats = live?.optJSONArray("categories")
        return ChannelInfo(
            id = o.optInt("id"),
            slug = o.optString("slug"),
            username = user.optString("username", o.optString("slug")),
            bio = user.optStringOrNull("bio"),
            avatar = user.optStringOrNull("profile_pic"),
            banner = o.optJSONObject("banner_image")?.optStringOrNull("url")
                ?: o.optJSONObject("offline_banner_image")?.optStringOrNull("src"),
            followers = o.optInt("followers_count"),
            verified = o.opt("verified").let { it != null && it != JSONObject.NULL && it != false },
            chatroomId = o.optJSONObject("chatroom")?.optInt("id") ?: 0,
            playbackUrl = o.optStringOrNull("playback_url"),
            isLive = live != null && live.optBoolean("is_live", true),
            liveTitle = live?.optStringOrNull("session_title"),
            liveViewers = live?.optInt("viewer_count") ?: 0,
            liveCategory = if (liveCats != null && liveCats.length() > 0) liveCats.getJSONObject(0).optStringOrNull("name") else null,
            liveStartTime = live?.optStringOrNull("start_time"),
            liveThumbnail = live?.optJSONObject("thumbnail")?.optStringOrNull("url"),
            subscriberBadges = o.optJSONArray("subscriber_badges")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val b = arr.getJSONObject(i)
                    val url = b.optJSONObject("badge_image")?.optStringOrNull("src") ?: return@mapNotNull null
                    b.optInt("months") to url
                }.sortedBy { it.first }
            }.orEmpty(),
        )
    }

    /** Channel info plus a usable live thumbnail (the one in [channel] is often forbidden). */
    suspend fun channelForCard(slug: String): ChannelInfo {
        val c = channel(slug)
        if (!c.isLive) return c
        val thumb = runCatching {
            JSONObject(get("https://kick.com/api/v2/channels/${slug.lowercase()}/livestream"))
                .optJSONObject("data")?.optJSONObject("thumbnail")?.optStringOrNull("src")
        }.getOrNull()
        return if (thumb != null) c.copy(liveThumbnail = thumb) else c
    }

    suspend fun videos(slug: String): List<Video> {
        val arr = JSONArray(get("https://kick.com/api/v2/channels/${slug.lowercase()}/videos"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val source = o.optStringOrNull("source") ?: return@mapNotNull null
            val video = o.optJSONObject("video")
            val cats = o.optJSONArray("categories")
            Video(
                uuid = video?.optStringOrNull("uuid") ?: o.optString("slug"),
                title = o.optString("session_title"),
                source = source,
                thumbnail = o.optJSONObject("thumbnail")?.optStringOrNull("src"),
                durationMs = o.optLong("duration"),
                views = video?.optInt("views") ?: o.optInt("views"),
                createdAt = o.optStringOrNull("start_time") ?: o.optStringOrNull("created_at"),
                category = if (cats != null && cats.length() > 0) cats.getJSONObject(0).optStringOrNull("name") else null,
                isLive = o.optBoolean("is_live"),
            )
        }
    }

    suspend fun search(query: String): List<SearchChannel> {
        val url = "https://kick.com/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("searched_word", query).build().toString()
        val arr = JSONObject(get(url)).optJSONArray("channels") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val user = o.optJSONObject("user") ?: JSONObject()
            SearchChannel(
                slug = o.optString("slug"),
                username = user.optString("username", o.optString("slug")),
                avatar = user.optStringOrNull("profilePic") ?: user.optStringOrNull("profile_pic"),
                followers = o.optInt("followers_count", o.optInt("followersCount")),
                isLive = o.optBoolean("isLive"),
                verified = o.opt("verified").let { it != null && it != JSONObject.NULL && it != false },
            )
        }.filter { it.slug.isNotEmpty() }
    }

    /** The signed-in user for [token], or null if the token isn't valid. */
    suspend fun me(token: String): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("https://kick.com/api/v1/user").header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val o = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull() ?: return@withContext null
            val name = o.optStringOrNull("username") ?: return@withContext null
            name to (o.optStringOrNull("profilepic") ?: o.optStringOrNull("profile_pic"))
        }
    }

    /** Channels the signed-in account follows. */
    suspend fun followed(): List<Favorite> {
        val out = LinkedHashMap<String, Favorite>()
        var cursor: String? = "0"
        var pages = 0
        while (cursor != null && pages < 15) {
            val text = get("https://kick.com/api/v2/channels/followed-page?cursor=$cursor")
            val root: Any = runCatching { JSONObject(text) }.getOrElse { JSONArray(text) }
            val arr = when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("channels") ?: root.optJSONArray("data") ?: JSONArray()
                else -> JSONArray()
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ch = o.optJSONObject("channel")
                val user = o.optJSONObject("user") ?: ch?.optJSONObject("user")
                val slug = o.optStringOrNull("channel_slug") ?: o.optStringOrNull("slug") ?: ch?.optStringOrNull("slug")
                    ?: continue
                val name = o.optStringOrNull("user_username") ?: o.optStringOrNull("username")
                    ?: user?.optStringOrNull("username") ?: slug
                val avatar = o.optStringOrNull("profile_picture") ?: o.optStringOrNull("profile_pic")
                    ?: user?.optStringOrNull("profile_pic")
                out[slug.lowercase()] = Favorite(slug, name, avatar)
            }
            val next = (root as? JSONObject)?.let { it.optStringOrNull("nextCursor") ?: it.optStringOrNull("next_cursor") }
            cursor = if (arr.length() == 0 || next == cursor) null else next
            pages++
        }
        return out.values.toList()
    }

    suspend fun setFollow(slug: String, follow: Boolean): Boolean = runCatching {
        post("https://kick.com/api/v2/channels/${slug.lowercase()}/follow", JSONObject(), if (follow) "POST" else "DELETE")
        true
    }.getOrDefault(false)

    /** Asks Kick which Pusher app key/cluster serves this channel's chat. */
    suspend fun pusherConfig(channelId: Int): Pair<String, String> = try {
        val body = JSONObject()
            .put("client", JSONObject().put("id", UUID.randomUUID().toString()).put("type", "web"))
            .put("capabilities", JSONObject().put("accepted_providers", JSONArray().put(JSONObject().put("provider", "pusher"))))
        val conns = JSONObject(post("https://web.kick.com/api/v1/realtime/channels/$channelId/chat/connection", body))
            .getJSONObject("data").getJSONArray("connections")
        var result = DEFAULT_PUSHER_KEY to DEFAULT_PUSHER_CLUSTER
        for (i in 0 until conns.length()) {
            val c = conns.getJSONObject(i)
            if (c.optString("provider") == "pusher") {
                val cred = c.getJSONObject("credentials")
                result = cred.getString("app_key") to cred.optString("cluster", DEFAULT_PUSHER_CLUSTER)
                break
            }
        }
        result
    } catch (e: Exception) {
        DEFAULT_PUSHER_KEY to DEFAULT_PUSHER_CLUSTER
    }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
