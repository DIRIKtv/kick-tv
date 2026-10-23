package com.diriktv.sidekick.ui

import android.animation.ArgbEvaluator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.diriktv.sidekick.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

// Native ports of Bencho blocks (bencho.dev): shimmer text, trace loader, rolling counter,
// liquid toggle, search (Seek) and step player. Each one drives its own animation from onDraw
// and stops asking for frames as soon as it has settled.

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
private val argb = ArgbEvaluator()
private fun blend(a: Int, b: Int, t: Float) = argb.evaluate(t.coerceIn(0f, 1f), a, b) as Int
private fun View.px(dp: Float) = dp * resources.displayMetrics.density

/** True when the system's animator scale is off (the TV's "remove animations" setting). */
internal fun Context.motionOff(): Boolean =
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Seconds since the previous frame; a long gap (the view was off screen) counts as one frame. */
private class FrameClock {
    private var last = 0L
    fun tick(): Float {
        val now = System.nanoTime()
        val dt = if (last == 0L) 1 / 60f else (now - last) / 1e9f
        last = now
        return if (dt > 0.1f) 1 / 60f else dt
    }
    fun reset() { last = 0L }
}

/** A mass-spring-damper in Framer's units (stiffness, damping, mass), stepped in small slices. */
private class Spring(val stiffness: Float, val damping: Float, val mass: Float) {
    var x = 0f
    var v = 0f
    fun step(target: Float, dt: Float) {
        var left = dt
        while (left > 0f) {
            val h = min(left, 0.004f)
            v += (-stiffness * (x - target) - damping * v) / mass * h
            x += v * h
            left -= h
        }
    }
    fun settled(target: Float, eps: Float) = abs(x - target) < eps && abs(v) < eps
    fun snap(to: Float) { x = to; v = 0f }
}

/**
 * TextView that can carry a band of ink sweeping across its words, on a loop, while something is
 * in progress. The sweep runs on the uptime clock, so a row that's rebuilt mid-sweep carries on
 * from the same place instead of starting over.
 */
class ShimmerTextView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    TextView(context, attrs) {
    var shimmer = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private val still = context.motionOff()
    private val band = Matrix()
    private var gradient: LinearGradient? = null
    private var gradientWidth = 0f
    private var gradientInk = 0

    override fun onDraw(canvas: Canvas) {
        val lay = layout
        if (!shimmer || lay == null || lay.lineCount == 0) return super.onDraw(canvas)
        // The words at about a third of their ink...
        val save = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 0x55)
        super.onDraw(canvas)
        canvas.restoreToCount(save)
        if (still) return
        // ...and the same words again, painted only where the band is.
        var textW = 1f
        var textL = Float.MAX_VALUE
        for (i in 0 until lay.lineCount) {
            textW = max(textW, lay.getLineWidth(i))
            textL = min(textL, lay.getLineLeft(i))
        }
        val ink = currentTextColor
        if (gradient == null || textW != gradientWidth || ink != gradientInk) {
            val half = textW * 0.4f
            gradient = LinearGradient(-half, 0f, half, 0f, intArrayOf(ink and 0xFFFFFF, ink, ink and 0xFFFFFF), null, Shader.TileMode.CLAMP)
            gradientWidth = textW
            gradientInk = ink
        }
        // The band enters off the left edge, crosses, and leaves off the right before the next.
        val t = (SystemClock.uptimeMillis() % SWEEP_MS) / SWEEP_MS.toFloat()
        band.setTranslate(totalPaddingLeft + textL - textW + 3 * textW * t, 0f)
        gradient!!.setLocalMatrix(band)
        paint.shader = gradient
        super.onDraw(canvas)
        paint.shader = null
        postInvalidateOnAnimation()
    }

    private companion object {
        const val SWEEP_MS = 2000L
    }
}

/**
 * Loading mark: a dash running round its own faint rounded-square track at one steady speed,
 * its length breathing on a slower clock so it never stalls or pinches.
 */
