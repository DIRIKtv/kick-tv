package com.diriktv.sidekick.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.diriktv.sidekick.data.HistoryEntry
import com.diriktv.sidekick.data.Prefs
import com.diriktv.sidekick.data.Video

/**
 * Opens sidekick:// links from the TV home screen: sidekick://watch/<channel> for a live stream,
 * sidekick://vod/<uuid> for a past broadcast in this TV's history. The player itself isn't
 * exported, so other apps can't point it at arbitrary URLs.
 */
class LinkActivity : Activity() {
    companion object {
        fun watchUri(ctx: Context, slug: String): String = link(ctx, "sidekick://watch/${Uri.encode(slug)}")
        fun vodUri(ctx: Context, uuid: String): String = link(ctx, "sidekick://vod/${Uri.encode(uuid)}")

        private fun link(ctx: Context, uri: String) =
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setClass(ctx, LinkActivity::class.java).toUri(Intent.URI_INTENT_SCHEME)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent.data
        val target = data?.pathSegments?.firstOrNull()
        val vod = if (data?.host == "vod") {
            Prefs(this).history.firstOrNull { it.kind == HistoryEntry.VOD && it.videoId == target && it.source != null }
        } else null
        when {
            data?.scheme != "sidekick" || target == null -> startActivity(Intent(this, MainActivity::class.java))
            data.host == "watch" -> Nav.watchLive(this, target)
            vod != null -> Nav.watchVod(this, vod.slug, Video(target, vod.title, vod.source!!, vod.thumbnail, vod.durationMs, 0, vod.createdAt, vod.category))
            else -> startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
