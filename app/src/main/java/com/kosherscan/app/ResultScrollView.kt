package com.kosherscan.app

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/** Measure the content naturally, then cap it so large text can scroll below the title. */
class ResultScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : NestedScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeight = (resources.displayMetrics.heightPixels * .78f).toInt()
        val available = MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: maxHeight
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(minOf(available, maxHeight), MeasureSpec.AT_MOST))
    }
}
