package com.kicktv.app.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Following" = channels the signed-in Kick account follows, plus favorites saved on this TV.
 * Account follows are cached briefly so screens don't refetch them on every visit.
 */
object Follows {
    private var cached: List<Favorite> = emptyList()
    private var cachedAt = 0L
    private val accountSlugs = HashSet<String>()

    val signedIn get() = KickApi.authToken != null

    /** Bumped whenever the follow list may have changed, so screens know to reload. */
    var version = 0
        private set

    fun init(context: Context) {
        KickApi.authToken = Prefs(context).sessionToken
    }

    fun signIn(context: Context, token: String, name: String, avatar: String?) {
        Prefs(context).apply { sessionToken = token; accountName = name; accountAvatar = avatar }
        KickApi.authToken = token
        invalidate()
    }

    fun signOut(context: Context) {
        Prefs(context).apply { sessionToken = null; accountName = null; accountAvatar = null }
        KickApi.authToken = null
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        invalidate()
    }

    fun invalidate() {
        cachedAt = 0
        version++
    }

    suspend fun all(context: Context): List<Favorite> {
        val local = Prefs(context).favorites
        if (!signedIn) return local
        if (System.currentTimeMillis() - cachedAt > 120_000) {
            runCatching { KickApi.followed() }.onSuccess { list ->
                cached = list
                cachedAt = System.currentTimeMillis()
                accountSlugs.clear()
                accountSlugs += list.map { it.slug.lowercase() }
            }
        }
        // Favorites saved on the TV come first, but keep the account's live hint for them.
        val hints = cached.associateBy({ it.slug.lowercase() }, { it.live })
        val seen = HashSet<String>()
        return (local + cached)
            .filter { seen.add(it.slug.lowercase()) }
            .map { f -> if (f.live == null) f.copy(live = hints[f.slug.lowercase()]) else f }
    }

    private val liveLock = Mutex()
    private var live: Map<String, LiveStream> = emptyMap()
    private var liveAt = 0L
    private var liveVersion = -1

    /**
     * Followed channels that are live now, most watched first. Screens share one result for up
     * to [maxAgeMs]; channels the account's follow list reports offline aren't looked up at all.
     */
    suspend fun liveNow(context: Context, maxAgeMs: Long = 60_000): List<LiveStream> = liveLock.withLock {
        if (liveVersion == version && System.currentTimeMillis() - liveAt < maxAgeMs) return@withLock sorted()
        val favs = all(context).filter { it.live != false }.take(100)
        val previous = live
        val results = coroutineScope {
            favs.map { f ->
                async { f.slug.lowercase() to runCatching { KickApi.liveStream(f.slug, f.username, f.avatar) } }
            }.awaitAll()
        }
        live = results.mapNotNull { (key, result) ->
            // A failed lookup keeps the last known state rather than flipping the channel offline.
            val stream = if (result.isSuccess) result.getOrNull() else previous[key]
            stream?.let { key to it }
        }.toMap()
        liveAt = System.currentTimeMillis()
        liveVersion = version
        sorted()
    }

    private fun sorted() = live.values.sortedByDescending { it.viewers }

    fun isFollowing(context: Context, slug: String) =
        Prefs(context).isFavorite(slug) || slug.lowercase() in accountSlugs

    /** Toggles follow state locally and, when signed in, on the Kick account. Returns the new state. */
    suspend fun toggle(context: Context, slug: String, username: String, avatar: String?): Boolean {
        val prefs = Prefs(context)
        val now = !isFollowing(context, slug)
        if (now != prefs.isFavorite(slug)) prefs.toggleFavorite(slug, username, avatar)
        if (signedIn && KickApi.setFollow(slug, now)) {
            if (now) accountSlugs += slug.lowercase() else accountSlugs -= slug.lowercase()
            invalidate()
        } else {
            if (!now) accountSlugs -= slug.lowercase()
            version++ // the TV's own favorites changed
        }
        return now
    }
}
