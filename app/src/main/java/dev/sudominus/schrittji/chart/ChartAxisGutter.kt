package dev.sudominus.schrittji.chart

import android.graphics.Paint

/**
 * Y-axis gutter width from the widest formatted tick so labels are not clipped
 * without reserving excessive left padding.
 */
fun computeYAxisGutterPx(
    scale: ChartAxisLabels.Scale,
    labelPaint: Paint,
    minGutterPx: Float,
    tickOverhangPx: Float,
    axisToLabelPadPx: Float
): Float {
    val maxW = ChartAxisLabels.tickValues(scale).maxOfOrNull { labelPaint.measureText(ChartAxisLabels.formatTick(it)) }
        ?: 0f
    return (tickOverhangPx + maxW + axisToLabelPadPx).coerceAtLeast(minGutterPx)
}
