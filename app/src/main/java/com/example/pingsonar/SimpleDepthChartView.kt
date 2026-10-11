package com.example.pingsonar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class SimpleDepthChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxPoints = 60
    private val history = ArrayList<Float>()

    private val linePaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#333333")
        strokeWidth = 1f
    }

    fun addDepth(depth: Float) {
        history.add(depth)
        if (history.size > maxPoints) {
            history.removeAt(0)
        }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (history.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        // Garis pemisah tengah
        canvas.drawLine(0f, h / 2, w, h / 2, gridPaint)

        val maxVal = (history.maxOrNull() ?: 10f).coerceAtLeast(2f)
        val minVal = 0f
        val range = (maxVal - minVal).coerceAtLeast(1f)

        val stepX = w / (maxPoints - 1)
        val path = Path()

        for (i in history.indices) {
            val x = i * stepX
            // Skala kedalaman: semakin dalam target, garis turun ke bawah
            val y = (history[i] - minVal) / range * (h - 20f) + 10f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
