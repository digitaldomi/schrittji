package dev.digitaldomi.schrittji.chart

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import dev.digitaldomi.schrittji.R
import kotlin.math.max

enum class DetailComparisonBar {
    EXISTING,
    PROJECTED
}

class DetailComparisonChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val existingColor = context.getColor(R.color.chart_existing_health)
    private val projectedColor = context.getColor(R.color.chart_projected_schrittji)
    private val textColor = context.getColor(R.color.brand_text)
    private val axisColor = context.getColor(R.color.panel_stroke)
    private val mutedIconAlpha = 55

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
        strokeWidth = 1f * density
        color = axisColor
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 11f * scaledDensity
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.CENTER
        textSize = 10f * scaledDensity
        alpha = 200
    }
    private val maxLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.LEFT
        textSize = 10f * scaledDensity
        alpha = 170
    }

    private var existingSteps: Float = 0f
    private var projectedSteps: Float = 0f
    private var showRun: Boolean = false
    private var showCycle: Boolean = false

    private val runDrawable = AppCompatResources.getDrawable(context, R.drawable.ic_activity_run)
    private val cycleDrawable = AppCompatResources.getDrawable(context, R.drawable.ic_activity_cycle)

    private var barHitLeft: RectF = RectF()
    private var barHitRight: RectF = RectF()

    var onBarClickListener: ((DetailComparisonBar) -> Unit)? = null

    fun submitData(
        existingSteps: Float,
        projectedSteps: Float,
        showRunIcon: Boolean,
        showCycleIcon: Boolean
    ) {
        this.existingSteps = existingSteps
        this.projectedSteps = projectedSteps
        this.showRun = showRunIcon
        this.showCycle = showCycleIcon
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (188 * density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val chartLeft = paddingLeft + 10f * density
        val chartRight = width - paddingRight - 10f * density
        val chartTop = paddingTop + 36f * density
        val chartBottom = height - paddingBottom - 22f * density
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop
        val maxValue = max(1f, max(existingSteps, projectedSteps))

        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

        val slotWidth = chartWidth / 2f
        val barWidth = (slotWidth * 0.48f).coerceAtLeast(22f * density)
        val gap = slotWidth / 2f

        val leftCenter = chartLeft + gap
        val rightCenter = chartLeft + gap + slotWidth

        val existingH = (existingSteps / maxValue) * chartHeight
        val projectedH = (projectedSteps / maxValue) * chartHeight

        val leftRect = RectF(
            leftCenter - barWidth / 2f,
            chartBottom - existingH,
            leftCenter + barWidth / 2f,
            chartBottom
        )
        val rightRect = RectF(
            rightCenter - barWidth / 2f,
            chartBottom - projectedH,
            rightCenter + barWidth / 2f,
            chartBottom
        )

        val valueLift = 4f * density
        val iconSize = (20 * density).toInt()
        val iconGap = 10f * density

        val existingValueY = leftRect.top - valueLift
        val projectedValueY = rightRect.top - valueLift
        val existingIconTop = existingValueY - iconGap - iconSize
        val projectedIconTop = projectedValueY - iconGap - iconSize

        val hitTop = minOf(existingIconTop, projectedIconTop) - 4f * density
        barHitLeft = RectF(leftRect.left - 8f * density, hitTop, leftRect.right + 8f * density, chartBottom + 4f * density)
        barHitRight = RectF(rightRect.left - 8f * density, hitTop, rightRect.right + 8f * density, chartBottom + 4f * density)

        canvas.drawRoundRect(leftRect, 6f * density, 6f * density, existingPaint)
        canvas.drawRoundRect(rightRect, 6f * density, 6f * density, projectedPaint)

        drawIconPair(canvas, leftCenter, existingIconTop, iconSize, showRun, showCycle)
        drawIconPair(canvas, rightCenter, projectedIconTop, iconSize, showRun, showCycle)

        canvas.drawText(formatValue(existingSteps), leftCenter, existingValueY, valuePaint)
        canvas.drawText(formatValue(projectedSteps), rightCenter, projectedValueY, valuePaint)

        canvas.drawText(
            context.getString(R.string.detail_chart_label_existing),
            leftCenter,
            chartBottom + (16f * density),
            labelPaint
        )
        canvas.drawText(
            context.getString(R.string.detail_chart_label_projected),
            rightCenter,
            chartBottom + (16f * density),
            labelPaint
        )

        canvas.drawText(
            "max ${formatValue(maxValue)}",
            chartLeft,
            paddingTop + (14f * density),
            maxLabelPaint
        )
    }

    private fun drawIconPair(
        canvas: Canvas,
        centerX: Float,
        top: Float,
        iconSize: Int,
        runActive: Boolean,
        cycleActive: Boolean
    ) {
        val spacing = 6f * density
        val totalW = iconSize * 2 + spacing
        val startX = centerX - totalW / 2f
        drawOneIcon(canvas, runDrawable, startX, top, iconSize, runActive)
        drawOneIcon(canvas, cycleDrawable, startX + iconSize + spacing, top, iconSize, cycleActive)
    }

    private fun drawOneIcon(
        canvas: Canvas,
        drawable: android.graphics.drawable.Drawable?,
        left: Float,
        top: Float,
        size: Int,
        active: Boolean
    ) {
        if (drawable == null) return
        val l = left.toInt()
        val t = top.toInt()
        drawable.alpha = if (active) 255 else mutedIconAlpha
        drawable.setBounds(l, t, l + size, t + size)
        drawable.draw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            when {
                barHitLeft.contains(x, y) -> {
                    onBarClickListener?.invoke(DetailComparisonBar.EXISTING)
                    performClick()
                    return true
                }
                barHitRight.contains(x, y) -> {
                    onBarClickListener?.invoke(DetailComparisonBar.PROJECTED)
                    performClick()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
