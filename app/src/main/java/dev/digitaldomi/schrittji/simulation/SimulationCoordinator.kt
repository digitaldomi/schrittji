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

        val slices = stepSimulationEngine.generateBetween(safeStart, safeEnd, config)
        val insertedRecords = healthConnectGateway.insertSlices(slices)
        val stepCount = slices.sumOf { it.count }
        val generatedDetails = buildGeneratedDetails(label, safeStart, safeEnd, slices)
        val summary = if (slices.isEmpty()) {
            "$label produced no slices for ${safeStart.format(formatter)} to ${safeEnd.format(formatter)}."
        } else {
            "$label created and added ${stepCount.formatThousands()} steps to Health Connect " +
                "across $insertedRecords minute records from ${safeStart.format(formatter)} " +
                "to ${safeEnd.format(formatter)}."
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
        slices: List<MinuteStepSlice>
    ): String {
        if (slices.isEmpty()) {
            return "$label did not create any new step records for ${start.format(formatter)} to ${end.format(formatter)}."
        }

        val byDay = slices.groupBy { it.start.withZoneSameInstant(zoneId).toLocalDate() }
            .toSortedMap()

        return buildString {
            appendLine("$label window written to Health Connect")
            appendLine("${start.format(formatter)} - ${end.format(formatter)}")
            appendLine("Total: ${slices.sumOf { it.count }.formatThousands()} steps in ${slices.size} minute records")
            appendLine()

            byDay.entries.forEachIndexed { index, (day, daySlices) ->
                append(renderDaySummary(day, daySlices))
                if (index < byDay.size - 1) {
                    appendLine()
                    appendLine()
                }
            }
        }.trim()
    }

    private fun renderDaySummary(day: LocalDate, daySlices: List<MinuteStepSlice>): String {
        val first = daySlices.minBy { it.start.toInstant() }
        val last = daySlices.maxBy { it.end.toInstant() }
        val steps = daySlices.sumOf { it.count }
        return buildString {
            append(day.format(dayFormatter))
            append(": ")
            append(steps.formatThousands())
            append(" steps in ")
            append(daySlices.size)
            append(" minute records")
            appendLine()
            append("Active span: ")
            append(first.start.format(timeFormatter))
            append(" - ")
            append(last.end.format(timeFormatter))
        }
    }
}

private fun Long.formatThousands(): String = "%,d".format(this)
