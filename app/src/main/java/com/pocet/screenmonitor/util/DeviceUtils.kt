package com.pocet.screenmonitor.util

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Utility class for device-specific optimizations.
 * Handles phone vs tablet detection, orientation, and screen characteristics
 * to optimize OCR crop regions and processing parameters.
 */
object DeviceUtils {

    /**
     * Determines if the device is a tablet based on screen size.
     * Uses the standard Android definition: smallest width >= 600dp.
     */
    fun isTablet(context: Context): Boolean {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)

        // Convert pixels to dp
        val widthDp = metrics.widthPixels / metrics.density
        val heightDp = metrics.heightPixels / metrics.density
        val smallestWidthDp = minOf(widthDp, heightDp)

        return smallestWidthDp >= 600f
    }

    /**
     * Gets the current orientation of the device.
     */
    fun getOrientation(context: Context): Int {
        return context.resources.configuration.orientation
    }

    /**
     * Checks if the device is in landscape orientation.
     */
    fun isLandscape(context: Context): Boolean {
        return getOrientation(context) == Configuration.ORIENTATION_LANDSCAPE
    }

    /**
     * Returns the screen dimensions in pixels.
     */
    fun getScreenDimensions(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Returns the screen density in DPI.
     */
    fun getScreenDensityDpi(context: Context): Int {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.densityDpi
    }

    /**
     * Returns the screen density (scale factor).
     */
    fun getScreenDensity(context: Context): Float {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.density
    }

    /**
     * Calculates the aspect ratio (width / height).
     */
    fun getAspectRatio(context: Context): Float {
        val (width, height) = getScreenDimensions(context)
        return width.toFloat() / height.toFloat()
    }

    /**
     * Determines the device category for OCR optimization.
     */
    enum class DeviceCategory {
        PHONE_PORTRAIT,
        PHONE_LANDSCAPE,
        TABLET_PORTRAIT,
        TABLET_LANDSCAPE
    }

    /**
     * Gets the device category for OCR crop optimization.
     */
    fun getDeviceCategory(context: Context): DeviceCategory {
        val isTabletDevice = isTablet(context)
        val isLandscapeDevice = isLandscape(context)

        return when {
            isTabletDevice && isLandscapeDevice -> DeviceCategory.TABLET_LANDSCAPE
            isTabletDevice -> DeviceCategory.TABLET_PORTRAIT
            isLandscapeDevice -> DeviceCategory.PHONE_LANDSCAPE
            else -> DeviceCategory.PHONE_PORTRAIT
        }
    }

    /**
     * Optimized crop ratios for the balance widget based on device category.
     * These are percentages of the full screen (0.0 to 1.0).
     *
     * The Pocket Option app places the balance widget in the top-right area.
     * On phones in portrait, it's roughly at 42% left, 3% top, 83% right, 13% bottom.
     * On tablets and landscape, the UI layout may differ.
     */
    data class CropRatios(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    fun getOptimalCropRatios(context: Context): CropRatios {
        val category = getDeviceCategory(context)
        val aspectRatio = getAspectRatio(context)

        return when (category) {
            // Phone portrait (~19:9 to 20:9 aspect ratio)
            DeviceCategory.PHONE_PORTRAIT -> CropRatios(
                left = 0.42f,
                top = 0.03f,
                right = 0.83f,
                bottom = 0.13f
            )

            // Phone landscape - Pocket Option might rotate UI or keep it similar
            DeviceCategory.PHONE_LANDSCAPE -> CropRatios(
                left = 0.55f,  // More rightward in landscape
                top = 0.02f,
                right = 0.90f,
                bottom = 0.15f
            )

            // Tablet portrait (~4:3 or 16:10) - widget might be smaller relative to screen
            DeviceCategory.TABLET_PORTRAIT -> CropRatios(
                left = 0.45f,
                top = 0.02f,
                right = 0.85f,
                bottom = 0.12f
            )

            // Tablet landscape - often has sidebar, widget in top-right of main area
            DeviceCategory.TABLET_LANDSCAPE -> CropRatios(
                left = 0.50f,
                top = 0.02f,
                right = 0.88f,
                bottom = 0.14f
            )
        }
    }

    /**
     * Gets the maximum dimension for bitmap downscaling based on device.
     * Higher DPI devices can use larger max dimension for better OCR accuracy.
     */
    fun getMaxBitmapDimension(context: Context): Int {
        val densityDpi = getScreenDensityDpi(context)
        val isTabletDevice = isTablet(context)

        // Base dimension on screen density
        // xxhdpi (480) and xxxhdpi (640) can handle larger bitmaps
        val baseDimension = when {
            densityDpi >= 640 -> 2560  // xxxhdpi tablets
            densityDpi >= 480 -> 2048  // xxhdpi phones/tablets
            densityDpi >= 320 -> 1920  // xhdpi
            else -> 1280  // mdpi/hdpi
        }

        // Tablets often have more memory, can process larger images
        return if (isTabletDevice) {
            minOf(baseDimension * 3 / 2, 3072)  // Up to 3072 for tablets
        } else {
            baseDimension
        }
    }

    /**
     * Checks if the device has high memory (typically tablets and flagship phones).
     * Used to decide if we can use more aggressive OCR processing.
     */
    fun isHighMemoryDevice(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        // Consider > 4GB as high memory (4000 MB in KB)
        return memInfo.totalMem > 4_000_000_000L
    }
}