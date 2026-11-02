package com.pocet.screenmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocet.screenmonitor.MainActivity
import com.pocet.screenmonitor.R
import com.pocet.screenmonitor.di.ServiceEntryPoint
import com.pocet.screenmonitor.model.BalanceResult
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "com.pocet.screenmonitor.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.pocet.screenmonitor.STOP_CAPTURE"
        const val ACTION_CAPTURE_STARTED = "com.pocet.screenmonitor.CAPTURE_STARTED"
        const val ACTION_CAPTURE_STOPPED = "com.pocet.screenmonitor.CAPTURE_STOPPED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val PREFS_NAME = "capture_prefs"
        const val PREF_KEY_ACCOUNT_TYPE = "target_account_type"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val CAPTURE_INTERVAL_MS = 10000L // 10 seconds
        private const val MAX_VD_RETRIES = 3
        private const val VD_RETRY_DELAY_MS = 500L
        private const val TEST_CAPTURE_TIMEOUT_MS = 3000L
        private const val MAX_CONSECUTIVE_FAILURES = 5
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayMetrics: DisplayMetrics? = null
    private var windowManager: WindowManager? = null

    private val ocrProcessor = OcrProcessor()
    private val textParser = TextParser()
    private val captureJob = SupervisorJob()
    private val mainJob = SupervisorJob()
    private val captureScope = CoroutineScope(captureJob + Dispatchers.IO)
    private val mainScope = CoroutineScope(mainJob + Dispatchers.Main)
    private var isCapturing = false
    private var lastSuccessfulWrite: Long = 0
    private val MIN_WRITE_INTERVAL_MS = 60000L // Minimum 1 minute between writes
    private var targetAccountType: BalanceResult.AccountType = BalanceResult.AccountType.REAL
    private var consecutiveFailures = 0
    private var lastSuccessfulCaptureTime: Long = 0
    private var captureVerificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("ScreenCaptureService onCreate - PID: ${android.os.Process.myPid()}, SDK: ${Build.VERSION.SDK_INT}")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(displayMetrics!!)

        // Load target account type preference
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accountTypeStr = prefs.getString(PREF_KEY_ACCOUNT_TYPE, "REAL") ?: "REAL"
        targetAccountType = BalanceResult.AccountType.valueOf(accountTypeStr)
        Timber.d("Target account type: $targetAccountType")
        Timber.d("Display metrics: ${displayMetrics?.widthPixels}x${displayMetrics?.heightPixels}, density: ${displayMetrics?.densityDpi}")

        createNotificationChannel()
    }

    private fun getAppsScriptClient(): AppsScriptClient? {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)
            entryPoint.appsScriptClient().get()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get AppsScriptClient from Hilt EntryPoint")
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand: action=${intent?.action}, flags=$flags, startId=$startId, isCapturing=$isCapturing")

        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                // Prevent duplicate start if already capturing
                if (isCapturing) {
                    Timber.w("Already capturing, ignoring duplicate START_CAPTURE")
                    return START_STICKY
                }

                // Get MediaProjection from result code and data
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                Timber.d("MediaProjection data: resultCode=$resultCode, resultData=${resultData != null}")

                if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
                    // Start foreground service FIRST with correct type (required on Android 14+)
                    // Then create MediaProjection
                    startForegroundServiceWithType()

                    // Small delay to ensure foreground service is fully active (fixes Android 14+ race condition)
                    mainScope.launch {
                        try {
                            delay(500) // Wait for foreground service to be fully registered
                        } catch (e: Exception) {
                            Timber.d("Delay interrupted")
                            return@launch
                        }

                        try {
                            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            Timber.d("Getting MediaProjection from manager")
                            val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                            if (projection != null) {
                                Timber.d("MediaProjection created successfully, token: ${projection.token}")
                                startCapture(projection)
                            } else {
                                Timber.e("Failed to create MediaProjection - returned null")
                                sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                                stopSelf()
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error creating MediaProjection")
                            sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                            stopSelf()
                        }
                    }
                } else {
                    Timber.e("Invalid MediaProjection data: resultCode=$resultCode, resultData=${resultData != null}")
                    sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                    stopSelf()
                }
            }
            ACTION_STOP_CAPTURE -> {
                Timber.d("ACTION_STOP_CAPTURE received")
                stopCapture()
                sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * Start foreground service with MEDIA_PROJECTION type (required on Android 14+)
     * Must be called BEFORE creating MediaProjection
     */
    private fun startForegroundServiceWithType() {
        Timber.d("Starting foreground service with MEDIA_PROJECTION type")

        // Create intent to open MainActivity when notification is tapped
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29): use startForeground with foregroundServiceType
            // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION = 8 (added in API 34)
            val mediaProjectionType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                // For Android 10-13, use the constant value directly
                8 // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            Timber.d("Calling startForeground with type: $mediaProjectionType (SDK: ${Build.VERSION.SDK_INT})")
            try {
                startForeground(NOTIFICATION_ID, notification, mediaProjectionType)
                Timber.d("startForeground SUCCESS")
            } catch (e: Exception) {
                Timber.e(e, "startForeground FAILED: ${e.message}")
                throw e
            }
        } else {
            Timber.d("Calling startForeground without type (SDK < 29)")
            try {
                startForeground(NOTIFICATION_ID, notification)
                Timber.d("startForeground SUCCESS (no type)")
            } catch (e: Exception) {
                Timber.e(e, "startForeground FAILED (no type): ${e.message}")
                throw e
            }
        }
    }

    private fun startCapture(projection: MediaProjection) {
        Timber.d("Starting screen capture, projection token: ${projection.token}")
        mediaProjection = projection
        consecutiveFailures = 0

        try {
            setupImageReader()

            // CRITICAL: Register MediaProjection callback BEFORE creating VirtualDisplay
            // Required on Android 14+ (API 34+)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Timber.d("MediaProjection stopped by system")
                    mainScope.launch {
                        stopCapture()
                        sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                        stopSelf()
                    }
                }
            }, Handler(Looper.getMainLooper()))
            Timber.d("MediaProjection callback registered")

            // Create VirtualDisplay with retry logic
            val vdCreated = createVirtualDisplayWithRetry()

            if (!vdCreated || virtualDisplay == null) {
                Timber.e("VirtualDisplay creation failed after all retries!")
                sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                stopCapture()
                stopSelf()
                return
            }

            // CRITICAL: Verify capture works immediately after VirtualDisplay creation
            val captureVerified = verifyCaptureWorks()
            if (!captureVerified) {
                Timber.e("Capture verification failed - VirtualDisplay not producing frames!")
                sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                stopCapture()
                stopSelf()
                return
            }

            isCapturing = true
            // Foreground already started in onStartCommand, just update notification if needed
            startCaptureLoop()
            startCaptureWatchdog()

            // Notify that capture started successfully
            sendBroadcast(Intent(ACTION_CAPTURE_STARTED))
            Timber.d("Screen capture started successfully, capture loop running")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start screen capture")
            sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
            stopCapture()
            stopSelf()
        }
    }

    private fun setupImageReader() {
        val width = displayMetrics?.widthPixels ?: 1080
        val height = displayMetrics?.heightPixels ?: 1920
        Timber.d("Setting up ImageReader: ${width}x${height}, format=RGBA_8888")

        try {
            // Close existing ImageReader if any
            imageReader?.close()

            imageReader = ImageReader.newInstance(
                width, height,
                PixelFormat.RGBA_8888,
                3 // Max images - increased buffer for reliability
            ).apply {
                // We don't use OnImageAvailableListener - instead we use periodic capture in startCaptureLoop()
                // This avoids race conditions and double-processing
            }
            Timber.d("ImageReader created successfully: ${imageReader?.surface != null}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create ImageReader")
            throw e
        }
    }

    private fun createVirtualDisplayWithRetry(): Boolean {
        for (attempt in 1..MAX_VD_RETRIES) {
            Timber.d("VirtualDisplay creation attempt $attempt/$MAX_VD_RETRIES")

            val success = createVirtualDisplay()
            if (success) {
                Timber.d("VirtualDisplay created successfully on attempt $attempt")
                return true
            }

            if (attempt < MAX_VD_RETRIES) {
                Timber.w("VirtualDisplay creation failed, retrying in ${VD_RETRY_DELAY_MS}ms...")
                try {
                    Thread.sleep(VD_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Timber.d("Retry sleep interrupted")
                    return false
                }
                // Recreate ImageReader on retry to ensure fresh surface
                setupImageReader()
            }
        }

        Timber.e("All VirtualDisplay creation attempts failed")
        return false
    }

    private fun createVirtualDisplay(): Boolean {
        val projection = mediaProjection ?: return false
        val width = displayMetrics?.widthPixels ?: 1080
        val height = displayMetrics?.heightPixels ?: 1920
        val density = displayMetrics?.densityDpi ?: 320
        val surface = imageReader?.surface

        Timber.d("Creating VirtualDisplay: ${width}x${height}, density=$density, surface=${surface != null}, projectionValid=${projection != null}")

        // Validate surface
        if (surface == null) {
            Timber.e("ImageReader surface is null - cannot create VirtualDisplay")
            return false
        }

        // Validate projection
        if (projection == null) {
            Timber.e("MediaProjection is null - cannot create VirtualDisplay")
            return false
        }

        // Check if projection is still valid
        if (!isProjectionValid(projection)) {
            Timber.e("MediaProjection appears invalid (no token or stopped)")
            return false
        }

        try {
            val vd = projection.createVirtualDisplay(
                "ScreenCaptureDisplay",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                    or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() {
                        Timber.d("VirtualDisplay paused")
                    }
                    override fun onResumed() {
                        Timber.d("VirtualDisplay resumed")
                    }
                    override fun onStopped() {
                        Timber.d("VirtualDisplay stopped")
                    }
                },
                Handler(Looper.getMainLooper())
            )

            virtualDisplay = vd

            if (vd == null) {
                Timber.e("Failed to create virtual display - returned null")
                return false
            } else {
                Timber.d("VirtualDisplay created successfully: display=${vd.display}, displayId=${vd.display?.displayId}")
                return true
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception creating virtual display: ${e.message}, cause: ${e.cause?.message}, class: ${e.javaClass.name}")
            return false
        }
    }

    private fun isProjectionValid(projection: MediaProjection): Boolean {
        try {
            // Check if projection has a valid token
            val tokenField = MediaProjection::class.java.getDeclaredField("mToken")
            tokenField.isAccessible = true
            val token = tokenField.get(projection)
            return token != null
        } catch (e: Exception) {
            Timber.w("Could not verify projection token: ${e.message}")
            return true // Assume valid if we can't check
        }
    }

    private fun verifyCaptureWorks(): Boolean {
        Timber.d("Verifying capture works...")
        val reader = imageReader ?: return false

        // Try to acquire a frame with timeout
        val startTime = System.currentTimeMillis()
        var image: Image? = null

        while (System.currentTimeMillis() - startTime < TEST_CAPTURE_TIMEOUT_MS) {
            image = reader.acquireLatestImage()
            if (image != null) {
                break
            }
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                break
            }
        }

        if (image != null) {
            Timber.d("Capture verification SUCCESS: Got frame ${image.width}x${image.height}, format=${image.format}")
            image.close()
            lastSuccessfulCaptureTime = System.currentTimeMillis()
            return true
        } else {
            Timber.e("Capture verification FAILED: No frame acquired within ${TEST_CAPTURE_TIMEOUT_MS}ms")
            return false
        }
    }

    private fun startCaptureLoop() {
        captureScope.launch {
            while (isCapturing && captureScope.isActive) {
                try {
                    captureAndProcessFrame()
                    consecutiveFailures = 0 // Reset on success
                } catch (e: Exception) {
                    Timber.e(e, "Error in capture loop")
                    consecutiveFailures++
                }
                delay(CAPTURE_INTERVAL_MS)
            }
        }
    }

    private fun startCaptureWatchdog() {
        captureVerificationJob?.cancel()
        captureVerificationJob = mainScope.launch {
            while (isCapturing && mainScope.isActive) {
                delay(30000) // Check every 30 seconds

                if (!isCapturing) break

                val timeSinceLastCapture = System.currentTimeMillis() - lastSuccessfulCaptureTime
                if (timeSinceLastCapture > 60000) { // No successful capture for 60 seconds
                    Timber.w("Watchdog: No successful capture for ${timeSinceLastCapture}ms, consecutiveFailures=$consecutiveFailures")

                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Timber.e("Watchdog: Too many consecutive failures ($consecutiveFailures), restarting capture...")
                        mainScope.launch {
                            restartCapture()
                        }
                        break
                    }
                }
            }
        }
    }

    private fun restartCapture() {
        Timber.d("Restarting capture...")
        val wasCapturing = isCapturing
        if (wasCapturing) {
            stopCaptureInternal()

            // Small delay before restart
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                return
            }

            if (mediaProjection != null) {
                startCapture(mediaProjection!!)
            }
        }
    }

    private suspend fun captureAndProcessFrame() {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage()
        if (image == null) {
            Timber.w("acquireLatestImage returned null - no frame available")
            consecutiveFailures++
            return
        }

        lastSuccessfulCaptureTime = System.currentTimeMillis()
        Timber.d("Captured frame: ${image.width}x${image.height}, format=${image.format}, planes=${image.planes.size}")

        try {
            // Process with OCR - pass target account type for focused detection
            val result = ocrProcessor.processImage(image, targetAccountType)

            if (result.isValid()) {
                Timber.d("Valid balance detected: ${result.accountType} = ${result.amount}, confidence=${result.confidence}")

                // Check if enough time has passed since last write
                val now = System.currentTimeMillis()
                if (now - lastSuccessfulWrite >= MIN_WRITE_INTERVAL_MS) {
                    writeToSheets(result)
                } else {
                    Timber.d("Skipping write - too soon since last write (${now - lastSuccessfulWrite}ms)")
                }
            } else {
                Timber.d("No valid balance detected: ${result.accountType}, confidence: ${result.confidence}, rawText: ${result.rawText?.take(100)}")
            }
        } finally {
            image.close()
        }
    }

    private fun writeToSheets(result: BalanceResult) {
        val client = getAppsScriptClient() ?: return

        captureScope.launch {
            val writeResult = client.sendBalance(result)
            if (writeResult.isSuccess) {
                Timber.d("Successfully sent to Apps Script")
                lastSuccessfulWrite = System.currentTimeMillis()
                updateNotification("Balance synced: ${result.amount}")
            } else {
                Timber.e("Failed to send to Apps Script: ${writeResult.errorMessage}")
                updateNotification("Sync failed: ${writeResult.errorMessage}")
            }
        }
    }

    private fun stopCapture() {
        Timber.d("Stopping screen capture")
        isCapturing = false
        stopCaptureInternal()
    }

    private fun stopCaptureInternal() {
        // Cancel coroutines safely
        captureJob.cancel()
        mainJob.cancel()
        captureVerificationJob?.cancel()

        // Release virtual display
        virtualDisplay?.let {
            try {
                it.release()
                Timber.d("Virtual display released")
            } catch (e: Exception) {
                Timber.e(e, "Error releasing virtual display")
            }
        }
        virtualDisplay = null

        // Close image reader
        imageReader?.let {
            try {
                it.close()
                Timber.d("Image reader closed")
            } catch (e: Exception) {
                Timber.e(e, "Error closing image reader")
            }
        }
        imageReader = null

        // Stop media projection
        mediaProjection?.let {
            try {
                it.stop()
                Timber.d("Media projection stopped")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping media projection")
            }
        }
        mediaProjection = null

        // Close OCR processor
        try {
            ocrProcessor.close()
            Timber.d("OCR processor closed")
        } catch (e: Exception) {
            Timber.e(e, "Error closing OCR processor")
        }

        // Stop foreground service
        try {
            stopForeground(true)
            Timber.d("Foreground service stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping foreground service")
        }

        // Notify that capture stopped
        sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use IMPORTANCE_DEFAULT for foreground service visibility on Android 14+
            // LOW importance may hide the notification on some OEM skins (HyperOS, MIUI, etc.)
            val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                NotificationManager.IMPORTANCE_DEFAULT
            } else {
                NotificationManager.IMPORTANCE_LOW
            }

            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                importance
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableVibration(false)
                // sound and audioAttributes are left as default (null) for silent notification
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Timber.d("Notification channel created with importance: $importance")
        }
    }

    override fun onDestroy() {
        Timber.d("ScreenCaptureService onDestroy")
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.d("Task removed, stopping service")
        stopCapture()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}