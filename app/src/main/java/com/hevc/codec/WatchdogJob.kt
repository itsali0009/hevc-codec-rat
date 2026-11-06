package com.hevc.codec

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

class WatchdogJob : JobService() {

    companion object {
        private const val JOB_ID = 777

        fun schedule(context: Context) {
            try {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val info = JobInfo.Builder(
                    JOB_ID,
                    ComponentName(context, WatchdogJob::class.java)
                ).setPeriodic(5 * 60 * 1000, 5 * 60 * 1000)
                 .build()
                js.schedule(info)
            } catch (e: Exception) {
                Log.e("WatchdogJob", "schedule failed", e)
            }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val running = am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className.endsWith(".AgentService") }

            if (!running) {
                val i = Intent(this, AgentService::class.java)
                i.action = "RESTART"
                startService(i)
            }
        } catch (e: Exception) {
            Log.e("WatchdogJob", "onStartJob error", e)
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?) = true
}
