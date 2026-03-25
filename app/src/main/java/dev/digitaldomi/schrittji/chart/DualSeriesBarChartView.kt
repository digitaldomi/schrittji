package dev.digitaldomi.schrittji.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.View.MeasureSpec
import dev.digitaldomi.schrittji.R
import kotlin.math.max

data class DualSeriesBarPoint(
    val label: String,
    val existingValue: Float,
    val projectedValue: Float,
    val hasRecordedCardioWorkout: Boolean = false,
    val hasRecordedMindfulnessWorkout: Boolean = false,
    val hasProjectedCardioWorkout: Boolean = false,
    val hasProjectedMindfulnessWorkout: Boolean = false
)

class DualSeriesBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val existingColor = context.getColor(R.color.chart_existing)
    private val projectedColor = context.getColor(R.color.chart_projected)
    private val workoutUnderlayRecorded = context.getColor(R.color.chart_workout_underlay)
    private val workoutUnderlayProjected = context.getColor(R.color.chart_workout_underlay_projected)
    private val workoutUnderlayMindfulnessRecorded = context.getColor(R.color.chart_workout_underlay_mindfulness)
    private val workoutUnderlayMindfulnessProjected = context.getColor(R.color.chart_workout_underlay_mindfulness_projected)
    private val axisColor = context.getColor(R.color.panel_stroke)
    private val textColor = context.getColor(R.color.brand_text)

    private val existingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = existingColor
    }
    private val projectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = projectedColor
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = axisColor
        strokeWidth = 1f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 10f * scaledDensity
    }
    private val yAxisLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.RIGHT
        textSize = 9f * scaledDensity
        alpha = 200
    }

    private val yAxisGutterPx = resources.getDimensionPixelSize(R.dimen.chart_y_axis_gutter).toFloat()
    private val underlayRecordedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = workoutUnderlayRecorded
    }
    private val underlayProjectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = workoutUnderlayProjected
    }
    private val underlayMindfulnessRecordedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = workoutUnderlayMindfulnessRecorded
    }
    private val underlayMindfulnessProjectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = workoutUnderlayMindfulnessProjected
    }

    private var points: List<DualSeriesBarPoint> = emptyList()

    fun submit(points: List<DualSeriesBarPoint>) {
        this.points = points
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultHeight = resources.getDimensionPixelSize(R.dimen.chart_main_height)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val height = when (heightMode) {
            MeasureSpec.EXACTLY -> heightSize
            MeasureSpec.AT_MOST -> kotlin.math.min(defaultHeight, heightSize)
            else -> defaultHeight
        }
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            height
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) {
            canvas.drawText("No data", width / 2f, height / 2f, labelPaint)
            return
        }

        val chartLeft = paddingLeft + yAxisGutterPx
        val chartRight = width - paddingRight - 12f * density
        val chartTop = paddingTop + 12f * density
        val chartBottom = height - paddingBottom - 20f * density
        val chartWidth = chartRight - chartLeft
        val maxValue = max(
            1f,
            points.maxOf { max(it.existingValue, it.projectedValue) }
        )
        val slotWidth = chartWidth / points.size.toFloat()
        val barWidth = (slotWidth * 0.28f).coerceAtLeast(4f * density)

        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)
        drawYAxisLabels(canvas, chartLeft, chartTop, chartBottom, maxValue)

        points.forEachIndexed { index, point ->
            val slotLeft = chartLeft + slotWidth * index
            val q = slotWidth / 4f
            if (point.hasRecordedCardioWorkout) {
                canvas.drawRect(
                    slotLeft + 1f * density,
                    chartTop,
                    slotLeft + q,
                    chartBottom,
                    underlayRecordedPaint
                )
            }
            if (point.hasRecordedMindfulnessWorkout) {
                canvas.drawRect(
                    slotLeft + q,
                    chartTop,
                    slotLeft + 2f * q,
                    chartBottom,
                    underlayMindfulnessRecordedPaint
                )
            }
            if (point.hasProjectedCardioWorkout) {
                canvas.drawRect(
                    slotLeft + 2f * q,
                    chartTop,
                    slotLeft + 3f * q,
                    chartBottom,
                    underlayProjectedPaint
                )
            }
            if (point.hasProjectedMindfulnessWorkout) {
                canvas.drawRect(
                    slotLeft + 3f * q,
                    chartTop,
                    slotLeft + slotWidth - 1f * density,
                    chartBottom,
                    underlayMindfulnessProjectedPaint
                )
            }
        }

        points.forEachIndexed { index, point ->
            val centerX = chartLeft + (slotWidth * index) + (slotWidth / 2f)
            if (point.existingValue > 0f) {
                drawBar(
                    canvas = canvas,
                    centerX = centerX - (barWidth * 0.65f),
                    value = point.existingValue,
                    maxValue = maxValue,
                    chartTop = chartTop,
                    chartBottom = chartBottom,
                    barWidth = barWidth,
                    paint = existingPaint
                )
            }
            if (point.projectedValue > 0f) {
                drawBar(
                    canvas = canvas,
                    centerX = centerX + (barWidth * 0.65f),
                    value = point.projectedValue,
                    maxValue = maxValue,
                    chartTop = chartTop,
                    chartBottom = chartBottom,
                    barWidth = barWidth,
                    paint = projectedPaint
                )
            }
            canvas.drawText(point.label, centerX, height - paddingBottom - (6f * density), labelPaint)
        }
    }

    private fun formatAxisValue(value: Float): String {
        return if (value >= 1_000f) {
            "${((value / 100f).toInt()) / 10f}k"
        } else {
            value.toInt().toString()
        }
    }

    private fun drawYAxisLabels(
        canvas: Canvas,
        chartLeft: Float,
        chartTop: Float,
        chartBottom: Float,
        maxValue: Float
    ) {
        val h = chartBottom - chartTop
        if (h <= 0f) return
        val ticks = 4
        val labelX = chartLeft - (4f * density)
        for (i in 0..ticks) {
            val frac = i / ticks.toFloat()
            val y = chartBottom - frac * h
            canvas.drawLine(chartLeft - (4f * density), y, chartLeft, y, axisPaint)
            val v = maxValue * frac
            canvas.drawText(formatAxisValue(v), labelX, y + (3f * density), yAxisLabelPaint)
        }
    }

    private fun drawBar(
        canvas: Canvas,
        centerX: Float,
        value: Float,
        maxValue: Float,
        chartTop: Float,
        chartBottom: Float,
        barWidth: Float,
        paint: Paint
    ) {
        val barHeight = ((value / maxValue) * (chartBottom - chartTop)).coerceAtLeast(2f * density)
        val rect = RectF(
            centerX - (barWidth / 2f),
            chartBottom - barHeight,
            centerX + (barWidth / 2f),
            chartBottom
        )
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
    }
}
