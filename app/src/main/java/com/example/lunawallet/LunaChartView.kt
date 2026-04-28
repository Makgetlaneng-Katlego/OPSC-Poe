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
    private var dataPoints: FloatArray = floatArrayOf(0.7f, 0.8f, 0.5f, 0.7f, 0.3f, 0.5f, 0.6f)

    fun setData(income: Double, expense: Double) {
        // Fallback for simple data
        this.income = if (income <= 0) 1.0 else income
        this.expense = expense
        invalidate()
    }

    fun setTrendData(points: List<Float>) {
        if (points.size >= 2) {
            dataPoints = points.toFloatArray()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val padding = 40f
        val chartHeight = h - padding * 2
        
        val size = dataPoints.size
        val points = FloatArray(size * 2)
        
        for (i in 0 until size) {
            val x = i * (w / (size - 1))
            val y = padding + (chartHeight * (1f - dataPoints[i]))
            
            points[i*2] = x
            points[i*2+1] = y
        }

        path.reset()
        path.moveTo(points[0], points[1])
        for (i in 1 until size) {
            val prevX = points[(i-1)*2]
            val prevY = points[(i-1)*2+1]
            val currX = points[i*2]
            val currY = points[i*2+1]
            path.cubicTo((prevX + currX) / 2, prevY, (prevX + currX) / 2, currY, currX, currY)
        }

        fillPath.set(path)
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()
        
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        for (i in 0 until size) {
            canvas.drawCircle(points[i*2], points[i*2+1], 10f, dotPaint)
            canvas.drawCircle(points[i*2], points[i*2+1], 5f, whitePaint)
        }
    }
}
