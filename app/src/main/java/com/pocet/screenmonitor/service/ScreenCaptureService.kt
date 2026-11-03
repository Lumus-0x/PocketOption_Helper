package com.pocet.screenmonitor.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
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
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pocet.screenmonitor.MainActivity
import com.pocet.screenmonitor.R
import com.pocet.screenmonitor.di.ServiceEntryPoint
import com.pocet.screenmonitor.model.BalanceResult
import com.pocet.screenmonitor.util.BalanceHistoryStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "com.pocet.screenmonitor.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.pocet.screenmonitor.STOP_CAPTURE"
        const val ACTION_CAPTURE_STARTED = "com.pocet.screenmonitor.CAPTURE_STARTED"
        const val ACTION_CAPTURE_STOPPED = "com.pocet.screenmonitor.CAPTURE_STOPPED"
        const val ACTION_CAPTURE_ERROR = "com.pocet.screenmonitor.CAPTURE_ERROR"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        const val PREFS_NAME = "capture_prefs"
        const val PREF_KEY_ACCOUNT_TYPE = "target_account_type"
        const val PREF_KEY_FAST_MODE = "fast_mode_enabled"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel_v2"
        private const val LEGACY_CHANNEL_ID = "screen_capture_channel"
        private const val CAPTURE_INTERVAL_MS = 10_000L
        private const val FAST_CAPTURE_INTERVAL_MS = 1_000L
        private const val MIN_WRITE_INTERVAL_MS = 60_000L
        private const val FAST_MIN_WRITE_INTERVAL_MS = 2_000L
        private const val FAST_RETRY_INTERVAL_MS = 5_000L
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 60_000L
        private const val MAX_VD_RETRIES = 3
        private const val VD_RETRY_DELAY_MS = 500L
        private const val PROJECTION_START_DELAY_MS = 300L

        /**
         * True only while this process has a live capture session. This is more
         * reliable than a SharedPreferences flag, which can survive a process
         * death or a reboot and leave the UI stuck in the "running" state.
         */
        @Volatile
        var isRunning: Boolean = false
            private set
    }

    @Volatile
    private var mediaProjection: MediaProjection? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    private var displayMetrics: DisplayMetrics? = null
    private var windowManager: WindowManager? = null

    private val ocrProcessor = OcrProcessor(this)

    // These scopes live for the whole service instance. Individual jobs are
    // cancelled when capture stops; the parent scopes are only cancelled in
    // onDestroy(). Cancelling a parent SupervisorJob here would make any
    // subsequent startCapture() silently do nothing.
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val captureJob = SupervisorJob()
    private val captureScope = CoroutineScope(captureJob + Dispatchers.IO)

    private var projectionSetupJob: Job? = null
    private var captureLoopJob: Job? = null

    private val capturePrefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile
    private var fastMode = false

    @Volatile
    private var isCapturing = false

    @Volatile
    private var isStopping = false

    @Volatile
    private var lastSuccessfulWrite: Long = 0

    @Volatile
    private var lastWriteAttempt: Long = 0

    @Volatile
    private var lastWrittenAmount: Double? = null

    @Volatile
    private var lastWrittenAccountType: BalanceResult.AccountType? = null

    @Volatile
    private var writeInProgress = false

    @Volatile
    private var lastNotificationText: String = ""

    @Volatile
    private var lastNotificationRefresh: Long = 0

    private var targetAccountType: BalanceResult.AccountType = BalanceResult.AccountType.REAL

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_KEY_FAST_MODE) {
            fastMode = capturePrefs.getBoolean(PREF_KEY_FAST_MODE, true)
            Timber.d("Fast mode changed: $fastMode")
            if (isCapturing) {
                updateNotification(
                    if (fastMode) {
                        getString(R.string.notification_text_fast)
                    } else {
                        getString(R.string.notification_text)
                    }
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d(
            "ScreenCaptureService onCreate - PID: ${android.os.Process.myPid()}, " +
                "SDK: ${Build.VERSION.SDK_INT}"
        )

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayMetrics = readDisplayMetrics()
        Timber.d(
            "Display metrics: ${displayMetrics?.widthPixels}x${displayMetrics?.heightPixels}, " +
                "density: ${displayMetrics?.densityDpi}"
        )

        val prefs = capturePrefs
        val accountTypeStr = prefs.getString(PREF_KEY_ACCOUNT_TYPE, "REAL") ?: "REAL"
        targetAccountType = try {
            BalanceResult.AccountType.valueOf(accountTypeStr)
        } catch (e: IllegalArgumentException) {
            Timber.w("Unknown account type '$accountTypeStr', falling back to REAL")
            BalanceResult.AccountType.REAL
        }
        Timber.d("Target account type: $targetAccountType")

        fastMode = prefs.getBoolean(PREF_KEY_FAST_MODE, true)
        Timber.d("Fast mode: $fastMode")
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        createNotificationChannel()
    }

    /**
     * Returns the real size of the display. getMetrics() can return only the
     * app window on some Android 11+ multi-window/foldable configurations,
     * which would crop the captured image.
     */
    @Suppress("DEPRECATION")
    private fun readDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = windowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.maximumWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                metrics.widthPixels = bounds.width()
                metrics.heightPixels = bounds.height()
                metrics.densityDpi = resources.displayMetrics.densityDpi
                return metrics
            }
        }

        if (wm != null) {
            wm.defaultDisplay.getRealMetrics(metrics)
        }

        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            metrics.widthPixels = resources.displayMetrics.widthPixels
            metrics.heightPixels = resources.displayMetrics.heightPixels
            metrics.densityDpi = resources.displayMetrics.densityDpi
        }
        return metrics
    }

    private fun getAppsScriptClient(): AppsScriptClient? {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                ServiceEntryPoint::class.java
            )
            entryPoint.appsScriptClient().get()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get AppsScriptClient from Hilt EntryPoint")
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d(
            "onStartCommand: action=${intent?.action}, flags=$flags, " +
                "startId=$startId, isCapturing=$isCapturing"
        )

        // A null intent means Android restarted the service after the process
        // was killed. MediaProjection consent cannot be restored, so keeping
        // the service alive here would only produce a "did not call
        // startForeground" crash.
        if (intent == null) {
            Timber.w("Service restarted without an intent; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START_CAPTURE -> handleStartCapture(intent)
            ACTION_STOP_CAPTURE -> {
                Timber.d("ACTION_STOP_CAPTURE received")
                stopCaptureAndStopSelf()
            }
            else -> {
                Timber.w("Unknown action: ${intent.action}")
                stopSelf()
            }
        }

        // START_STICKY is wrong for MediaProjection: a sticky restart has no
        // consent token and cannot start capture.
        return START_NOT_STICKY
    }

    private fun handleStartCapture(intent: Intent) {
        if (isCapturing) {
            Timber.w("Already capturing, ignoring duplicate START_CAPTURE")
            return
        }

        // Android 14+ requires a foreground service of type mediaProjection
        // to be running BEFORE MediaProjectionManager.getMediaProjection().
        // Start it first, even if the extras below turn out to be invalid.
        try {
            startForegroundWithType()
        } catch (e: Exception) {
            failCapture("Unable to start foreground service: ${e.message}")
            return
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            failCapture("MediaProjection consent data is missing")
            return
        }

        projectionSetupJob?.cancel()
        projectionSetupJob = serviceScope.launch {
            var createdProjection: MediaProjection? = null
            try {
                // Give the system a moment to register the foreground service
                // on OEM builds that are slow to update the service state.
                delay(PROJECTION_START_DELAY_MS)

                val projection = withContext(Dispatchers.Default) {
                    val manager = getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE
                    ) as MediaProjectionManager
                    manager.getMediaProjection(resultCode, resultData).also {
                        createdProjection = it
                    }
                }

                if (projection == null) {
                    failCapture("MediaProjectionManager returned null")
                    return@launch
                }

                if (isStopping) {
                    projection.stop()
                    return@launch
                }

                startCapture(projection)
            } catch (e: CancellationException) {
                // If cancellation happened after the projection was created,
                // make sure the screen is not left being captured.
                try {
                    createdProjection?.stop()
                } catch (stopError: Exception) {
                    Timber.w(stopError, "Failed to stop orphaned MediaProjection")
                }
                throw e
            } catch (e: Exception) {
                failCapture("Failed to create MediaProjection: ${e.message}")
            }
        }
    }

    /**
     * Starts the foreground service with the correct type on all supported
     * Android versions.
     *
     * The old code used the literal value 8 for Android 10-13. 8 is
     * FOREGROUND_SERVICE_TYPE_LOCATION, not MEDIA_PROJECTION (32). Passing a
     * type that is not declared in the manifest makes startForeground() throw
     * on those versions, so capture never starts.
     */
    private fun startForegroundWithType() {
        Timber.d("Starting foreground service with MEDIA_PROJECTION type")
        val notification = buildNotification(getString(R.string.notification_text))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Timber.d("startForeground SUCCESS")
    }

    private suspend fun startCapture(projection: MediaProjection) {
        Timber.d("Starting screen capture")
        mediaProjection = projection

        val ready = withContext(Dispatchers.Default) {
            try {
                setupImageReader()

                // Android 14+ requires the callback to be registered before
                // createVirtualDisplay().
                registerProjectionCallback(projection)

                createVirtualDisplayWithRetry()
            } catch (e: Exception) {
                Timber.e(e, "Failed to start screen capture")
                false
            }
        }

        if (!ready) {
            failCapture("VirtualDisplay could not be created")
            return
        }

        if (isStopping) {
            releaseCaptureResources()
            return
        }

        isCapturing = true
        isRunning = true
        updateNotification(
            if (fastMode) {
                getString(R.string.notification_text_fast)
            } else {
                getString(R.string.notification_text)
            }
        )
        startCaptureLoop()
        sendCaptureBroadcast(ACTION_CAPTURE_STARTED)
        Timber.d("Screen capture started successfully (fastMode=$fastMode)")
    }

    private fun registerProjectionCallback(projection: MediaProjection) {
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Timber.d("MediaProjection stopped by system")
                serviceScope.launch {
                    // Ignore the callback caused by our own stopCapture().
                    if (!isStopping && isCapturing) {
                        stopCaptureAndStopSelf()
                    }
                }
            }
        }, Handler(Looper.getMainLooper()))
        Timber.d("MediaProjection callback registered")
    }

    private fun setupImageReader() {
        val width = displayMetrics?.widthPixels ?: 1080
        val height = displayMetrics?.heightPixels ?: 1920
        Timber.d("Setting up ImageReader: ${width}x${height}, format=RGBA_8888")

        imageReader?.close()
        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            3
        )
        Timber.d("ImageReader created: surface=${imageReader?.surface != null}")
    }

    private fun createVirtualDisplayWithRetry(): Boolean {
        for (attempt in 1..MAX_VD_RETRIES) {
            Timber.d("VirtualDisplay creation attempt $attempt/$MAX_VD_RETRIES")

            if (createVirtualDisplay()) {
                Timber.d("VirtualDisplay created successfully on attempt $attempt")
                return true
            }

            if (attempt < MAX_VD_RETRIES) {
                Timber.w("VirtualDisplay creation failed, retrying in ${VD_RETRY_DELAY_MS}ms...")
                virtualDisplay?.release()
                virtualDisplay = null
                try {
                    Thread.sleep(VD_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
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
        val surface = imageReader?.surface ?: run {
            Timber.e("ImageReader surface is null - cannot create VirtualDisplay")
            return false
        }

        Timber.d(
            "Creating VirtualDisplay: ${width}x${height}, density=$density, " +
                "surface=${surface != null}"
        )

        return try {
            // AUTO_MIRROR is the flag that actually mirrors the default
            // display. OWN_CONTENT_ONLY means "do not mirror", so combining
            // the two (as the previous version did) can produce a display
            // that never sends frames to the ImageReader.
            val vd = projection.createVirtualDisplay(
                "ScreenCaptureDisplay",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() {
                        Timber.d("VirtualDisplay paused")
                    }

                    override fun onResumed() {
                        Timber.d("VirtualDisplay resumed")
                    }

                    override fun onStopped() {
                        Timber.w("VirtualDisplay stopped")
                        serviceScope.launch {
                            if (!isStopping && isCapturing) {
                                failCapture("VirtualDisplay was stopped by the system")
                            }
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )

            virtualDisplay = vd
            if (vd == null) {
                Timber.e("createVirtualDisplay returned null")
                false
            } else {
                Timber.d(
                    "VirtualDisplay created: displayId=${vd.display?.displayId}"
                )
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception creating VirtualDisplay")
            false
        }
    }

    private fun startCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = captureScope.launch {
            Timber.d("Capture loop started")
            while (isActive && isCapturing) {
                try {
                    refreshNotificationIfNeeded()
                    captureAndProcessFrame()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error in capture loop")
                }
                delayUntilNextCapture()
            }
            Timber.d("Capture loop ended")
        }
    }

    /**
     * Waits for the next capture tick. In normal mode the 10-second wait is
     * split into 1-second chunks so enabling fast mode takes effect almost
     * immediately instead of after the current long delay.
     */
    private suspend fun delayUntilNextCapture() {
        if (fastMode) {
            delay(FAST_CAPTURE_INTERVAL_MS)
            return
        }

        var waited = 0L
        while (isCapturing && !fastMode && waited < CAPTURE_INTERVAL_MS) {
            delay(FAST_CAPTURE_INTERVAL_MS)
            waited += FAST_CAPTURE_INTERVAL_MS
        }
    }

    /**
     * Some Android versions let the user swipe away a foreground-service
     * notification. Re-post the same silent notification periodically so it
     * stays present for the whole capture session.
     */
    private fun refreshNotificationIfNeeded() {
        val text = lastNotificationText
        if (text.isEmpty()) return

        val now = System.currentTimeMillis()
        if (now - lastNotificationRefresh >= NOTIFICATION_REFRESH_INTERVAL_MS) {
            lastNotificationRefresh = now
            updateNotification(text)
        }
    }

    private suspend fun captureAndProcessFrame() {
        val reader = imageReader ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Timber.w(e, "ImageReader is closed")
            return
        } catch (e: Exception) {
            Timber.e(e, "Failed to acquire image")
            return
        }

        if (image == null) {
            // This is normal when the screen has not changed: SurfaceFlinger
            // does not produce a new frame for a static screen. It is NOT a
            // capture failure, so don't tear the session down.
            Timber.v("No new frame available (screen unchanged)")
            return
        }

        try {
            if (!isImageValid(image)) {
                Timber.w("Invalid image acquired, skipping")
                return
            }

            Timber.d(
                "Captured frame: ${image.width}x${image.height}, " +
                    "format=${image.format}, planes=${image.planes.size}"
            )

            val result = ocrProcessor.processImage(image, targetAccountType)
            if (result.isValid()) {
                Timber.d(
                    "Valid balance detected: ${result.accountType} = " +
                        "${result.amount}, confidence=${result.confidence}"
                )

                val now = System.currentTimeMillis()
                val balanceChanged =
                    result.amount != lastWrittenAmount ||
                        result.accountType != lastWrittenAccountType
                val minWriteInterval = if (fastMode) {
                    FAST_MIN_WRITE_INTERVAL_MS
                } else {
                    MIN_WRITE_INTERVAL_MS
                }
                val enoughSinceSuccess = now - lastSuccessfulWrite >= minWriteInterval
                val enoughSinceAttempt =
                    !fastMode || now - lastWriteAttempt >= FAST_RETRY_INTERVAL_MS

                val shouldWrite = if (fastMode) {
                    // Fast mode: write only when the value actually changed.
                    // This keeps Apps Script usage low and avoids duplicate
                    // history entries while still reacting within ~2 seconds.
                    balanceChanged && enoughSinceSuccess && enoughSinceAttempt
                } else {
                    enoughSinceSuccess
                }

                if (shouldWrite) {
                    writeToSheets(result)
                } else {
                    Timber.d(
                        "Skipping write - changed=$balanceChanged, " +
                            "sinceSuccess=${now - lastSuccessfulWrite}ms, " +
                            "sinceAttempt=${now - lastWriteAttempt}ms, fast=$fastMode"
                    )
                }
            } else {
                Timber.d(
                    "No valid balance detected: ${result.accountType}, " +
                        "confidence=${result.confidence}, " +
                        "rawText=${result.rawText.take(100)}"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Error processing frame")
        } finally {
            safeCloseImage(image)
        }
    }

    private fun isImageValid(image: Image): Boolean {
        return try {
            if (image.width <= 0 || image.height <= 0) return false
            if (image.format <= 0) return false

            val planes = image.planes
            if (planes.isEmpty()) return false

            for (plane in planes) {
                if (plane.buffer.capacity() <= 0) return false
            }
            true
        } catch (e: Exception) {
            Timber.w(e, "Image validation failed")
            false
        }
    }

    private fun safeCloseImage(image: Image?) {
        image?.let {
            try {
                it.close()
            } catch (e: IllegalStateException) {
                if (e.message?.contains("invalid write locks") == true ||
                    e.message?.contains("already freed") == true ||
                    e.message?.contains("not locked") == true
                ) {
                    Timber.w("Image close ignored (buffer state): ${e.message}")
                } else {
                    Timber.e(e, "Error closing image")
                }
            } catch (e: Exception) {
                Timber.e(e, "Unexpected error closing image")
            }
        }
    }

    private fun writeToSheets(result: BalanceResult) {
        if (writeInProgress) {
            Timber.d("Skipping write - another write is already in progress")
            return
        }

        val client = getAppsScriptClient() ?: return
        writeInProgress = true
        lastWriteAttempt = System.currentTimeMillis()

        captureScope.launch {
            try {
                val writeResult = client.sendBalance(result)
                if (writeResult.isSuccess) {
                    lastSuccessfulWrite = System.currentTimeMillis()
                    // Clear the retry gate: the 5-second delay is only meant
                    // for failed writes, not for the next real balance change.
                    lastWriteAttempt = 0L
                    lastWrittenAmount = result.amount
                    lastWrittenAccountType = result.accountType
                    BalanceHistoryStore.add(applicationContext, result)
                    Timber.d("Successfully sent to Apps Script")
                    if (isCapturing) {
                        updateNotification("Balance synced: ${result.amount}")
                    }
                } else {
                    // Wait a full retry interval after a failed attempt,
                    // even if the network call itself took a long time.
                    lastWriteAttempt = System.currentTimeMillis()
                    Timber.e("Failed to send to Apps Script: ${writeResult.errorMessage}")
                    if (isCapturing) {
                        updateNotification("Sync failed: ${writeResult.errorMessage}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastWriteAttempt = System.currentTimeMillis()
                Timber.e(e, "Error sending balance to Apps Script")
            } finally {
                writeInProgress = false
            }
        }
    }

    private fun stopCaptureAndStopSelf() {
        if (isStopping) return

        isStopping = true
        val wasCapturing = isCapturing
        try {
            releaseCaptureResources()
            if (wasCapturing) {
                sendCaptureBroadcast(ACTION_CAPTURE_STOPPED)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Timber.e(e, "Error stopping screen capture")
        } finally {
            isStopping = false
        }
        stopSelf()
    }

    /**
     * Releases all capture resources but keeps the service object usable.
     * Called by stopCaptureAndStopSelf() and onDestroy().
     */
    private fun releaseCaptureResources() {
        isCapturing = false
        isRunning = false

        projectionSetupJob?.cancel()
        projectionSetupJob = null

        captureLoopJob?.cancel()
        captureLoopJob = null

        virtualDisplay?.let {
            try {
                it.release()
                Timber.d("Virtual display released")
            } catch (e: Exception) {
                Timber.e(e, "Error releasing virtual display")
            }
        }
        virtualDisplay = null

        imageReader?.let {
            try {
                it.close()
                Timber.d("Image reader closed")
            } catch (e: Exception) {
                Timber.e(e, "Error closing image reader")
            }
        }
        imageReader = null

        val projection = mediaProjection
        mediaProjection = null
        try {
            projection?.stop()
            if (projection != null) {
                Timber.d("Media projection stopped")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error stopping media projection")
        }
    }

    private fun failCapture(message: String) {
        Timber.e("Screen capture failed: $message")
        sendCaptureBroadcast(ACTION_CAPTURE_ERROR, message)
        stopCaptureAndStopSelf()
    }

    private fun sendCaptureBroadcast(action: String, errorMessage: String? = null) {
        val intent = Intent(action).setPackage(packageName)
        if (errorMessage != null) {
            intent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        // Ongoing + NO_CLEAR keeps the notification in the shade while the
        // capture service is alive, including "Clear all".
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun updateNotification(text: String) {
        lastNotificationText = text
        lastNotificationRefresh = System.currentTimeMillis()
        try {
            val notification = buildNotification(text)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.w(e, "Unable to update foreground notification")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // The old channel was created with IMPORTANCE_DEFAULT on Android
            // 14+, which allowed sound/vibration on every notify() update.
            // Recreate the channel as LOW and silent; a new ID is required
            // because importance changes do not override an existing channel.
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            manager.createNotificationChannel(channel)
            Timber.d("Silent notification channel created: $CHANNEL_ID")
        }
    }

    override fun onDestroy() {
        Timber.d("ScreenCaptureService onDestroy")
        val wasCapturing = isCapturing

        isStopping = true
        try {
            releaseCaptureResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Timber.e(e, "Error releasing capture resources in onDestroy")
        } finally {
            isStopping = false
        }

        if (wasCapturing) {
            sendCaptureBroadcast(ACTION_CAPTURE_STOPPED)
        }

        try {
            capturePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {
            Timber.w(e, "Failed to unregister preference listener")
        }

        try {
            ocrProcessor.close()
        } catch (e: Exception) {
            Timber.e(e, "Error closing OCR processor")
        }

        serviceJob.cancel()
        captureJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // stopWithTask="false" means the capture should survive the user
        // swiping the task away. Do not stop the foreground service here.
        Timber.d("Task removed; keeping foreground capture service alive")
        super.onTaskRemoved(rootIntent)
    }
}
