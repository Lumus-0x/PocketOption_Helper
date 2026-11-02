package com.pocet.screenmonitor.service

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import com.pocet.screenmonitor.model.BalanceResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import timber.log.Timber

class TextParser {

    // Header is rendered as "QT Real USD" or "QT Demo USD". ML Kit can split
    // it into "QT Real" + "USD" or put each word on its own line, so \s*
    // intentionally matches spaces and newlines.
    private val qtRealRegex = Regex("""\bqt\s*real\b""", RegexOption.IGNORE_CASE)
    private val qtDemoRegex = Regex("""\bqt\s*demo\b""", RegexOption.IGNORE_CASE)

    private val qtRealPatterns = listOf(
        "qt real",
        "real usd",
        "real account",
        "real balance"
    )

    private val qtDemoPatterns = listOf(
        "qt demo",
        "demo usd",
        "demo account",
        "demo balance"
    )

    // US format used by Pocket Option: 46,563.80 / 12.40 / 1,234
    private val usAmountPattern = Regex(
        """(?<![\d.,])(\d{1,3}(?:[,\s]\d{3})*(?:\s*[.]\s*\d{1,2})?)(?![\d.,])"""
    )

    // European format: 1.234,56 / 12,40
    private val euAmountPattern = Regex(
        """(?<![\d.,])(\d{1,3}(?:[.\s]\d{3})*(?:\s*[,]\s*\d{1,2})?)(?![\d.,])"""
    )

    // Last resort for OCR that mixed up separators, e.g. "46.563.80".
    private val looseAmountPattern = Regex("""(?<!\d)(\d[\d.,\s]*\d)(?!\d)""")

    /**
     * Parse OCR text result to find balance information.
     *
     * @param text ML Kit text recognition result (ideally from the cropped
     *             top-right balance area).
     * @param targetAccountType If specified, only accept this account type.
     */
    fun parseText(text: Text, targetAccountType: BalanceResult.AccountType? = null): BalanceResult {
        val fullText = text.text.lowercase()
        Timber.d("OCR Full Text: $fullText")

        val detectedType = detectAccountType(fullText)
        if (targetAccountType != null &&
            detectedType != BalanceResult.AccountType.UNKNOWN &&
            detectedType != targetAccountType
        ) {
            // The user is looking at the other account (for example REAL is
            // selected but the screen shows QT Demo). Never write that
            // balance into the REAL row.
            Timber.d("Screen shows $detectedType, target is $targetAccountType - skipping")
            return BalanceResult.unknown(fullText)
        }

        val accountType = targetAccountType ?: detectedType
        if (accountType == BalanceResult.AccountType.UNKNOWN) {
            return BalanceResult.unknown(fullText)
        }

        val keywordFound = hasKeyword(fullText, accountType)

        // Primary: the balance is directly below the account type header.
        var amount = extractAmountBelowKeyword(text, accountType)

        // Fallback: OCR split the header into separate elements. The balance
        // uses the largest font in the cropped area, so pick that amount.
        if (amount == null) {
            amount = extractLargestAmountInBalanceRegion(text)
            if (amount != null) {
                Timber.d("Using largest amount in balance region: $amount")
            }
        }

        // Last fallback: any amount in the text. Confidence is deliberately
        // below BalanceResult.isValid() so it is only kept for diagnostics.
        if (amount == null) {
            amount = extractAnyAmount(fullText)
            if (amount != null) {
                return BalanceResult(accountType, amount, 0.2f, fullText)
            }
            return BalanceResult.unknown(fullText)
        }

        val confidence = calculateConfidence(text, amount, keywordFound)
        return BalanceResult(accountType, amount, confidence, fullText)
    }

    private fun detectAccountType(text: String): BalanceResult.AccountType {
        val hasReal = qtRealRegex.containsMatchIn(text) ||
            qtRealPatterns.any { text.contains(it) }
        val hasDemo = qtDemoRegex.containsMatchIn(text) ||
            qtDemoPatterns.any { text.contains(it) }

        return when {
            hasReal && !hasDemo -> BalanceResult.AccountType.REAL
            hasDemo && !hasReal -> BalanceResult.AccountType.DEMO
            else -> BalanceResult.AccountType.UNKNOWN
        }
    }

