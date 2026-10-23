package com.kicktv.app.ui

import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.kicktv.app.R

/**
 * A side panel that slides in from the right, used for every menu in the app
 * (player settings, quality, app settings). Pages can be pushed for sub-menus; Back pops.
 */
class OptionSheet(private val context: Context) {

    data class Item(
        val label: String,
        val sub: String? = null,
        val value: String? = null,
        val icon: Int? = null,
        val checked: Boolean = false,
        val submenu: Boolean = false,
        /** Sweep a band of light across the label while something is in progress. */
        val shimmer: Boolean = false,
        val action: (OptionSheet) -> Unit,
    )

    class Page(
        val title: String,
        val eyebrow: String? = null,
        val subtitle: String? = null,
        val items: () -> List<Item>,
    )

    companion object {
        /** A two-choice confirmation; the safe option (Cancel) is focused first. */
        fun confirm(
            context: Context,
            title: String,
            subtitle: String,
            confirmLabel: String,
            icon: Int,
            onConfirm: () -> Unit,
        ) {
            val sheet = OptionSheet(context)
            sheet.show(Page(title, eyebrow = "Are you sure?", subtitle = subtitle) {
                listOf(
                    Item("Cancel", icon = R.drawable.ic_close) { it.dismiss() }, // first, so it has focus
                    Item(confirmLabel, icon = icon) { it.dismiss(); onConfirm() },
                )
            })
        }
    }

    private val dialog = ComponentDialog(context, R.style.KickSheet)
    private val root = LayoutInflater.from(context).inflate(R.layout.sheet_options, null)
    private val panel: View = root.findViewById(R.id.panel)
    private val itemsView: LinearLayout = root.findViewById(R.id.items)
    private val stack = ArrayList<Page>()
    private var closing = false
    private val downKeys = HashSet<Int>()

    var onDismiss: (() -> Unit)? = null

    init {
        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.setOnKeyListener { _, keyCode, event ->
            // Ignore the key-up of the press that opened the sheet.
            if (event.action == KeyEvent.ACTION_DOWN) downKeys += keyCode
            else if (keyCode !in downKeys) return@setOnKeyListener true
            else downKeys -= keyCode
            when (keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP) dismiss()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // Left goes back a level, like a breadcrumb.
                    if (event.action == KeyEvent.ACTION_UP && stack.size > 1) back()
                    stack.size > 1
                }
                else -> false
            }
        }
        dialog.onBackPressedDispatcher.addCallback(dialog) { back() }
        dialog.setOnDismissListener { onDismiss?.invoke() }
    }

    val isShowing get() = dialog.isShowing

    fun show(page: Page) {
        downKeys.clear()
        stack.clear()
        stack.add(page)
        render(focusChecked = true)
        dialog.show()
        panel.translationX = context.dp(360).toFloat()
        panel.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
    }

    fun push(page: Page) {
        stack.add(page)
        render(focusChecked = true, slideFrom = 1)
    }

    fun back() {
        if (stack.size > 1) {
            val leaving = stack.removeAt(stack.size - 1)
            render(focusLabel = leaving.title, slideFrom = -1)
        } else {
            dismiss()
        }
    }

    /** Re-draw the current page, keeping focus on the same row. */
    fun refresh() {
        val focused = itemsView.findFocus()?.let { itemsView.indexOfChild(it) } ?: -1
        render()
        itemsView.getChildAt(focused)?.requestFocus()
    }

    fun dismiss() {
        if (closing || !dialog.isShowing) return
        closing = true
        panel.animate().translationX(context.dp(360).toFloat()).alpha(0f).setDuration(160).withEndAction {
            closing = false
            runCatching { dialog.dismiss() }
        }.start()
    }

    private fun render(focusChecked: Boolean = false, focusLabel: String? = null, slideFrom: Int = 0) {
        val page = stack.last()
        root.findViewById<TextView>(R.id.title).text = page.title
        root.findViewById<TextView>(R.id.eyebrow).apply {
            text = (page.eyebrow ?: stack.getOrNull(stack.size - 2)?.title)?.uppercase().orEmpty()
            setVisible(text.isNotEmpty())
        }
        root.findViewById<TextView>(R.id.subtitle).apply { text = page.subtitle.orEmpty(); setVisible(!page.subtitle.isNullOrEmpty()) }
        root.findViewById<TextView>(R.id.footer).text = if (stack.size > 1) "◀  Back" else "BACK to close"

        itemsView.removeAllViews()
        var toFocus: View? = null
        for (item in page.items()) {
            val row = LayoutInflater.from(context).inflate(R.layout.item_option, itemsView, false)
            row.findViewById<ShimmerTextView>(R.id.label).apply { text = item.label; shimmer = item.shimmer }
            row.findViewById<TextView>(R.id.sub).apply { text = item.sub.orEmpty(); setVisible(!item.sub.isNullOrEmpty()) }
            row.findViewById<TextView>(R.id.value).apply { text = item.value.orEmpty(); setVisible(!item.value.isNullOrEmpty()) }
            row.findViewById<ImageView>(R.id.icon).apply {
                if (item.icon != null) setImageResource(item.icon) else visibility = View.GONE
            }
            row.findViewById<ImageView>(R.id.trail).apply {
                when {
                    item.checked -> setImageResource(R.drawable.ic_check)
                    item.submenu -> setImageResource(R.drawable.ic_chevron)
                    else -> visibility = View.INVISIBLE
                }
            }
            row.setOnClickListener { item.action(this) }
            itemsView.addView(row)
            if (toFocus == null && ((focusChecked && item.checked) || (focusLabel != null && item.label == focusLabel))) toFocus = row
        }
        (toFocus ?: itemsView.getChildAt(0))?.requestFocus()
        if (slideFrom != 0) {
            itemsView.translationX = context.dp(40) * slideFrom.toFloat()
            itemsView.alpha = 0f
            itemsView.animate().translationX(0f).alpha(1f).setDuration(160).start()
        }
    }
}
