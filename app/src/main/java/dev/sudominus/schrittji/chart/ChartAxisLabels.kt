package dev.sudominus.schrittji.chart

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Y-axis scale with round tick steps (1/2/5 × 10ⁿ) so labels are never odd values like 620 or 4.3k.
 */
object ChartAxisLabels {
    data class Scale(val axisMax: Float, val step: Float)

    fun computeScale(dataMax: Float, preferredTicks: Int = 5): Scale {
        val dm = max(dataMax, 1f)
        var rough = dm / preferredTicks.toFloat()
        if (rough < 1e-6f) rough = 1f
        val exp = floor(log10(rough.toDouble())).toInt()
        val magnitude = 10.0.pow(exp).toFloat()
        val normalized = rough / magnitude
        var step = when {
            normalized <= 1f -> magnitude
            normalized <= 2f -> 2f * magnitude
            normalized <= 5f -> 5f * magnitude
            else -> 10f * magnitude
        }
        var axisMax = ceil((dm / step).toDouble()).toFloat() * step
        if (axisMax < dm) axisMax += step
        var tickCount = (axisMax / step).toInt() + 1
        while (tickCount > 7) {
            step *= 2f
            axisMax = ceil((dm / step).toDouble()).toFloat() * step
            if (axisMax < dm) axisMax += step
            tickCount = (axisMax / step).toInt() + 1
        }
        return Scale(axisMax, step)
    }

    fun tickValues(scale: Scale): List<Float> {
        val out = mutableListOf<Float>()
        var v = 0f
        while (v <= scale.axisMax + scale.step * 0.01f) {
            out.add(v)
            v += scale.step
        }
        return out
    }

    fun formatTick(value: Float): String {
        val i = value.toInt()
        if (i >= 1_000_000 && i % 1_000_000 == 0) return "${i / 1_000_000}M"
        if (i >= 1_000 && i % 1_000 == 0) return "${i / 1_000}k"
        return i.toString()
    }
}
