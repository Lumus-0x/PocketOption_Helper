package com.pocet.screenmonitor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.pocet.screenmonitor.service.CaptureScheduler
import com.pocet.screenmonitor.service.ScreenCaptureService
import com.pocet.screenmonitor.di.ServiceEntryPoint
import com.pocet.screenmonitor.util.BalanceHistoryStore
import com.pocet.screenmonitor.util.BatteryOptimizationUtils
import com.pocet.screenmonitor.util.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var captureScheduler: CaptureScheduler

    @Inject
    lateinit var batteryUtils: BatteryOptimizationUtils

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var btnStartCapture: Button
    private lateinit var btnStopCapture: Button
    private lateinit var btnTestConnection: Button
    private lateinit var btnRequestPermissions: Button
    private lateinit var btnSetWebAppUrl: Button
    private lateinit var btnCopyError: Button
    private lateinit var btnHistory: Button
    private lateinit var btnGithub: Button
    private lateinit var btnTelegram: Button
    private lateinit var switchFastMode: MaterialSwitch
    private lateinit var rgAccountType: RadioGroup
    private lateinit var rbReal: RadioButton
    private lateinit var rbDemo: RadioButton

    // Store last error for copy functionality
    private var lastTestError: String? = null

    // True between the consent result and the service's CAPTURE_STARTED /
    // CAPTURE_ERROR broadcast. Keeps the UI from briefly showing "stopped"
    // while the foreground service is starting.
    private var isStartingCapture = false

    // BroadcastReceiver for service state changes
    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ScreenCaptureService.ACTION_CAPTURE_STARTED -> {
                    Timber.d("Received CAPTURE_STARTED broadcast")
                    isStartingCapture = false
                    captureScheduler.setServiceRunning(true)
                    updateServiceStatusUI()
                    updateStatus(getString(R.string.status_running))
                }
                ScreenCaptureService.ACTION_CAPTURE_STOPPED -> {
                    Timber.d("Received CAPTURE_STOPPED broadcast")
                    isStartingCapture = false
                    captureScheduler.setServiceRunning(false)
                    updateServiceStatusUI()
                    updateStatus(getString(R.string.status_stopped))
                }
                ScreenCaptureService.ACTION_CAPTURE_ERROR -> {
                    val message = intent.getStringExtra(ScreenCaptureService.EXTRA_ERROR_MESSAGE)
                        ?: getString(R.string.status_capture_error, "unknown")
                    Timber.e("Received CAPTURE_ERROR broadcast: $message")
                    isStartingCapture = false
                    captureScheduler.setServiceRunning(false)
                    updateServiceStatusUI()
                    updateStatus(getString(R.string.status_capture_error, message))
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.toast_capture_error, message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Activity Result Launchers
    private val mediaProjectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleMediaProjectionResult(result.resultCode, result.data)
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkBatteryOptimization()
    }

    private val exactAlarmLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkExactAlarmPermission()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Timber.d("Notification permission granted")
        } else {
            Timber.w("Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            initViews()
            initManagers()
            setupClickListeners()
            registerServiceStateReceiver()
            checkPermissionsAndSetup()
        } catch (e: Exception) {
            Timber.e(e, "Fatal error in onCreate")
            if (this::tvStatus.isInitialized) {
                tvStatus.text = "Ошибка инициализации: ${e.message}"
            }
            Toast.makeText(this, "Ошибка запуска: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvLastSync = findViewById(R.id.tvLastSync)
        btnStartCapture = findViewById(R.id.btnStartCapture)
        btnStopCapture = findViewById(R.id.btnStopCapture)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnRequestPermissions = findViewById(R.id.btnRequestPermissions)
        btnSetWebAppUrl = findViewById(R.id.btnSetWebAppUrl)
        btnCopyError = findViewById(R.id.btnCopyError)
        btnHistory = findViewById(R.id.btnHistory)
        btnGithub = findViewById(R.id.btnGithub)
        btnTelegram = findViewById(R.id.btnTelegram)
        switchFastMode = findViewById(R.id.switchFastMode)
        rgAccountType = findViewById(R.id.rgAccountType)
        rbReal = findViewById(R.id.rbReal)
        rbDemo = findViewById(R.id.rbDemo)

        // Load saved account type preference
        loadAccountTypePreference()

        // Load saved Web App URL
        loadWebAppUrl()

        // Load fast mode preference and listen for changes
        loadFastModePreference()

        // Set listener for account type changes
        rgAccountType.setOnCheckedChangeListener { _, checkedId ->
            saveAccountTypePreference(checkedId)
        }

        // Copy error button click listener
        btnCopyError.setOnClickListener { copyErrorToClipboard() }

        updateHistoryButton()
    }

    private fun updateHistoryButton() {
        if (!this::btnHistory.isInitialized) return
        val count = BalanceHistoryStore.getAll(this).size
        btnHistory.text = if (count > 0) {
            getString(R.string.btn_history_count, count)
        } else {
            getString(R.string.btn_history)
        }
    }

    private fun loadFastModePreference() {
        val prefs = getSharedPreferences(ScreenCaptureService.PREFS_NAME, Context.MODE_PRIVATE)
        switchFastMode.isChecked = prefs.getBoolean(ScreenCaptureService.PREF_KEY_FAST_MODE, true)

        switchFastMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit()
                .putBoolean(ScreenCaptureService.PREF_KEY_FAST_MODE, isChecked)
                .apply()
            Timber.d("Fast mode preference: $isChecked")

            Toast.makeText(
                this,
                if (isChecked) R.string.toast_fast_mode_on else R.string.toast_fast_mode_off,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun initManagers() {
        Timber.d("Managers initialized via Hilt")
    }

    private fun setupClickListeners() {
        btnStartCapture.setOnClickListener { requestMediaProjection() }
        btnStopCapture.setOnClickListener { stopCaptureService() }
        btnTestConnection.setOnClickListener { testAppsScriptConnection() }
        btnRequestPermissions.setOnClickListener { requestAllPermissions() }
        btnSetWebAppUrl.setOnClickListener { showWebAppUrlDialog() }
        btnHistory.setOnClickListener { showBalanceHistory() }
        btnGithub.setOnClickListener { openExternalLink(getString(R.string.url_github)) }
        btnTelegram.setOnClickListener { openExternalLink(getString(R.string.url_telegram)) }
    }

    private fun openExternalLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No app available to open $url")
            Toast.makeText(this, R.string.toast_link_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBalanceHistory() {
        val entries = BalanceHistoryStore.getAll(this)

        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_history_title, 0))
                .setMessage(R.string.history_empty)
                .setPositiveButton(R.string.btn_close, null)
                .show()
            return
        }

        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        val historyText = entries.asReversed().joinToString("\n\n") { entry ->
            val accountLabel = if (entry.accountType == "REAL") "QT Real" else "QT Demo"
            val amountText = String.format(Locale.US, "%,.2f", entry.amount)
            getString(
                R.string.history_entry_format,
                amountText,
                accountLabel,
                dateFormat.format(Date(entry.timestamp))
            )
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_history_title, entries.size))
            .setMessage(historyText)
            .setPositiveButton(R.string.btn_close, null)
            .show()
    }

    private fun checkPermissionsAndSetup() {
        lifecycleScope.launch {
            // Check battery optimization
            checkBatteryOptimization()

            // Check exact alarm permission
            checkExactAlarmPermission()

            // Check notification permission
            checkNotificationPermission()

            // Update UI based on service state
            updateServiceStatusUI()
        }
    }

    private fun loadAccountTypePreference() {
        val prefs = getSharedPreferences(ScreenCaptureService.PREFS_NAME, Context.MODE_PRIVATE)
        val accountType = prefs.getString(ScreenCaptureService.PREF_KEY_ACCOUNT_TYPE, "REAL")
        when (accountType) {
            "REAL" -> rbReal.isChecked = true
            "DEMO" -> rbDemo.isChecked = true
        }
    }

    private fun saveAccountTypePreference(checkedId: Int) {
        val accountType = when (checkedId) {
            R.id.rbReal -> "REAL"
            R.id.rbDemo -> "DEMO"
            else -> "REAL"
        }
        val prefs = getSharedPreferences(ScreenCaptureService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(ScreenCaptureService.PREF_KEY_ACCOUNT_TYPE, accountType).apply()
        Timber.d("Saved account type preference: $accountType")

        // If service is running, we need to restart it to pick up the new preference
        if (captureScheduler.isServiceRunning()) {
            updateStatus(getString(R.string.toast_account_type_changed_restart))
        }
    }

    private fun loadWebAppUrl() {
        if (!this::btnSetWebAppUrl.isInitialized || !this::btnTestConnection.isInitialized) {
            return
        }
        val prefs = getSharedPreferences("apps_script_prefs", Context.MODE_PRIVATE)
        val url = prefs.getString("web_app_url", "") ?: ""
        if (url.isNotEmpty()) {
            btnSetWebAppUrl.text = getString(R.string.btn_web_app_url_set)
            btnTestConnection.isEnabled = true
        } else {
            btnSetWebAppUrl.text = getString(R.string.btn_web_app_url_not_set)
            btnTestConnection.isEnabled = false
        }
    }

    private fun saveWebAppUrl(url: String) {
        val prefs = getSharedPreferences("apps_script_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("web_app_url", url).apply()
        Timber.d("Saved Web App URL")

        // Update Hilt's AppsScriptClient with new URL
        updateAppsScriptClientUrl(url)

        btnSetWebAppUrl.text = getString(R.string.btn_web_app_url_set)
        btnTestConnection.isEnabled = true
        Toast.makeText(this, getString(R.string.toast_web_app_url_saved), Toast.LENGTH_SHORT).show()
    }

    private fun updateAppsScriptClientUrl(url: String) {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)
            val client = entryPoint.appsScriptClient().get()
            // Note: AppsScriptClient is immutable, so we can't update it directly.
            // The service will pick up the new URL on next start since it reads from SharedPreferences.
            Timber.d("AppsScriptClient URL will be updated on next service start")
        } catch (e: Exception) {
            Timber.e(e, "Failed to update AppsScriptClient URL")
        }
    }

    private fun showWebAppUrlDialog() {
        val input = EditText(this).apply {
            val prefs = getSharedPreferences("apps_script_prefs", Context.MODE_PRIVATE)
            val currentUrl = prefs.getString("web_app_url", "") ?: ""
            setText(currentUrl)
            hint = getString(R.string.hint_web_app_url)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setMinWidth(400)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_web_app_url_title)
            .setMessage(R.string.dialog_web_app_url_message)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty() && (url.startsWith("https://script.google.com/") || url.startsWith("https://script.googleusercontent.com/"))) {
                    saveWebAppUrl(url)
                } else {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_invalid_web_app_url), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun requestAllPermissions() {
        PermissionUtils.requestAllPermissions(this)

        // Also request special permissions via Settings intents
        if (!batteryUtils.isIgnoringBatteryOptimizations()) {
            val intent = batteryUtils.getRequestBatteryOptimizationExemptionIntent()
            if (intent != null) {
                batteryOptimizationLauncher.launch(intent)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !batteryUtils.canScheduleExactAlarms()) {
            val intent = batteryUtils.getRequestExactAlarmPermissionIntent()
            if (intent != null) {
                exactAlarmLauncher.launch(intent)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !batteryUtils.hasNotificationPermission()) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkBatteryOptimization() {
        if (!batteryUtils.isIgnoringBatteryOptimizations()) {
            updateStatus(getString(R.string.status_need_permissions))
        }
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !batteryUtils.canScheduleExactAlarms()) {
            updateStatus(getString(R.string.status_need_permissions))
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !batteryUtils.hasNotificationPermission()) {
            updateStatus(getString(R.string.status_need_permissions))
        }
    }

    private fun requestMediaProjection() {
        // Ask for the Apps Script URL before showing the system consent
        // dialog. Otherwise the user grants capture permission and the
        // service starts only to fail because there is nowhere to send data.
        val prefs = getSharedPreferences("apps_script_prefs", Context.MODE_PRIVATE)
        val webAppUrl = prefs.getString("web_app_url", "") ?: ""
        if (webAppUrl.isEmpty()) {
            updateStatus(getString(R.string.status_need_web_app_url))
            Toast.makeText(this, getString(R.string.toast_web_app_url_required), Toast.LENGTH_LONG).show()
            showWebAppUrlDialog()
            return
        }

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun handleMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            Timber.d("MediaProjection granted")

            // Check if Web App URL is set
            val prefs = getSharedPreferences("apps_script_prefs", Context.MODE_PRIVATE)
            val webAppUrl = prefs.getString("web_app_url", "") ?: ""
            if (webAppUrl.isEmpty()) {
                updateStatus(getString(R.string.status_need_web_app_url))
                Toast.makeText(this, getString(R.string.toast_web_app_url_required), Toast.LENGTH_LONG).show()
                showWebAppUrlDialog()
                return
            }

            // Start the foreground service with the projection data
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_CAPTURE
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }

            isStartingCapture = true
            try {
                ContextCompat.startForegroundService(this, serviceIntent)

                // Service status will be updated via broadcast receiver when
                // the service actually starts.
                updateServiceStatusUI()
                updateStatus(getString(R.string.status_starting_capture))
                Toast.makeText(this, getString(R.string.toast_capture_starting), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                isStartingCapture = false
                Timber.e(e, "Unable to start ScreenCaptureService")
                val message = e.message ?: "unknown"
                updateServiceStatusUI()
                updateStatus(getString(R.string.status_capture_error, message))
                Toast.makeText(
                    this,
                    getString(R.string.toast_capture_error, message),
                    Toast.LENGTH_LONG
                ).show()
            }
        } else {
            Timber.w("MediaProjection denied or cancelled")
            isStartingCapture = false
            updateStatus(getString(R.string.status_need_permissions))
            Toast.makeText(this, getString(R.string.toast_capture_permission_required), Toast.LENGTH_LONG).show()
        }
    }

    private fun testAppsScriptConnection() {
        val prefs = getSharedPreferences("apps_script_prefs", Context.MODE_PRIVATE)
        val webAppUrl = prefs.getString("web_app_url", "") ?: ""

        if (webAppUrl.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_web_app_url_required), Toast.LENGTH_SHORT).show()
            showWebAppUrlDialog()
            return
        }

        updateStatus(getString(R.string.status_testing_connection))
        btnTestConnection.isEnabled = false
        btnCopyError.visibility = View.GONE
        lastTestError = null

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)
                val client = entryPoint.appsScriptClient().get()

                // Send test payload
                val testResult = com.pocet.screenmonitor.model.BalanceResult(
                    accountType = com.pocet.screenmonitor.model.BalanceResult.AccountType.REAL,
                    amount = 0.0,
                    confidence = 1.0f,
                    rawText = "test"
                )
                val result = client.sendBalance(testResult)

                runOnUiThread {
                    btnTestConnection.isEnabled = true
                    if (result.isSuccess) {
                        updateStatus(getString(R.string.status_connection_success))
                        btnCopyError.visibility = View.GONE
                        Toast.makeText(this@MainActivity, getString(R.string.toast_connection_success), Toast.LENGTH_SHORT).show()
                    } else {
                        val errorMsg = result.errorMessage ?: "Unknown error"
                        lastTestError = errorMsg
                        updateStatus(getString(R.string.status_connection_error))
                        btnCopyError.visibility = View.VISIBLE
                        Toast.makeText(this@MainActivity, getString(R.string.toast_connection_failed, errorMsg), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btnTestConnection.isEnabled = true
                    val errorMsg = e.message ?: "Error"
                    lastTestError = errorMsg
                    updateStatus(getString(R.string.status_connection_error))
                    btnCopyError.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, getString(R.string.toast_connection_failed, errorMsg), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyErrorToClipboard() {
        val errorToCopy = lastTestError ?: getString(R.string.toast_connection_failed, "No error details")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Apps Script Error", errorToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.toast_error_copied), Toast.LENGTH_SHORT).show()
        Timber.d("Error copied to clipboard: $errorToCopy")
    }

    private fun stopCaptureService() {
        // stopService() tears the service down even if it is currently not in
        // the foreground. The service releases MediaProjection/VirtualDisplay
        // in onDestroy() and broadcasts CAPTURE_STOPPED.
        val stopped = stopService(Intent(this, ScreenCaptureService::class.java))
        Timber.d("stopService returned $stopped")
        isStartingCapture = false
        captureScheduler.setServiceRunning(false)

        // Do not call updateServiceStatusUI() here: the service is destroyed
        // asynchronously and ScreenCaptureService.isRunning may still be true
        // for a moment. The CAPTURE_STOPPED broadcast will refresh the UI.
        if (this::btnStartCapture.isInitialized) {
            btnStartCapture.isEnabled = true
        }
        if (this::btnStopCapture.isInitialized) {
            btnStopCapture.isEnabled = false
        }
        updateStatus(getString(R.string.status_stopped))
        Toast.makeText(this, getString(R.string.toast_capture_stopped), Toast.LENGTH_SHORT).show()
    }

    private fun updateServiceStatusUI() {
        // onCreate() can fail while inflating the layout. onResume() still
        // runs afterwards, so every view access must be guarded.
        if (!this::btnStartCapture.isInitialized ||
            !this::btnStopCapture.isInitialized ||
            !this::tvStatus.isInitialized
        ) {
            return
        }

        val isRunning = captureScheduler.isServiceRunning()
        val isStarting = isStartingCapture && !isRunning

        btnStartCapture.isEnabled = !isRunning && !isStarting
        btnStopCapture.isEnabled = isRunning || isStarting

        tvStatus.text = when {
            isRunning -> getString(R.string.status_running)
            isStarting -> getString(R.string.status_starting_capture)
            else -> getString(R.string.status_stopped)
        }
    }

    private fun updateStatus(message: String) {
        Timber.d("Status: $message")
        if (this::tvStatus.isInitialized) {
            tvStatus.text = message
        }
    }

    private fun registerServiceStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_CAPTURE_STARTED)
            addAction(ScreenCaptureService.ACTION_CAPTURE_STOPPED)
            addAction(ScreenCaptureService.ACTION_CAPTURE_ERROR)
        }
        // Context.RECEIVER_NOT_EXPORTED only exists on API 33+. On older
        // versions the 3-argument registerReceiver() overload does not accept
        // the flag at all, so use ContextCompat for a version-safe call.
        ContextCompat.registerReceiver(
            this,
            serviceStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        // The receiver is registered in onCreate() so that CAPTURE_STARTED /
        // CAPTURE_ERROR broadcasts emitted right after the MediaProjection
        // consent dialog closes are not lost while the activity is paused.
        updateServiceStatusUI()
        loadWebAppUrl()
        updateHistoryButton()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(serviceStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was never registered (e.g. onCreate failed early)
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.REQUEST_CODE_PERMISSIONS) {
            val deniedPermissions = mutableListOf<String>()
            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i])
                }
            }

            if (deniedPermissions.isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_all_permissions_granted), Toast.LENGTH_SHORT).show()
            } else {
                val deniedList = deniedPermissions.joinToString(", ") { it.substringAfterLast(".") }
                val msg = getString(R.string.toast_some_permissions_denied_detail, deniedList)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                Timber.w("Denied permissions: $deniedList")
            }
        }
    }
}