package com.kicktv.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
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

    /** At most this many kick.com API calls in flight; bursts (one lookup per follow) got rate limited. */
    private val apiSlots = Semaphore(6)

    /** After a 429, every API call waits until this time. */
    @Volatile private var coolDownUntil = 0L

    private fun isApi(url: HttpUrl) =
        (url.host == "kick.com" || url.host.endsWith(".kick.com")) && url.encodedPath.startsWith("/api/")

    /**
     * Calls that act for the signed-in account. Everything else goes out without the token, so
     * Cloudflare can answer from its cache and the calls don't count against the account's limit.
     */
    private fun needsAuth(url: HttpUrl): Boolean {
        val path = url.encodedPath
        return path.startsWith("/api/v2/channels/followed") || path.endsWith("/follow") || path == "/api/v1/user"
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        // Images and playlists come from a few hosts; the default of 5 per host queues thumbnails.
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder().header("User-Agent", USER_AGENT)
            if (!isApi(req.url)) return@addInterceptor chain.proceed(b.build())
            if (req.header("Accept") == null) b.header("Accept", "application/json")
            if (needsAuth(req.url)) {
                authToken?.let { if (req.header("Authorization") == null) b.header("Authorization", "Bearer $it") }
            }
            throttled(chain, b.build())
        }
        .build()

    /** Runs an API call inside the concurrency limit, waiting out and retrying 429s a couple of times. */
    private fun throttled(chain: Interceptor.Chain, req: Request): Response {
        apiSlots.acquire()
        try {
            var attempt = 0
            while (true) {
                val wait = coolDownUntil - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                val resp = chain.proceed(req)
                if (resp.code != 429 || attempt >= 2) return resp
                val retryAfter = resp.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: (2000L shl attempt)
                resp.close()
                coolDownUntil = maxOf(coolDownUntil, System.currentTimeMillis() + retryAfter.coerceIn(1000, 15_000))
                attempt++
            }
        } finally {
            apiSlots.release()
        }
    }

    private fun httpError(resp: Response) = IOException(
        if (resp.code == 429) "Kick is limiting requests right now. Try again in a minute."
        else "HTTP ${resp.code} for ${resp.request.url.encodedPath}"
    )

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw httpError(resp)
            body
        }
    }

    private suspend fun post(url: String, json: JSONObject, method: String = "POST"): String = withContext(Dispatchers.IO) {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(url).method(method, body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw httpError(resp)
            text
        }
    }

    private class Cached<T>(val at: Long, val value: T)
    private val channelCache = ConcurrentHashMap<String, Cached<ChannelInfo>>()
    private val liveCache = ConcurrentHashMap<String, Cached<LiveStream?>>()

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

    /** Channel details, reusing a lookup from the last [maxAgeMs] (several screens ask for the same ones). */
    suspend fun channel(slug: String, maxAgeMs: Long = 20_000): ChannelInfo {
        val key = slug.lowercase()
        channelCache[key]?.let { if (System.currentTimeMillis() - it.at < maxAgeMs) return it.value }
        return fetchChannel(key).also { channelCache[key] = Cached(System.currentTimeMillis(), it) }
    }

    private suspend fun fetchChannel(slug: String): ChannelInfo {
        val o = JSONObject(get("https://kick.com/api/v2/channels/$slug"))
        val user = o.optJSONObject("user") ?: JSONObject()
        val live = o.optJSONObject("livestream")
        val liveCats = live?.optJSONArray("categories")
        return ChannelInfo(
            id = o.optInt("id"),
            userId = o.optInt("user_id", user.optInt("id")),
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

    /**
     * The channel's current broadcast as a card, or null when it's offline. One small call per
     * channel (vs. [channel]), with the name and avatar the caller already has.
     */
    suspend fun liveStream(slug: String, username: String, avatar: String?, maxAgeMs: Long = 45_000): LiveStream? {
        val key = slug.lowercase()
        liveCache[key]?.let { if (System.currentTimeMillis() - it.at < maxAgeMs) return it.value }
        val o = JSONObject(get("https://kick.com/api/v2/channels/$key/livestream")).optJSONObject("data")
        val live = o?.let {
            LiveStream(
                slug = slug,
                username = username,
                title = it.optString("session_title"),
                viewers = it.optInt("viewers"),
                thumbnail = it.optJSONObject("thumbnail")?.optStringOrNull("src"),
                avatar = avatar,
                category = it.optJSONObject("category")?.optStringOrNull("name"),
                language = it.optStringOrNull("language"),
                startTime = it.optStringOrNull("created_at"),
            )
        }
        liveCache[key] = Cached(System.currentTimeMillis(), live)
        return live
    }

    /** Most-viewed clips, site-wide or for one channel. [time] is day, week, month or all. */
    suspend fun clips(slug: String? = null, cursor: String? = null, time: String = "day"): Page<Clip> {
        val base = if (slug == null) "https://kick.com/api/v2/clips" else "https://kick.com/api/v2/channels/${slug.lowercase()}/clips"
        val url = base.toHttpUrl().newBuilder()
            .addQueryParameter("sort", "view")
            .addQueryParameter("time", time)
            .apply { cursor?.let { addQueryParameter("cursor", it) } }
            .build().toString()
        val json = JSONObject(get(url))
        val arr = json.optJSONArray("clips") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val src = o.optStringOrNull("video_url") ?: o.optStringOrNull("clip_url") ?: return@mapNotNull null
            val ch = o.optJSONObject("channel") ?: JSONObject()
            Clip(
                id = o.optString("id"),
                title = o.optString("title"),
                source = src,
                thumbnail = o.optStringOrNull("thumbnail_url"),
                durationMs = o.optLong("duration") * 1000,
                views = o.optInt("view_count", o.optInt("views")),
                createdAt = o.optStringOrNull("created_at"),
                category = o.optJSONObject("category")?.optStringOrNull("name"),
                channelSlug = ch.optString("slug"),
                channelName = ch.optString("username", ch.optString("slug")),
                channelAvatar = ch.optStringOrNull("profile_picture"),
            )
        }.filter { it.channelSlug.isNotEmpty() }
        val next = json.optStringOrNull("nextCursor")
        return Page(items, if (items.isEmpty() || next == cursor) null else next)
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
                val live = if (o.has("is_live")) o.optBoolean("is_live") else null
                out[slug.lowercase()] = Favorite(slug, name, avatar, live)
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
