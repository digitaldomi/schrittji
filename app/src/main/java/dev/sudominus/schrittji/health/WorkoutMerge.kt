package dev.sudominus.schrittji.health

import dev.sudominus.schrittji.simulation.WorkoutPlan
import java.time.ZonedDateTime

/**
 * Detects when a Health Connect exercise session is the same event as a projected [WorkoutPlan]
 * (e.g. after Schrittji wrote the session), so we show it once as HC on the chart and in text.
 */
object WorkoutMerge {
    fun overlaps(
        aStart: ZonedDateTime,
        aEnd: ZonedDateTime,
        bStart: ZonedDateTime,
        bEnd: ZonedDateTime
    ): Boolean {
        val asMin = minOf(aStart, aEnd)
        val ae = maxOf(aStart, aEnd)
        val bs = minOf(bStart, bEnd)
        val be = maxOf(bStart, bEnd)
        return asMin.isBefore(be) && bs.isBefore(ae)
    }

    fun hcMatchesProjectedPlan(hc: HealthConnectExerciseSession, plan: WorkoutPlan): Boolean {
        if (hc.type != plan.type) return false
        return overlaps(hc.start, hc.end, plan.start, plan.end)
    }
}