class TraceLoader @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    var color: Int = context.getColor(R.color.kick_green)
        set(value) { field = value; invalidate() }

    private val still = context.motionOff()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val segment = Path()
    private val measure = PathMeasure()
    private var length = 0f
    private val start = SystemClock.uptimeMillis()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = px(48f).roundToInt()
        setMeasuredDimension(resolveSize(d, widthMeasureSpec), resolveSize(d, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Built on a 40-unit square drawn at four fifths of the view, like the block in its slot.
        val u = min(w, h) * 0.8f / 40f
        val cx = w / 2f
        val cy = h / 2f
        path.reset()
        path.addRoundRect(RectF(cx - 16 * u, cy - 16 * u, cx + 16 * u, cy + 16 * u), 9 * u, 9 * u, Path.Direction.CW)
        measure.setPath(path, true)
        length = measure.length
        track.strokeWidth = 3.5f * u
        dash.strokeWidth = 3.5f * u
    }

    override fun onDraw(canvas: Canvas) {
        track.color = (color and 0xFFFFFF) or (0x24 shl 24)
        dash.color = color
        canvas.drawPath(path, track)
        val now = if (still) 0L else SystemClock.uptimeMillis() - start
        val lap = (now % 1400) / 1400f
        val swing = (now % 4200) / 2100f
        val reach = (1 - cos(PI * (if (swing > 1f) 2f - swing else swing))).toFloat() / 2f
        val from = lap * length
        val to = from + mix(0.16f, 0.30f, reach) * length
        segment.reset()
        if (to <= length) {
            measure.getSegment(from, to, segment, true)
        } else {
            measure.getSegment(from, length, segment, true)
            measure.getSegment(0f, to - length, segment, true)
        }
        canvas.drawPath(segment, dash)
        if (!still) postInvalidateOnAnimation()
    }
}

/**
 * A number whose digits roll like an odometer. Each column counts whole tens, hundreds… of the
 * value, so 9 → 10 rolls the ones forward and carries one into the tens, and the columns move
 * in turn from the right so a carry ripples up the number. Display only.
 */
