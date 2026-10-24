package com.hevc.codec

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val i = Intent(context, AgentService::class.java)
        context.startService(i)
        WatchdogJob.schedule(context)
    }
}