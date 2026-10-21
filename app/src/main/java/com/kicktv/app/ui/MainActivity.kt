package com.kicktv.app.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.kicktv.app.R

class MainActivity : FragmentActivity() {
    private lateinit var content: ContentFrame
    private lateinit var navItems: List<TextView>
    private val pages = arrayOfNulls<MainPage>(5)
    private var active = -1
    private var lastBack = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_KickTV) // swap out the launch screen
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(R.color.bg)
        setContentView(R.layout.activity_main)
        content = findViewById(R.id.content)
        navItems = listOf(R.id.navHome, R.id.navFollowing, R.id.navBrowse, R.id.navSearch, R.id.navSettings)
            .map { findViewById(it) }
        content.leftExit = { navItems.getOrNull(active) }

        navItems.forEachIndexed { i, item ->
            item.setOnFocusChangeListener { _, has -> if (has) show(i) }
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
        // Quietly look for a new release a little after startup.
        content.postDelayed({
            lifecycleScope.launch {
                val release = com.kicktv.app.data.Updater.checkIfDue(this@MainActivity) ?: return@launch
                if (!isFinishing) UpdateFlow(this@MainActivity, release).show()
            }
        }, 6000)
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

    override fun onResume() {
        super.onResume()
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Menu on the home screen refreshes.
        if (keyCode == KeyEvent.KEYCODE_MENU && active == 0) {
            (pages[0] as? HomePage)?.reload()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
