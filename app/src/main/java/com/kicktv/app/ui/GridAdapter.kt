package com.kicktv.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kicktv.app.R
import com.kicktv.app.data.Category
import com.kicktv.app.data.LiveStream
import com.kicktv.app.data.Video

sealed class GridItem {
    data class Header(val text: String) : GridItem()
    data class Stream(val stream: LiveStream) : GridItem()
    data class Channel(
        val slug: String,
        val username: String,
        val avatar: String?,
        val isLive: Boolean,
        val subtitle: String,
    ) : GridItem()
    data class Cat(val category: Category) : GridItem()
    data class Vod(val video: Video, val channelSlug: String, val progress: Float) : GridItem()
}

/** One adapter for every card type, so rows and grids look and behave the same. */
class GridAdapter(
    private val cardWidthPx: Int? = null,
    private val onClick: (GridItem) -> Unit,
    private val onLongClick: ((GridItem) -> Unit)? = null,
    private val onNearEnd: (() -> Unit)? = null,
) : RecyclerView.Adapter<GridAdapter.VH>() {

    val items = mutableListOf<GridItem>()
    private var recycler: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (recycler === recyclerView) recycler = null
    }

    fun submit(list: List<GridItem>) {
        val wasEmpty = items.isEmpty()
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
        if (wasEmpty && list.isNotEmpty()) revealList()
    }

    fun append(list: List<GridItem>) {
        val wasEmpty = items.isEmpty()
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
        if (wasEmpty && list.isNotEmpty()) revealList()
    }

    /** First content for a list fades up into place instead of popping in. */
    private fun revealList() {
        val rv = recycler ?: return
        rv.alpha = 0f
        rv.translationY = rv.resources.displayMetrics.density * 12
        rv.animate().alpha(1f).translationY(0f).setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    fun spanLookup(spanCount: Int) = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int) =
            if (items.getOrNull(position) is GridItem.Header) spanCount else 1
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (items[position]) {
        is GridItem.Header -> 0
        is GridItem.Stream, is GridItem.Vod -> 1
        is GridItem.Channel -> 2
        is GridItem.Cat -> 3
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            0 -> R.layout.item_header
            1 -> R.layout.item_stream_card
            2 -> R.layout.item_channel_card
            else -> R.layout.item_category_card
        }
        val v = parent.inflate(layout)
        if (viewType == 1) {
            v.layoutParams = v.layoutParams.apply {
                width = cardWidthPx ?: ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        if (viewType == 3) v.findViewById<AspectImageView>(R.id.banner).ratio = 4f / 3f
        // Clip images to the rounded card so corners don't poke out under the focus outline.
        v.findViewById<View?>(R.id.thumbFrame)?.clipToOutline = true
        (v.findViewById<View?>(R.id.banner)?.parent as? View)?.clipToOutline = true
        v.findViewById<AspectImageView?>(R.id.avatar)?.ratio = 1f
        if (viewType != 0) v.focusZoom(if (viewType == 2) 1.08f else 1.05f)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val v = holder.itemView
        if (onNearEnd != null && position >= items.size - 6) v.post { onNearEnd.invoke() }
        if (item !is GridItem.Header) {
            v.setOnClickListener { onClick(item) }
            v.setOnLongClickListener { onLongClick?.invoke(item); onLongClick != null }
            // The remote's ☰ button does the same as a long press.
            v.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_MENU && onLongClick != null) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) onLongClick.invoke(item)
                    true
                } else false
            }
        }
        when (item) {
            is GridItem.Header -> (v as TextView).text = item.text
            is GridItem.Stream -> {
                val s = item.stream
                v.findViewById<ImageView>(R.id.thumb).loadThumb(Img.thumb(s.thumbnail))
                v.findViewById<ImageView>(R.id.avatar).loadAvatar(s.avatar)
                v.findViewById<TextView>(R.id.title).text = s.title
                v.findViewById<TextView>(R.id.channel).text = s.username
                v.findViewById<TextView>(R.id.category).apply { text = s.category.orEmpty(); setVisible(s.category != null) }
                v.findViewById<TextView>(R.id.viewers).apply { text = Fmt.viewers(s.viewers); setVisible(true) }
                v.findViewById<View>(R.id.liveBadge).setVisible(true)
                v.findViewById<View>(R.id.duration).setVisible(false)
                v.findViewById<View>(R.id.resumeBar).setVisible(false)
            }
            is GridItem.Vod -> {
                val video = item.video
                v.findViewById<ImageView>(R.id.thumb).loadThumb(Img.thumb(video.thumbnail))
                v.findViewById<View>(R.id.avatar).setVisible(false)
                v.findViewById<TextView>(R.id.title).text = video.title
                v.findViewById<TextView>(R.id.channel).text =
                    listOf(Fmt.ago(video.createdAt), "${Fmt.count(video.views)} views").filter { it.isNotEmpty() }.joinToString(" • ")
                v.findViewById<TextView>(R.id.category).apply { text = video.category.orEmpty(); setVisible(video.category != null) }
                v.findViewById<View>(R.id.viewers).setVisible(false)
                v.findViewById<View>(R.id.liveBadge).setVisible(false)
                v.findViewById<TextView>(R.id.duration).apply { text = if (video.isLive) "● Recording" else Fmt.time(video.durationMs); setVisible(true) }
                v.findViewById<ProgressBar>(R.id.resumeBar).apply {
                    max = 1000
                    progress = (item.progress * 1000).toInt()
                    setVisible(item.progress > 0.01f)
                }
            }
            is GridItem.Channel -> {
                v.findViewById<ImageView>(R.id.avatar).loadAvatar(item.avatar)
                v.findViewById<TextView>(R.id.name).text = item.username
                v.findViewById<TextView>(R.id.followers).text = item.subtitle
                v.findViewById<View>(R.id.liveBadge).setVisible(item.isLive)
            }
            is GridItem.Cat -> {
                v.findViewById<ImageView>(R.id.banner).loadThumb(item.category.banner)
                v.findViewById<TextView>(R.id.name).text = item.category.name
                v.findViewById<TextView>(R.id.viewers).text = Fmt.viewers(item.category.viewers)
            }
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v)
}
