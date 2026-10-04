package com.kosherscan.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/** Native version of the HTML's 82vw frame, rounded corners and green sweep. */
class ScanOverlay @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 850; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }
    var animating = true
        set(value) { field = value; if (value && isAttachedToWindow) { if (!animator.isStarted) animator.start() } else animator.cancel(); invalidate() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (animating) animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = density; val w = minOf(width * .82f, 400 * d)
        val left = (width - w) / 2; val top = height * .425f - 75 * d
        val right = left + w; val bottom = top + 150 * d
        paint.shader = RadialGradient(width / 2f, height * .42f, height * .7f, intArrayOf(0x00000000, 0x99000000.toInt()), floatArrayOf(.24f, 1f), Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL; canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint); paint.shader = null
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = 4 * d; paint.strokeCap = Paint.Cap.ROUND
        val len = 52 * d; val radius = 22 * d
        val path = Path().apply {
            moveTo(left, top + len); lineTo(left, top + radius); quadTo(left, top, left + radius, top); lineTo(left + len, top)
            moveTo(right - len, top); lineTo(right - radius, top); quadTo(right, top, right, top + radius); lineTo(right, top + len)
            moveTo(left, bottom - len); lineTo(left, bottom - radius); quadTo(left, bottom, left + radius, bottom); lineTo(left + len, bottom)
            moveTo(right - len, bottom); lineTo(right - radius, bottom); quadTo(right, bottom, right, bottom - radius); lineTo(right, bottom - len)
        }
        canvas.drawPath(path, paint)
        if (animating) {
            paint.color = 0xFF4FF397.toInt(); paint.strokeWidth = 2 * d
            val y = top + (18 + phase * 110) * d
            canvas.drawLine(left + 17 * d, y, right - 17 * d, y, paint)
        }
    }
}
