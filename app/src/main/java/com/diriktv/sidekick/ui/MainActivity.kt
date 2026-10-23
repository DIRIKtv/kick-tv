package com.diriktv.sidekick.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.diriktv.sidekick.R

class MainActivity : FragmentActivity() {
    private lateinit var content: ContentFrame
    private lateinit var navItems: List<TextView>
    private val pages = arrayOfNulls<MainPage>(5)
    private var active = -1
    private var lastBack = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Sidekick) // swap out the launch screen
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.color.bg)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        navItems = listOf(R.id.navHome, R.id.navFollowing, R.id.navBrowse, R.id.navSearch, R.id.navSettings)
            .map { findViewById(it) }
        content.leftExit = { navItems.getOrNull(active) }

        navItems.forEachIndexed { i, item ->
            item.setOnFocusChangeListener { _, has ->
                if (!has) return@setOnFocusChangeListener
                // Only a key press moves between pages. Focus also lands here when a focused card
                // disappears (a list refreshing); that mustn't switch pages, so put it back.
                if (i == active || SystemClock.uptimeMillis() - lastKeyAt < 600) show(i)
                else item.post { navItems[active].requestFocus() }
            }
            item.setOnClickListener { pages[i]?.focusContent() }
            item.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    pages[i]?.focusContent() ?: false
                } else false
            }
        }
        // Back via the dispatcher: Android 16+ no longer calls onBackPressed for apps targeting it.
        onBackPressedDispatcher.addCallback(this) { handleBack() }
        show(0)
        content.post { if (!pages[0]!!.focusContent()) navItems[0].requestFocus() }
        content.postDelayed({ (page(2) as BrowsePage).preload() }, 3000)
    }

    private fun page(i: Int): MainPage = pages[i] ?: when (i) {
        0 -> HomePage(this)
        1 -> FollowingPage(this)
        2 -> BrowsePage(this)
        3 -> SearchPage(this)
        else -> SettingsPage(this) { (pages[0] as? HomePage)?.reload() }
    }.also { pages[i] = it }

    private fun show(i: Int) {
        if (i == active) return
        active = i
        navItems.forEachIndexed { idx, v -> v.isActivated = idx == i }
        val p = page(i)
        content.removeAllViews()
        (p.view.parent as? ViewGroup)?.removeView(p.view)
        content.addView(p.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        p.view.animate().cancel()
        p.view.alpha = 0f
        p.view.translationX = dp(16).toFloat()
        p.view.animate().alpha(1f).translationX(0f).setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        p.onShown()
    }

    /** Each time the app is opened, look for a release (per the update setting) once Home has settled. */
    private fun checkForUpdateOnOpen() {
        if (!com.diriktv.sidekick.data.Updater.openedPending) return
        com.diriktv.sidekick.data.Updater.openedPending = false
        content.postDelayed({
            lifecycleScope.launch {
                val release = com.diriktv.sidekick.data.Updater.checkIfDue(this@MainActivity) ?: return@launch
                if (!isFinishing && hasWindowFocus()) UpdateFlow(this@MainActivity, release).show()
            }
        }, 2500)
    }

    override fun onResume() {
        super.onResume()
        checkForUpdateOnOpen()
        // Favorites may have changed in the player or channel screen.
        if (active == 1) pages[1]?.onShown()
        if (active == 0) pages[0]?.onShown()
        if (active == 4) pages[4]?.onShown()
    }

    private fun handleBack() {
        val focus = currentFocus
        if (focus != null && focus !in navItems) {
            navItems[active].requestFocus()
            return
        }
        if (active != 0) {
            navItems[0].requestFocus()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) {
            finish()
        } else {
            lastBack = now
            android.widget.Toast.makeText(this, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var lastKeyAt = 0L

    // Overriding Activity.dispatchKeyEvent is standard; lint trips on AndroidX's restricted override of it.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) lastKeyAt = SystemClock.uptimeMillis()
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Menu on the home screen refreshes.
        if (keyCode == KeyEvent.KEYCODE_MENU && active == 0) {
            (pages[0] as? HomePage)?.reload()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
