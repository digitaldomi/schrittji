package dev.digitaldomi.schrittji.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.View.MeasureSpec
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
    CYCLING,
    MINDFULNESS
}

private enum class WorkoutOverlayCategory {
    CARDIO_RECORDED,
    CARDIO_PROJECTED,
    MINDFULNESS_RECORDED,
    MINDFULNESS_PROJECTED
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
    val workoutIsProjected: Boolean = true,
    /**
     * Inclusive start / exclusive end of the interval in seconds since local midnight (0..86400).
     * When set, clipping uses sub-minute precision; otherwise [startMinute] and [endMinute] are used as minute indices.
     */
    val startSecondOfDay: Int? = null,
    val endSecondOfDay: Int? = null
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
    private val workoutColorRecorded = context.getColor(R.color.chart_workout)
    private val workoutColorProjected = context.getColor(R.color.chart_workout_projected)
    private val workoutColorMindfulnessRecorded = context.getColor(R.color.chart_workout_mindfulness)
    private val workoutColorMindfulnessProjected = context.getColor(R.color.chart_workout_mindfulness_projected)
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
        color = workoutColorRecorded
        alpha = 220
    }
    private val workoutStripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = workoutColorRecorded
        alpha = 235
    }
    private val workoutStripPaintProjected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = workoutColorProjected
        alpha = 200
    }
    private val workoutStripPaintMindfulnessRecorded = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = workoutColorMindfulnessRecorded
        alpha = 220
    }
    private val workoutStripPaintMindfulnessProjected = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = workoutColorMindfulnessProjected
        alpha = 200
    }
    private val workoutUnderlayRecordedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.chart_workout_underlay)
    }
    private val workoutUnderlayProjectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.chart_workout_underlay_projected)
    }
    private val workoutUnderlayMindfulnessRecordedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.chart_workout_underlay_mindfulness)
    }
    private val workoutUnderlayMindfulnessProjectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.chart_workout_underlay_mindfulness_projected)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = axisColor
    }
    private val nowLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = context.getColor(R.color.chart_now_line)
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

    private var buckets: List<TimelineBucket> = emptyList()
    private var maxValue: Float = 1f
    private var workoutOverlays: List<WorkoutOverlay> = emptyList()
    private var hitTargets: List<Pair<RectF, WorkoutTapInfo>> = emptyList()
    /** When set (e.g. for “today”), existing/HC data is clipped to before this minute-of-day; projected after. */
    private var nowMarkerMinuteOfDay: Float? = null

    private val runDrawable = ContextCompat.getDrawable(context, R.drawable.ic_workout_run)?.mutate()
    private val cycleDrawable = ContextCompat.getDrawable(context, R.drawable.ic_workout_cycle)?.mutate()
    private val mindfulnessDrawable = ContextCompat.getDrawable(context, R.drawable.ic_workout_mindfulness)?.mutate()

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

    /**
     * Optional vertical “now” line and bucket clipping: minute-of-day in [0, 1440), fractional allowed.
     * Pass `null` for past/future days or when no split is needed.
     */
    fun setNowMarkerMinuteOfDay(minuteOfDay: Float?) {
        nowMarkerMinuteOfDay = minuteOfDay
    }

    fun submitEntries(entries: List<TimelineBarEntry>) {
        buckets = buildBuckets(entries, nowMarkerMinuteOfDay)
        workoutOverlays = extractWorkoutOverlays(entries, nowMarkerMinuteOfDay)
        maxValue = max(
            1f,
            buckets.maxOfOrNull { max(it.existingValue, it.projectedValue) } ?: 1f
        )
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        return handled || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val chartLeft = paddingLeft + yAxisGutterPx
        val chartRight = width - paddingRight - 12f * density
        val chartTop = paddingTop + 10f * density
        val chartBottom = height - paddingBottom - 18f * density
        val chartWidth = chartRight - chartLeft

        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, axisPaint)
        drawYAxisLabels(canvas, chartLeft, chartTop, chartBottom, maxValue)

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

        nowMarkerMinuteOfDay?.let { nowM ->
            val xNow = minuteToX(nowM, chartLeft, chartWidth)
            canvas.drawLine(xNow, chartTop, xNow, chartBottom, nowLinePaint)
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(context.getString(R.string.chart_now_label), xNow, chartTop - (6f * density), labelPaint)
        }

        workoutOverlays.forEach { overlay ->
            val xStart = minuteToX(overlay.startMinuteFloat, chartLeft, chartWidth)
            val xEnd = minuteToX(overlay.endMinuteFloat, chartLeft, chartWidth)
            val left = min(xStart, xEnd)
            val right = max(xStart, xEnd).coerceAtLeast(left + (3f * density))
            canvas.drawRect(left, chartTop, right, chartBottom, underlayPaintFor(overlay.category))
        }

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
            val xStart = minuteToX(overlay.startMinuteFloat, chartLeft, chartWidth)
            val xEnd = minuteToX(overlay.endMinuteFloat, chartLeft, chartWidth)
            val left = min(xStart, xEnd)
            val right = max(xStart, xEnd).coerceAtLeast(left + (3f * density))
            canvas.drawRoundRect(
                RectF(left, workoutStripTop, right, workoutStripBottom),
                3f * density,
                3f * density,
                stripPaintFor(overlay.category)
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
                TimelineWorkoutKind.MINDFULNESS -> mindfulnessDrawable
            }
            if (drawable != null) {
                val tint = iconTintFor(overlay.category)
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
        return minuteToX(minute.toFloat(), chartLeft, chartWidth)
    }

    private fun minuteToX(minute: Float, chartLeft: Float, chartWidth: Float): Float {
        val m = minute.coerceIn(0f, 1440f)
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
        val sorted = overlays.sortedWith(compareBy({ it.startMinuteFloat }, { it.endMinuteFloat }))
        val placed = mutableListOf<RectF>()
        val result = mutableListOf<Triple<WorkoutOverlay, Float, Float>>()

        for (overlay in sorted) {
            val centerX = minuteToX((overlay.startMinuteFloat + overlay.endMinuteFloat) / 2f, chartLeft, chartWidth)
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
            canvas.drawText(formatValue(v), labelX, y + (3f * density), yAxisLabelPaint)
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

    private fun buildBuckets(entries: List<TimelineBarEntry>, nowSplit: Float?): List<TimelineBucket> {
        if (entries.isEmpty()) return emptyList()

        val bucketMinutes = 1440 / bucketCount
        val buckets = MutableList(bucketCount) { TimelineBucket() }

        entries.forEach { entry ->
            val startMin: Float
            val endMin: Float
            if (entry.startSecondOfDay != null && entry.endSecondOfDay != null) {
                startMin = (entry.startSecondOfDay.coerceIn(0, 86400)) / 60f
                endMin = (entry.endSecondOfDay.coerceIn(0, 86400)) / 60f
            } else {
                val start = entry.startMinute.coerceIn(0, 1439)
                val endExclusive = entry.endMinute.coerceIn(start + 1, 1440)
                startMin = start.toFloat()
                endMin = endExclusive.toFloat()
            }
            val totalSpan = (endMin - startMin).coerceAtLeast(1f / 60f)

            when (entry.series) {
                TimelineSeries.WORKOUT -> {
                    if (entry.workoutKind == null) {
                        var m = startMin.toInt()
                        val endI = endMin.toInt().coerceAtMost(1440)
                        while (m < endI) {
                            val bucketIndex = (m / bucketMinutes).coerceIn(0, bucketCount - 1)
                            val bucketEnd = ((bucketIndex + 1) * bucketMinutes).coerceAtMost(endI)
                            buckets[bucketIndex].workoutMarker = true
                            m = bucketEnd
                        }
                    }
                }
                TimelineSeries.EXISTING, TimelineSeries.PROJECTED -> {
                    val rangeLo: Float
                    val rangeHi: Float
                    if (nowSplit != null) {
                        when (entry.series) {
                            TimelineSeries.EXISTING -> {
                                rangeLo = 0f
                                rangeHi = nowSplit
                            }
                            TimelineSeries.PROJECTED -> {
                                rangeLo = nowSplit
                                rangeHi = 1440f
                            }
                            else -> return@forEach
                        }
                    } else {
                        rangeLo = 0f
                        rangeHi = 1440f
                    }

                    for (bucketIndex in 0 until bucketCount) {
                        val bs = bucketIndex * bucketMinutes
                        val be = (bucketIndex + 1) * bucketMinutes
                        val overlap = segmentOverlap(
                            startMin,
                            endMin,
                            max(rangeLo, bs.toFloat()),
                            min(rangeHi, be.toFloat())
                        )
                        if (overlap <= 0f) continue
                        val portion = entry.value * (overlap / totalSpan)
                        val current = buckets[bucketIndex]
                        when (entry.series) {
                            TimelineSeries.EXISTING -> current.existingValue += portion
                            TimelineSeries.PROJECTED -> {
                                current.projectedValue += portion
                                current.projectedEmphasized =
                                    current.projectedEmphasized || entry.emphasized
                            }
                            else -> {}
                        }
                    }
                }
            }
        }

        return buckets
    }

    private fun segmentOverlap(a0: Float, a1: Float, b0: Float, b1: Float): Float {
        val lo = max(a0, b0)
        val hi = min(a1, b1)
        return (hi - lo).coerceAtLeast(0f)
    }

    private fun extractWorkoutOverlays(
        entries: List<TimelineBarEntry>,
        nowSplit: Float?
    ): List<WorkoutOverlay> {
        return entries.mapNotNull { entry ->
            if (entry.series != TimelineSeries.WORKOUT) return@mapNotNull null
            val kind = entry.workoutKind ?: return@mapNotNull null

            var startM = entry.startMinute.toFloat().coerceIn(0f, 1439f)
            var endM = entry.endMinute.toFloat().coerceIn(startM + 1f, 1440f)

            if (nowSplit != null) {
                if (entry.workoutIsProjected) {
                    startM = max(startM, nowSplit)
                } else {
                    endM = min(endM, nowSplit)
                }
            }

            if (startM >= endM) return@mapNotNull null

            val category = when (kind) {
                TimelineWorkoutKind.MINDFULNESS ->
                    if (entry.workoutIsProjected) WorkoutOverlayCategory.MINDFULNESS_PROJECTED
                    else WorkoutOverlayCategory.MINDFULNESS_RECORDED
                else ->
                    if (entry.workoutIsProjected) WorkoutOverlayCategory.CARDIO_PROJECTED
                    else WorkoutOverlayCategory.CARDIO_RECORDED
            }
            WorkoutOverlay(
                startMinuteFloat = startM.coerceIn(0f, 1440f),
                endMinuteFloat = endM.coerceIn(0f, 1440f),
                kind = kind,
                category = category,
                title = entry.workoutTitle.orEmpty(),
                detail = entry.workoutDetail.orEmpty(),
                isProjected = entry.workoutIsProjected
            )
        }
    }

    private fun underlayPaintFor(category: WorkoutOverlayCategory): Paint {
        return when (category) {
            WorkoutOverlayCategory.CARDIO_RECORDED -> workoutUnderlayRecordedPaint
            WorkoutOverlayCategory.CARDIO_PROJECTED -> workoutUnderlayProjectedPaint
            WorkoutOverlayCategory.MINDFULNESS_RECORDED -> workoutUnderlayMindfulnessRecordedPaint
            WorkoutOverlayCategory.MINDFULNESS_PROJECTED -> workoutUnderlayMindfulnessProjectedPaint
        }
    }

    private fun stripPaintFor(category: WorkoutOverlayCategory): Paint {
        return when (category) {
            WorkoutOverlayCategory.CARDIO_RECORDED -> workoutStripPaint
            WorkoutOverlayCategory.CARDIO_PROJECTED -> workoutStripPaintProjected
            WorkoutOverlayCategory.MINDFULNESS_RECORDED -> workoutStripPaintMindfulnessRecorded
            WorkoutOverlayCategory.MINDFULNESS_PROJECTED -> workoutStripPaintMindfulnessProjected
        }
    }

    private fun iconTintFor(category: WorkoutOverlayCategory): Int {
        return when (category) {
            WorkoutOverlayCategory.CARDIO_RECORDED -> workoutColorRecorded
            WorkoutOverlayCategory.CARDIO_PROJECTED -> workoutColorProjected
            WorkoutOverlayCategory.MINDFULNESS_RECORDED -> workoutColorMindfulnessRecorded
            WorkoutOverlayCategory.MINDFULNESS_PROJECTED -> workoutColorMindfulnessProjected
        }
    }
}

private data class WorkoutOverlay(
    /** Minutes since midnight (fractional) for drawing. */
    val startMinuteFloat: Float,
    val endMinuteFloat: Float,
    val kind: TimelineWorkoutKind,
    val category: WorkoutOverlayCategory,
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
