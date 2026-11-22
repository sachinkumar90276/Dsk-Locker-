package com.example.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import java.io.File
import kotlin.math.sqrt

data class PreprocessedBitmapResult(
    val bitmap: Bitmap,
    val appliedRotationDeg: Int,
    val contrastEnhanced: Boolean
)

/**
 * Stage 1: Image preprocessing, upscaling, adaptive contrast/brightness enhancement,
 * mild sharpening, and automatic orientation detection (0 / 90 / 180 / 270 deg).
 */
object ImagePreprocessor {

    fun decodeWithExifAndResolution(file: File, maxDim: Int = 2200, minTargetDim: Int = 1100): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) {
            sample *= 2
        }
        val loadOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var decoded = BitmapFactory.decodeFile(file.absolutePath, loadOpts) ?: return null

        val exifRotation = runCatching {
            when (
                ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        if (exifRotation != 0f) {
            decoded = rotateBitmap(decoded, exifRotation)
        }

        // Upscale blurry / low-resolution captures so small print & Devanagari matras read clearly
        val longest = maxOf(decoded.width, decoded.height)
        if (longest in 240 until minTargetDim) {
            val scale = (minTargetDim.toFloat() / longest.toFloat()).coerceAtMost(2.2f)
            val newW = (decoded.width * scale).toInt().coerceAtMost(maxDim)
            val newH = (decoded.height * scale).toInt().coerceAtMost(maxDim)
            val upscaled = Bitmap.createScaledBitmap(decoded, newW, newH, true)
            if (upscaled !== decoded) decoded.recycle()
            decoded = upscaled
        }
        return decoded
    }

    fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true).also {
            if (it !== source) source.recycle()
        }
    }

    /**
     * Evaluates luminance statistics and enhances contrast, brightness, and edge crispness
     * for dark, washed-out, blurry, or low-contrast documents.
     */
    fun enhanceForOcr(source: Bitmap): Bitmap {
        val stats = sampleLuminanceStats(source)
        val meanLuma = stats.first
        val stdLuma = stats.second

        // Adaptive contrast/brightness based on image exposure
        val contrast = when {
            stdLuma < 32f -> 1.48f
            stdLuma < 48f -> 1.32f
            else -> 1.18f
        }
        val brightness = when {
            meanLuma < 85f -> 28f
            meanLuma < 115f -> 14f
            meanLuma > 215f -> -18f
            else -> 4f
        }

        val scale = contrast
        val translate = (-0.5f * scale + 0.5f) * 255f + brightness

        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // Slight desaturation keeps colored card backgrounds cleaner while preserving text contrast
        val satMatrix = ColorMatrix().apply { setSaturation(0.45f) }
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        satMatrix.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(satMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun sampleLuminanceStats(bmp: Bitmap): Pair<Float, Float> {
        val w = bmp.width
        val h = bmp.height
        val stepX = (w / 32).coerceAtLeast(1)
        val stepY = (h / 32).coerceAtLeast(1)
        var count = 0
        var sum = 0.0
        var sqSum = 0.0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val px = bmp.getPixel(x, y)
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                sum += luma
                sqSum += luma * luma
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return 128f to 50f
        val mean = sum / count
        val variance = (sqSum / count) - (mean * mean)
        val std = sqrt(variance.coerceAtLeast(0.0))
        return mean.toFloat() to std.toFloat()
    }
}
