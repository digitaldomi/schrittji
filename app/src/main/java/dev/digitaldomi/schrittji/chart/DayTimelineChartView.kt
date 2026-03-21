package dev.digitaldomi.schrittji.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.digitaldomi.schrittji.R
import kotlin.math.max

data class TimelineBarEntry(
    val startMinute: Int,
    val endMinute: Int,
    val value: Float,
    val emphasized: Boolean = false
)

class DayTimelineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val primaryColor = context.getColor(R.color.brand_primary)
    private val emphasizedColor = context.getColor(R.color.brand_primary_dark)
    private val textColor = context.getColor(R.color.brand_text)
    private val axisColor = context.getColor(R.color.panel_stroke)

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = primaryColor
        alpha = 190
    }
    private val emphasizedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = emphasizedColor
        alpha = 210
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = axisColor
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 10f * scaledDensity
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.LEFT
        textSize = 10f * scaledDensity
        alpha = 170
    }

    private var entries: List<TimelineBarEntry> = emptyList()
    private var maxValue: Float = 1f

    fun submitEntries(entries: List<TimelineBarEntry>) {
        this.entries = entries
        maxValue = max(1f, entries.maxOfOrNull { it.value } ?: 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (250 * density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val chartLeft = paddingLeft + 12f * density
        val chartRight = width - paddingRight - 12f * density
        val chartTop = paddingTop + 24f * density
        val chartBottom = height - paddingBottom - 28f * density
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)
        canvas.drawText("max ${formatValue(maxValue)}", chartLeft, chartTop - (6f * density), valuePaint)

        if (entries.isEmpty()) {
            canvas.drawText(
                "No entries",
                width / 2f,
                height / 2f,
                labelPaint
            )
            drawHourLabels(canvas, chartLeft, chartRight, chartBottom)
            return
        }

        entries.forEach { entry ->
            val startFraction = (entry.startMinute.coerceIn(0, 1440)) / 1440f
            val endFraction = (entry.endMinute.coerceIn(entry.startMinute + 1, 1440)) / 1440f
            val left = chartLeft + (chartWidth * startFraction)
            val right = max(left + 1.5f * density, chartLeft + (chartWidth * endFraction))
            val top = chartBottom - ((entry.value / maxValue) * chartHeight)
            val rect = RectF(left, top, right, chartBottom)
            canvas.drawRoundRect(
                rect,
                3f * density,
                3f * density,
                if (entry.emphasized) emphasizedPaint else barPaint
            )
        }

        drawHourLabels(canvas, chartLeft, chartRight, chartBottom)
    }

    private fun drawHourLabels(canvas: Canvas, chartLeft: Float, chartRight: Float, chartBottom: Float) {
        val labels = listOf("00", "04", "08", "12", "16", "20", "24")
        labels.forEachIndexed { index, label ->
            val fraction = index / (labels.size - 1).toFloat()
            val x = chartLeft + ((chartRight - chartLeft) * fraction)
            canvas.drawLine(x, chartBottom, x, chartBottom + (5f * density), axisPaint)
            canvas.drawText(label, x, chartBottom + (18f * density), labelPaint)
        }
    }

    private fun formatValue(value: Float): String {
        return if (value >= 1_000f) {
            "${((value / 100f).toInt()) / 10f}k"
        } else {
            value.toInt().toString()
        }
    }
}
