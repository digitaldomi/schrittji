package dev.digitaldomi.schrittji.simulation

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

data class MinuteStepSlice(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val count: Long
)

private data class WindowPlan(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val shareWeight: Double,
    val minPace: Int,
    val maxPace: Int,
    val pauseChance: Double,
    val continuity: Double
)

class StepSimulationEngine {
    fun generateBetween(
        startInclusive: ZonedDateTime,
        endExclusive: ZonedDateTime,
        config: SimulationConfig
    ): List<MinuteStepSlice> {
        if (!startInclusive.isBefore(endExclusive)) {
            return emptyList()
        }

        val zone = startInclusive.zone
        val startDate = startInclusive.toLocalDate()
        val endDate = endExclusive.minusNanos(1).withZoneSameInstant(zone).toLocalDate()
        val slices = mutableListOf<MinuteStepSlice>()

        var date = startDate
        while (!date.isAfter(endDate)) {
            slices += generateDay(date, zone, config).filter { slice ->
                slice.end.isAfter(startInclusive) && slice.start.isBefore(endExclusive)
            }
            date = date.plusDays(1)
        }

        return slices.sortedBy { it.start.toInstant() }
    }

    private fun generateDay(
        date: LocalDate,
        zoneId: ZoneId,
        config: SimulationConfig
    ): List<MinuteStepSlice> {
        val daySeed = config.randomSeed xor (date.toEpochDay() * -7046029254386353131L)
        val random = Random(daySeed)
        val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        val targetSteps = computeTargetSteps(date, config, random, isWeekend)
        val wakeTime = buildWakeTime(date, zoneId, config.profile, random, isWeekend)
        val sleepTime = buildSleepTime(date, zoneId, config.profile, random, isWeekend)
        val minuteCounts = linkedMapOf<ZonedDateTime, Int>()
        val windows = buildWindows(date, zoneId, wakeTime, sleepTime, config.profile, random, isWeekend)

        val weightTotal = windows.sumOf { it.shareWeight }.takeIf { it > 0.0 } ?: 1.0
        windows.forEach { window ->
            val windowTarget = (targetSteps * (window.shareWeight / weightTotal)).roundToInt()
            populateWindow(minuteCounts, window, windowTarget, random)
        }

        addAmbientMovement(minuteCounts, wakeTime, sleepTime, targetSteps, random)
        rebalanceToTarget(minuteCounts, wakeTime, sleepTime, targetSteps, random)

        return minuteCounts.entries
            .sortedBy { it.key.toInstant() }
            .map { (start, count) ->
                MinuteStepSlice(
                    start = start,
                    end = start.plusMinutes(1),
                    count = count.toLong()
                )
            }
    }

    private fun computeTargetSteps(
        date: LocalDate,
        config: SimulationConfig,
        random: Random,
        isWeekend: Boolean
    ): Int {
        val minimum = config.minimumDailySteps.coerceAtLeast(1_000)
        val maximum = config.maximumDailySteps.coerceAtLeast(minimum + 500)
        val range = maximum - minimum
        val blendedRandom = (random.nextDouble() + random.nextDouble() + random.nextDouble()) / 3.0
        val weekdayBias = when (config.profile) {
            SimulationProfile.OFFICE_COMMUTER -> if (isWeekend) 0.94 else 1.0
            SimulationProfile.HYBRID_ERRANDS -> if (isWeekend) 1.02 else 0.98
            SimulationProfile.ACTIVE_SOCIAL -> if (isWeekend) 1.08 else 1.0
        }
        val longerWave = 1.0 + (sin(date.toEpochDay() / 5.5 * PI / 2.0) * 0.07)
        val target = minimum + (range * blendedRandom).roundToInt()
        return (target * weekdayBias * longerWave).roundToInt().coerceIn(minimum, maximum)
    }

