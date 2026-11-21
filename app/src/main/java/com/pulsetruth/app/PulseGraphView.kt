package com.pulsetruth.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

class PulseGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxPoints = 120
    private val values = ArrayDeque<Float>(maxPoints)

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1A2233")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val wavePaint = Paint().apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 6f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val path = Path()

    fun setWaveColor(colorInt: Int) {
        wavePaint.color = colorInt
        postInvalidate()
    }

    fun addSample(v: Float) {
        if (values.size >= maxPoints) {
            values.removeFirst()
        }
        values.addLast(v)
        postInvalidate()
    }

    fun clear() {
        values.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Draw medical monitor grid
        val cols = 10
        val rows = 4
        for (i in 1 until cols) {
            val x = (w / cols) * i
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        for (j in 1 until rows) {
            val y = (h / rows) * j
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        if (values.size < 2) return

        // Auto-normalize amplitude so pulse wave always fills cleanly
        var maxAmp = 0.05f
        for (v in values) {
            val a = abs(v)
            if (a > maxAmp) maxAmp = a
        }

        path.reset()
        val stepX = w / (maxPoints - 1).toFloat()
        val midY = h / 2f
        val scaleY = (h * 0.40f) / maxAmp

        var index = 0
        for (v in values) {
            val x = index * stepX
            val y = (midY - (v * scaleY)).coerceIn(10f, h - 10f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            index++
        }

        canvas.drawPath(path, wavePaint)
    }
}
