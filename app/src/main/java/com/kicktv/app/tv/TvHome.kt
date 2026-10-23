package com.kicktv.app.tv

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.tv.TvContract
import android.os.Build
import androidx.annotation.RequiresApi
import com.kicktv.app.R
import com.kicktv.app.data.Follows
import com.kicktv.app.data.HistoryEntry
import com.kicktv.app.data.KickApi
import com.kicktv.app.data.LiveStream
import com.kicktv.app.data.Prefs
import com.kicktv.app.ui.Img
import com.kicktv.app.ui.LinkActivity
import com.kicktv.app.ui.MainActivity
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * The Android TV home screen: a "Live on Kick" row (live follows first, then top streams) and
 * past broadcasts in the system's Continue watching row. Does nothing where the launcher doesn't
 * support it (older Android, Fire TV).
 */
object TvHome {
    private const val CHANNEL_KEY = "tv_channel_id"
    private const val JOB_PERIODIC = 4101
    private const val JOB_NOW = 4102
    private const val MAX_PROGRAMS = 20

    /** One thread for every provider write, so two refreshes can't interleave their delete + insert. */
    private val io = Executors.newSingleThreadExecutor()
    private val ioDispatcher = io.asCoroutineDispatcher()

    fun supported(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            ctx.packageManager.resolveContentProvider(TvContract.AUTHORITY, 0) != null

    /** Keep the row fresh in the background (every 30 minutes, when there's a network). */
    fun schedule(ctx: Context) {
        if (!supported(ctx) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_PERIODIC) != null) return
        js.schedule(
            JobInfo.Builder(JOB_PERIODIC, ComponentName(ctx, TvHomeJob::class.java))
                .setPeriodic(30 * 60_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
        )
    }

    /** Fill the row as soon as possible (the launcher asks for this right after install). */
    fun refreshSoon(ctx: Context) {
        if (!supported(ctx)) return
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        js.schedule(
            JobInfo.Builder(JOB_NOW, ComponentName(ctx, TvHomeJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(60_000)
                .build()
        )
        schedule(ctx)
    }

    /** Looks up who's live and republishes the row. */
    suspend fun refresh(ctx: Context) {
        if (!supported(ctx)) return
        val follows = runCatching { Follows.liveNow(ctx) }.getOrDefault(emptyList())
        val top = if (follows.size >= MAX_PROGRAMS) emptyList() else runCatching {
            KickApi.livestreams(language = Prefs(ctx).language.ifEmpty { null }, limit = MAX_PROGRAMS).items
        }.getOrDefault(emptyList())
        withContext(ioDispatcher) { publishLive(ctx, follows, top) }
    }

    /** Same as [refresh] with data a screen already has. */
    fun publishAsync(ctx: Context, follows: List<LiveStream>, top: List<LiveStream>) {
        if (!supported(ctx)) return
        val app = ctx.applicationContext
        io.execute { publishLive(app, follows, top) }
    }

    private fun publishLive(ctx: Context, follows: List<LiveStream>, top: List<LiveStream>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val id = channelId(ctx) ?: return
            val cr = ctx.contentResolver
            cr.delete(TvContract.buildPreviewProgramsUriForChannel(id), null, null)
            val seen = HashSet<String>()
            val items = (follows.map { it to true } + top.map { it to false })
                .filter { seen.add(it.first.slug.lowercase()) }
                .take(MAX_PROGRAMS)
            items.forEachIndexed { i, (s, followed) ->
                cr.insert(TvContract.PreviewPrograms.CONTENT_URI, ContentValues().apply {
                    put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, id)
                    put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CHANNEL)
                    put(TvContract.PreviewPrograms.COLUMN_TITLE, s.username)
                    put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                        listOfNotNull(if (followed) "Following" else null, s.category, s.title.takeIf { it.isNotBlank() }).joinToString(" • "))
                    put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, Img.thumb(s.thumbnail, 720))
                    put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9)
                    put(TvContract.PreviewPrograms.COLUMN_LOGO_URI, s.avatar)
                    put(TvContract.PreviewPrograms.COLUMN_LIVE, 1)
                    put(TvContract.PreviewPrograms.COLUMN_INTERACTION_TYPE, TvContract.PreviewPrograms.INTERACTION_TYPE_VIEWERS)
                    put(TvContract.PreviewPrograms.COLUMN_INTERACTION_COUNT, s.viewers.toLong())
                    put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, LinkActivity.watchUri(ctx, s.slug))
                    put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, s.slug)
                    put(TvContract.PreviewPrograms.COLUMN_WEIGHT, items.size - i)
                })
            }
        }
    }

    /** Our preview channel, created (and shown on the home screen) the first time. */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun channelId(ctx: Context): Long? {
        val sp = ctx.getSharedPreferences("kicktv", Context.MODE_PRIVATE)
        val cr = ctx.contentResolver
        val saved = sp.getLong(CHANNEL_KEY, -1)
        // The provider only returns this app's channels. Keep one (preferably the one the system
        // shows; only the first channel an app makes is approved automatically) and drop extras.
        val ours = cr.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(TvContract.Channels._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, TvContract.Channels.COLUMN_BROWSABLE),
            null, null, null,
        )?.use { c ->
            buildList { while (c.moveToNext()) if (c.getString(1) == "live") add(c.getLong(0) to (c.getInt(2) == 1)) }
        }.orEmpty()
        if (ours.isNotEmpty()) {
            val keep = ours.maxBy { (id, browsable) -> (if (browsable) 2 else 0) + (if (id == saved) 1 else 0) }.first
            ours.filter { it.first != keep }.forEach { cr.delete(TvContract.buildChannelUri(it.first), null, null) }
            if (keep != saved) sp.edit().putLong(CHANNEL_KEY, keep).apply()
            return keep
        }
        val uri = cr.insert(TvContract.Channels.CONTENT_URI, ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, "Live on Kick")
            put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
                Intent(ctx, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME))
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, "live")
        }) ?: return null
        val id = ContentUris.parseId(uri)
        sp.edit().putLong(CHANNEL_KEY, id).apply()
        runCatching {
            cr.openOutputStream(TvContract.buildChannelLogoUri(id))?.use { out ->
                logo(ctx).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        runCatching { TvContract.requestChannelBrowsable(ctx, id) }
        return id
    }

    private fun logo(ctx: Context): Bitmap {
        val d = ctx.getDrawable(R.mipmap.ic_launcher)!!
        val size = 256
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(it))
        }
    }

    /**
     * Puts a partly watched past broadcast in Continue watching, or takes it out when it's
     * finished ([positionMs] 0).
     */
    fun updateWatchNext(ctx: Context, entry: HistoryEntry, positionMs: Long, durationMs: Long) {
        if (!supported(ctx) || entry.kind != HistoryEntry.VOD || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val id = entry.videoId ?: return
        val app = ctx.applicationContext
        io.execute {
            runCatching {
                val cr = app.contentResolver
                val existing = cr.query(
                    TvContract.WatchNextPrograms.CONTENT_URI,
                    arrayOf(TvContract.WatchNextPrograms._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
                    null, null, null,
                )?.use { c ->
                    var found: Long? = null
                    while (c.moveToNext()) if (c.getString(1) == id) { found = c.getLong(0); break }
                    found
                }
                if (positionMs <= 0) {
                    existing?.let { cr.delete(TvContract.buildWatchNextProgramUri(it), null, null) }
                    return@runCatching
                }
                val values = ContentValues().apply {
                    put(TvContract.WatchNextPrograms.COLUMN_TYPE, TvContract.WatchNextPrograms.TYPE_CLIP)
                    put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis())
                    put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, positionMs.toInt())
                    put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, durationMs.toInt())
                    put(TvContract.WatchNextPrograms.COLUMN_TITLE, entry.title.ifBlank { entry.username })
                    put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, entry.username)
                    put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, Img.thumb(entry.thumbnail, 720))
                    put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9)
                    put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, LinkActivity.vodUri(app, id))
                    put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, id)
                }
                if (existing != null) cr.update(TvContract.buildWatchNextProgramUri(existing), values, null, null)
                else cr.insert(TvContract.WatchNextPrograms.CONTENT_URI, values)
            }
        }
    }

    /** Removes everything we put in Continue watching (after clearing watch history). */
    fun clearWatchNext(ctx: Context) {
        if (!supported(ctx) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val app = ctx.applicationContext
        io.execute { runCatching { app.contentResolver.delete(TvContract.WatchNextPrograms.CONTENT_URI, null, null) } }
    }
}
