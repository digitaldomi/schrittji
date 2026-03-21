package dev.digitaldomi.schrittji.simulation

import dev.digitaldomi.schrittji.health.HealthConnectGateway
import java.time.Duration
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

class SimulationCoordinator(
    private val healthConnectGateway: HealthConnectGateway,
    private val configStore: SimulationConfigStore,
    private val stepSimulationEngine: StepSimulationEngine = StepSimulationEngine()
) {
    private val zoneId: ZoneId = ZoneId.systemDefault()
    private val formatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm")

    fun saveConfig(config: SimulationConfig): SimulationConfig {
        return configStore.save(config)
    }

    fun loadConfig(): SimulationConfig = configStore.load()

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
            return PublishResult(0, 0, safeStart, safeEnd, summary)
        }

        val slices = stepSimulationEngine.generateBetween(safeStart, safeEnd, config)
        val insertedRecords = healthConnectGateway.insertSlices(slices)
        val stepCount = slices.sumOf { it.count }
        val summary = if (slices.isEmpty()) {
            "$label produced no slices for ${safeStart.format(formatter)} to ${safeEnd.format(formatter)}."
        } else {
            "$label wrote ${stepCount.formatThousands()} steps across $insertedRecords minute records " +
                "from ${safeStart.format(formatter)} to ${safeEnd.format(formatter)}."
        }

        configStore.setLastPublished(safeEnd.toInstant())
        configStore.setLastSummary(summary)

        return PublishResult(
            recordCount = insertedRecords,
            stepCount = stepCount,
            start = safeStart,
            end = safeEnd,
            summary = summary
        )
    }
}

private fun Long.formatThousands(): String = "%,d".format(this)
