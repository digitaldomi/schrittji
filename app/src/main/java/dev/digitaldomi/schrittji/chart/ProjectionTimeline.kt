package dev.digitaldomi.schrittji.chart

import dev.digitaldomi.schrittji.simulation.MinuteStepSlice
import java.time.LocalDate
import java.time.ZoneId

object ProjectionTimeline {
    fun sliceToProjectedEntry(slice: MinuteStepSlice, date: LocalDate, zoneId: ZoneId): TimelineBarEntry {
        val zStart = slice.start.withZoneSameInstant(zoneId)
        val zEnd = slice.end.withZoneSameInstant(zoneId)
        val startSec = zStart.toLocalTime().toSecondOfDay()
        val endSec = zEnd.toLocalTime().toSecondOfDay().coerceAtLeast(startSec + 1)
        return TimelineBarEntry(
            startMinute = zStart.hour * 60 + zStart.minute,
            endMinute = zEnd.hour * 60 + zEnd.minute,
            value = slice.count.toFloat(),
            series = TimelineSeries.PROJECTED,
            emphasized = false,
            startSecondOfDay = startSec,
            endSecondOfDay = endSec
        )
    }

    /**
     * Keeps only the portion of each minute slice whose [start,end) lies after [nowSec] (exclusive of past).
     */
    fun splitSlicesAtNow(
        slices: List<MinuteStepSlice>,
        date: LocalDate,
        zoneId: ZoneId,
        nowSec: Int
    ): List<TimelineBarEntry> {
        val out = mutableListOf<TimelineBarEntry>()
        for (slice in slices) {
            val zStart = slice.start.withZoneSameInstant(zoneId)
            if (zStart.toLocalDate() != date) continue
            val zEnd = slice.end.withZoneSameInstant(zoneId)
            val startSec = zStart.toLocalTime().toSecondOfDay()
            val endSec = zEnd.toLocalTime().toSecondOfDay().coerceAtLeast(startSec + 1)
            if (endSec <= nowSec) continue
            val effStart = maxOf(startSec, nowSec)
            if (effStart >= endSec) continue
            val span = (endSec - startSec).coerceAtLeast(1)
            val portion = (endSec - effStart).toFloat() / span.toFloat()
            val zs = date.atStartOfDay(zoneId).plusSeconds(effStart.toLong())
            val ze = date.atStartOfDay(zoneId).plusSeconds(endSec.toLong())
            out += TimelineBarEntry(
                startMinute = zs.hour * 60 + zs.minute,
                endMinute = ze.hour * 60 + ze.minute,
                value = slice.count.toFloat() * portion,
                series = TimelineSeries.PROJECTED,
                emphasized = false,
                startSecondOfDay = effStart,
                endSecondOfDay = endSec
            )
        }
        return out
    }
}
