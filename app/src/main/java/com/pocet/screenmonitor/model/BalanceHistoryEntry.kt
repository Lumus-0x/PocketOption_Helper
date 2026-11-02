package com.pocet.screenmonitor.model

import androidx.annotation.Keep

@Keep
data class BalanceHistoryEntry(
    val amount: Double,
    val accountType: String,
    val timestamp: Long
)
