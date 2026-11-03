package com.pocet.screenmonitor.service

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.util.Size
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pocet.screenmonitor.model.BalanceResult
import com.pocet.screenmonitor.util.DeviceUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

class OcrProcessor(private val context: Context) {

    // Nullable TextRecognizer - initialization failure won't crash the app
    private var textRecognizer: TextRecognizer? = null
    private var initError: String? = null
    private val processingJob = SupervisorJob()
    private val processingScope = CoroutineScope(processingJob + Dispatchers.IO)
    private var isProcessing = false

    // Cache the last OCR result for an unchanged balance region. The chart
    // animates continuously, but the top-right crop usually stays identical,
    // so this avoids running ML Kit every second in fast mode.
    private var hashBuffer = IntArray(0)
    private var lastFrameHash = 0
    private var lastFrameHashTime = 0L
    private var lastCachedResult: BalanceResult? = null
    private var lastCachedTarget: BalanceResult.AccountType? = null

    // Device-specific crop ratios and max dimension
    private val cropRatios = DeviceUtils.getOptimalCropRatios(context)
    private val maxBitmapDimension = DeviceUtils.getMaxBitmapDimension(context)

    init {
        Timber.d("OcrProcessor initialized for device: ${DeviceUtils.getDeviceCategory(context)}, " +
            "crop ratios: L=${cropRatios.left}, T=${cropRatios.top}, R=${cropRatios.right}, B=${cropRatios.bottom}, " +
            "maxBitmapDimension=$maxBitmapDimension")
        initializeTextRecognizer()
    }

    private fun initializeTextRecognizer() {
        try {
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            Timber.d("TextRecognizer initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize TextRecognizer, OCR will not work")
            initError = "TextRecognizer initialization failed: ${e.message}"
            textRecognizer = null
        }
    }

    /**
     * Process an ImageProxy from ImageReader and return BalanceResult
     * @param targetAccountType If specified, only look for this account type. If null, detect both.
     * Note: Caller is responsible for closing the image
     */
    suspend fun processImage(image: Image, targetAccountType: BalanceResult.AccountType? = null): BalanceResult = withContext(Dispatchers.IO) {
        if (isProcessing) {
            return@withContext BalanceResult.unknown("Already processing")
        }

        // Check if textRecognizer is initialized
        val recognizer = textRecognizer
        if (recognizer == null) {
            val error = initError ?: "OCR not initialized"
            Timber.e("TextRecognizer not available: $error")
            return@withContext BalanceResult.unknown(error)
        }

        isProcessing = true
        var bitmapToRecycle: Bitmap? = null
        var frameHash: Int? = null
        try {
            // Create InputImage directly from media.Image
            // ML Kit supports YUV_420_888 and NV21 formats directly
            val inputImage: InputImage
            if (image.format == ImageFormat.YUV_420_888 || image.format == ImageFormat.NV21) {
                // Use direct media image input for YUV formats
                inputImage = InputImage.fromMediaImage(image, 0)
            } else {
                // For RGBA_8888, copy only the top-right balance region
                // instead of the whole screen. At a 1-second fast-mode poll
                // this avoids allocating/copying 10+ MB per frame.
                val balanceBitmap = imageToBalanceBitmap(image)
                if (balanceBitmap == null) {
                    return@withContext BalanceResult.unknown("Failed to crop image")
                }
                bitmapToRecycle = balanceBitmap

                val hash = contentHash(balanceBitmap)
                frameHash = hash
                getCachedResult(hash, targetAccountType)?.let { cached ->
                    Timber.v("Balance region unchanged, reusing OCR result")
                    return@withContext cached
                }

                inputImage = InputImage.fromBitmap(balanceBitmap, 0)
            }

            // Run text recognition. A timeout prevents a stuck ML Kit task
            // from blocking the capture loop forever.
            val task = recognizer.process(inputImage)
            val mlKitText = Tasks.await(task, OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // Process with TextParser
            val parser = TextParser()
            val result = parser.parseText(mlKitText, targetAccountType)
            frameHash?.let { cacheResult(it, targetAccountType, result) }

            Timber.d("OCR Result: ${result.accountType}, Amount: ${result.amount}, Confidence: ${result.confidence}")

            result
        } catch (e: Exception) {
            Timber.e(e, "OCR processing failed")
            BalanceResult.unknown("Error: ${e.message}")
        } finally {
            isProcessing = false
            // Tasks.await() above guarantees ML Kit has finished with the
            // bitmap, so it can be released now instead of waiting for GC.
            bitmapToRecycle?.recycle()
            // NOTE: Caller (ScreenCaptureService) is responsible for closing the image
        }
    }

    /**
     * Process ImageReader's latest image
     */
    suspend fun processImageReader(imageReader: ImageReader, targetAccountType: BalanceResult.AccountType? = null): BalanceResult = withContext(Dispatchers.IO) {
        val image = imageReader.acquireLatestImage()
            ?: return@withContext BalanceResult.unknown("No image available")

        return@withContext processImage(image, targetAccountType)
    }

    /**
     * Copies only the top-right balance region from an RGBA_8888 frame.
     *
     * Reading the crop directly from the plane buffer is important in fast
     * mode: creating a full-screen Bitmap every second would copy 10+ MB per
     * frame and cause unnecessary GC and battery pressure.
     *
     * Uses device-specific crop ratios from DeviceUtils for optimal
     * compatibility with phones, tablets, and different orientations.
     */
    private fun imageToBalanceBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val width = image.width
        val height = image.height
        if (planes.size != 1 || width <= 0 || height <= 0) {
            Timber.w("Invalid image: ${width}x$height, planes=${planes.size}")
            return null
        }

        val plane = planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) {
            Timber.w("Invalid plane: rowStride=$rowStride, pixelStride=$pixelStride")
            return null
        }

