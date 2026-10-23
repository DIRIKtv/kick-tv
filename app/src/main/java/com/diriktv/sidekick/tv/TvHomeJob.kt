package com.diriktv.sidekick.tv

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Refreshes the home screen row in the background. */
class TvHomeJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = MainScope().launch {
            runCatching { TvHome.refresh(applicationContext) }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }
}

/** The launcher asks new apps to set up their home screen rows; also re-arm the job after boot. */
class TvHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.media.tv.action.INITIALIZE_PROGRAMS", Intent.ACTION_BOOT_COMPLETED -> TvHome.refreshSoon(context)
        }
    }
}
