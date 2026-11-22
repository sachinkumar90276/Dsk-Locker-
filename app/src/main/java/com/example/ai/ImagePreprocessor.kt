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
import kotlin.math.abs
import kotlin.math.sqrt

data class ImageQualityMetrics(
    val meanLuma: Float,
    val contrastStdDev: Float,
    val sharpnessScore: Float,
    val isLowLight: Boolean,
    val isLowContrast: Boolean,
    val isBlurry: Boolean
)

/**
 * Multi-stage Image Quality Analyzer & Enhancer:
 * - EXIF orientation normalization & resolution upscaling for small/blurry docs
 * - Luminance/contrast/sharpness inspection
 * - Adaptive contrast & brightness boost + 3x3 edge crispness enhancement
 * - Adaptive local thresholding variant for faint grey government certificates & stamps
 */
object ImagePreprocessor {

    fun decodeWithExifAndResolution(file: File, maxDim: Int = 2400, minTargetDim: Int = 1280): Bitmap? {
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

        // Upscale blurry / low-resolution captures so Hindi matras & small numbers read clearly
        val longest = maxOf(decoded.width, decoded.height)
        if (longest in 240 until minTargetDim) {
            val scale = (minTargetDim.toFloat() / longest.toFloat()).coerceAtMost(2.4f)
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

    fun analyzeImageQuality(bmp: Bitmap): ImageQualityMetrics {
        val w = bmp.width
        val h = bmp.height
        val stepX = (w / 48).coerceAtLeast(1)
        val stepY = (h / 48).coerceAtLeast(1)
        var count = 0
        var sum = 0.0
        var sqSum = 0.0
        var gradSum = 0.0
        var y = 0
        while (y < h) {
            var x = 0
            var prevLuma = -1.0
            while (x < w) {
                val px = bmp.getPixel(x, y)
                val luma = 0.299 * ((px shr 16) and 0xFF) +
                    0.587 * ((px shr 8) and 0xFF) +
                    0.114 * (px and 0xFF)
                sum += luma
                sqSum += luma * luma
                if (prevLuma >= 0.0) {
                    gradSum += abs(luma - prevLuma)
                }
                prevLuma = luma
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) {
            return ImageQualityMetrics(128f, 50f, 15f, isLowLight = false, isLowContrast = false, isBlurry = false)
        }
        val mean = (sum / count).toFloat()
        val variance = ((sqSum / count) - (mean * mean)).coerceAtLeast(0.0)
        val std = sqrt(variance).toFloat()
        val sharpness = (gradSum / count).toFloat()
        return ImageQualityMetrics(
            meanLuma = mean,
            contrastStdDev = std,
            sharpnessScore = sharpness,
            isLowLight = mean < 95f,
            isLowContrast = std < 42f,
            isBlurry = sharpness < 10.5f
        )
    }

    /**
     * Enhances contrast, brightness, background cleanliness, and mild edge crispness.
     */
    fun enhanceForOcr(source: Bitmap): Bitmap {
        val metrics = analyzeImageQuality(source)

        val contrast = when {
            metrics.contrastStdDev < 30f -> 1.52f
            metrics.isLowContrast -> 1.35f
            else -> 1.18f
        }
        val brightness = when {
            metrics.meanLuma < 80f -> 34f
            metrics.meanLuma < 115f -> 16f
            metrics.meanLuma > 215f -> -20f
            else -> 4f
        }

        val translate = (-0.5f * contrast + 0.5f) * 255f + brightness
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val satMatrix = ColorMatrix().apply { setSaturation(0.35f) }
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        satMatrix.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(satMatrix)
        canvas.drawBitmap(source, 0f, 0f, paint)

        // Apply fast mild unsharp boost only when blurry & reasonably sized
        if (metrics.isBlurry && out.width * out.height <= 4_200_000) {
            return applyFastUnsharpCrispen(out)
        }
        return out
    }

    /**
     * High-contrast adaptive threshold pass for uneven lighting, tinted card watermarks,
     * and faded thermal/government printouts.
     */
    fun createAdaptiveThresholdVariant(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val total = w * h
        if (total <= 0) return source

        val pixels = IntArray(total)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = IntArray(total)
        for (i in 0 until total) {
            val px = pixels[i]
            luma[i] = ((299 * ((px shr 16) and 0xFF) +
                587 * ((px shr 8) and 0xFF) +
                114 * (px and 0xFF)) / 1000)
        }

        // Integral image for O(1) adaptive window threshold
        val integral = LongArray(total)
        for (y in 0 until h) {
            var rowSum = 0L
            val rowOff = y * w
            for (x in 0 until w) {
                rowSum += luma[rowOff + x]
                integral[rowOff + x] = rowSum + if (y > 0) integral[(y - 1) * w + x] else 0L
            }
        }

        val radius = (minOf(w, h) / 28).coerceIn(10, 28)
        val outPixels = IntArray(total)
        for (y in 0 until h) {
            val y1 = (y - radius).coerceAtLeast(0)
            val y2 = (y + radius).coerceAtMost(h - 1)
            val rowOff = y * w
            for (x in 0 until w) {
                val x1 = (x - radius).coerceAtLeast(0)
                val x2 = (x + radius).coerceAtMost(w - 1)
                val area = (x2 - x1 + 1) * (y2 - y1 + 1)
                val br = integral[y2 * w + x2]
                val top = if (y1 > 0) integral[(y1 - 1) * w + x2] else 0L
                val left = if (x1 > 0) integral[y2 * w + (x1 - 1)] else 0L
                val tl = if (y1 > 0 && x1 > 0) integral[(y1 - 1) * w + (x1 - 1)] else 0L
                val localMean = (br - top - left + tl) / area
                val cur = luma[rowOff + x]
                // Soft binarization retains anti-aliased edges for better ML Kit glyph shapes
                outPixels[rowOff + x] = when {
                    cur < localMean - 14 -> 0xFF101010.toInt()
                    cur < localMean - 4 -> 0xFF555555.toInt()
                    else -> 0xFFFAFAFA.toInt()
                }
            }
        }
        return Bitmap.createBitmap(outPixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun applyFastUnsharpCrispen(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w < 4 || h < 4) return src
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = pixels.copyOf()

        for (y in 1 until h - 1) {
            val off = y * w
            for (x in 1 until w - 1) {
                val c = pixels[off + x]
                val t = pixels[off - w + x]
                val b = pixels[off + w + x]
                val l = pixels[off + x - 1]
                val r = pixels[off + x + 1]

                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF

                val avgR = (((t shr 16) and 0xFF) + ((b shr 16) and 0xFF) + ((l shr 16) and 0xFF) + ((r shr 16) and 0xFF)) shr 2
                val avgG = (((t shr 8) and 0xFF) + ((b shr 8) and 0xFF) + ((l shr 8) and 0xFF) + ((r shr 8) and 0xFF)) shr 2
                val avgB = ((t and 0xFF) + (b and 0xFF) + (l and 0xFF) + (r and 0xFF)) shr 2

                val nr = (cr + ((cr - avgR) * 3) / 5).coerceIn(0, 255)
                val ng = (cg + ((cg - avgG) * 3) / 5).coerceIn(0, 255)
                val nb = (cb + ((cb - avgB) * 3) / 5).coerceIn(0, 255)
                out[off + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        src.recycle()
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
