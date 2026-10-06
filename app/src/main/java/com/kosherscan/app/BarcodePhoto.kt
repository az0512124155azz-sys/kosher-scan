package com.kosherscan.app

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object BarcodePhoto {
    fun capture(proxy: ImageProxy, bounds: Rect?): ByteArray? {
        if (bounds == null) return null
        return try {
            val raw = proxy.toBitmap()
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }, true)
            // Keep the package context because a certification mark is often near,
            // but outside, a tight barcode crop.
            val bytes = crop(rotated, Rect(0, 0, rotated.width, rotated.height))
            if (rotated !== raw) rotated.recycle()
            raw.recycle(); bytes
        } catch (_: Exception) { null }
    }
    fun crop(bitmap: Bitmap, bounds: Rect): ByteArray? {
        val rect = Rect(bounds)
        if (!rect.intersect(0, 0, bitmap.width, bitmap.height) || rect.width() <= 0 || rect.height() <= 0) return null
        val crop = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val ratio = minOf(1f, 1000f / maxOf(crop.width, crop.height))
        val scaled = if (ratio < 1) Bitmap.createScaledBitmap(crop, maxOf(1, (crop.width * ratio).toInt()), maxOf(1, (crop.height * ratio).toInt()), true) else crop
        val output = ByteArrayOutputStream()
        for (quality in listOf(80, 65, 50)) {
            output.reset(); scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)
            if (output.size() <= 200000) break
        }
        if (scaled !== crop) scaled.recycle()
        if (crop !== bitmap) crop.recycle()
        return output.toByteArray().takeIf { it.size <= 200000 }
    }
}
