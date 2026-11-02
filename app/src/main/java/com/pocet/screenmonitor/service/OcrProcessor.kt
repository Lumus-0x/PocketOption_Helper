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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

class OcrProcessor {

    // Nullable TextRecognizer - initialization failure won't crash the app
    private var textRecognizer: TextRecognizer? = null
    private var initError: String? = null
    private val processingJob = SupervisorJob()
    private val processingScope = CoroutineScope(processingJob + Dispatchers.IO)
    private var isProcessing = false

    init {
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
        try {
            // Create InputImage directly from media.Image
            // ML Kit supports YUV_420_888 and NV21 formats directly
            val inputImage: InputImage
            if (image.format == ImageFormat.YUV_420_888 || image.format == ImageFormat.NV21) {
                // Use direct media image input for YUV formats
                inputImage = InputImage.fromMediaImage(image, 0)
            } else {
                // For RGBA_8888, convert to Bitmap first
                val bitmap = imageToBitmap(image)
                if (bitmap == null) {
                    return@withContext BalanceResult.unknown("Failed to convert image")
                }
                // Downscale for faster OCR
                val scaledBitmap = downscaleBitmap(bitmap)
                inputImage = InputImage.fromBitmap(scaledBitmap, 0)
            }

            // Run text recognition. A timeout prevents a stuck ML Kit task
            // from blocking the capture loop forever.
            val task = recognizer.process(inputImage)
            val mlKitText = Tasks.await(task, OCR_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // Process with TextParser
            val parser = TextParser()
            val result = parser.parseText(mlKitText, targetAccountType)

            Timber.d("OCR Result: ${result.accountType}, Amount: ${result.amount}, Confidence: ${result.confidence}")

            result
        } catch (e: Exception) {
            Timber.e(e, "OCR processing failed")
            BalanceResult.unknown("Error: ${e.message}")
        } finally {
            isProcessing = false
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
     * Convert Android Media Image to Bitmap (fallback for RGBA_8888)
     * Handles RGBA_8888 format (from ImageReader with PixelFormat.RGBA_8888)
     * Note: For YUV_420_888, we use InputImage.fromMediaImage directly in processImage
     */
    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val width = image.width
        val height = image.height

        // Handle RGBA_8888 format (from ImageReader with PixelFormat.RGBA_8888)
        if (image.format == android.graphics.PixelFormat.RGBA_8888 || planes.size == 1) {
            val plane = planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            if (rowStride <= 0 || pixelStride <= 0 || width <= 0 || height <= 0) {
                Timber.w("Invalid image plane: ${width}x$height, rowStride=$rowStride, pixelStride=$pixelStride")
                return null
            }

            buffer.rewind()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // Fast path: rows are tightly packed with no padding.
            if (pixelStride == 4 &&
                rowStride == width * 4 &&
                buffer.remaining() >= rowStride * height
            ) {
                bitmap.copyPixelsFromBuffer(buffer)
                return bitmap
            }

            // Slow path: GPU/hardware buffers often pad each row to an
            // alignment boundary. copyPixelsFromBuffer() would shift pixels
            // on every row, so copy row by row and skip the padding.
            val rowData = ByteArray(rowStride)
            val pixels = IntArray(width)
            val bytesPerPixel = minOf(pixelStride, 4)

            for (row in 0 until height) {
                val rowStart = row * rowStride
                if (rowStart >= buffer.limit()) break

                buffer.position(rowStart)
                val bytesToRead = minOf(rowStride, buffer.remaining())
                buffer.get(rowData, 0, bytesToRead)

                for (col in 0 until width) {
                    val offset = col * pixelStride
                    if (offset + bytesPerPixel > bytesToRead) break

                    val r = rowData[offset].toInt() and 0xFF
                    val g = if (bytesPerPixel > 1) rowData[offset + 1].toInt() and 0xFF else r
                    val b = if (bytesPerPixel > 2) rowData[offset + 2].toInt() and 0xFF else r
                    val a = if (bytesPerPixel > 3) rowData[offset + 3].toInt() and 0xFF else 0xFF
                    pixels[col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }

                bitmap.setPixels(pixels, 0, width, 0, row, width, 1)
            }
            return bitmap
        }

        // For YUV_420_888, we don't convert to Bitmap here
        // Instead, processImage uses InputImage.fromMediaImage directly
        Timber.w("Unsupported image format for bitmap conversion: ${image.format}, planes: ${planes.size}")
        return null
    }

    /**
     * Downscale bitmap for faster OCR processing
     */
    fun downscaleBitmap(bitmap: Bitmap, maxDimension: Int = 1920): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        val scale = if (width > height) {
            maxDimension.toFloat() / width
        } else {
            maxDimension.toFloat() / height
        }

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun close() {
        processingJob.cancel()
        textRecognizer?.close()
        textRecognizer = null
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
    }
}