    private fun hasKeyword(text: String, accountType: BalanceResult.AccountType): Boolean {
        return when (accountType) {
            BalanceResult.AccountType.REAL ->
                qtRealRegex.containsMatchIn(text) || qtRealPatterns.any { text.contains(it) }

            BalanceResult.AccountType.DEMO ->
                qtDemoRegex.containsMatchIn(text) || qtDemoPatterns.any { text.contains(it) }

            else -> false
        }
    }

    private fun keywordsFor(accountType: BalanceResult.AccountType): List<String> {
        return when (accountType) {
            BalanceResult.AccountType.REAL -> qtRealPatterns
            BalanceResult.AccountType.DEMO -> qtDemoPatterns
            else -> emptyList()
        }
    }

    /**
     * Finds the "QT Real USD" / "QT Demo USD" header and then the amount
     * directly below it. This is the primary strategy because Pocket Option
     * renders the balance under the account type header.
     */
    private fun extractAmountBelowKeyword(
        text: Text,
        accountType: BalanceResult.AccountType
    ): Double? {
        val keywords = keywordsFor(accountType)
        if (keywords.isEmpty()) return null

        val lines = mutableListOf<LineInfo>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                lines.add(LineInfo(line.text, box))
            }
        }
        if (lines.isEmpty()) return null

        val keywordLines = lines.filter { line ->
            val lower = line.text.lowercase()
            keywords.any { lower.contains(it) } ||
                (lower.contains("qt") && (lower.contains("real") || lower.contains("demo")))
        }.filter { line ->
            val lower = line.text.lowercase()
            when (accountType) {
                BalanceResult.AccountType.REAL -> lower.contains("real")
                BalanceResult.AccountType.DEMO -> lower.contains("demo")
                else -> false
            }
        }

        for (keywordLine in keywordLines) {
            val keywordBox = keywordLine.box

            // Same line: "QT Real USD 12.40".
            extractAmountFromLine(keywordLine.text)?.let { return it }

            val candidates = lines.asSequence()
                .filter { it !== keywordLine }
                .filter { it.box.top >= keywordBox.top }
                .filter { it.box.top <= keywordBox.bottom + keywordBox.height() * 6 }
                .filter { horizontalOverlap(keywordBox, it.box) }
                .sortedBy { abs(it.box.top - keywordBox.bottom) }
                .toList()

            for (candidate in candidates) {
                val amount = extractAmountFromLine(candidate.text)
                if (amount != null) {
                    Timber.d(
                        "Found amount $amount below keyword '${keywordLine.text}': '${candidate.text}'"
                    )
                    return amount
                }
            }
        }
        return null
    }

    /**
     * Fallback for a split header: choose the amount rendered with the largest
     * font in the cropped balance area. In the Pocket Option UI the balance is
     * much larger than the header and the chart labels.
     */
    private fun extractLargestAmountInBalanceRegion(text: Text): Double? {
        var bestAmount: Double? = null
        var bestHeight = -1

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val amount = extractAmountFromLine(line.text) ?: continue
                val height = line.boundingBox?.height() ?: 0
                if (height > bestHeight) {
                    bestHeight = height
                    bestAmount = amount
                }
            }
        }
        return bestAmount
    }

    private fun extractAmountFromLine(lineText: String): Double? {
        val cleaned = normalizeOcrDigits(lineText)

        usAmountPattern.find(cleaned)?.let { match ->
            val amount = match.groupValues[1]
                .replace(",", "")
                .replace(" ", "")
                .replace("\u00A0", "")
                .toDoubleOrNull()
            if (amount != null && amount > 0) return amount
        }

        euAmountPattern.find(cleaned)?.let { match ->
            val amount = match.groupValues[1]
                .replace(".", "")
                .replace(" ", "")
                .replace("\u00A0", "")
                .replace(",", ".")
                .toDoubleOrNull()
            if (amount != null && amount > 0) return amount
        }

        looseAmountPattern.find(cleaned)?.let { match ->
            parseLooseAmount(match.groupValues[1])?.let { return it }
        }

        return null
    }

    /**
     * Handles cases like "46.563.80" (OCR dropped a comma) or "46 563,80".
     */
    private fun parseLooseAmount(raw: String): Double? {
        val value = raw.replace(" ", "").replace("\u00A0", "").trim()
        if (value.isEmpty()) return null

        val lastDot = value.lastIndexOf('.')
        val lastComma = value.lastIndexOf(',')
        val lastSeparator = max(lastDot, lastComma)
        if (lastSeparator <= 0 || lastSeparator >= value.length - 1) {
            return value.toDoubleOrNull()?.takeIf { it > 0 }
        }

        val lastGroup = value.substring(lastSeparator + 1)
        if (lastGroup.any { !it.isDigit() }) return null

        // More than two digits after the last separator is not a balance
        // fraction. Chart prices often have 4-5 decimals.
        if (lastGroup.length > 2) {
            if (lastGroup.length != 3) return null
            val withoutSeparators = value.replace(".", "").replace(",", "")
            return withoutSeparators.toDoubleOrNull()?.takeIf { it > 0 }
        }

        val integerPart = value.substring(0, lastSeparator)
            .replace(".", "")
            .replace(",", "")
        val normalized = "$integerPart.$lastGroup"
        return normalized.toDoubleOrNull()?.takeIf { it > 0 }
    }

    /**
     * Fixes common OCR letter/digit confusions, but only inside tokens that
     * already contain a digit. Otherwise "USD" would become "U5D" and the
     * header itself could be parsed as the amount 5.
     */
    private fun normalizeOcrDigits(text: String): String {
        if (text.none { it.isDigit() }) return text
        return text.split(' ').joinToString(" ") { token ->
            if (token.none { it.isDigit() }) {
                token
            } else {
                token
                    .replace('O', '0')
                    .replace('o', '0')
                    .replace('I', '1')
                    .replace('l', '1')
                    .replace('|', '1')
                    .replace('S', '5')
                    .replace('B', '8')
                    .replace('Z', '2')
            }
        }
    }

    private fun extractAnyAmount(text: String): Double? {
        val amounts = mutableListOf<Double>()
        val cleaned = normalizeOcrDigits(text)

        usAmountPattern.findAll(cleaned).forEach { match ->
            match.groupValues[1]
                .replace(",", "")
                .replace(" ", "")
                .replace("\u00A0", "")
                .toDoubleOrNull()
                ?.let { amounts.add(it) }
        }
        if (amounts.isNotEmpty()) return amounts.maxOrNull()

        euAmountPattern.findAll(cleaned).forEach { match ->
            match.groupValues[1]
                .replace(".", "")
                .replace(" ", "")
                .replace("\u00A0", "")
                .replace(",", ".")
                .toDoubleOrNull()
                ?.let { amounts.add(it) }
        }
        return amounts.maxOrNull()
    }

    private fun calculateConfidence(
        text: Text,
        amount: Double,
        keywordFound: Boolean
    ): Float {
        // Without a recognised header the result must stay below
        // BalanceResult.isValid() (0.5), otherwise a balance from the wrong
        // account could be written to the sheet.
        var confidence = if (keywordFound) 0.5f else 0.2f
        if (keywordFound) confidence += 0.3f
        if (amount >= 0.01 && amount <= 1_000_000.0) confidence += 0.1f
        if (text.textBlocks.size > 1) confidence += 0.1f
        return min(confidence, 1.0f)
    }

    private fun horizontalOverlap(a: Rect, b: Rect): Boolean {
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        if (overlap <= 0) return false
        val minWidth = min(a.width(), b.width()).coerceAtLeast(1)
        return overlap.toFloat() / minWidth >= 0.25f
    }

    private data class LineInfo(
        val text: String,
        val box: Rect
    )
}
