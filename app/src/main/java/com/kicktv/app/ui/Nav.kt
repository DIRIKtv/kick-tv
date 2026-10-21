package com.kicktv.app.ui

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kicktv.app.data.Video
import com.kicktv.app.player.PlayerActivity

object Nav {
    fun watchLive(ctx: Context, slug: String) {
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_SLUG, slug))
    }

    fun watchVod(ctx: Context, slug: String, video: Video) {
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_SLUG, slug)
                .putExtra(PlayerActivity.EXTRA_VOD_URL, video.source)
                .putExtra(PlayerActivity.EXTRA_VOD_UUID, video.uuid)
                .putExtra(PlayerActivity.EXTRA_VOD_TITLE, video.title)
        )
    }

    fun channel(ctx: Context, slug: String) {
        ctx.startActivity(Intent(ctx, ChannelActivity::class.java).putExtra(ChannelActivity.EXTRA_SLUG, slug))
    }

    fun category(ctx: Context, id: Int, name: String) {
        ctx.startActivity(
            Intent(ctx, CategoryActivity::class.java)
                .putExtra(CategoryActivity.EXTRA_ID, id)
                .putExtra(CategoryActivity.EXTRA_NAME, name)
        )
    }

    /** Default card actions: OK plays, long-press OK opens the channel. */
    fun onCardClick(ctx: Context, item: GridItem) {
        when (item) {
            is GridItem.Stream -> watchLive(ctx, item.stream.slug)
            is GridItem.Channel -> if (item.isLive) watchLive(ctx, item.slug) else channel(ctx, item.slug)
            is GridItem.Cat -> category(ctx, item.category.id, item.category.name)
            is GridItem.Vod -> watchVod(ctx, item.channelSlug, item.video)
            is GridItem.Header -> Unit
        }
    }

    fun onCardLongClick(ctx: Context, item: GridItem) {
        when (item) {
            is GridItem.Stream -> channel(ctx, item.stream.slug)
            is GridItem.Channel -> channel(ctx, item.slug)
            else -> onCardClick(ctx, item)
        }
    }
}

/** Keeps the focused row pinned near the top as you move down the home screen. */
class TopAlignLayoutManager(ctx: Context, private val offsetPx: Int) : LinearLayoutManager(ctx) {
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean {
        val dy = child.top - parent.paddingTop - offsetPx
        if (dy == 0) return false
        if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
        return true
    }
}

/** Keeps the focused grid row vertically centered. */
class CenterGridLayoutManager(ctx: Context, span: Int) : GridLayoutManager(ctx, span) {
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean {
        val dy = (child.top + child.height / 2) - parent.height / 2
        val canScroll = if (dy > 0) parent.canScrollVertically(1) else parent.canScrollVertically(-1)
        if (dy == 0 || !canScroll) return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
        if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy)
        return true
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
