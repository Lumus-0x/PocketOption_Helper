package com.pocet.screenmonitor.service

import android.content.Context
import com.google.gson.Gson
import com.pocet.screenmonitor.model.BalanceResult
import com.pocet.screenmonitor.util.DateUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Client for sending balance data to Google Apps Script Web App.
 *
 * The Apps Script should be deployed as a Web App with:
 * - Execute as: Me
 * - Who has access: Anyone (or Anyone with link)
 *
 * Expected Apps Script doPost(e) format:
 * ```javascript
 * function doPost(e) {
 *   const data = JSON.parse(e.postData.contents);
 *   // data: { date: "dd.MM.yyyy", amount: 1234.56, accountType: "REAL" }
 *   const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName('Plan_30_days');
 *   const dates = sheet.getRange('B6:B35').getValues().flat();
 *   const timeZone = SpreadsheetApp.getActiveSpreadsheet().getSpreadsheetTimeZone();
 *   const target = String(data.date).trim();
 *   const index = dates.findIndex(value => {
 *     if (!value) return false;
 *     const d = value instanceof Date ? value : new Date(value);
 *     if (isNaN(d.getTime())) return false;
 *     return Utilities.formatDate(d, timeZone, 'dd.MM.yyyy') === target;
 *   });
 *   if (index === -1) return ContentService.createTextOutput('Date not found: ' + target);
 *   sheet.getRange(`E${6 + index}`).setValue(Number(data.amount));
 *   return ContentService.createTextOutput('OK');
 * }
 * ```
 */
class AppsScriptClient private constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val dateUtils: DateUtils
) {

    companion object {
        private const val TIMEOUT_SECONDS = 30L
        private const val CONTENT_TYPE = "application/json; charset=utf-8"
        private const val PREFS_NAME = "apps_script_prefs"
        private const val KEY_WEB_APP_URL = "web_app_url"

        fun create(context: Context, dateUtils: DateUtils): AppsScriptClient {
            val client = OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            return AppsScriptClient(context, client, Gson(), dateUtils)
        }

        private fun getWebAppUrl(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_WEB_APP_URL, "") ?: ""
        }
    }

    /**
     * Sends balance data to the Apps Script Web App.
     * Reads URL from SharedPreferences each time to get the latest value.
     *
     * @param result BalanceResult with amount, accountType, and current date
     * @return Result with success status and optional error message
     */
    suspend fun sendBalance(result: BalanceResult): AppsScriptResult {
        val webAppUrl = getWebAppUrl(context)
        if (webAppUrl.isEmpty()) {
            return AppsScriptResult.Error("Web App URL не задан. Укажите URL в настройках приложения.")
        }

        val date = dateUtils.getCurrentDateForSheets()

        val payload = mapOf(
            "date" to date,
            "amount" to result.amount,
            "accountType" to result.accountType.name
        )

        val json = gson.toJson(payload)
        val body = json.toRequestBody(CONTENT_TYPE.toMediaType())
        val request = Request.Builder()
            .url(webAppUrl)
            .post(body)
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Timber.d("Apps Script response: $responseBody")
                    // Apps Script always returns HTTP 200, even for application
                    // errors. Treat explicit error bodies as failures so the
                    // service does not record a write that never reached E.
                    val normalized = responseBody.trim()
                    if (normalized.startsWith("Error:", ignoreCase = true) ||
                        normalized.startsWith("Date not found", ignoreCase = true)
                    ) {
                        AppsScriptResult.Error(normalized)
                    } else {
                        AppsScriptResult.Success(responseBody)
                    }
                } else {
                    Timber.e("Apps Script error: ${response.code} - $responseBody")
                    // Detect HTML error pages (Google login, bot protection, etc.)
                    val errorMessage = if (responseBody.trim().startsWith("<")) {
                        // It's an HTML page - likely Google login/consent or bot protection
                        "HTTP ${response.code}: Получен HTML вместо JSON. Проверьте настройки Web App: Развернуть → Веб-приложение → Доступ: Все"
                    } else {
                        "HTTP ${response.code}: $responseBody"
                    }
                    AppsScriptResult.Error(errorMessage)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Apps Script request failed")
            AppsScriptResult.Error(e.message ?: "Network error")
        }
    }
}

/** Result of Apps Script request */
sealed class AppsScriptResult {
    data class Success(val response: String) : AppsScriptResult()
    data class Error(val message: String) : AppsScriptResult()

    val isSuccess: Boolean
        get() = this is Success

    val errorMessage: String?
        get() = when (this) {
            is Error -> message
            else -> null
        }
}