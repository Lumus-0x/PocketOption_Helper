package com.pocet.screenmonitor.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pocet.screenmonitor.model.BalanceHistoryEntry
import com.pocet.screenmonitor.model.BalanceResult
import timber.log.Timber
import kotlin.math.abs

/**
 * Local, bounded history of successfully synced balances.
 *
 * Consecutive identical values (same account type and amount) are stored only
 * once, so the normal 60-second re-write does not flood the list.
 */
object BalanceHistoryStore {

    const val MAX_ENTRIES = 25

    private const val PREFS_NAME = "balance_history_prefs"
    private const val KEY_ENTRIES = "entries"

    private val lock = Any()
    private val gson = Gson()

    fun add(context: Context, result: BalanceResult) {
        if (result.amount <= 0.0) return

        synchronized(lock) {
            val entries = read(context).toMutableList()
            val last = entries.lastOrNull()
            if (last != null &&
                last.accountType == result.accountType.name &&
                abs(last.amount - result.amount) < 0.001
            ) {
                return
            }

            entries.add(
                BalanceHistoryEntry(
                    amount = result.amount,
                    accountType = result.accountType.name,
                    timestamp = System.currentTimeMillis()
                )
            )

            while (entries.size > MAX_ENTRIES) {
                entries.removeAt(0)
            }

            write(context, entries)
            Timber.d("Balance history updated: ${entries.size} entries")
        }
    }

    fun getAll(context: Context): List<BalanceHistoryEntry> {
        return synchronized(lock) { read(context) }
    }

    private fun read(context: Context): List<BalanceHistoryEntry> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
            ?: return emptyList()

        return try {
            val type = object : TypeToken<List<BalanceHistoryEntry>>() {}.type
            gson.fromJson<List<BalanceHistoryEntry>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Timber.w(e, "Failed to read balance history")
            emptyList()
        }
    }

    private fun write(context: Context, entries: List<BalanceHistoryEntry>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, gson.toJson(entries))
            .apply()
    }
}
