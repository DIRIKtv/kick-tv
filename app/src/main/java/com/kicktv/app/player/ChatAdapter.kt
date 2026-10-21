package com.kicktv.app.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.LruCache
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import com.kicktv.app.R
import com.kicktv.app.data.ChatMessage
import com.kicktv.app.ui.inflate

class ChatAdapter(private val context: Context, var textSizeSp: Int) : RecyclerView.Adapter<ChatAdapter.VH>() {
    private val messages = ArrayList<ChatMessage>()
    private val images = LruCache<String, Drawable>(400)
    private val pending = HashSet<String>()
    private var refreshPosted = false
    private var recycler: RecyclerView? = null

    private val emoteRegex = Regex("""\[emote:(\d+):([^\]]*)]""")

    /** Kick's own artwork for the role badges, in the order Kick shows them. */
    private val badgeIcons = linkedMapOf(
        "broadcaster" to R.drawable.badge_broadcaster,
        "staff" to R.drawable.badge_staff,
        "moderator" to R.drawable.badge_moderator,
        "verified" to R.drawable.badge_verified,
        "vip" to R.drawable.badge_vip,
        "og" to R.drawable.badge_og,
        "founder" to R.drawable.badge_founder,
        "sidekick" to R.drawable.badge_sidekick,
        "bot" to R.drawable.badge_bot,
        "subscriber" to R.drawable.badge_subscriber,
        "sub_gifter" to R.drawable.badge_sub_gifter,
    )

    /** The channel's own subscriber badges (months -> image), set once channel info loads. */
    var subscriberBadges: List<Pair<Int, String>> = emptyList()
        set(value) { field = value; refreshVisible() }

    companion object {
        const val MAX_MESSAGES = 150
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
    }

    fun add(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
        if (messages.size > MAX_MESSAGES) {
            val drop = messages.size - MAX_MESSAGES
            repeat(drop) { messages.removeAt(0) }
            notifyItemRangeRemoved(0, drop)
        }
    }

    fun remove(id: String) {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx >= 0) {
            messages.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    fun clear() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount() = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(parent.inflate(R.layout.item_chat) as TextView)

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp.toFloat())
        holder.text.text = render(messages[position], holder.text.lineHeight)
    }

    private fun render(m: ChatMessage, lineHeight: Int): CharSequence {
        val sb = SpannableStringBuilder()
        if (m.isSystem) {
            sb.append(m.content)
            sb.setSpan(ForegroundColorSpan(0xFF9AA0A6.toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.ITALIC), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return sb
        }
        val badgeSize = (lineHeight * 0.95f).toInt().coerceAtLeast(18)
        for (b in m.badges.sortedBy { badgeIcons.keys.indexOf(it.type).let { i -> if (i < 0) 99 else i } }) {
            val drawable = if (b.type == "subscriber") {
                subscriberBadgeUrl(b.count)?.let { remoteImage(it) } ?: context.getDrawable(R.drawable.badge_subscriber)
            } else {
                badgeIcons[b.type]?.let { context.getDrawable(it) }
            } ?: continue
            appendImage(sb, drawable, badgeSize, "◆")
            sb.append(' ')
        }
        for (url in m.badgeImages) {
            remoteImage(url)?.let { appendImage(sb, it, badgeSize, "◆"); sb.append(' ') }
        }
        val nameStart = sb.length
        sb.append(m.username)
        sb.setSpan(ForegroundColorSpan(parseColor(m.color)), nameStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), nameStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(": ")

        var last = 0
        val size = (lineHeight * 1.25f).toInt().coerceAtLeast(24)
        for (match in emoteRegex.findAll(m.content)) {
            sb.append(m.content, last, match.range.first)
            val name = match.groupValues[2]
            val d = remoteImage("https://files.kick.com/emotes/${match.groupValues[1]}/fullsize")
            if (d != null) appendImage(sb, d, size, name) else sb.append(name)
            last = match.range.last + 1
        }
        sb.append(m.content, last, m.content.length)
        return sb
    }

    /** Adds [drawable] scaled to [height] and centered on the text line; [alt] is the hidden text. */
    private fun appendImage(sb: SpannableStringBuilder, drawable: Drawable, height: Int, alt: String) {
        val copy = drawable.constantState?.newDrawable(context.resources)?.mutate() ?: drawable
        val ratio = if (copy.intrinsicHeight > 0) copy.intrinsicWidth.toFloat() / copy.intrinsicHeight else 1f
        copy.setBounds(0, 0, (height * ratio).toInt(), height)
        val start = sb.length
        sb.append(alt)
        sb.setSpan(CenteredImageSpan(copy), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** A cached network image (emote or badge), or null while it loads; rows redraw when it arrives. */
    private fun remoteImage(url: String): Drawable? {
        images.get(url)?.let { return it }
        if (pending.add(url)) {
            val req = ImageRequest.Builder(context)
                .data(url)
                .target(onSuccess = { image ->
                    images.put(url, image.asDrawable(context.resources))
                    scheduleRefresh()
                })
                .build()
            SingletonImageLoader.get(context).enqueue(req)
        }
        return null
    }

    private fun subscriberBadgeUrl(months: Int): String? =
        subscriberBadges.lastOrNull { it.first <= months.coerceAtLeast(1) }?.second
            ?: subscriberBadges.firstOrNull()?.second

    private fun refreshVisible() {
        refreshPosted = false
        scheduleRefresh()
    }

    /** Batch redraws as emotes arrive instead of rebinding per image. */
    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        val rv = recycler
        if (rv == null) {
            refreshPosted = false
            return
        }
        rv.postDelayed({
            refreshPosted = false
            val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
            val first = lm?.findFirstVisibleItemPosition() ?: 0
            val last = lm?.findLastVisibleItemPosition() ?: (messages.size - 1)
            if (first >= 0 && last >= first) notifyItemRangeChanged(first, last - first + 1)
        }, 250)
    }

    private fun parseColor(c: String?): Int = try {
        if (c.isNullOrBlank()) 0xFF53FC18.toInt() else Color.parseColor(c)
    } catch (e: Exception) {
        0xFF53FC18.toInt()
    }

    class VH(val text: TextView) : RecyclerView.ViewHolder(text)
}

/** ImageSpan that sits centered on the text line instead of on the baseline. */
private class CenteredImageSpan(drawable: Drawable) : android.text.style.ImageSpan(drawable) {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
        val bounds = drawable.bounds
        if (fm != null) {
            val pfm = paint.fontMetricsInt
            val textHeight = pfm.descent - pfm.ascent
            val extra = (bounds.height() - textHeight).coerceAtLeast(0) / 2
            fm.ascent = pfm.ascent - extra
            fm.descent = pfm.descent + extra
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
        return bounds.right
    }

    override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
        val d = drawable
        val pfm = paint.fontMetricsInt
        val centerY = y + (pfm.descent + pfm.ascent) / 2
        canvas.save()
        canvas.translate(x, (centerY - d.bounds.height() / 2).toFloat())
        d.draw(canvas)
        canvas.restore()
    }
}