class RollingCounter @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        color = 0xCCFFFFFF.toInt()
    }
    var textColor: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }

    private class Col(n: Float) {
        var pos = n
        var vel = 0f
        var target = n
        val due = ArrayDeque<Long>()
        val next = ArrayDeque<Float>()
    }

    private val cols = ArrayList<Col>()
    private val clock = FrameClock()
    private val still = context.motionOff()
    private var value = 0
    private var hasValue = false
    private var shown = 1

    fun setTextSizeSp(sp: Float) {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        requestLayout()
    }

    /** Rolls to [v]; the first value (or [animate] false) is shown without rolling. */
    fun setValue(v: Int, animate: Boolean = true) {
        val n = v.coerceAtLeast(0)
        if (hasValue && n == value) return
        val places = n.toString().length
        while (cols.size < places) cols += Col(countAt(value, cols.size))
        if (!hasValue || !animate || still) {
            cols.forEachIndexed { p, c ->
                c.pos = countAt(n, p); c.target = c.pos; c.vel = 0f; c.due.clear(); c.next.clear()
            }
        } else {
            val now = SystemClock.uptimeMillis()
            cols.forEachIndexed { p, c -> c.due.addLast(now + p * STAGGER_MS); c.next.addLast(countAt(n, p)) }
        }
        value = n
        hasValue = true
        contentDescription = n.toString()
        if (places != shown) { shown = places; requestLayout() }
        invalidate()
    }

    private fun countAt(n: Int, place: Int) = floor(n / 10.0.pow(place)).toFloat()

    private val digitWidth get() = (0..9).maxOf { paint.measureText(it.toString()) }
    private val commaWidth get() = paint.measureText(",")
    private val cell get() = paint.fontMetrics.let { it.descent - it.ascent }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = paddingLeft + paddingRight + shown * digitWidth + ((shown - 1) / 3) * commaWidth
        val h = paddingTop + paddingBottom + cell
        setMeasuredDimension(resolveSize(w.roundToInt(), widthMeasureSpec), resolveSize(h.roundToInt(), heightMeasureSpec))
    }

    override fun getBaseline(): Int = paddingTop + (-paint.fontMetrics.ascent).roundToInt()

    override fun onDraw(canvas: Canvas) {
        val dt = clock.tick() * 60f
        val now = SystemClock.uptimeMillis()
        var busy = false
        for (c in cols) {
            while (c.due.isNotEmpty() && c.due.first() <= now) { c.due.removeFirst(); c.target = c.next.removeFirst() }
            if (c.due.isNotEmpty()) busy = true
            // Far from home, jump most of the way in whole turns first; it looks the same.
            val gap = c.target - c.pos
            if (abs(gap) > 24) c.pos += ((gap - gap.sign * 24) / 10).roundToInt() * 10
            c.vel += (c.target - c.pos) * SPRING_K * dt
            c.vel *= SPRING_D.pow(dt)
            c.pos += c.vel * dt
            if (abs(c.target - c.pos) < 0.004f && abs(c.vel) < 0.004f) { c.pos = c.target; c.vel = 0f } else busy = true
        }

        val dw = digitWidth
        val ch = cell
        val base = getBaseline().toFloat()
        val ink = paint.color
        val alpha = ink ushr 24
        var x = paddingLeft.toFloat()
        for (i in 0 until shown) {
            val p = shown - 1 - i
            val c = cols.getOrNull(p) ?: continue
            val m = ((c.pos % 10) + 10) % 10
            val d = floor(m).toInt()
            val f = m - d
            canvas.save()
            canvas.clipRect(x, paddingTop.toFloat(), x + dw, paddingTop + ch)
            // The digit leaving and the one arriving, each fading toward the window's edge.
            drawDigit(canvas, d % 10, x, dw, base - f * ch, alpha * (1 - f))
            drawDigit(canvas, (d + 1) % 10, x, dw, base + (1 - f) * ch, alpha * f)
            canvas.restore()
            paint.color = ink
            x += dw
            if (p > 0 && p % 3 == 0) { canvas.drawText(",", x, base, paint); x += commaWidth }
        }
        if (busy) postInvalidateOnAnimation() else clock.reset()
    }

    private fun drawDigit(canvas: Canvas, digit: Int, x: Float, w: Float, y: Float, alpha: Float) {
        if (alpha < 2f) return
        val s = DIGITS[digit]
        paint.alpha = alpha.roundToInt()
        canvas.drawText(s, x + (w - paint.measureText(s)) / 2, y, paint)
    }

    private companion object {
        val DIGITS = Array(10) { it.toString() }
        const val STAGGER_MS = 45L
        // The block's spring at bounce 45: per-frame stiffness and the share of velocity kept.
        const val SPRING_K = 0.152f
        const val SPRING_D = 0.71f
    }
}

/**
 * Settings switch whose droplet stretches along its travel as it crosses, keeping its area.
 * Its row owns the press; call [setChecked]. Duplicates its parent's state so a focused (green)
 * row inverts it: black track, green droplet.
 */
