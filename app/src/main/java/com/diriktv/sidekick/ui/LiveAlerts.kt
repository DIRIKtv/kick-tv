package com.diriktv.sidekick.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.Follows
import com.diriktv.sidekick.data.LiveStream
import com.diriktv.sidekick.data.Prefs
import com.diriktv.sidekick.player.MultiViewActivity
import com.diriktv.sidekick.player.PlayerActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Go-live alerts. While the app is open it rechecks followed channels every few minutes and
 * shows a banner when one goes live: ▶❚❚ opens it, and in the player it's at the top of ☰.
 */
object LiveAlerts : Application.ActivityLifecycleCallbacks {
    private const val INTERVAL_MS = 3 * 60_000L
    private const val FIRST_CHECK_MS = 15_000L
    private const val BANNER_MS = 12_000L
    private const val RECENT_MS = 5 * 60_000L

    private lateinit var app: Application
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var resumed: WeakReference<Activity>? = null
    private val wrapped = WeakHashMap<Activity, Boolean>()

    /** Live follows at the last check; null until the first check sets the baseline. */
    private var known: Set<String>? = null
    private var knownVersion = -1
    private var lastCheck = 0L
    private var scheduled = false
    private var checking = false

    private var recent: LiveStream? = null
    private var recentAt = 0L
    private var banner: WeakReference<View>? = null
    private var bannerOn: WeakReference<Activity>? = null
    private val bannerHide = Runnable { dismissBanner(animate = true) }
    private val poll = Runnable { scheduled = false; check() }

    fun init(app: Application) {
        this.app = app
        app.registerActivityLifecycleCallbacks(this)
    }

    /** The latest alert from the last few minutes, unless it's the channel being watched. */
    fun recent(exclude: String?): LiveStream? = recent
        ?.takeIf { System.currentTimeMillis() - recentAt < RECENT_MS && !it.slug.equals(exclude, true) }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        val delay = if (lastCheck == 0L) FIRST_CHECK_MS else (lastCheck + INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(2_000)
        main.postDelayed(poll, delay)
    }

    private fun check() {
        if (resumed?.get() == null || checking) return
        if (!Prefs(app).liveAlerts) {
            // Start over when they're turned back on, so it doesn't announce everyone at once.
            known = null
            lastCheck = System.currentTimeMillis()
            schedule()
            return
        }
        checking = true
        scope.launch {
            val live = runCatching { Follows.liveNow(app, maxAgeMs = INTERVAL_MS - 15_000) }.getOrNull()
            checking = false
            lastCheck = System.currentTimeMillis()
            if (live != null) {
                val slugs = live.map { it.slug.lowercase() }.toSet()
                val before = known
                // A changed follow list resets the baseline (no alert for someone you just followed).
                if (before != null && knownVersion == Follows.version) {
                    val watching = PlayerActivity.currentSlug
                    val fresh = live.filter { it.slug.lowercase() !in before && !it.slug.equals(watching, true) }
                    if (fresh.isNotEmpty()) announce(fresh)
                }
                known = slugs
                knownVersion = Follows.version
            }
            if (resumed?.get() != null) schedule()
        }
    }

    private fun announce(streams: List<LiveStream>) {
        val s = streams.first()
        recent = s
        recentAt = System.currentTimeMillis()
        val act = resumed?.get() ?: return
        if (act is PlayerActivity && act.inPip) return
        showBanner(act, s, streams.size - 1)
    }

    // region banner

    private fun showBanner(act: Activity, s: LiveStream, others: Int) {
        dismissBanner(animate = false)
        val inPlayer = act is PlayerActivity || act is MultiViewActivity
        val v = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(act.getColor(R.color.surface2))
                cornerRadius = act.dp(14).toFloat()
                setStroke(act.dp(1), act.getColor(R.color.kick_green_dim))
            }
            setPadding(act.dp(14), act.dp(12), act.dp(18), act.dp(12))
            elevation = act.dp(12).toFloat()
            isFocusable = false
        }
        v.addView(ImageView(act).apply { loadAvatar(s.avatar) }, LinearLayout.LayoutParams(act.dp(44), act.dp(44)).apply { marginEnd = act.dp(12) })
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(act).apply {
            text = "●  JUST WENT LIVE"
            setTextColor(act.getColor(R.color.kick_green))
            textSize = 11f
            letterSpacing = 0.08f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        texts.addView(TextView(act).apply {
            text = if (others > 0) "${s.username} and $others more" else s.username
            setTextColor(act.getColor(R.color.text_primary))
            textSize = 17f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        texts.addView(TextView(act).apply {
            text = listOfNotNull(s.category, s.title.takeIf { it.isNotBlank() }).joinToString(" • ")
            setTextColor(act.getColor(R.color.text_secondary))
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = act.dp(300)
        })
        texts.addView(TextView(act).apply {
            text = if (inPlayer) "☰  Settings to switch" else "▶❚❚  Play to watch"
            setTextColor(act.getColor(R.color.text_primary))
            textSize = 12f
            setPadding(0, act.dp(6), 0, 0)
        })
        v.addView(texts)

        val decor = act.window.decorView as? FrameLayout ?: return
        decor.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, act.dp(24), act.dp(28), 0)
        })
        banner = WeakReference(v)
        bannerOn = WeakReference(act)
        if (!act.motionOff()) {
            v.alpha = 0f
            v.translationX = act.dp(60).toFloat()
            v.animate().alpha(1f).translationX(0f).setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
        main.removeCallbacks(bannerHide)
        main.postDelayed(bannerHide, BANNER_MS)
    }

    private fun dismissBanner(animate: Boolean) {
        main.removeCallbacks(bannerHide)
        val v = banner?.get() ?: return
        banner = null
        val parent = v.parent as? ViewGroup ?: return
        if (animate) {
            v.animate().alpha(0f).translationX(v.width / 3f).setDuration(220).withEndAction { parent.removeView(v) }.start()
        } else {
            parent.removeView(v)
        }
    }

    /** ▶❚❚ opens the announced stream while its banner is up (outside the player, which uses the key itself). */
    private fun handleKey(act: Activity, event: KeyEvent): Boolean {
        if (banner?.get() == null || bannerOn?.get() !== act || act is PlayerActivity || act is MultiViewActivity) return false
        if (event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && event.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY) return false
        if (event.action == KeyEvent.ACTION_UP) {
            val s = recent ?: return true
            dismissBanner(animate = false)
            Nav.watchLive(act, s.slug)
        }
        return true
    }

    // endregion

    // region lifecycle

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
        if (wrapped.put(activity, true) == null) {
            val original = activity.window.callback
            activity.window.callback = object : Window.Callback by original {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                    handleKey(activity, event) || original.dispatchKeyEvent(event)
            }
        }
        schedule()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
        if (bannerOn?.get() === activity) dismissBanner(animate = false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        wrapped.remove(activity)
    }

    // endregion
}
