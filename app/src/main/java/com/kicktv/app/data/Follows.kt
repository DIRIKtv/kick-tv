package com.kicktv.app.data

import android.content.Context

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
        val seen = HashSet<String>()
        return (local + cached).filter { seen.add(it.slug.lowercase()) }
    }

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
        } else if (!now) {
            accountSlugs -= slug.lowercase()
        }
        return now
    }
}