    private fun buildWakeTime(
        date: LocalDate,
        zoneId: ZoneId,
        profile: SimulationProfile,
        random: Random,
        isWeekend: Boolean
    ): ZonedDateTime {
        val baseMinutes = when (profile) {
            SimulationProfile.OFFICE_COMMUTER -> if (isWeekend) 8 * 60 + 42 else 6 * 60 + 52
            SimulationProfile.HYBRID_ERRANDS -> if (isWeekend) 8 * 60 + 18 else 7 * 60 + 16
            SimulationProfile.ACTIVE_SOCIAL -> if (isWeekend) 8 * 60 + 5 else 6 * 60 + 36
        }
        val jitter = if (isWeekend) random.nextInt(-35, 56) else random.nextInt(-28, 42)
        return date.atStartOfDay(zoneId).plusMinutes((baseMinutes + jitter).toLong())
    }

    private fun buildSleepTime(
        date: LocalDate,
        zoneId: ZoneId,
        profile: SimulationProfile,
        random: Random,
        isWeekend: Boolean
    ): ZonedDateTime {
        val baseMinutes = when (profile) {
            SimulationProfile.OFFICE_COMMUTER -> if (isWeekend) 23 * 60 + 28 else 22 * 60 + 52
            SimulationProfile.HYBRID_ERRANDS -> if (isWeekend) 23 * 60 + 10 else 23 * 60 + 2
            SimulationProfile.ACTIVE_SOCIAL -> if (isWeekend) 23 * 60 + 42 else 23 * 60 + 12
        }
        val jitter = if (isWeekend) random.nextInt(-25, 41) else random.nextInt(-22, 30)
        return date.atStartOfDay(zoneId).plusMinutes((baseMinutes + jitter).toLong())
    }

