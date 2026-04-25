package com.example.lunawallet

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class LunaChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var income: Double = 1.0
    private var expense: Double = 0.0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#10B981")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 16, 185, 129) // Semi-transparent income_green
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#10B981")
    }

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val path = Path()
    private val fillPath = Path()
    private val points = FloatArray(14)
    private val basePoints = floatArrayOf(0.7f, 0.8f, 0.5f, 0.7f, 0.3f, 0.5f, 0.6f)

    fun setData(income: Double, expense: Double) {
        this.income = if (income <= 0) 1.0 else income
        this.expense = expense
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val padding = 40f
        val chartHeight = h - padding * 2
        val expenseRatio = (expense / income).coerceIn(0.0, 1.2).toFloat()
        
        for (i in 0 until 7) {
            val x = i * (w / 6)
            // Higher ratio (more spending) pushes the line higher up on screen (smaller Y)
            val yFactor = (basePoints[i] * (1f - expenseRatio * 0.5f)).coerceIn(0.1f, 0.9f)
            val y = padding + (chartHeight * yFactor)
            
            points[i*2] = x
            points[i*2+1] = y
        }

        path.reset()
        path.moveTo(points[0], points[1])
        for (i in 1 until 7) {
            val prevX = points[(i-1)*2]
            val prevY = points[(i-1)*2+1]
            val currX = points[i*2]
            val currY = points[i*2+1]
            path.quadTo((prevX + currX) / 2, (prevY + currY) / 2, currX, currY)
        }

        fillPath.set(path)
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()
        
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        for (i in 0 until 7) {
            canvas.drawCircle(points[i*2], points[i*2+1], 12f, dotPaint)
            canvas.drawCircle(points[i*2], points[i*2+1], 6f, whitePaint)
        }
    }
}
