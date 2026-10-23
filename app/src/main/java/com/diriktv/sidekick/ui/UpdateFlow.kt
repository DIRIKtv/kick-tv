package com.diriktv.sidekick.ui

import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.diriktv.sidekick.R
import com.diriktv.sidekick.data.ReleaseNotes
import com.diriktv.sidekick.data.Updater
import kotlinx.coroutines.launch
import java.io.File

/** The "update available" panel: download with progress, then hand off to the installer. */
class UpdateFlow(private val act: FragmentActivity, private val release: Updater.Release) {
    private val sheet = OptionSheet(act)
    private var progress = -1
    private var apk: File? = null
    private var error: String? = null

    fun show() {
        sheet.show(OptionSheet.Page("Update available", eyebrow = "Sidekick ${release.version}", subtitle = notes()) {
            listOf(
                OptionSheet.Item("Update now", sub = sizeLabel(), icon = R.drawable.ic_forward) { startDownload() },
                OptionSheet.Item("Not now", icon = R.drawable.ic_close) { it.dismiss() },
                OptionSheet.Item("Skip this version", icon = R.drawable.ic_info) {
                    Updater.skip(act, release.version); it.dismiss()
                },
            )
        })
    }

    private fun notes(): String = ReleaseNotes.forTv(release.notes)

    private fun sizeLabel() = if (release.sizeBytes > 0) String.format("%.1f MB", release.sizeBytes / 1_048_576f) else null

    private fun startDownload() {
        progress = 0
        error = null
        sheet.push(OptionSheet.Page("Update", eyebrow = "Sidekick ${release.version}") {
            when {
                error != null -> listOf(
                    OptionSheet.Item("Try again", sub = error, icon = R.drawable.ic_refresh) { it.back(); startDownload() },
                    OptionSheet.Item("Cancel", icon = R.drawable.ic_close) { it.dismiss() },
                )
                apk != null -> installItems()
                else -> listOf(OptionSheet.Item("Downloading…", value = "$progress%", icon = R.drawable.ic_refresh, shimmer = true) {})
            }
        })
        act.lifecycleScope.launch {
            try {
                apk = Updater.download(act, release) { pct -> progress = pct; if (sheet.isShowing) sheet.refresh() }
            } catch (e: Exception) {
                error = e.message ?: "Download failed"
            }
            if (sheet.isShowing) sheet.refresh()
            if (apk != null && Updater.canInstall(act)) install()
        }
    }

    private fun installItems(): List<OptionSheet.Item> {
        if (Updater.canInstall(act)) {
            return listOf(OptionSheet.Item("Install update", sub = "Ready to install", icon = R.drawable.ic_check) { install() })
        }
        return listOf(
            OptionSheet.Item(
                "Allow Sidekick to install updates",
                sub = "Downloaded. Allow installs, then come back and choose Install.",
                icon = R.drawable.ic_settings,
            ) {
                if (!Updater.openInstallPermission(act)) {
                    Toast.makeText(
                        act,
                        "Open Settings → My Fire TV → Developer options → Install unknown apps, and turn on Sidekick.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            OptionSheet.Item("Install update", icon = R.drawable.ic_check) { install() },
        )
    }

    private fun install() {
        val file = apk ?: return
        if (!Updater.canInstall(act)) {
            sheet.refresh()
            return
        }
        sheet.dismiss()
        runCatching { Updater.install(act, file) }.onFailure {
            Toast.makeText(act, "Couldn't open the installer: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
