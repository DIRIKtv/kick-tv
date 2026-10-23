package com.diriktv.sidekick.ui

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Category
import com.diriktv.sidekick.data.LiveStream
import com.diriktv.sidekick.data.Video

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
    /** [channelName] set shows whose broadcast it is (rows mixing channels, like Continue watching). */
    data class Vod(val video: Video, val channelSlug: String, val progress: Float, val channelName: String? = null) : GridItem()
    data class Clip(val clip: com.diriktv.sidekick.data.Clip) : GridItem()

    /** Identity for de-duplicating pages as they load. */
    val key: String
        get() = when (this) {
            is Header -> "h:$text"
            is Stream -> "s:${stream.slug.lowercase()}"
            is Channel -> "c:${slug.lowercase()}"
            is Cat -> "k:${category.id}"
            is Vod -> "v:${video.uuid}"
            is Clip -> "p:${clip.id}"
        }
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

    init {
        // A refresh keeps each card's view (and its focus) instead of rebuilding the list; otherwise
        // the focused card vanishes and Android hands focus to the nav rail, switching pages.
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = stableId(items[position].key)

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

    /** Swap in new contents of the same shape without the reveal animation (keeps focus in place). */
    fun update(list: List<GridItem>) {
        if (list.size != items.size) return submit(list)
        items.clear()
        items.addAll(list)
        notifyItemRangeChanged(0, list.size)
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
        is GridItem.Stream, is GridItem.Vod, is GridItem.Clip -> 1
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
        // In a horizontal row (fixed card width), channel cards get a fixed width too instead of filling it.
        if (viewType == 2 && cardWidthPx != null) {
            v.layoutParams = v.layoutParams.apply { width = cardWidthPx * 3 / 4 }
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
                v.findViewById<ImageView>(R.id.avatar).apply { setVisible(true); loadAvatar(s.avatar) }
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
                    listOfNotNull(item.channelName, Fmt.ago(video.createdAt), video.views.takeIf { it > 0 }?.let { "${Fmt.count(it)} views" })
                        .filter { it.isNotEmpty() }.joinToString(" • ")
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
            is GridItem.Clip -> {
                val c = item.clip
                v.findViewById<ImageView>(R.id.thumb).loadThumb(c.thumbnail)
                v.findViewById<ImageView>(R.id.avatar).apply { setVisible(true); loadAvatar(c.channelAvatar) }
                v.findViewById<TextView>(R.id.title).text = c.title.ifBlank { "Clip" }
                v.findViewById<TextView>(R.id.channel).text =
                    listOf(c.channelName, "${Fmt.count(c.views)} views", Fmt.ago(c.createdAt)).filter { it.isNotEmpty() }.joinToString(" • ")
                v.findViewById<TextView>(R.id.category).apply { text = c.category.orEmpty(); setVisible(c.category != null) }
                v.findViewById<View>(R.id.viewers).setVisible(false)
                v.findViewById<View>(R.id.liveBadge).setVisible(false)
                v.findViewById<TextView>(R.id.duration).apply { text = Fmt.time(c.durationMs); setVisible(true) }
                v.findViewById<View>(R.id.resumeBar).setVisible(false)
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
