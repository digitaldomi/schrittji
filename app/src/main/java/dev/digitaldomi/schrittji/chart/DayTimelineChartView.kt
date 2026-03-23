package dev.digitaldomi.schrittji.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.GestureDetectorCompat
import dev.digitaldomi.schrittji.R
import kotlin.math.max
import kotlin.math.min

enum class TimelineSeries {
    EXISTING,
    PROJECTED,
    WORKOUT
}

enum class TimelineWorkoutKind {
    RUNNING,
    CYCLING
}

data class WorkoutTapInfo(
    val kind: TimelineWorkoutKind,
    val title: String,
    val detail: String,
    val isProjected: Boolean
)

data class TimelineBarEntry(
    val startMinute: Int,
    val endMinute: Int,
    val value: Float,
    val series: TimelineSeries = TimelineSeries.PROJECTED,
    val emphasized: Boolean = false,
    val workoutKind: TimelineWorkoutKind? = null,
    val workoutTitle: String? = null,
    val workoutDetail: String? = null,
    val workoutIsProjected: Boolean = true
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
    private val workoutColor = context.getColor(R.color.chart_workout)
    private val textColor = context.getColor(R.color.brand_text)
    private val axisColor = context.getColor(R.color.panel_stroke)
    private val bucketCount = 24

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
    private val workoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = workoutColor
        alpha = 220
    }
    private val workoutStripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = workoutColor
        alpha = 235
    }
    private val workoutStripPaintProjected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = projectedColor
        alpha = 230
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
    private var workoutOverlays: List<WorkoutOverlay> = emptyList()
    private var hitTargets: List<Pair<RectF, WorkoutTapInfo>> = emptyList()

    private val runDrawable = ContextCompat.getDrawable(context, R.drawable.ic_workout_run)?.mutate()
    private val cycleDrawable = ContextCompat.getDrawable(context, R.drawable.ic_workout_cycle)?.mutate()

    private var workoutTapListener: ((WorkoutTapInfo) -> Unit)? = null

    private val gestureDetector = GestureDetectorCompat(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val x = e.x
                val y = e.y
                hitTargets.forEach { (rect, info) ->
                    if (rect.contains(x, y)) {
                        workoutTapListener?.invoke(info)
                        performClick()
                        return true
                    }
                }
                return false
            }
        }
    )

    init {
        isClickable = true
    }

    fun setOnWorkoutTapListener(listener: ((WorkoutTapInfo) -> Unit)?) {
        workoutTapListener = listener
    }

    fun submitEntries(entries: List<TimelineBarEntry>) {
        buckets = buildBuckets(entries)
        workoutOverlays = extractWorkoutOverlays(entries)
        maxValue = max(
            1f,
            buckets.maxOfOrNull { max(it.existingValue, it.projectedValue) } ?: 1f
        )
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (230 * density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        return handled || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val chartLeft = paddingLeft + 12f * density
        val chartRight = width - paddingRight - 12f * density
        val chartTop = paddingTop + 14f * density
        val chartBottom = height - paddingBottom - 24f * density
        val chartWidth = chartRight - chartLeft

        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)
        canvas.drawText("max ${formatValue(maxValue)}", chartLeft, chartTop - (6f * density), valuePaint)

        val newHits = mutableListOf<Pair<RectF, WorkoutTapInfo>>()

        if (buckets.isEmpty()) {
            canvas.drawText(
                "No entries",
                width / 2f,
                height / 2f,
                labelPaint
            )
            drawHourLabels(canvas, chartLeft, chartRight, chartBottom)
            hitTargets = emptyList()
            return
        }

        val slotWidth = chartWidth / buckets.size.toFloat()
        val groupedWidth = (slotWidth * 0.32f).coerceAtLeast(2.5f * density)
        val singleWidth = (slotWidth * 0.72f).coerceAtLeast(6f * density)

        val workoutStripHeight = 7f * density
        val workoutStripBottom = chartBottom - (2f * density)
        val workoutStripTop = workoutStripBottom - workoutStripHeight

        buckets.forEachIndexed { index, bucket ->
            val centerX = chartLeft + (slotWidth * index) + (slotWidth / 2f)
            val hasExisting = bucket.existingValue > 0f
            val hasProjected = bucket.projectedValue > 0f

            if (hasExisting && hasProjected) {
                drawBar(
                    canvas = canvas,
                    centerX = centerX - (groupedWidth * 0.7f),
                    value = bucket.existingValue,
                    maxValue = maxValue,
                    chartTop = chartTop,
                    chartBottom = chartBottom,
                    barWidth = groupedWidth,
                    paint = existingPaint
                )
                drawBar(
                    canvas = canvas,
                    centerX = centerX + (groupedWidth * 0.7f),
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

            if (bucket.workoutMarker) {
                drawWorkoutMarker(
                    canvas = canvas,
                    centerX = centerX,
                    chartTop = chartTop,
                    barWidth = singleWidth.coerceAtLeast(8f * density)
                )
            }
        }

        workoutOverlays.forEach { overlay ->
            val xStart = minuteToX(overlay.startMinute, chartLeft, chartWidth)
            val xEnd = minuteToX(overlay.endMinute, chartLeft, chartWidth)
            val left = min(xStart, xEnd)
            val right = max(xStart, xEnd).coerceAtLeast(left + (3f * density))
            val paint = if (overlay.isProjected) workoutStripPaintProjected else workoutStripPaint
            canvas.drawRoundRect(
                RectF(left, workoutStripTop, right, workoutStripBottom),
                3f * density,
                3f * density,
                paint
            )
        }

        val iconSizePx = 18f * density
        val touchPad = 12f * density
        val iconLayouts = layoutWorkoutIcons(
            overlays = workoutOverlays,
            chartLeft = chartLeft,
            chartWidth = chartWidth,
            chartTop = chartTop,
            iconSize = iconSizePx,
            workoutStripTop = workoutStripTop
        )
        iconLayouts.forEach { (overlay, centerX, iconTop) ->
            val iconLeft = (centerX - iconSizePx / 2f).toInt()
            val iconTopI = iconTop.toInt()
            val iconRight = iconLeft + iconSizePx.toInt()
            val iconBottom = iconTopI + iconSizePx.toInt()

            val drawable = when (overlay.kind) {
                TimelineWorkoutKind.RUNNING -> runDrawable
                TimelineWorkoutKind.CYCLING -> cycleDrawable
            }
            if (drawable != null) {
                val tint = if (overlay.isProjected) projectedColor else workoutColor
                DrawableCompat.setTint(drawable, tint)
                drawable.bounds = Rect(iconLeft, iconTopI, iconRight, iconBottom)
                drawable.draw(canvas)
            }

            val tap = WorkoutTapInfo(
                kind = overlay.kind,
                title = overlay.title,
                detail = overlay.detail,
                isProjected = overlay.isProjected
            )
            newHits.add(
                RectF(
                    centerX - touchPad,
                    iconTop - touchPad * 0.5f,
                    centerX + touchPad,
                    iconBottom + touchPad * 0.5f
                ) to tap
            )
        }
        hitTargets = newHits

        drawHourLabels(canvas, chartLeft, chartRight, chartBottom)
    }

    private fun minuteToX(minute: Int, chartLeft: Float, chartWidth: Float): Float {
        val m = minute.coerceIn(0, 1440)
        return chartLeft + (m / 1440f) * chartWidth
    }

    /**
     * Stacks workout icons vertically when their horizontal positions would overlap.
     */
    private fun layoutWorkoutIcons(
        overlays: List<WorkoutOverlay>,
        chartLeft: Float,
        chartWidth: Float,
        chartTop: Float,
        iconSize: Float,
        workoutStripTop: Float
    ): List<Triple<WorkoutOverlay, Float, Float>> {
        if (overlays.isEmpty()) return emptyList()

        val horizontalPad = 4f * density
        val verticalGap = 4f * density
        val baseIconTop = chartTop + (6f * density)
        val maxLane = 8
        val minIconBottom = workoutStripTop - (2f * density)
        val sorted = overlays.sortedWith(compareBy({ it.startMinute }, { it.endMinute }))
        val placed = mutableListOf<RectF>()
        val result = mutableListOf<Triple<WorkoutOverlay, Float, Float>>()

        for (overlay in sorted) {
            val centerX = minuteToX(overlay.midpointMinute, chartLeft, chartWidth)
            var chosen: Pair<Float, RectF>? = null
            for (lane in 0 until maxLane) {
                val top = baseIconTop - lane * (iconSize + verticalGap)
                val bottom = top + iconSize
                if (bottom > minIconBottom) continue
                val candidate = RectF(
                    centerX - iconSize / 2f - horizontalPad,
                    top - verticalGap,
                    centerX + iconSize / 2f + horizontalPad,
                    bottom + verticalGap
                )
                if (placed.none { RectF.intersects(it, candidate) }) {
                    chosen = top to candidate
                    break
                }
            }
            val (top, rect) = chosen ?: run {
                val lane = maxLane - 1
                val t = baseIconTop - lane * (iconSize + verticalGap)
                val candidate = RectF(
                    centerX - iconSize / 2f - horizontalPad,
                    t - verticalGap,
                    centerX + iconSize / 2f + horizontalPad,
                    t + iconSize + verticalGap
                )
                t to candidate
            }
            placed.add(rect)
            result.add(Triple(overlay, centerX, top))
        }
        return result
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
        val barHeight = ((value / maxValue) * (chartBottom - chartTop)).coerceAtLeast(6f * density)
        val rect = RectF(
            centerX - (barWidth / 2f),
            chartBottom - barHeight,
            centerX + (barWidth / 2f),
            chartBottom
        )
        canvas.drawRoundRect(rect, 5f * density, 5f * density, paint)
    }

    private fun formatValue(value: Float): String {
        return if (value >= 1_000f) {
            "${((value / 100f).toInt()) / 10f}k"
        } else {
            value.toInt().toString()
        }
    }

    private fun drawWorkoutMarker(
        canvas: Canvas,
        centerX: Float,
        chartTop: Float,
        barWidth: Float
    ) {
        val rect = RectF(
            centerX - (barWidth / 2f),
            chartTop + (4f * density),
            centerX + (barWidth / 2f),
            chartTop + (14f * density)
        )
        canvas.drawRoundRect(rect, 5f * density, 5f * density, workoutPaint)
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
                    TimelineSeries.WORKOUT -> {
                        if (entry.workoutKind == null) {
                            current.workoutMarker = true
                        }
                    }
                }
                minute = bucketEnd
            }
        }

        return buckets
    }

    private fun extractWorkoutOverlays(entries: List<TimelineBarEntry>): List<WorkoutOverlay> {
        return entries.mapNotNull { entry ->
            if (entry.series != TimelineSeries.WORKOUT) return@mapNotNull null
            val kind = entry.workoutKind ?: return@mapNotNull null
            WorkoutOverlay(
                startMinute = entry.startMinute.coerceIn(0, 1439),
                endMinute = entry.endMinute.coerceIn(entry.startMinute + 1, 1440),
                midpointMinute = ((entry.startMinute + entry.endMinute) / 2).coerceIn(0, 1440),
                kind = kind,
                title = entry.workoutTitle.orEmpty(),
                detail = entry.workoutDetail.orEmpty(),
                isProjected = entry.workoutIsProjected
            )
        }
    }
}

private data class WorkoutOverlay(
    val startMinute: Int,
    val endMinute: Int,
    val midpointMinute: Int,
    val kind: TimelineWorkoutKind,
    val title: String,
    val detail: String,
    val isProjected: Boolean
)

private data class TimelineBucket(
    var existingValue: Float = 0f,
    var projectedValue: Float = 0f,
    var projectedEmphasized: Boolean = false,
    var workoutMarker: Boolean = false
)
