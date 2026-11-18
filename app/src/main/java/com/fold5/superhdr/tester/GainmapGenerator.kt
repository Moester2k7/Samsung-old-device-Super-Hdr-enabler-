package com.fold5.superhdr.tester

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Gainmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.pow

object GainmapGenerator {

    fun createBuiltInHdrTestCard(width: Int = 1440, height: Int = 1080): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.rgb(8, 12, 24), Color.rgb(18, 24, 42), Color.rgb(6, 8, 14)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        val sunX = width * 0.72f
        val sunY = height * 0.34f
        val sunRadius = width * 0.22f
        paint.shader = RadialGradient(
            sunX, sunY, sunRadius,
            intArrayOf(
                Color.rgb(255, 255, 255),
                Color.rgb(255, 242, 200),
                Color.rgb(255, 160, 60),
                Color.argb(0, 255, 90, 0)
            ),
            floatArrayOf(0f, 0.22f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(sunX, sunY, sunRadius, paint)
        paint.shader = null

        val neonColors = listOf(
            Color.rgb(0, 245, 255),
            Color.rgb(255, 50, 190),
            Color.rgb(255, 220, 40)
        )
        neonColors.forEachIndexed { idx, color ->
            val cy = height * (0.28f + idx * 0.16f)
            paint.color = color
            paint.strokeWidth = 22f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(width * 0.10f, cy, width * 0.42f, cy, paint)

            paint.color = Color.rgb(248, 252, 255)
            paint.strokeWidth = 8f
            canvas.drawLine(width * 0.11f, cy, width * 0.41f, cy, paint)
        }

        val barTop = height * 0.80f
        val barBottom = height * 0.92f
        val steps = 12
        val stepW = (width * 0.84f) / steps
        val startX = width * 0.08f
        paint.style = Paint.Style.FILL
        for (i in 0 until steps) {
            val v = ((i.toFloat() / (steps - 1)) * 255f).toInt()
            paint.color = Color.rgb(v, v, v)
            canvas.drawRect(
                startX + i * stepW,
                barTop,
                startX + (i + 1) * stepW - 4f,
                barBottom,
                paint
            )
        }

        return bmp
    }

    fun buildBitmapWithGainmap(
        source: Bitmap,
        maxRatio: Float,
        threshold: Float,
        splitRatio: Float
    ): Bitmap {
        val copy = source.copy(Bitmap.Config.ARGB_8888, true)
        val scale = 4
        val gmW = (copy.width / scale).coerceAtLeast(64)
        val gmH = (copy.height / scale).coerceAtLeast(64)

        val scaled = Bitmap.createScaledBitmap(copy, gmW, gmH, true)
        val pixels = IntArray(gmW * gmH)
        scaled.getPixels(pixels, 0, gmW, 0, 0, gmW, gmH)
        scaled.recycle()

        val gmPixels = IntArray(gmW * gmH)
        val splitX = (gmW * splitRatio).toInt()

        for (y in 0 until gmH) {
            val rowOffset = y * gmW
            for (x in 0 until gmW) {
                if (x < splitX) {
                    gmPixels[rowOffset + x] = Color.BLACK
                    continue
                }
                val c = pixels[rowOffset + x]
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                val luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                if (luma > threshold) {
                    val norm = ((luma - threshold) / (1f - threshold).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
                    val v = (norm.pow(1.5f) * 255f).toInt().coerceIn(0, 255)
                    gmPixels[rowOffset + x] = Color.rgb(v, v, v)
                } else {
                    gmPixels[rowOffset + x] = Color.BLACK
                }
            }
        }

        if (splitX in 1 until gmW) {
            val lineX = (splitX * scale).coerceAtMost(copy.width - 1)
            for (y in 0 until copy.height) {
                copy.setPixel(lineX, y, Color.WHITE)
            }
        }

        val gainmapBmp = Bitmap.createBitmap(gmW, gmH, Bitmap.Config.ARGB_8888)
        gainmapBmp.setPixels(gmPixels, 0, gmW, 0, 0, gmW, gmH)

        val gainmap = Gainmap(gainmapBmp).apply {
            setRatioMin(1.0f, 1.0f, 1.0f)
            setRatioMax(maxRatio, maxRatio, maxRatio)
            setGamma(1.0f, 1.0f, 1.0f)
            setEpsilonSdr(0.01f, 0.01f, 0.01f)
            setEpsilonHdr(0.01f, 0.01f, 0.01f)
            minDisplayRatioForHdrTransition = 1.01f
        }
        copy.gainmap = gainmap
        return copy
    }
}