    private fun buildWindows(
        date: LocalDate,
        zoneId: ZoneId,
        wakeTime: ZonedDateTime,
        sleepTime: ZonedDateTime,
        profile: SimulationProfile,
        random: Random,
        isWeekend: Boolean
    ): List<WindowPlan> {
        val windows = mutableListOf<WindowPlan>()

        addWindow(
            windows = windows,
            start = wakeTime.plusMinutes(random.nextInt(12, 32).toLong()),
            durationMinutes = random.nextInt(7, 18),
            shareWeight = 0.09,
            minPace = 58,
            maxPace = 84,
            pauseChance = 0.18,
            continuity = 0.82,
            sleepTime = sleepTime
        )

        when (profile) {
            SimulationProfile.OFFICE_COMMUTER -> {
                if (isWeekend) {
                    addWindow(windows, atTime(date, zoneId, 10, 48, random, 40), random.nextInt(16, 36), 0.19, 82, 112, 0.12, 0.9, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 14, 8, random, 75), random.nextInt(18, 46), 0.26, 78, 114, 0.16, 0.88, sleepTime)
                    maybeAddWindow(windows, random, 0.72) {
                        WindowPlan(atTime(date, zoneId, 18, 42, random, 70), atTime(date, zoneId, 19, 24, random, 65), 0.15, 74, 104, 0.2, 0.86)
                    }
                } else {
                    addWindow(windows, atTime(date, zoneId, 7, 44, random, 28), random.nextInt(12, 25), 0.18, 84, 118, 0.08, 0.92, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 10, 34, random, 35), random.nextInt(4, 12), 0.04, 54, 76, 0.28, 0.8, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 12, 22, random, 35), random.nextInt(11, 28), 0.14, 82, 116, 0.12, 0.89, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 15, 26, random, 48), random.nextInt(4, 11), 0.03, 48, 72, 0.28, 0.82, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 17, 48, random, 38), random.nextInt(12, 28), 0.2, 84, 118, 0.1, 0.9, sleepTime)
                    maybeAddWindow(windows, random, 0.64) {
                        WindowPlan(atTime(date, zoneId, 19, 4, random, 55), atTime(date, zoneId, 19, 34, random, 55), 0.09, 72, 102, 0.2, 0.85)
                    }
                    maybeAddWindow(windows, random, 0.56) {
                        WindowPlan(atTime(date, zoneId, 20, 6, random, 45), atTime(date, zoneId, 20, 56, random, 55), 0.16, 86, 122, 0.12, 0.9)
                    }
                }
            }

            SimulationProfile.HYBRID_ERRANDS -> {
                if (isWeekend) {
                    addWindow(windows, atTime(date, zoneId, 9, 58, random, 45), random.nextInt(14, 28), 0.13, 72, 98, 0.2, 0.85, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 12, 54, random, 60), random.nextInt(20, 44), 0.24, 78, 112, 0.14, 0.88, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 17, 18, random, 90), random.nextInt(24, 52), 0.26, 84, 118, 0.1, 0.9, sleepTime)
                    maybeAddWindow(windows, random, 0.68) {
                        WindowPlan(atTime(date, zoneId, 20, 18, random, 50), atTime(date, zoneId, 20, 38, random, 45), 0.08, 60, 88, 0.22, 0.82)
                    }
                } else {
                    addWindow(windows, atTime(date, zoneId, 8, 18, random, 35), random.nextInt(8, 18), 0.11, 72, 96, 0.18, 0.86, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 12, 18, random, 45), random.nextInt(12, 28), 0.17, 78, 112, 0.14, 0.88, sleepTime)
                    maybeAddWindow(windows, random, 0.74) {
                        WindowPlan(atTime(date, zoneId, 15, 48, random, 75), atTime(date, zoneId, 16, 16, random, 75), 0.13, 68, 98, 0.18, 0.84)
                    }
                    addWindow(windows, atTime(date, zoneId, 18, 34, random, 55), random.nextInt(20, 48), 0.23, 82, 118, 0.12, 0.9, sleepTime)
                    maybeAddWindow(windows, random, 0.6) {
                        WindowPlan(atTime(date, zoneId, 20, 44, random, 35), atTime(date, zoneId, 20, 58, random, 35), 0.06, 58, 82, 0.24, 0.8)
                    }
                }
            }

            SimulationProfile.ACTIVE_SOCIAL -> {
                if (isWeekend) {
                    addWindow(windows, atTime(date, zoneId, 8, 34, random, 38), random.nextInt(20, 40), 0.2, 84, 122, 0.08, 0.92, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 12, 42, random, 55), random.nextInt(16, 34), 0.14, 74, 104, 0.16, 0.88, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 16, 8, random, 90), random.nextInt(18, 42), 0.17, 78, 108, 0.14, 0.88, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 19, 22, random, 75), random.nextInt(26, 56), 0.27, 86, 124, 0.08, 0.91, sleepTime)
                } else {
                    addWindow(windows, atTime(date, zoneId, 7, 6, random, 28), random.nextInt(16, 34), 0.19, 84, 124, 0.08, 0.92, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 12, 18, random, 45), random.nextInt(12, 26), 0.14, 76, 108, 0.16, 0.88, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 16, 24, random, 60), random.nextInt(14, 28), 0.15, 76, 108, 0.16, 0.88, sleepTime)
                    addWindow(windows, atTime(date, zoneId, 19, 8, random, 55), random.nextInt(22, 50), 0.24, 86, 124, 0.1, 0.9, sleepTime)
                    maybeAddWindow(windows, random, 0.58) {
                        WindowPlan(atTime(date, zoneId, 21, 18, random, 35), atTime(date, zoneId, 21, 32, random, 30), 0.06, 60, 86, 0.22, 0.82)
                    }
                }
            }
        }

        repeat(random.nextInt(3, 7)) {
            val awakeMinutes = ChronoUnit.MINUTES.between(wakeTime, sleepTime).toInt().coerceAtLeast(90)
            val start = wakeTime.plusMinutes(random.nextInt(35, awakeMinutes - 20).toLong())
            addWindow(
                windows = windows,
                start = start,
                durationMinutes = random.nextInt(2, 7),
                shareWeight = random.nextDouble(0.01, 0.025),
                minPace = 38,
                maxPace = 68,
                pauseChance = 0.32,
                continuity = 0.76,
                sleepTime = sleepTime
            )
        }

        return windows.mapNotNull { window ->
            val clampedStart = if (window.start.isBefore(wakeTime)) wakeTime else window.start
            val clampedEnd = if (window.end.isAfter(sleepTime)) sleepTime else window.end
            if (clampedStart.isBefore(clampedEnd)) {
                window.copy(start = clampedStart, end = clampedEnd)
            } else {
                null
            }
        }
    }

    private fun populateWindow(
        minuteCounts: MutableMap<ZonedDateTime, Int>,
        window: WindowPlan,
        targetSteps: Int,
        random: Random
    ) {
        if (targetSteps <= 0) {
            return
        }

        val usedMinutes = mutableListOf<ZonedDateTime>()
        var remaining = targetSteps
        var cursor = window.start.truncatedTo(ChronoUnit.MINUTES)
        var priorPace = random.nextInt(window.minPace, window.maxPace + 1).toDouble()

        while (cursor.isBefore(window.end) && remaining > 0) {
            if (random.nextDouble() < window.pauseChance) {
                cursor = cursor.plusMinutes(random.nextLong(1, 3))
                continue
            }

            val remainingWindowMinutes = ChronoUnit.MINUTES.between(cursor, window.end).toInt()
            if (remainingWindowMinutes <= 0) {
                break
            }

            val segmentLength = minOf(remainingWindowMinutes, random.nextInt(2, 8))
            repeat(segmentLength) {
                if (!cursor.isBefore(window.end) || remaining <= 0) {
                    return@repeat
                }

                val targetPace = random.nextInt(window.minPace, window.maxPace + 1).toDouble()
                val noise = random.nextDouble(-8.0, 9.0)
                priorPace = (window.continuity * priorPace) + ((1.0 - window.continuity) * targetPace) + noise
                val rawCount = priorPace.roundToInt().coerceIn(window.minPace, window.maxPace)
                val count = rawCount.coerceAtMost(remaining)

                if (count > 0) {
                    minuteCounts[cursor] = (minuteCounts[cursor] ?: 0) + count
                    usedMinutes += cursor
                    remaining -= count
                }

                cursor = cursor.plusMinutes(1)

                if (random.nextDouble() < 0.06) {
                    cursor = cursor.plusMinutes(1)
                }
            }

            cursor = cursor.plusMinutes(random.nextLong(0, 2))
        }

        if (remaining > 0 && usedMinutes.isNotEmpty()) {
            var cursorIndex = 0
            val distinctMinutes = usedMinutes.distinct()
            var guard = 0
            while (remaining > 0 && guard < distinctMinutes.size * 30) {
                val minute = distinctMinutes[cursorIndex % distinctMinutes.size]
                val current = minuteCounts[minute] ?: 0
                if (current < 150) {
                    val add = minOf(remaining, random.nextInt(2, 8), 150 - current)
                    minuteCounts[minute] = current + add
                    remaining -= add
                }
                cursorIndex++
                guard++
            }

            if (remaining > 0) {
                val lastMinute = distinctMinutes.last()
                minuteCounts[lastMinute] = (minuteCounts[lastMinute] ?: 0) + remaining
            }
        }
    }

    private fun addAmbientMovement(
        minuteCounts: MutableMap<ZonedDateTime, Int>,
        wakeTime: ZonedDateTime,
        sleepTime: ZonedDateTime,
        targetSteps: Int,
        random: Random
    ) {
        val extraClusters = random.nextInt(2, 5)
        repeat(extraClusters) {
            val awakeMinutes = ChronoUnit.MINUTES.between(wakeTime, sleepTime).toInt().coerceAtLeast(120)
            val clusterStart = wakeTime.plusMinutes(random.nextInt(25, awakeMinutes - 10).toLong())
            var cursor = clusterStart
            repeat(random.nextInt(1, 4)) {
                if (cursor.isBefore(sleepTime)) {
                    minuteCounts[cursor] = (minuteCounts[cursor] ?: 0) + random.nextInt(18, 46)
                }
                cursor = cursor.plusMinutes(1)
            }
        }

        val current = minuteCounts.values.sum()
        if (current < targetSteps / 3) {
            var cursor = wakeTime.plusMinutes(18)
            while (cursor.isBefore(sleepTime.minusMinutes(12))) {
                if (random.nextDouble() < 0.025) {
                    minuteCounts[cursor] = (minuteCounts[cursor] ?: 0) + random.nextInt(15, 34)
                }
                cursor = cursor.plusMinutes(1)
            }
        }
    }

    private fun rebalanceToTarget(
        minuteCounts: MutableMap<ZonedDateTime, Int>,
        wakeTime: ZonedDateTime,
        sleepTime: ZonedDateTime,
        targetSteps: Int,
        random: Random
    ) {
        var delta = targetSteps - minuteCounts.values.sum()
        if (delta == 0) {
            return
        }

        val existingMinutes = minuteCounts.keys.sorted().toMutableList()
        if (delta > 0) {
            while (delta > 0) {
                val candidateMinute = if (existingMinutes.isNotEmpty() && random.nextDouble() < 0.8) {
                    existingMinutes[random.nextInt(existingMinutes.size)]
                } else {
                    val awakeMinutes = ChronoUnit.MINUTES.between(wakeTime, sleepTime).toInt().coerceAtLeast(30)
                    wakeTime.plusMinutes(random.nextInt(0, awakeMinutes - 1).toLong()).truncatedTo(ChronoUnit.MINUTES)
                }
                val current = minuteCounts[candidateMinute] ?: 0
                val add = minOf(delta, random.nextInt(2, 10), (160 - current).coerceAtLeast(1))
                minuteCounts[candidateMinute] = current + add
                if (candidateMinute !in existingMinutes) {
                    existingMinutes += candidateMinute
                }
                delta -= add
            }
            return
        }

        delta = -delta
        while (delta > 0 && existingMinutes.isNotEmpty()) {
            val minute = existingMinutes[random.nextInt(existingMinutes.size)]
            val current = minuteCounts[minute] ?: 0
            val removable = if (current <= 8) current else current - 8
            val remove = minOf(delta, removable.coerceAtLeast(1), random.nextInt(1, 9))
            val updated = current - remove
            if (updated <= 0) {
                minuteCounts.remove(minute)
                existingMinutes.remove(minute)
            } else {
                minuteCounts[minute] = updated
            }
            delta -= remove
        }
    }

    private fun addWindow(
        windows: MutableList<WindowPlan>,
        start: ZonedDateTime,
        durationMinutes: Int,
        shareWeight: Double,
        minPace: Int,
        maxPace: Int,
        pauseChance: Double,
        continuity: Double,
        sleepTime: ZonedDateTime
    ) {
        val safeStart = start.truncatedTo(ChronoUnit.MINUTES)
        val safeEnd = safeStart.plusMinutes(durationMinutes.toLong()).coerceBefore(sleepTime)
        if (safeStart.isBefore(safeEnd)) {
            windows += WindowPlan(
                start = safeStart,
                end = safeEnd,
                shareWeight = shareWeight,
                minPace = minPace,
                maxPace = maxPace,
                pauseChance = pauseChance,
                continuity = continuity
            )
        }
    }

    private fun maybeAddWindow(
        windows: MutableList<WindowPlan>,
        random: Random,
        probability: Double,
        block: () -> WindowPlan
    ) {
        if (random.nextDouble() <= probability) {
            windows += block()
        }
    }

    private fun atTime(
        date: LocalDate,
        zoneId: ZoneId,
        hour: Int,
        minute: Int,
        random: Random,
        jitterMinutes: Int
    ): ZonedDateTime {
        return date.atStartOfDay(zoneId)
            .plusHours(hour.toLong())
            .plusMinutes(minute.toLong())
            .plusMinutes(random.nextInt(-jitterMinutes, jitterMinutes + 1).toLong())
            .truncatedTo(ChronoUnit.MINUTES)
    }

    private fun ZonedDateTime.coerceBefore(other: ZonedDateTime): ZonedDateTime {
        return if (this.isAfter(other)) other else this
    }
}
