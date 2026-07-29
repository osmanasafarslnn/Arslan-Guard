package com.example.applock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Basit ve hafif bir 3x3 desen kilidi bileşeni. Ağır kütüphane bağımlılığı
 * olmadan Canvas ile çizilir; eski/düşük performanslı cihazlarda akıcı
 * çalışması için gereksiz yeniden çizimlerden kaçınılır.
 */
class PatternLockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dotRadius = 18f
    private val selectedDotRadius = 24f
    private val lineWidth = 10f

    private val dotPaintIdle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_secondary)
        style = Paint.Style.FILL
    }
    private val dotPaintSelected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.STROKE
        strokeWidth = lineWidth
        strokeCap = Paint.Cap.ROUND
    }
    private val errorLinePaint = Paint(linePaint).apply {
        color = ContextCompat.getColor(context, R.color.error)
    }
    private val errorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.error)
        style = Paint.Style.FILL
    }

    private val points = Array(9) { PointF() }
    private val selectedIndices = mutableListOf<Int>()
    private var currentTouch: PointF? = null
    private var showError = false

    var onPatternComplete: ((String) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cellW = w / 3f
        val cellH = h / 3f
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val index = row * 3 + col
                points[index] = PointF(cellW * col + cellW / 2f, cellH * row + cellH / 2f)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                showError = false
                selectedIndices.clear()
                handleTouch(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                currentTouch = PointF(event.x, event.y)
                handleTouch(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                currentTouch = null
                finishPattern()
            }
        }
        invalidate()
        return true
    }

    private fun handleTouch(x: Float, y: Float) {
        for (i in points.indices) {
            if (selectedIndices.contains(i)) continue
            val dx = x - points[i].x
            val dy = y - points[i].y
            val distance = Math.sqrt((dx * dx + dy * dy).toDouble())
            if (distance < selectedDotRadius * 2.2) {
                selectedIndices.add(i)
            }
        }
    }

    private fun finishPattern() {
        if (selectedIndices.size >= 2) {
            onPatternComplete?.invoke(selectedIndices.joinToString(""))
        }
    }

    /** Kilit açma başarısız olduğunda kırmızı desen animasyonu için çağrılır. */
    fun showErrorAndReset() {
        showError = true
        invalidate()
        postDelayed({
            showError = false
            selectedIndices.clear()
            invalidate()
        }, 400)
    }

    fun reset() {
        selectedIndices.clear()
        showError = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paintLine = if (showError) errorLinePaint else linePaint

        // Seçili noktalar arasındaki çizgiler
        for (i in 0 until selectedIndices.size - 1) {
            val p1 = points[selectedIndices[i]]
            val p2 = points[selectedIndices[i + 1]]
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paintLine)
        }
        // Son seçili noktadan parmağın anlık konumuna çizgi (aktif sürükleme)
        if (!showError && selectedIndices.isNotEmpty() && currentTouch != null) {
            val last = points[selectedIndices.last()]
            canvas.drawLine(last.x, last.y, currentTouch!!.x, currentTouch!!.y, linePaint)
        }

        // Noktaların kendisi
        for (i in points.indices) {
            val isSelected = selectedIndices.contains(i)
            val paint = if (isSelected) {
                if (showError) errorDotPaint else dotPaintSelected
            } else dotPaintIdle
            val radius = if (isSelected) selectedDotRadius else dotRadius
            canvas.drawCircle(points[i].x, points[i].y, radius, paint)
        }
    }
}
