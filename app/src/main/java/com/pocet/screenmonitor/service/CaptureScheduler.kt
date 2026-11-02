package com.pocet.screenmonitor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.pocet.screenmonitor.util.BatteryOptimizationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

class CaptureScheduler(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)
    private val batteryUtils = BatteryOptimizationUtils(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("capture_scheduler_prefs", Context.MODE_PRIVATE)
    private val scheduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val WORK_NAME = "balance_capture_work"
        const val WORK_TAG = "balance_capture"
        const val CAPTURE_INTERVAL_MINUTES = 15L
        const val PREF_KEY_LAST_SCHEDULE = "last_schedule_time"
        const val PREF_KEY_SERVICE_RUNNING = "service_running"
    }

    /**
     * Schedule periodic capture using WorkManager (recommended for Android 16)
     */
    fun schedulePeriodicCapture() {
        scheduleScope.launch {
            // Check constraints
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val captureWork = PeriodicWorkRequest.Builder(CaptureWorker::class.java, CAPTURE_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                captureWork
            )

            prefs.edit().putLong(PREF_KEY_LAST_SCHEDULE, System.currentTimeMillis()).apply()
            Timber.d("Scheduled periodic capture work")
        }
    }

    /**
     * Schedule exact capture using AlarmManager (for more precise timing)
     */
    fun scheduleExactCapture(intervalMinutes: Long = 15) {
        if (!batteryUtils.canScheduleExactAlarms()) {
            Timber.w("Cannot schedule exact alarms - permission not granted")
            return
        }

        scheduleScope.launch {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CaptureWorker::class.java).apply {
                action = CaptureWorker.ACTION_TRIGGER_CAPTURE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val intervalMillis = intervalMinutes * 60 * 1000
            val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMillis

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }

            prefs.edit().putLong(PREF_KEY_LAST_SCHEDULE, System.currentTimeMillis()).apply()
            Timber.d("Scheduled exact capture for $intervalMinutes minutes")
        }
    }

    /**
     * Trigger immediate capture
     */
    fun triggerImmediateCapture() {
        scheduleScope.launch {
            val immediateWork = OneTimeWorkRequest.Builder(CaptureWorker::class.java)
                .addTag(WORK_TAG)
                .build()
            workManager.enqueue(immediateWork)
            Timber.d("Triggered immediate capture")
        }
    }

    /**
     * Cancel all scheduled work
     */
    fun cancelAll() {
        scheduleScope.launch {
            workManager.cancelAllWorkByTag(WORK_TAG)
            workManager.cancelUniqueWork(WORK_NAME)
            Timber.d("Cancelled all capture work")
        }
    }

    /**
     * Persist the last known service state. The authoritative value is
     * [ScreenCaptureService.isRunning]; this flag is kept only for the
     * informational CaptureWorker check.
     */
    fun setServiceRunning(running: Boolean) {
        prefs.edit().putBoolean(PREF_KEY_SERVICE_RUNNING, running).apply()
    }

    /**
     * Check whether screen capture is actually alive in this process.
     *
     * The previous implementation returned a SharedPreferences flag, which
     * survives process death and reboots. After a reboot the UI showed
     * "running" even though MediaProjection consent is gone and the service
     * does not exist.
     */
    fun isServiceRunning(): Boolean {
        return ScreenCaptureService.isRunning
    }

    /**
     * Reschedule on boot
     */
    fun rescheduleOnBoot() {
        scheduleScope.launch {
            // Small delay to let system settle
            delay(5000)
            schedulePeriodicCapture()

            // MediaProjection consent cannot survive a reboot, so capture is
            // never running at this point. Clear any stale flag.
            setServiceRunning(false)
        }
    }
}

/**
 * Worker for periodic screen capture.
 * Note: This worker cannot start screen capture directly because it requires
 * MediaProjection permission which must be granted by user via Activity.
 * Instead, it checks if the service should be running and notifies if not.
 */
class CaptureWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        const val ACTION_TRIGGER_CAPTURE = "com.pocet.screenmonitor.TRIGGER_CAPTURE"
    }

    override fun doWork(): Result {
        Timber.d("CaptureWorker: Checking capture status")

        try {
            // Check if service is supposed to be running
            val prefs = applicationContext.getSharedPreferences("capture_scheduler_prefs", Context.MODE_PRIVATE)
            val isServiceRunning = prefs.getBoolean(CaptureScheduler.PREF_KEY_SERVICE_RUNNING, false)

            if (isServiceRunning) {
                // Service should be running but might have been killed
                // We can't restart it without MediaProjection user consent
                // Just log and return success - the app will handle restart when user opens it
                Timber.d("CaptureWorker: Service should be running, but cannot auto-restart without user consent")
            } else {
                Timber.d("CaptureWorker: Service not scheduled to run")
            }

            return Result.success()
        } catch (e: Exception) {
            Timber.e(e, "CaptureWorker failed")
            return Result.retry()
        }
    }
}