class LiquidToggle @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    var isChecked = false
        private set
    private var bound = false
    private var hot = false
    private val still = context.motionOff()
    private val clock = FrameClock()

    // Simulated in the block's own 46px of travel, so its tuning carries over unchanged.
    private val pos = Spring(170f, 21.5f, 0.9f)
    private val speed = Spring(380f, 34f, 0.6f)
    private val swell = Spring(380f, 34f, 0.6f).apply { x = 1f }
    private var on = 0f
    private var focus = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val trackOff = context.getColor(R.color.surface3)
    private val trackOn = context.getColor(R.color.kick_green)
    private val thumbOff = 0xFF8A8F97.toInt()
    private val thumbOn = context.getColor(R.color.bg)

    fun setChecked(checked: Boolean, animate: Boolean) {
        if (bound && checked == isChecked) return
        isChecked = checked
        contentDescription = if (checked) "On" else "Off"
        if (!bound || !animate || still) {
            pos.snap(if (checked) TRAVEL else 0f)
            on = if (checked) 1f else 0f
        }
        bound = true
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(px(46f).roundToInt(), widthMeasureSpec), resolveSize(px(26f).roundToInt(), heightMeasureSpec))
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val focused = android.R.attr.state_focused in drawableState
        if (focused != hot) { hot = focused; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val dt = clock.tick()
        val target = if (isChecked) TRAVEL else 0f
        if (still) { pos.snap(target); swell.snap(1f) } else {
            pos.step(target, dt)
            speed.step(pos.v, dt)
            swell.step(if (hot) 1.035f else 1f, dt)
        }
        on = (on + (if (isChecked) dt else -dt) / 0.42f).coerceIn(0f, 1f)
        focus = (focus + (if (hot) dt else -dt) / 0.13f).coerceIn(0f, 1f)

        val w = width.toFloat()
        val h = height.toFloat()
        val pad = px(3f)
        val thumb = h - pad * 2
        // Longer along the travel and thinner across it, by how fast it's moving.
        val lengthen = 1 + min(0.4f, abs(speed.x) / 600f) * STRETCH
        val sx = lengthen * swell.x
        val sy = swell.x / lengthen

        val restTrack = blend(trackOff, trackOn, on)
        val restThumb = blend(thumbOff, thumbOn, on)
        val focusTrack = blend(0x40000000, context.getColor(R.color.bg), on)
        val focusThumb = blend(0x99000000.toInt(), trackOn, on)
        fill.color = blend(restTrack, focusTrack, focus)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)

        fill.color = blend(restThumb, focusThumb, focus)
        canvas.save()
        canvas.translate(pad + thumb / 2 + pos.x / TRAVEL * (w - thumb - pad * 2), h / 2)
        canvas.scale(sx, sy)
        canvas.drawCircle(0f, 0f, thumb / 2, fill)
        canvas.restore()

        val moving = !pos.settled(target, 0.05f) || !speed.settled(0f, 0.5f) ||
            !swell.settled(if (hot) 1.035f else 1f, 0.0005f) || on != (if (isChecked) 1f else 0f) || focus != (if (hot) 1f else 0f)
        if (moving) postInvalidateOnAnimation() else clock.reset()
    }

    private companion object {
        const val TRAVEL = 46f
        const val STRETCH = 0.36f
    }
}

/**
 * The search field as one object: a round lens button that, when it takes focus, gives under the
 * press and then springs open into the field, and closes back to the lens when focus leaves with
 * nothing typed. The lens never moves; the pill grows around it. Wraps a single EditText.
 */
