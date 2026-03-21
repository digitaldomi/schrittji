package dev.digitaldomi.schrittji.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.digitaldomi.schrittji.R
import kotlin.math.max

data class BarChartPoint(
    val label: String,
    val value: Float,
    val emphasized: Boolean = false
)

class SimpleBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val primaryColor = context.getColor(R.color.brand_primary)
    private val emphasizedColor = context.getColor(R.color.brand_primary_dark)
    private val textColor = context.getColor(R.color.brand_text)
    private val axisColor = context.getColor(R.color.panel_stroke)
    private val emptyText = "No data"

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = primaryColor
    }
    private val emphasizedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = emphasizedColor
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = axisColor
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.scaledDensity
        alpha = 180
    }

    private var points: List<BarChartPoint> = emptyList()

    fun submitPoints(points: List<BarChartPoint>) {
        this.points = points
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (220 * density).toInt()
        val resolvedHeight = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(resolveSize(suggestedMinimumWidth, widthMeasureSpec), resolvedHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (points.isEmpty()) {
            canvas.drawText(
                emptyText,
                width / 2f,
                height / 2f,
                labelPaint
            )
            return
        }

        val chartLeft = paddingLeft + 8f * density
        val chartRight = width - paddingRight - 8f * density
        val chartTop = paddingTop + 24f * density
        val chartBottom = height - paddingBottom - 28f * density
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop
        val maxValue = max(1f, points.maxOf { it.value })
        val slotWidth = chartWidth / points.size.toFloat()
        val barWidth = (slotWidth * 0.62f).coerceAtLeast(10f * density)

        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

        points.forEachIndexed { index, point ->
            val barHeight = (point.value / maxValue) * chartHeight
            val centerX = chartLeft + (slotWidth * index) + (slotWidth / 2f)
            val left = centerX - (barWidth / 2f)
            val top = chartBottom - barHeight
            val rect = RectF(left, top, left + barWidth, chartBottom)

            canvas.drawRoundRect(
                rect,
                6f * density,
                6f * density,
                if (point.emphasized) emphasizedPaint else barPaint
            )
            canvas.drawText(formatValue(point.value), centerX, top - (6f * density), valuePaint)
            canvas.drawText(point.label, centerX, height - paddingBottom - (8f * density), labelPaint)
        }
    }

    private fun formatValue(value: Float): String {
        return if (value >= 1_000f) {
            val rounded = ((value / 100f).toInt()) / 10f
            "${rounded}k"
        } else {
            value.toInt().toString()
        }
    }
}
