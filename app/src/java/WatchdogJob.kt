package com.hevc.codec

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class WatchdogJob : JobService() {

    companion object {
        private const val JOB_ID = 777

        fun schedule(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, WatchdogJob::class.java)
            ).setPeriodic(5 * 60 * 1000, 5 * 60 * 1000)
             .build()
            js.schedule(info)
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val running = am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className.endsWith(".AgentService") }

        if (!running) {
            val i = Intent(this, AgentService::class.java)
            i.action = "RESTART"
            startService(i)
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?) = true
}