class SearchPill @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private lateinit var field: EditText
    private val still = context.motionOff()
    private val clock = FrameClock()
    private var open = false
    private var w = -1f
    private var wv = 0f
    private var typingUntil = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.surface2) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = px(2f); color = context.getColor(R.color.kick_green)
    }
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val clip = Path()

    init {
        setWillNotDraw(false)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        field = getChildAt(0) as EditText
        field.setOnFocusChangeListener { _, _ -> update() }
        field.doAfterTextChanged {
            typingUntil = SystemClock.uptimeMillis() + 340
            update()
            invalidate()
        }
        field.alpha = 0f
    }

    private fun update() {
        val want = field.hasFocus() || !field.text.isNullOrBlank()
        if (want == open) return invalidate()
        if (want && !still) {
            // It yields for a beat before it opens: a thing that gives reads as pushed.
            pivotX = height / 2f
            pivotY = height / 2f
            animate().scaleX(0.93f).scaleY(0.93f).setDuration(90).withEndAction {
                open = true
                invalidate()
                animate().scaleX(1f).scaleY(1f).setDuration(220).start()
            }.start()
        } else {
            open = want
        }
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val h = height.toFloat()
        val full = width.toFloat()
        val target = if (open) full else h
        if (w < 0f || still) { w = target; wv = 0f } else {
            // The block's spring at 50: per-frame stiffness 0.16, keeping 0.72 of the velocity.
            val dt = clock.tick() * 60f
            wv += (target - w) * 0.16f * dt
            wv *= 0.72f.pow(dt)
            w += wv * dt
            if (abs(target - w) < 0.5f && abs(wv) < 0.5f) { w = target; wv = 0f }
        }
        val p = ((w - h) / max(1f, full - h)).coerceIn(0f, 1f)
        // The words arrive in the last stretch, once there's somewhere for them to be.
        val say = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        field.alpha = say
        field.translationX = (1 - say) * -px(6f)

        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        clip.reset()
        clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restore()
        if (field.hasFocus()) {
            val r = ring.strokeWidth / 2
            rect.inset(r, r)
            canvas.drawRoundRect(rect, h / 2 - r, h / 2 - r, ring)
        }

        // The lens, at an inset that centres it in the circle and pads it in the field.
        val size = h * 0.42f
        val inset = (h - size) / 2
        val u = size / 18f
        val typing = SystemClock.uptimeMillis() < typingUntil
        lens.color = context.getColor(if (typing || (field.hasFocus() && !open)) R.color.text_primary else R.color.text_secondary)
        lens.strokeWidth = u * (if (typing) 2.1f else 1.8f)
        canvas.drawCircle(inset + 7.6f * u, inset + 7.6f * u, 5.4f * u, lens)
        canvas.drawLine(inset + 11.6f * u, inset + 11.6f * u, inset + 15.4f * u, inset + 15.4f * u, lens)

        if (w != target || typing) postInvalidateOnAnimation() else clock.reset()
    }
}

/**
 * The hero carousel's step player: a dot per item, the current one stretched into a bar that
 * fills while the carousel waits on it. When it's full the bar eases over to the next dot and a
 * small wake runs through the dots it crossed. The round pill beside it shows a pause mark while
 * it runs and morphs to a play mark while it's held ([playing] false).
 */
class StepPlayerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    /** How long one step takes to fill. */
    var paceMs = 9000L
    /** Called when a full bar moves the player on by itself. */
    var onAdvance: ((Int) -> Unit)? = null
    var playing = true
        set(value) {
            if (field == value) return
            field = value
            markFrom = markT
            markStart = SystemClock.uptimeMillis()
            invalidate()
        }

    private class Edge(var x: Float) {
        var from = 0f
        var to = 0f
        var t0 = -1L
    }
    private class Dot(val l: Edge, val r: Edge, var ink: Float)
    private class Wave(val t0: Long, val origin: Int, val from: Int)

    private val still = context.motionOff()
    private val clock = FrameClock()
    private val dots = ArrayList<Dot>()
    private var count = 0
    private var at = 0
    private var prog = 0f
    private var wave: Wave? = null
    private var markT = 0f
    private var markFrom = 0f
    private var markStart = 0L

    private val dot = px(7f)
    private val bar = px(30f)
    private val gap = px(7f)
    private val pad = px(11f)
    private val slabH = dot + pad * 2
    private val between = px(6f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL_AND_STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val markPath = Path()
    private val rect = RectF()
    private val slab = 0x990B0E0F.toInt()
    private val rest = 0x66FFFFFF
    private val ink = context.getColor(R.color.kick_green)

    /** Shows [n] steps with [index] current; a new index moves the bar there and starts it afresh. */
    fun bind(n: Int, index: Int) {
        val i = index.coerceIn(0, max(0, n - 1))
        if (n != count) {
            count = n
            at = i
            prog = 0f
            wave = null
            dots.clear()
            slots(n, i).forEachIndexed { k, (l, r) -> dots += Dot(Edge(l), Edge(r), if (k < i) 1f else 0f) }
            contentDescription = "Step ${i + 1} of $n"
            requestLayout()
            invalidate()
        } else if (i != at) {
            go(i)
        }
    }

    private fun slots(n: Int, cur: Int): List<Pair<Float, Float>> {
        var x = 0f
        return List(n) { k ->
            val w = if (k == cur) bar else dot
            (x to x + w).also { x += w + gap }
        }
    }

    private fun go(to: Int) {
        val now = SystemClock.uptimeMillis()
        if (!still) {
            val will = slots(count, to)
            dots.forEachIndexed { k, d ->
                d.l.from = d.l.x; d.l.to = will[k].first; d.l.t0 = now
                d.r.from = d.r.x; d.r.to = will[k].second; d.r.t0 = now
            }
            wave = Wave(now, to, at)
        }
        at = to
        prog = 0f
        contentDescription = "Step ${to + 1} of $count"
        invalidate()
    }

    private val trackWidth get() = if (count == 0) 0f else (count - 1) * (dot + gap) + bar

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = trackWidth + pad * 2 + between + slabH
        setMeasuredDimension(resolveSize(w.roundToInt(), widthMeasureSpec), resolveSize(slabH.roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (count == 0) return
        val dt = clock.tick()
        val now = SystemClock.uptimeMillis()
        var busy = false

        if (playing && count > 1) {
            busy = true
            prog += dt * 1000f / paceMs
            if (prog >= 1f) {
                // From the last step the bar crosses the whole row back to the first.
                val next = (at + 1) % count
                go(next)
                onAdvance?.invoke(next)
            }
        }

        var slot = 0f
        dots.forEachIndexed { k, d ->
            val end = slot + if (k == at) bar else dot
            if (ease(d.l, slot, now)) busy = true
            if (ease(d.r, end, now)) busy = true
            slot = end + gap
            // Visited is full, the current one is the fill, the rest are empty; eased, so a
            // restart drains the row instead of blinking it.
            val want = if (k < at) 1f else if (k == at) prog else 0f
            val next = if (still || (k == at && playing)) want else d.ink + (want - d.ink) * min(1f, 0.22f * dt * 60f)
            d.ink = if (abs(want - next) < 0.002f) want else next
            if (d.ink != want) busy = true
        }
        val wv = wave
        if (wv != null && now - wv.t0 < LEAD_MS + (REACH + 1) * STAGGER_MS + RISE_MS) busy = true else wave = null

        // The row's slab, then each dot: grey, with its ink drawn over from the left.
        paint.color = slab
        rect.set(0f, 0f, trackWidth + pad * 2, slabH)
        canvas.drawRoundRect(rect, slabH / 2, slabH / 2, paint)
        val top = (slabH - dot) / 2
        dots.forEachIndexed { k, d ->
            val w = max(0f, d.r.x - d.l.x)
            val sy = lift(k, now)
            val y0 = top + dot / 2 - dot * sy / 2
            val y1 = top + dot / 2 + dot * sy / 2
            val r = dot * min(1f, sy) / 2
            paint.color = rest
            rect.set(pad + d.l.x, y0, pad + d.l.x + w, y1)
            canvas.drawRoundRect(rect, r, r, paint)
            if (d.ink > 0.001f) {
                paint.color = ink
                // Never narrower than a dot, and that first dot fades in over the first few per cent.
                if (k == at) paint.alpha = (255 * min(1f, d.ink * 14)).roundToInt()
                rect.set(pad + d.l.x, y0, pad + d.l.x + max(min(dot, w), w * d.ink), y1)
                canvas.drawRoundRect(rect, r, r, paint)
            }
        }

        // The transport pill, and its mark: the same two quads as pause bars or a split triangle.
        val cx0 = trackWidth + pad * 2 + between
        paint.color = slab
        canvas.drawCircle(cx0 + slabH / 2, slabH / 2, slabH / 2, paint)
        val markTo = if (playing) 0f else 1f
        if (still) markT = markTo else {
            val u = ((now - markStart) / 300f).coerceIn(0f, 1f)
            val s = if (u < 0.5f) 4 * u * u * u else 1 - (-2 * u + 2).pow(3) / 2
            markT = mix(markFrom, markTo, s)
            if (u < 1f) busy = true
        }
        drawMark(canvas, cx0 + slabH / 2, slabH / 2, px(12f))

        if (busy) postInvalidateOnAnimation() else clock.reset()
    }

    /** Moves an edge along its timed slide, or to [rest] when it has none. True while sliding. */
    private fun ease(e: Edge, rest: Float, now: Long): Boolean {
        if (still || e.t0 < 0) { e.x = rest; return false }
        val u = ((now - e.t0) / MOVE_MS.toFloat()).coerceIn(0f, 1f)
        e.x = mix(e.from, e.to, 1 - (1 - u).pow(3))
        if (u >= 1f) e.t0 = -1
        return u < 1f
    }

    /** How far dot [k] stands up in the wake, as a scale on its height. */
    private fun lift(k: Int, now: Long): Float {
        val w = wave ?: return 1f
        val span = abs(w.origin - w.from)
        val skip = max(0, span - REACH)
        val lo = min(w.from, w.origin)
        val hi = max(w.from, w.origin)
        var delay = -1f
        var share = 1f
        if (k == w.origin) {
            delay = min(span, REACH) * STAGGER_MS
            share = SELF
        } else if (k in lo..hi) {
            val d = abs(k - w.from)
            if (span - d <= REACH) delay = (d - skip) * STAGGER_MS
        }
        if (delay < 0) return 1f
        val u = (now - w.t0 - LEAD_MS - delay) / RISE_MS
        return if (u > 0f && u < 1f) 1 + sin(PI * u).toFloat() * AMP * share else 1f
    }

    private fun drawMark(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val t = markT
        // A soft bulge on the way between the two shapes: squash, lean together, tip and level.
        val goo = sin(t.coerceIn(0f, 1f) * PI).toFloat()
        val pull = 1.6f * goo
        val tip = (if (playing) -9f else 9f) * goo
        val u = size / 24f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(tip)
        canvas.scale((1 - 0.13f * goo) * u, (1 + 0.11f * goo) * u)
        canvas.translate(-12f, -12f)
        mark.strokeWidth = 2f
        quad(PAUSE_L, PLAY_L, t, pull)
        canvas.drawPath(markPath, mark)
        quad(PAUSE_R, PLAY_R, t, -pull)
        canvas.drawPath(markPath, mark)
        canvas.restore()
    }

    private fun quad(a: FloatArray, b: FloatArray, t: Float, dx: Float) {
        markPath.reset()
        for (i in 0 until 8 step 2) {
            val x = mix(a[i], b[i], t) + dx
            val y = mix(a[i + 1], b[i + 1], t)
            if (i == 0) markPath.moveTo(x, y) else markPath.lineTo(x, y)
        }
        markPath.close()
    }

    private companion object {
        const val MOVE_MS = 520L
        const val LEAD_MS = 60f
        const val RISE_MS = 300f
        // The block's wake at full reach.
        const val AMP = 0.34f
        const val REACH = 8
        const val SELF = 0.3f
        const val STAGGER_MS = 55f
        val PAUSE_L = floatArrayOf(6f, 4f, 10f, 4f, 10f, 20f, 6f, 20f)
        val PLAY_L = floatArrayOf(6.5f, 4f, 13.25f, 8f, 13.25f, 16f, 6.5f, 20f)
        val PAUSE_R = floatArrayOf(14f, 4f, 18f, 4f, 18f, 20f, 14f, 20f)
        val PLAY_R = floatArrayOf(13.25f, 8f, 20f, 12f, 20f, 12f, 13.25f, 16f)
    }
}