        // Use device-specific crop ratios
        val left = (width * cropRatios.left).toInt()
            .coerceIn(0, width - 1)
        val top = (height * cropRatios.top).toInt()
            .coerceIn(0, height - 1)
        val right = (width * cropRatios.right).toInt()
            .coerceIn(left + 1, width)
        val bottom = (height * cropRatios.bottom).toInt()
            .coerceIn(top + 1, height)
        val cropWidth = right - left
        val cropHeight = bottom - top

        val bitmap = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        val rowData = ByteArray(rowStride)
        val pixels = IntArray(cropWidth)
        val bytesPerPixel = minOf(pixelStride, 4)

        buffer.rewind()
        for (row in 0 until cropHeight) {
            val rowStart = (top + row) * rowStride
            if (rowStart >= buffer.limit()) break

            buffer.position(rowStart)
            val bytesToRead = minOf(rowStride, buffer.remaining())
            buffer.get(rowData, 0, bytesToRead)

            for (col in 0 until cropWidth) {
                val offset = (left + col) * pixelStride
                if (offset + bytesPerPixel > bytesToRead) break

                val r = rowData[offset].toInt() and 0xFF
                val g = if (bytesPerPixel > 1) rowData[offset + 1].toInt() and 0xFF else r
                val b = if (bytesPerPixel > 2) rowData[offset + 2].toInt() and 0xFF else r
                val a = if (bytesPerPixel > 3) rowData[offset + 3].toInt() and 0xFF else 0xFF
                pixels[col] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }

            bitmap.setPixels(pixels, 0, cropWidth, 0, row, cropWidth, 1)
        }
        return bitmap
    }

    /**
     * Cheap content hash of the cropped balance bitmap. A few hundred
     * thousand pixels are scanned in a tight loop, which is far cheaper than
     * a full ML Kit recognition pass.
     */
    private fun contentHash(bitmap: Bitmap): Int {
        val pixelCount = bitmap.width * bitmap.height
        if (pixelCount <= 0) return 0
        if (hashBuffer.size < pixelCount) {
            hashBuffer = IntArray(pixelCount)
        }
        bitmap.getPixels(hashBuffer, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var hash = 1
        for (i in 0 until pixelCount) {
            hash = 31 * hash + hashBuffer[i]
        }
        return hash
    }

    private fun getCachedResult(
        hash: Int,
        targetAccountType: BalanceResult.AccountType?
    ): BalanceResult? {
        val cached = lastCachedResult ?: return null
        if (hash != lastFrameHash || targetAccountType != lastCachedTarget) return null

        // Re-validate a valid result occasionally; retry an invalid one sooner
        // in case ML Kit failed transiently.
        val ttl = if (cached.isValid()) {
            OCR_CACHE_VALID_TTL_MS
        } else {
            OCR_CACHE_INVALID_TTL_MS
        }
        if (System.currentTimeMillis() - lastFrameHashTime > ttl) return null
        return cached
    }

    private fun cacheResult(
        hash: Int,
        targetAccountType: BalanceResult.AccountType?,
        result: BalanceResult
    ) {
        lastFrameHash = hash
        lastFrameHashTime = System.currentTimeMillis()
        lastCachedTarget = targetAccountType
        lastCachedResult = result
    }

    /**
     * Downscale bitmap for faster OCR processing.
     * Uses device-specific max dimension from DeviceUtils for optimal quality/performance balance.
     */
    fun downscaleBitmap(bitmap: Bitmap, maxDimension: Int = -1): Bitmap {
        val effectiveMaxDimension = if (maxDimension > 0) maxDimension else maxBitmapDimension
        val width = bitmap.width
        val height = bitmap.height

        if (width <= effectiveMaxDimension && height <= effectiveMaxDimension) {
            return bitmap
        }

        val scale = if (width > height) {
            effectiveMaxDimension.toFloat() / width
        } else {
            effectiveMaxDimension.toFloat() / height
        }

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun close() {
        processingJob.cancel()
        textRecognizer?.close()
        textRecognizer = null
        lastCachedResult = null
        lastCachedTarget = null
        lastFrameHash = 0
        lastFrameHashTime = 0L
    }

    fun isInitialized(): Boolean {
        return textRecognizer != null
    }

    fun getInitError(): String? {
        return initError
    }

    companion object {
        private const val TAG = "OcrProcessor"
        private const val OCR_TIMEOUT_SECONDS = 20L

        // Reuse a cached OCR result while the crop is pixel-identical.
        private const val OCR_CACHE_VALID_TTL_MS = 60_000L
        private const val OCR_CACHE_INVALID_TTL_MS = 10_000L
    }
}