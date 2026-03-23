package dev.digitaldomi.schrittji.simulation

import dev.digitaldomi.schrittji.health.HealthConnectGateway
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class PublishResult(
    val recordCount: Int,
    val stepCount: Long,
    val start: ZonedDateTime?,
    val end: ZonedDateTime?,
    val summary: String
)

data class ProjectedStepDay(
    val date: LocalDate,
    val totalSteps: Long
)

data class ProjectedStepDayDetail(
    val date: LocalDate,
    val totalSteps: Long,
    val slices: List<MinuteStepSlice>
)

class SimulationCoordinator(
    private val healthConnectGateway: HealthConnectGateway,
    private val configStore: SimulationConfigStore,
    private val stepSimulationEngine: StepSimulationEngine = StepSimulationEngine()
) {
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val formatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm")
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun saveConfig(config: SimulationConfig): SimulationConfig {
        return configStore.save(config)
    }

    fun loadConfig(): SimulationConfig = configStore.load()

    fun projectNextDays(
        config: SimulationConfig,
        dayCount: Int = 7
    ): List<ProjectedStepDay> {
        val startDate = ZonedDateTime.now(zoneId).toLocalDate()
        return stepSimulationEngine
            .projectNextDays(startDate, dayCount, zoneId, config)
            .map { ProjectedStepDay(it.date, it.totalSteps) }
    }

    fun projectDayDetail(
        config: SimulationConfig,
        date: LocalDate
    ): ProjectedStepDayDetail {
        val start = date.atStartOfDay(zoneId)
        val end = start.plusDays(1)
        val slices = stepSimulationEngine.generateBetween(start, end, config)
        return ProjectedStepDayDetail(
            date = date,
            totalSteps = slices.sumOf { it.count },
            slices = slices
        )
    }

    suspend fun publishBackfill(config: SimulationConfig): PublishResult {
        val storedConfig = configStore.save(config)
        val now = ZonedDateTime.now(zoneId).truncatedTo(ChronoUnit.MINUTES)
        val start = now.toLocalDate()
            .minusDays((storedConfig.backfillDays - 1).coerceAtLeast(0).toLong())
            .atStartOfDay(zoneId)
        return publishWindow(storedConfig, start, now, "Backfill")
    }

    suspend fun publishSinceLast(
        config: SimulationConfig,
        fallbackWindow: Duration = Duration.ofHours(2)
    ): PublishResult {
        val storedConfig = configStore.save(config)
        val now = ZonedDateTime.now(zoneId).truncatedTo(ChronoUnit.MINUTES)
        val lastPublished = storedConfig.lastPublishedEpochMilli?.let {
            ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), zoneId)
        }
        val start = lastPublished ?: now.minus(fallbackWindow)
        return publishWindow(storedConfig, start, now, "Incremental sync")
    }

    suspend fun publishRecentWindow(
        config: SimulationConfig,
        window: Duration = Duration.ofHours(1)
    ): PublishResult {
        val storedConfig = configStore.save(config)
        val end = ZonedDateTime.now(zoneId).truncatedTo(ChronoUnit.MINUTES)
        val start = end.minus(window)
        return publishWindow(storedConfig, start, end, "Recent window")
    }

    fun disableAutomation(): SimulationConfig {
        val updated = configStore.load().copy(automationEnabled = false)
        return configStore.save(updated)
    }

    private suspend fun publishWindow(
        config: SimulationConfig,
        start: ZonedDateTime,
        end: ZonedDateTime,
        label: String
    ): PublishResult {
        val safeStart = start.truncatedTo(ChronoUnit.MINUTES)
        val safeEnd = end.truncatedTo(ChronoUnit.MINUTES)
        if (!safeStart.isBefore(safeEnd)) {
            val summary = "$label skipped because there was no missing time range to generate."
            configStore.setLastSummary(summary)
            configStore.setLastGeneratedDetails(
                "No new step minutes were created because the requested time window was empty."
            )
            return PublishResult(0, 0, safeStart, safeEnd, summary)
        }

        val generatedWindow = stepSimulationEngine.generateWindowData(safeStart, safeEnd, config)
        val slices = generatedWindow.stepSlices
        val workouts = generatedWindow.workouts
        val insertedStepRecords = healthConnectGateway.insertSlices(slices)
        val insertedWorkouts = healthConnectGateway.insertWorkouts(workouts)
        val stepCount = slices.sumOf { it.count }
        val generatedDetails = buildGeneratedDetails(label, safeStart, safeEnd, slices, workouts)
        val summary = if (slices.isEmpty() && workouts.isEmpty()) {
            "$label produced no slices for ${safeStart.format(formatter)} to ${safeEnd.format(formatter)}."
        } else {
            "$label created and added ${stepCount.formatThousands()} steps to Health Connect " +
                "across $insertedStepRecords minute records and $insertedWorkouts workouts " +
                "from ${safeStart.format(formatter)} to ${safeEnd.format(formatter)}."
        }

        configStore.setLastPublished(safeEnd.toInstant())
        configStore.setLastSummary(summary)
        configStore.setLastGeneratedDetails(generatedDetails)

        return PublishResult(
            recordCount = insertedRecords,
            stepCount = stepCount,
            start = safeStart,
            end = safeEnd,
            summary = summary
        )
    }

    private fun buildGeneratedDetails(
        label: String,
        start: ZonedDateTime,
        end: ZonedDateTime,
        slices: List<MinuteStepSlice>,
        workouts: List<WorkoutPlan>
    ): String {
        if (slices.isEmpty() && workouts.isEmpty()) {
            return "$label did not create any new step records for ${start.format(formatter)} to ${end.format(formatter)}."
        }

        val byDay = slices.groupBy { it.start.withZoneSameInstant(zoneId).toLocalDate() }
            .toSortedMap()
        val workoutsByDay = workouts.groupBy { it.start.withZoneSameInstant(zoneId).toLocalDate() }
            .toSortedMap()
        val allDates = (byDay.keys + workoutsByDay.keys).sorted()

        return buildString {
            appendLine("$label window written to Health Connect")
            appendLine("${start.format(formatter)} - ${end.format(formatter)}")
            appendLine(
                "Total: ${slices.sumOf { it.count }.formatThousands()} steps in ${slices.size} minute records " +
                    "and ${workouts.size} workouts"
            )
            appendLine()

            allDates.forEachIndexed { index, day ->
                append(
                    renderDaySummary(
                        day,
                        byDay[day].orEmpty(),
                        workoutsByDay[day].orEmpty()
                    )
                )
                if (index < allDates.size - 1) {
                    appendLine()
                    appendLine()
                }
            }
        }.trim()
    }

    private fun renderDaySummary(
        day: LocalDate,
        daySlices: List<MinuteStepSlice>,
        dayWorkouts: List<WorkoutPlan>
    ): String {
        val first = daySlices.minByOrNull { it.start.toInstant() }
        val last = daySlices.maxByOrNull { it.end.toInstant() }
        val steps = daySlices.sumOf { it.count }
        return buildString {
            append(day.format(dayFormatter))
            append(": ")
            append(steps.formatThousands())
            append(" steps in ")
            append(daySlices.size)
            append(" minute records")
            appendLine()
            if (first != null && last != null) {
                append("Active span: ")
                append(first.start.format(timeFormatter))
                append(" - ")
                append(last.end.format(timeFormatter))
            } else {
                append("Active span: none")
            }
            if (dayWorkouts.isNotEmpty()) {
                appendLine()
                append("Workouts: ")
                append(dayWorkouts.joinToString { workout ->
                    "${workout.title} (${workout.start.format(timeFormatter)}-${workout.end.format(timeFormatter)})"
                })
            }
        }
    }
}

private fun Long.formatThousands(): String = "%,d".format(this)
