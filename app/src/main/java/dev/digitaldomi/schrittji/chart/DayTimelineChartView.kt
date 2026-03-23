package dev.digitaldomi.schrittji.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.digitaldomi.schrittji.R
import kotlin.math.max

enum class TimelineSeries {
    EXISTING,
    PROJECTED
}

data class TimelineBarEntry(
    val startMinute: Int,
    val endMinute: Int,
    val value: Float,
    val series: TimelineSeries = TimelineSeries.PROJECTED,
    val emphasized: Boolean = false
)

class DayTimelineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val projectedColor = context.getColor(R.color.chart_projected)
    private val projectedEmphasizedColor = context.getColor(R.color.brand_primary_dark)
    private val existingColor = context.getColor(R.color.chart_existing)
    private val textColor = context.getColor(R.color.brand_text)
    private val axisColor = context.getColor(R.color.panel_stroke)
    private val bucketCount = 48

    private val projectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = projectedColor
        alpha = 190
    }
    private val projectedEmphasizedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = projectedEmphasizedColor
        alpha = 210
    }
    private val existingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = existingColor
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

    private var buckets: List<TimelineBucket> = emptyList()
    private var maxValue: Float = 1f

    fun submitEntries(entries: List<TimelineBarEntry>) {
        buckets = buildBuckets(entries)
        maxValue = max(
            1f,
            buckets.maxOfOrNull { max(it.existingValue, it.projectedValue) } ?: 1f
        )
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

        if (buckets.isEmpty()) {
            canvas.drawText(
                "No entries",
                width / 2f,
                height / 2f,
                labelPaint
            )
            drawHourLabels(canvas, chartLeft, chartRight, chartBottom)
            return
        }

        val slotWidth = chartWidth / buckets.size.toFloat()
        val groupedWidth = (slotWidth * 0.68f).coerceAtLeast(3f * density)
        val singleWidth = (slotWidth * 0.78f).coerceAtLeast(4f * density)

        buckets.forEachIndexed { index, bucket ->
            val centerX = chartLeft + (slotWidth * index) + (slotWidth / 2f)
            val hasExisting = bucket.existingValue > 0f
            val hasProjected = bucket.projectedValue > 0f

            if (hasExisting && hasProjected) {
                drawBar(
                    canvas = canvas,
                    centerX = centerX - (groupedWidth * 0.42f),
                    value = bucket.existingValue,
                    maxValue = maxValue,
                    chartTop = chartTop,
                    chartBottom = chartBottom,
                    barWidth = groupedWidth,
                    paint = existingPaint
                )
                drawBar(
                    canvas = canvas,
                    centerX = centerX + (groupedWidth * 0.42f),
                    value = bucket.projectedValue,
                    maxValue = maxValue,
                    chartTop = chartTop,
                    chartBottom = chartBottom,
                    barWidth = groupedWidth,
                    paint = if (bucket.projectedEmphasized) projectedEmphasizedPaint else projectedPaint
                )
            } else if (hasExisting) {
                drawBar(
                    canvas = canvas,
                    centerX = centerX,
                    value = bucket.existingValue,
                    maxValue = maxValue,
                    chartTop = chartTop,
                    chartBottom = chartBottom,
                    barWidth = singleWidth,
                    paint = existingPaint
                )
            } else if (hasProjected) {
                drawBar(
                    canvas = canvas,
                    centerX = centerX,
                    value = bucket.projectedValue,
                    maxValue = maxValue,
                    chartTop = chartTop,
                    chartBottom = chartBottom,
                    barWidth = singleWidth,
                    paint = if (bucket.projectedEmphasized) projectedEmphasizedPaint else projectedPaint
                )
            }
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

    private fun buildBuckets(entries: List<TimelineBarEntry>): List<TimelineBucket> {
        if (entries.isEmpty()) return emptyList()

        val bucketMinutes = 1440 / bucketCount
        val buckets = MutableList(bucketCount) { TimelineBucket() }

        entries.forEach { entry ->
            val start = entry.startMinute.coerceIn(0, 1439)
            val endExclusive = entry.endMinute.coerceIn(start + 1, 1440)
            var minute = start
            while (minute < endExclusive) {
                val bucketIndex = (minute / bucketMinutes).coerceIn(0, bucketCount - 1)
                val bucketEnd = ((bucketIndex + 1) * bucketMinutes).coerceAtMost(endExclusive)
                val overlapMinutes = (bucketEnd - minute).coerceAtLeast(1)
                val portion = entry.value * (overlapMinutes / (endExclusive - start).toFloat())
                val current = buckets[bucketIndex]
                when (entry.series) {
                    TimelineSeries.EXISTING -> current.existingValue += portion
                    TimelineSeries.PROJECTED -> {
                        current.projectedValue += portion
                        current.projectedEmphasized = current.projectedEmphasized || entry.emphasized
                    }
                }
                minute = bucketEnd
            }
        }

        return buckets
    }
}

private data class TimelineBucket(
    var existingValue: Float = 0f,
    var projectedValue: Float = 0f,
    var projectedEmphasized: Boolean = false
)
