package dev.digitaldomi.schrittji.simulation

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Minimum gap between any two workout sessions (cardio or mindfulness), in minutes. */
private const val WORKOUT_GAP_MINUTES = 15L

data class MinuteStepSlice(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val count: Long
)

data class DailyProjectedSteps(
    val date: LocalDate,
    val totalSteps: Long
)

enum class WorkoutType {
    RUNNING,
    CYCLING,
    MINDFULNESS
}

data class WorkoutPlan(
    val type: WorkoutType,
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val title: String,
    /** Optional; omitted when writing to Health Connect so nothing identifiable is stored. */
    val notes: String?,
    val distanceMeters: Double,
    val kilocalories: Double
)

data class GeneratedWindowData(
    val stepSlices: List<MinuteStepSlice>,
    val workouts: List<WorkoutPlan>
)

private data class GeneratedDayData(
    val stepSlices: List<MinuteStepSlice>,
    val workouts: List<WorkoutPlan>
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

private data class WeeklyRoutine(
    val lunchOutDays: Set<DayOfWeek>,
    val runningDays: Set<DayOfWeek>,
    val cyclingDays: Set<DayOfWeek>,
    val mindfulnessDays: Set<DayOfWeek>,
    val errandDays: Set<DayOfWeek>,
    val weekendEarlyRiserDays: Set<DayOfWeek>,
    val weekendLongOutingDay: DayOfWeek,
    val lighterFriday: Boolean
)

class StepSimulationEngine {
    fun generateBetween(
        startInclusive: ZonedDateTime,
        endExclusive: ZonedDateTime,
        config: SimulationConfig
    ): List<MinuteStepSlice> {
        return generateWindowData(startInclusive, endExclusive, config).stepSlices
    }

    fun generateWindowData(
        startInclusive: ZonedDateTime,
        endExclusive: ZonedDateTime,
        config: SimulationConfig
    ): GeneratedWindowData {
        if (!startInclusive.isBefore(endExclusive)) {
            return GeneratedWindowData(emptyList(), emptyList())
        }

        val zone = startInclusive.zone
        val startDate = startInclusive.toLocalDate()
        val endDate = endExclusive.minusNanos(1).withZoneSameInstant(zone).toLocalDate()
        val slices = mutableListOf<MinuteStepSlice>()
        val workouts = mutableListOf<WorkoutPlan>()

        var date = startDate
        while (!date.isAfter(endDate)) {
            val generatedDay = generateDayData(date, zone, config)
            slices += generatedDay.stepSlices.filter { slice ->
                slice.end.isAfter(startInclusive) && slice.start.isBefore(endExclusive)
            }
            workouts += generatedDay.workouts.filter { workout ->
                workout.end.isAfter(startInclusive) && workout.start.isBefore(endExclusive)
            }
            date = date.plusDays(1)
        }

        return GeneratedWindowData(
            stepSlices = slices.sortedBy { it.start.toInstant() },
            workouts = workouts.sortedBy { it.start.toInstant() }
        )
    }

    fun projectNextDays(
        startDate: LocalDate,
        dayCount: Int,
        zoneId: ZoneId,
        config: SimulationConfig
    ): List<DailyProjectedSteps> {
        return (0 until dayCount.coerceAtLeast(0)).map { dayOffset ->
            val date = startDate.plusDays(dayOffset.toLong())
            DailyProjectedSteps(
                date = date,
                totalSteps = generateDayData(date, zoneId, config).stepSlices.sumOf { it.count }
            )
        }
    }

    private fun generateDayData(
        date: LocalDate,
        zoneId: ZoneId,
        config: SimulationConfig
    ): GeneratedDayData {
        val daySeed = config.randomSeed xor (date.toEpochDay() * -7046029254386353131L)
        val random = Random(daySeed)
        val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        val weeklyRoutine = buildWeeklyRoutine(date, config)
        val targetSteps = computeTargetSteps(date, config, random, isWeekend, weeklyRoutine)
        val wakeTime = buildWakeTime(date, zoneId, random, isWeekend, weeklyRoutine)
        val sleepTime = buildSleepTime(date, zoneId, random, isWeekend)
        val workouts = generateWorkoutPlans(date, random, isWeekend, wakeTime, sleepTime, weeklyRoutine, config)
        if (!config.dailyStepsEnabled) {
            return GeneratedDayData(stepSlices = emptyList(), workouts = workouts)
        }
        val minuteCounts = linkedMapOf<ZonedDateTime, Int>()
        val windows = buildWindows(date, zoneId, wakeTime, sleepTime, random, isWeekend, weeklyRoutine, workouts)

        val weightTotal = windows.sumOf { it.shareWeight }.takeIf { it > 0.0 } ?: 1.0
        windows.forEach { window ->
            val windowTarget = (targetSteps * (window.shareWeight / weightTotal)).roundToInt()
            populateWindow(minuteCounts, window, windowTarget, random)
        }

        addAmbientMovement(minuteCounts, wakeTime, sleepTime, targetSteps, random)
        rebalanceToTarget(minuteCounts, wakeTime, sleepTime, targetSteps, random)

        return GeneratedDayData(
            stepSlices = minuteCounts.entries
                .sortedBy { it.key.toInstant() }
                .map { (start, count) ->
                    MinuteStepSlice(
                        start = start,
                        end = start.plusMinutes(1),
                        count = count.toLong()
                    )
                },
            workouts = workouts
        )
    }

    private fun computeTargetSteps(
        date: LocalDate,
        config: SimulationConfig,
        random: Random,
        isWeekend: Boolean,
        weeklyRoutine: WeeklyRoutine
    ): Int {
        val minSteps = config.minimumDailySteps.coerceAtLeast(1_000)
        val maxSteps = config.maximumDailySteps.coerceAtLeast(minSteps + 500)
        val base = if (isWeekend) {
            6_100 + random.nextInt(0, 2_200)
        } else {
            7_400 + random.nextInt(0, 1_900)
        }

        val runBonus = when {
            date.dayOfWeek in weeklyRoutine.runningDays -> 2_200 + random.nextInt(250, 1_250)
            else -> 0
        }
        val outingBonus = when {
            isWeekend && weeklyRoutine.weekendLongOutingDay == date.dayOfWeek -> 1_600 + random.nextInt(200, 1_200)
            !isWeekend && date.dayOfWeek in weeklyRoutine.lunchOutDays -> 550 + random.nextInt(0, 450)
            else -> 0
        }
        val errandBonus = if (!isWeekend && date.dayOfWeek in weeklyRoutine.errandDays) {
            350 + random.nextInt(0, 650)
        } else {
            0
        }
        val cyclingAdjacencyBonus = if (date.dayOfWeek in weeklyRoutine.cyclingDays) {
            120 + random.nextInt(0, 280)
        } else {
            0
        }
        val lightFridayPenalty = if (
            date.dayOfWeek == DayOfWeek.FRIDAY &&
            weeklyRoutine.lighterFriday &&
            !isWeekend &&
            date.dayOfWeek !in weeklyRoutine.runningDays
        ) {
            900 + random.nextInt(0, 450)
        } else {
            0
        }
        val longerWave = 1.0 + (sin(date.toEpochDay() / 6.3 * PI / 2.0) * 0.06)
        val blendedRandom = (random.nextDouble() + random.nextDouble() + random.nextDouble()) / 3.0
        val microVariance = random.nextInt(-190, 311)
        val dayTarget = (base + runBonus + outingBonus + errandBonus + cyclingAdjacencyBonus + microVariance - lightFridayPenalty)
            .coerceAtLeast(if (isWeekend) 5_200 else 6_200)
        return (dayTarget * longerWave * (0.94 + blendedRandom * 0.12)).roundToInt()
            .coerceIn(minSteps, maxSteps)
    }

    private fun buildWakeTime(
        date: LocalDate,
        zoneId: ZoneId,
        random: Random,
        isWeekend: Boolean,
        weeklyRoutine: WeeklyRoutine
    ): ZonedDateTime {
        val baseMinutes = when {
            !isWeekend -> 6 * 60 + 34
            date.dayOfWeek in weeklyRoutine.weekendEarlyRiserDays -> 7 * 60 + 6
            else -> 8 * 60 + 38
        }
        val jitter = if (isWeekend) random.nextInt(-28, 58) else random.nextInt(-18, 24)
        return date.atStartOfDay(zoneId).plusMinutes((baseMinutes + jitter).toLong())
    }

    private fun buildSleepTime(
        date: LocalDate,
        zoneId: ZoneId,
        random: Random,
        isWeekend: Boolean
    ): ZonedDateTime {
        val baseMinutes = if (isWeekend) 23 * 60 + 36 else 22 * 60 + 54
        val jitter = if (isWeekend) random.nextInt(-24, 44) else random.nextInt(-20, 24)
        return date.atStartOfDay(zoneId).plusMinutes((baseMinutes + jitter).toLong())
    }

    private fun buildWindows(
        date: LocalDate,
        zoneId: ZoneId,
        wakeTime: ZonedDateTime,
        sleepTime: ZonedDateTime,
        random: Random,
        isWeekend: Boolean,
        weeklyRoutine: WeeklyRoutine,
        workouts: List<WorkoutPlan>
    ): List<WindowPlan> {
        val windows = mutableListOf<WindowPlan>()

        addWindow(
            windows = windows,
            start = wakeTime.plusMinutes(random.nextInt(10, 22).toLong()),
            durationMinutes = random.nextInt(6, 15),
            shareWeight = if (isWeekend) 0.06 else 0.08,
            minPace = 48,
            maxPace = 74,
            pauseChance = 0.22,
            continuity = 0.84,
            sleepTime = sleepTime
        )

        if (isWeekend) {
            addWindow(
                windows,
                atTime(date, zoneId, 10, 46, random, 55),
                random.nextInt(10, 24),
                0.09,
                54,
                80,
                0.2,
                0.84,
                sleepTime
            )
            addWindow(
                windows,
                atTime(date, zoneId, if (weeklyRoutine.weekendLongOutingDay == date.dayOfWeek) 13 else 14, 18, random, 85),
                random.nextInt(
                    if (weeklyRoutine.weekendLongOutingDay == date.dayOfWeek) 30 else 16,
                    if (weeklyRoutine.weekendLongOutingDay == date.dayOfWeek) 62 else 32
                ),
                if (weeklyRoutine.weekendLongOutingDay == date.dayOfWeek) 0.28 else 0.18,
                76,
                112,
                0.12,
                0.89,
                sleepTime
            )
            maybeAddWindow(windows, random, 0.82) {
                WindowPlan(
                    start = atTime(date, zoneId, 17, 42, random, 90),
                    end = atTime(date, zoneId, 18, 28, random, 85),
                    shareWeight = 0.14,
                    minPace = 66,
                    maxPace = 96,
                    pauseChance = 0.18,
                    continuity = 0.85
                )
            }
        } else {
            addWindow(
                windows,
                atTime(date, zoneId, 7, 34, random, 18),
                random.nextInt(12, 24),
                0.17,
                84,
                116,
                0.08,
                0.92,
                sleepTime
            )
            addWindow(
                windows,
                atTime(date, zoneId, 10, 26, random, 18),
                random.nextInt(3, 8),
                0.025,
                42,
                60,
                0.3,
                0.8,
                sleepTime
            )
            if (date.dayOfWeek in weeklyRoutine.lunchOutDays) {
                addWindow(
                    windows,
                    atTime(date, zoneId, 12, 18, random, 22),
                    random.nextInt(16, 32),
                    0.14,
                    76,
                    106,
                    0.14,
                    0.88,
                    sleepTime
                )
            } else {
                addWindow(
                    windows,
                    atTime(date, zoneId, 12, 22, random, 16),
                    random.nextInt(6, 14),
                    0.06,
                    48,
                    72,
                    0.24,
                    0.82,
                    sleepTime
                )
            }
            maybeAddWindow(windows, random, 0.72) {
                WindowPlan(
                    start = atTime(date, zoneId, 15, 18, random, 20),
                    end = atTime(date, zoneId, 15, 28, random, 18),
                    shareWeight = 0.025,
                    minPace = 38,
                    maxPace = 58,
                    pauseChance = 0.32,
                    continuity = 0.78
                )
            }
            addWindow(
                windows,
                atTime(date, zoneId, 17, 36, random, 26),
                random.nextInt(12, 24),
                0.17,
                82,
                114,
                0.1,
                0.9,
                sleepTime
            )
            if (date.dayOfWeek in weeklyRoutine.errandDays) {
                maybeAddWindow(windows, random, 0.84) {
                    WindowPlan(
                        start = atTime(date, zoneId, 18, 36, random, 30),
                        end = atTime(date, zoneId, 19, 2, random, 28),
                        shareWeight = 0.08,
                        minPace = 64,
                        maxPace = 94,
                        pauseChance = 0.18,
                        continuity = 0.84
                    )
                }
            }
            if (date.dayOfWeek !in weeklyRoutine.runningDays) {
                maybeAddWindow(windows, random, 0.42) {
                    WindowPlan(
                        start = atTime(date, zoneId, 20, 6, random, 24),
                        end = atTime(date, zoneId, 20, 22, random, 22),
                        shareWeight = 0.05,
                        minPace = 58,
                        maxPace = 82,
                        pauseChance = 0.22,
                        continuity = 0.82
                    )
                }
            }
        }

        workouts.forEach { workout ->
            when (workout.type) {
                WorkoutType.MINDFULNESS -> addWindow(
                    windows = windows,
                    start = workout.start,
                    durationMinutes = ChronoUnit.MINUTES.between(workout.start, workout.end).toInt().coerceAtLeast(5),
                    shareWeight = 0.012,
                    minPace = 28,
                    maxPace = 42,
                    pauseChance = 0.45,
                    continuity = 0.7,
                    sleepTime = sleepTime
                )

                WorkoutType.RUNNING -> addWindow(
                    windows = windows,
                    start = workout.start,
                    durationMinutes = ChronoUnit.MINUTES.between(workout.start, workout.end).toInt().coerceAtLeast(12),
                    shareWeight = if (isWeekend) 0.2 else 0.23,
                    minPace = 96,
                    maxPace = 136,
                    pauseChance = 0.08,
                    continuity = 0.93,
                    sleepTime = sleepTime
                )

                WorkoutType.CYCLING -> {
                    addWindow(
                        windows = windows,
                        start = workout.start.minusMinutes(8),
                        durationMinutes = 6,
                        shareWeight = 0.018,
                        minPace = 34,
                        maxPace = 56,
                        pauseChance = 0.26,
                        continuity = 0.82,
                        sleepTime = sleepTime
                    )
                    addWindow(
                        windows = windows,
                        start = workout.end.plusMinutes(3),
                        durationMinutes = 5,
                        shareWeight = 0.015,
                        minPace = 32,
                        maxPace = 52,
                        pauseChance = 0.28,
                        continuity = 0.8,
                        sleepTime = sleepTime
                    )
                }
            }
        }

        repeat(if (isWeekend) random.nextInt(4, 7) else random.nextInt(3, 6)) {
            val awakeMinutes = ChronoUnit.MINUTES.between(wakeTime, sleepTime).toInt().coerceAtLeast(90)
            val start = wakeTime.plusMinutes(random.nextInt(35, awakeMinutes - 20).toLong())
            addWindow(
                windows = windows,
                start = start,
                durationMinutes = random.nextInt(2, 8),
                shareWeight = random.nextDouble(0.008, 0.024),
                minPace = 34,
                maxPace = 64,
                pauseChance = 0.34,
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
                    minuteCounts[cursor] = (minuteCounts[cursor] ?: 0) + random.nextInt(12, 34)
                }
                cursor = cursor.plusMinutes(1)
            }
        }

        val current = minuteCounts.values.sum()
        if (current < targetSteps / 3) {
            var cursor = wakeTime.plusMinutes(18)
            while (cursor.isBefore(sleepTime.minusMinutes(12))) {
                if (random.nextDouble() < 0.02) {
                    minuteCounts[cursor] = (minuteCounts[cursor] ?: 0) + random.nextInt(10, 24)
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

    private fun generateWorkoutPlans(
        date: LocalDate,
        random: Random,
        isWeekend: Boolean,
        wakeTime: ZonedDateTime,
        sleepTime: ZonedDateTime,
        weeklyRoutine: WeeklyRoutine,
        config: SimulationConfig
    ): List<WorkoutPlan> {
        val workouts = mutableListOf<WorkoutPlan>()

        fun blockedIntervals(): List<Pair<ZonedDateTime, ZonedDateTime>> =
            workouts.filter { it.start.isBefore(it.end) }.map { it.start to it.end }.sortedBy { it.first }

        if (config.runningEnabled && date.dayOfWeek in weeklyRoutine.runningDays) {
            val duration = randomDuration(
                config.runningMinDurationMinutes,
                config.runningMaxDurationMinutes,
                18,
                90,
                random
            )
            val preferredStart = (if (isWeekend) {
                wakeTime.plusMinutes(random.nextInt(55, 120).toLong())
            } else {
                date.atStartOfDay(wakeTime.zone).plusHours(19).plusMinutes(6)
                    .plusMinutes(random.nextInt(-24, 28).toLong())
            }).coerceAfter(wakeTime.plusMinutes(25)).coerceBefore(sleepTime.minusMinutes(duration.toLong() + 20))
            val earliest = wakeTime.plusMinutes(25)
            val latest = sleepTime.minusMinutes(duration.toLong() + 20)
            findNonOverlappingStart(
                preferredStart = preferredStart,
                earliest = earliest,
                latestStart = latest,
                durationMinutes = duration,
                blocked = emptyList(),
                random = random
            )?.let { start ->
                val end = start.plusMinutes(duration.toLong())
                val paceMetersPerMinute = random.nextDouble(155.0, 205.0)
                workouts += WorkoutPlan(
                    type = WorkoutType.RUNNING,
                    start = start,
                    end = end,
                    title = "Running",
                    notes = null,
                    distanceMeters = duration * paceMetersPerMinute,
                    kilocalories = duration * random.nextDouble(10.5, 14.5)
                )
            }
        }

        if (config.cyclingEnabled && date.dayOfWeek in weeklyRoutine.cyclingDays) {
            val duration = randomDuration(
                config.cyclingMinDurationMinutes,
                config.cyclingMaxDurationMinutes,
                20,
                180,
                random
            )
            val preferredStart = (if (isWeekend) {
                wakeTime.plusMinutes(random.nextInt(95, 180).toLong())
            } else {
                date.atStartOfDay(wakeTime.zone).plusHours(18).plusMinutes(18)
                    .plusMinutes(random.nextInt(-26, 34).toLong())
            }).coerceAfter(wakeTime.plusMinutes(35)).coerceBefore(sleepTime.minusMinutes(duration.toLong() + 25))
            val earliest = wakeTime.plusMinutes(35)
            val latest = sleepTime.minusMinutes(duration.toLong() + 25)
            findNonOverlappingStart(
                preferredStart = preferredStart,
                earliest = earliest,
                latestStart = latest,
                durationMinutes = duration,
                blocked = blockedIntervals(),
                random = random
            )?.let { start ->
                val end = start.plusMinutes(duration.toLong())
                val distanceMetersPerMinute = random.nextDouble(260.0, 420.0)
                workouts += WorkoutPlan(
                    type = WorkoutType.CYCLING,
                    start = start,
                    end = end,
                    title = "Cycling",
                    notes = null,
                    distanceMeters = duration * distanceMetersPerMinute,
                    kilocalories = duration * random.nextDouble(7.0, 11.5)
                )
            }
        }

        if (config.mindfulnessEnabled && date.dayOfWeek in weeklyRoutine.mindfulnessDays) {
            val duration = randomDuration(
                config.mindfulnessMinDurationMinutes,
                config.mindfulnessMaxDurationMinutes,
                8,
                45,
                random
            )
            val blocked = blockedIntervals()
            placeMindfulnessSession(
                wakeTime = wakeTime,
                sleepTime = sleepTime,
                durationMinutes = duration,
                blocked = blocked,
                zone = wakeTime.zone,
                date = date,
                random = random
            )?.let { (start, end) ->
                workouts += WorkoutPlan(
                    type = WorkoutType.MINDFULNESS,
                    start = start,
                    end = end,
                    title = "Mindfulness",
                    notes = null,
                    distanceMeters = 0.0,
                    kilocalories = duration * random.nextDouble(1.2, 2.8)
                )
            }
        }

        return workouts.filter { it.start.isBefore(it.end) }
    }

    /**
     * True if [aStart],[aEnd) conflicts with [bStart],[bEnd) including [WORKOUT_GAP_MINUTES] gap.
     */
    private fun workoutIntervalsConflict(
        aStart: ZonedDateTime,
        aEnd: ZonedDateTime,
        bStart: ZonedDateTime,
        bEnd: ZonedDateTime
    ): Boolean {
        return aStart.isBefore(bEnd.plusMinutes(WORKOUT_GAP_MINUTES)) &&
            aEnd.isAfter(bStart.minusMinutes(WORKOUT_GAP_MINUTES))
    }

    private fun conflictsAnyBlocked(
        start: ZonedDateTime,
        end: ZonedDateTime,
        blocked: List<Pair<ZonedDateTime, ZonedDateTime>>
    ): Boolean {
        return blocked.any { (b, e) -> workoutIntervalsConflict(start, end, b, e) }
    }

    /**
     * Picks a session start in [earliest, latestStart] so the session does not overlap (with gap)
     * any [blocked] interval. Tries random samples then a coarse forward scan.
     */
    private fun findNonOverlappingStart(
        preferredStart: ZonedDateTime,
        earliest: ZonedDateTime,
        latestStart: ZonedDateTime,
        durationMinutes: Int,
        blocked: List<Pair<ZonedDateTime, ZonedDateTime>>,
        random: Random
    ): ZonedDateTime? {
        if (earliest.isAfter(latestStart)) return null
        val dur = durationMinutes.toLong()
        fun ok(s: ZonedDateTime): Boolean {
            val e = s.plusMinutes(dur)
            if (!s.isBefore(e)) return false
            return !conflictsAnyBlocked(s, e, blocked)
        }
        val pref = when {
            preferredStart.isBefore(earliest) -> earliest
            preferredStart.isAfter(latestStart) -> latestStart
            else -> preferredStart
        }.truncatedTo(ChronoUnit.MINUTES)
        if (ok(pref)) return pref
        val span = ChronoUnit.MINUTES.between(earliest, latestStart).toInt().coerceAtLeast(0)
        repeat(64) {
            val off = if (span == 0) 0 else random.nextInt(0, span + 1)
            val s = earliest.plusMinutes(off.toLong()).truncatedTo(ChronoUnit.MINUTES)
            if (ok(s)) return s
        }
        var t = earliest.truncatedTo(ChronoUnit.MINUTES)
        val endLimit = latestStart.plusMinutes(1)
        while (!t.isAfter(latestStart)) {
            if (ok(t)) return t
            t = t.plusMinutes(5)
            if (t.isAfter(endLimit)) break
        }
        return null
    }

    private fun placeMindfulnessSession(
        wakeTime: ZonedDateTime,
        sleepTime: ZonedDateTime,
        durationMinutes: Int,
        blocked: List<Pair<ZonedDateTime, ZonedDateTime>>,
        zone: java.time.ZoneId,
        date: LocalDate,
        random: Random
    ): Pair<ZonedDateTime, ZonedDateTime>? {
        val dayStart = date.atStartOfDay(zone)
        val dur = durationMinutes.toLong()

        fun overlaps(aStart: ZonedDateTime, aEnd: ZonedDateTime): Boolean {
            return conflictsAnyBlocked(aStart, aEnd, blocked)
        }

        fun latestEndBeforeNoon(): ZonedDateTime? =
            blocked.filter { it.first.hour < 12 }.maxOfOrNull { it.second }

        // Morning window: after wake + buffer, before noon, not overlapping run/cycle
        val morningEarliest = maxOf(
            wakeTime.plusMinutes(25),
            latestEndBeforeNoon()?.plusMinutes(WORKOUT_GAP_MINUTES) ?: wakeTime.plusMinutes(25)
        )
        val morningLatest = minOf(
            dayStart.plusHours(12),
            sleepTime.minusMinutes(dur + 20)
        )

        // Evening window: after 19:00 and after last blocking workout ends
        val eveningEarliest = maxOf(
            dayStart.plusHours(19),
            blocked.maxOfOrNull { it.second }?.plusMinutes(WORKOUT_GAP_MINUTES) ?: dayStart.plusHours(19)
        )
        val eveningLatest = sleepTime.minusMinutes(20)

        val candidates = mutableListOf<Pair<ZonedDateTime, ZonedDateTime>>()

        if (morningEarliest.plusMinutes(dur).isBefore(morningLatest) ||
            morningEarliest.plusMinutes(dur) == morningLatest
        ) {
            val span = ChronoUnit.MINUTES.between(morningEarliest, morningLatest).toInt() - durationMinutes
            if (span >= 0) {
                val offset = if (span == 0) 0 else random.nextInt(0, span + 1)
                val start = morningEarliest.plusMinutes(offset.toLong())
                val end = start.plusMinutes(dur)
                if (!end.isAfter(sleepTime) && !overlaps(start, end)) {
                    candidates.add(start to end)
                }
            }
        }

        if (eveningEarliest.plusMinutes(dur).isBefore(eveningLatest) ||
            eveningEarliest.plusMinutes(dur) == eveningLatest
        ) {
            val span = ChronoUnit.MINUTES.between(eveningEarliest, eveningLatest).toInt() - durationMinutes
            if (span >= 0) {
                val offset = if (span == 0) 0 else random.nextInt(0, span + 1)
                val start = eveningEarliest.plusMinutes(offset.toLong())
                val end = start.plusMinutes(dur)
                if (!end.isAfter(sleepTime) && !overlaps(start, end)) {
                    candidates.add(start to end)
                }
            }
        }

        return candidates.randomOrNull(random)
    }

    private fun <T> List<T>.randomOrNull(random: Random): T? {
        if (isEmpty()) return null
        return this[random.nextInt(size)]
    }

    private fun buildWeeklyRoutine(date: LocalDate, config: SimulationConfig): WeeklyRoutine {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val weekSeed = config.randomSeed xor (monday.toEpochDay() * -7046029254386353131L)
        val random = Random(weekSeed)
        val weekdayCandidates = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val runningPool = listOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.FRIDAY)
        val cyclingPool = listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

        val lunchOutDays = weekdayCandidates.shuffled(random).take(random.nextInt(2, 4)).toSet()
        val errandDays = weekdayCandidates.shuffled(random).take(random.nextInt(1, 3)).toSet()
        val runningSessionCount = randomSessionCount(
            config.runningMinSessionsPerWeek,
            config.runningMaxSessionsPerWeek,
            runningPool.size,
            random
        )
        val cyclingSessionCount = randomSessionCount(
            config.cyclingMinSessionsPerWeek,
            config.cyclingMaxSessionsPerWeek,
            cyclingPool.size,
            random
        )
        val runningDays = runningPool.shuffled(random).take(runningSessionCount).toSet()
        val cyclingDays = cyclingPool.shuffled(random).take(cyclingSessionCount).toSet()
        // Mindfulness can fall on the same calendar day as run/cycle; placement avoids time overlap.
        // Session count is capped by 7 days/week, not by "leftover" days after run/cycle.
        val mindfulnessSessionCount = randomSessionCount(
            config.mindfulnessMinSessionsPerWeek,
            config.mindfulnessMaxSessionsPerWeek,
            DayOfWeek.entries.size,
            random
        )
        val mindfulnessDays = DayOfWeek.entries.shuffled(random).take(mindfulnessSessionCount).toSet()
        val weekendEarlyRisers = buildSet {
            if (random.nextDouble() < 0.3) add(DayOfWeek.SATURDAY)
            if (random.nextDouble() < 0.2) add(DayOfWeek.SUNDAY)
        }

        return WeeklyRoutine(
            lunchOutDays = lunchOutDays,
            runningDays = runningDays,
            cyclingDays = cyclingDays,
            mindfulnessDays = mindfulnessDays,
            errandDays = errandDays,
            weekendEarlyRiserDays = weekendEarlyRisers,
            weekendLongOutingDay = if (random.nextBoolean()) DayOfWeek.SATURDAY else DayOfWeek.SUNDAY,
            lighterFriday = random.nextDouble() < 0.42
        )
    }

    private fun randomSessionCount(min: Int, max: Int, hardMax: Int, random: Random): Int {
        val safeMin = min.coerceIn(0, hardMax)
        val safeMax = max.coerceIn(safeMin, hardMax)
        return if (safeMax == safeMin) safeMin else random.nextInt(safeMin, safeMax + 1)
    }

    private fun randomDuration(min: Int, max: Int, fallbackMin: Int, fallbackMax: Int, random: Random): Int {
        val safeMin = min.coerceAtLeast(fallbackMin)
        val safeMax = max.coerceAtLeast(safeMin).coerceAtMost(fallbackMax)
        return if (safeMax == safeMin) safeMin else random.nextInt(safeMin, safeMax + 1)
    }

    private fun ZonedDateTime.coerceAfter(other: ZonedDateTime): ZonedDateTime {
        return if (this.isBefore(other)) other else this
    }
}
