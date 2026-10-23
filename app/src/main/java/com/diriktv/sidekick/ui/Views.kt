package com.diriktv.sidekick.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Main content area. Sends LEFT presses that would leave it to the active nav item. */
class ContentFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    var leftExit: (() -> View?)? = null

    override fun focusSearch(focused: View?, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        if (direction == View.FOCUS_LEFT && (result == null || !isDescendant(result))) {
            return leftExit?.invoke() ?: result
        }
        return result
    }

    private fun isDescendant(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }
}

/** Horizontal row that keeps focus inside itself at either end instead of jumping to another row. */
class HRecyclerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    RecyclerView(context, attrs) {
    /** When true, LEFT at the first item may leave the row (towards the nav rail). */
    var allowLeftExit = true

    override fun focusSearch(focused: View?, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        if (focused != null && (direction == View.FOCUS_RIGHT || direction == View.FOCUS_LEFT)) {
            val inside = result != null && findContainingItemView(result) != null
            if (!inside) {
                if (direction == View.FOCUS_LEFT && allowLeftExit) {
                    val pos = findContainingViewHolder(focused)?.bindingAdapterPosition ?: -1
                    if (pos == 0) return result
                }
                return focused
            }
        }
        return result
    }
}

/**
 * ImageView that sizes its height from its width. Its size never depends on the image, so
 * swapping drawables doesn't request a layout (which would ripple through every list on screen).
 */
class AspectImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ImageView(context, attrs) {
    /** height / width */
    var ratio: Float = 9f / 16f
        set(value) { field = value; requestLayout() }

    private var settingDrawable = false

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        settingDrawable = true
        super.setImageDrawable(drawable)
        settingDrawable = false
    }

    override fun requestLayout() {
        if (!settingDrawable) super.requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * ratio).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}

/** Gentle zoom on focus, the standard TV affordance. */
fun View.focusZoom(scale: Float = 1.06f, extra: ((Boolean) -> Unit)? = null) {
    setOnFocusChangeListener { v, hasFocus ->
        val s = if (hasFocus) scale else 1f
        v.animate().scaleX(s).scaleY(s).translationZ(if (hasFocus) 8f else 0f).setDuration(140).start()
        extra?.invoke(hasFocus)
    }
}

fun ImageView.loadThumb(url: String?) {
    load(url) { crossfade(150) }
}

fun ImageView.loadAvatar(url: String?) {
    load(url) {
        crossfade(150)
        transformations(CircleCropTransformation())
    }
}

fun View.setVisible(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

/** Fades (and optionally lifts) a view into place. Safe to call on an already-visible view. */
fun View.fadeIn(duration: Long = 200, fromY: Float = 0f, delay: Long = 0) {
    animate().cancel()
    if (visibility != View.VISIBLE) {
        alpha = 0f
        translationY = fromY
        visibility = View.VISIBLE
    }
    animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration)
        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
}

/** Fades a view out, then removes it from layout. */
fun View.fadeOut(duration: Long = 160, toY: Float = 0f) {
    if (visibility != View.VISIBLE) return
    animate().cancel()
    animate().alpha(0f).translationY(toY).setStartDelay(0).setDuration(duration).withEndAction {
        visibility = View.GONE
        alpha = 1f
        translationY = 0f
    }.start()
}

fun ViewGroup.inflate(layout: Int): View =
    android.view.LayoutInflater.from(context).inflate(layout, this, false)

object Img {
    private val sizeRegex = Regex("""/(160|360|480|720|1080)\.webp(\?.*)?$""")

    /** Kick thumbnails exist at 160/360/480/720/1080 px wide; ask for the one that fits. */
    fun thumb(url: String?, width: Int = 480): String? =
        url?.replace(sizeRegex, "/$width.webp")

    /** From an HTML srcset, the smallest image at least [minWidth] px wide. */
    fun fromSrcset(srcset: String?, minWidth: Int): String? {
        val options = srcset.orEmpty().split(',').mapNotNull { part ->
            val bits = part.trim().split(' ')
            val w = bits.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: return@mapNotNull null
            bits[0] to w
        }.sortedBy { it.second }
        return (options.firstOrNull { it.second >= minWidth } ?: options.lastOrNull())?.first
    }
}

object Fmt {
    fun count(n: Int): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0).replace(".0K", "K")
        else -> n.toString()
    }

    fun viewers(n: Int) = "${count(n)} watching"

    fun time(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Kick returns either ISO "2026-10-20T19:22:23Z" or "2026-10-20 19:22:23", both UTC. */
    fun parseUtc(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val clean = s.replace('T', ' ').removeSuffix("Z").substringBefore('.').substringBefore('+')
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(clean)?.time
        } catch (e: Exception) {
            null
        }
    }

    fun uptime(start: String?): String? {
        val t = parseUtc(start) ?: return null
        return time(System.currentTimeMillis() - t)
    }

    fun ago(date: String?): String = parseUtc(date)?.let { ago(it) } ?: ""

    fun ago(t: Long): String {
        val mins = (System.currentTimeMillis() - t) / 60000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            mins < 60 * 24 * 30 -> "${mins / (60 * 24)}d ago"
            else -> "${mins / (60 * 24 * 30)}mo ago"
        }
    }
}

/** A 64-bit hash of [key] for RecyclerView stable ids (FNV-1a; collisions are vanishingly rare). */
fun stableId(key: String): Long {
    var h = -0x340d631b7bdddcdbL
    for (c in key) {
        h = h xor c.code.toLong()
        h *= 0x100000001b3L
    }
    return h
}
