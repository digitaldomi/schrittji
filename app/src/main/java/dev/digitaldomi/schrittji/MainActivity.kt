package dev.digitaldomi.schrittji

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_AVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.digitaldomi.schrittji.chart.DualSeriesBarPoint
import dev.digitaldomi.schrittji.chart.TimelineBarEntry
import dev.digitaldomi.schrittji.databinding.ActivityMainBinding
import dev.digitaldomi.schrittji.health.HealthConnectGateway
import dev.digitaldomi.schrittji.health.HealthConnectStepsSnapshot
import dev.digitaldomi.schrittji.simulation.SimulationConfig
import dev.digitaldomi.schrittji.simulation.SimulationConfigStore
import dev.digitaldomi.schrittji.simulation.SimulationCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val configStore by lazy { SimulationConfigStore(applicationContext) }
    private val simulationCoordinator by lazy {
        SimulationCoordinator(healthConnectGateway, configStore)
    }
    private val formatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm")
    private var latestSnapshot: HealthConnectStepsSnapshot? = null
    private var chartMode: ChartMode = ChartMode.DETAIL
    private var selectedProjectionDate: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()

        lifecycleScope.launch {
            refreshUiState()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            refreshUiState()
        }
    }

    private fun setupListeners() {
        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.toggleChartMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            chartMode = when (checkedId) {
                R.id.buttonModeDetail -> ChartMode.DETAIL
                R.id.buttonModeWeekly -> ChartMode.WEEKLY
                R.id.buttonModeMonthly -> ChartMode.MONTHLY
                else -> ChartMode.DAILY
            }
            renderOverviewChart(simulationCoordinator.loadConfig())
        }
        binding.buttonPreviousProjectionDay.setOnClickListener {
            selectedProjectionDate = selectedProjectionDate.minusDays(1)
            renderOverviewChart(simulationCoordinator.loadConfig())
        }
        binding.buttonNextProjectionDay.setOnClickListener {
            selectedProjectionDate = selectedProjectionDate.plusDays(1)
            renderOverviewChart(simulationCoordinator.loadConfig())
        }
        binding.buttonTodayProjectionDay.setOnClickListener {
            selectedProjectionDate = LocalDate.now()
            renderOverviewChart(simulationCoordinator.loadConfig())
        }
    }

    private suspend fun refreshUiState() {
        val config = simulationCoordinator.loadConfig()

        val availability = healthConnectGateway.availability()
        val permissionGranted = if (availability == SDK_AVAILABLE) {
            healthConnectGateway.hasRequiredPermissions()
        } else {
            false
        }
        latestSnapshot = if (permissionGranted) {
            try {
                healthConnectGateway.readAllStepsSnapshot()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        updateStatus(
            view = binding.dotHealthConnect,
            textView = binding.textHealthConnectStatus,
            ok = availability == SDK_AVAILABLE,
            okText = "Health Connect connected",
            badText = when (availability) {
                SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect needs install/update"
                SDK_UNAVAILABLE -> "Health Connect not supported"
                else -> "Health Connect unavailable"
            }
        )
        updateStatus(
            view = binding.dotPermissions,
            textView = binding.textPermissionsStatus,
            ok = permissionGranted,
            okText = "Permissions granted",
            badText = "Permissions missing"
        )
        val updatesOk = config.lastPublishedEpochMilli?.let {
            Instant.ofEpochMilli(it).isAfter(Instant.now().minusSeconds(45 * 60))
        } == true
        updateStatus(
            view = binding.dotUpdates,
            textView = binding.textUpdatesStatus,
            ok = updatesOk,
            okText = "Updates current",
            badText = "Updates need sync"
        )

        if (binding.toggleChartMode.checkedButtonId == View.NO_ID) {
            binding.toggleChartMode.check(R.id.buttonModeDetail)
        } else {
            renderOverviewChart(config)
        }
    }

    private fun renderOverviewChart(config: SimulationConfig) {
        if (chartMode == ChartMode.DETAIL) {
            renderProjectionDetail(config)
            return
        }
        binding.panelLegend.visibility = View.VISIBLE
        binding.panelDetailContent.visibility = View.GONE
        binding.panelOverviewContent.visibility = View.VISIBLE
        binding.textProjectionDescription.text = getString(R.string.projection_description)
        val points = when (chartMode) {
            ChartMode.DAILY -> buildDailyOverview(config)
            ChartMode.WEEKLY -> buildWeeklyOverview(config)
            ChartMode.MONTHLY -> buildMonthlyOverview(config)
            ChartMode.DETAIL -> emptyList()
        }
        binding.chartOverview.submit(points)
        val existingTotal = points.sumOf { it.existingValue.toLong() }
        val projectedTotal = points.sumOf { it.projectedValue.toLong() }
        binding.textChartSummary.text = buildString {
            append("Existing: ${existingTotal.formatThousands()} steps")
            append("  |  ")
            append("Projected: ${projectedTotal.formatThousands()} steps")
            latestSnapshot?.latestEnd?.let {
                appendLine()
                append("Latest Health Connect end: ${it.format(formatter)}")
            }
        }
    }

    private fun renderProjectionDetail(config: SimulationConfig) {
        binding.panelLegend.visibility = View.VISIBLE
        binding.panelDetailContent.visibility = View.VISIBLE
        binding.panelOverviewContent.visibility = View.GONE
        binding.textProjectionDescription.text = "Existing Health Connect entries and projected Schrittji entries across one day."
        binding.textSelectedProjectionDate.text =
            selectedProjectionDate.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        binding.buttonTodayProjectionDay.isEnabled = selectedProjectionDate != LocalDate.now()

        val detail = simulationCoordinator.projectDayDetail(config, selectedProjectionDate)
        val existingEntries = latestSnapshot?.records
            ?.filter { it.start.toLocalDate() == selectedProjectionDate }
            .orEmpty()
        binding.chartProjectionDetail.submitEntries(
            existingEntries.map { entry ->
                TimelineBarEntry(
                    startMinute = entry.start.hour * 60 + entry.start.minute,
                    endMinute = entry.end.hour * 60 + entry.end.minute,
                    value = entry.count.toFloat(),
                    series = dev.digitaldomi.schrittji.chart.TimelineSeries.EXISTING,
                    emphasized = false
                )
            } + detail.slices.map { slice ->
                TimelineBarEntry(
                    startMinute = slice.start.hour * 60 + slice.start.minute,
                    endMinute = slice.end.hour * 60 + slice.end.minute,
                    value = slice.count.toFloat(),
                    series = dev.digitaldomi.schrittji.chart.TimelineSeries.PROJECTED,
                    emphasized = false
                )
            }
        )
        binding.textProjectionDetailSummary.text = buildString {
            appendLine("Existing Health Connect: ${existingEntries.sumOf { it.count }.formatThousands()} steps")
            appendLine("Projected total: ${detail.totalSteps.formatThousands()} steps")
            appendLine("Generated entries: ${detail.slices.size}")
            if (detail.workouts.isNotEmpty()) {
                append("Workouts: ")
                append(detail.workouts.joinToString { workout ->
                    "${workout.title} ${workout.start.format(DateTimeFormatter.ofPattern("HH:mm"))}-${workout.end.format(DateTimeFormatter.ofPattern("HH:mm"))}"
                })
            } else {
                append("Workouts: none")
            }
        }
    }

    private fun buildDailyOverview(config: SimulationConfig): List<DualSeriesBarPoint> {
        val actualByDate = latestSnapshot?.daySummaries?.associateBy { it.date }.orEmpty()
        val today = LocalDate.now()
        val projection = simulationCoordinator.projectNextDays(config, 7).associateBy { it.date }
        val past = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { date ->
            DualSeriesBarPoint(
                label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3),
                existingValue = (actualByDate[date]?.totalSteps ?: 0L).toFloat(),
                projectedValue = 0f
            )
        }
        val future = (1..7).map { today.plusDays(it.toLong()) }.map { date ->
            DualSeriesBarPoint(
                label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3),
                existingValue = 0f,
                projectedValue = (projection[date]?.totalSteps ?: 0L).toFloat()
            )
        }
        return past + future
    }

    private fun buildWeeklyOverview(config: SimulationConfig): List<DualSeriesBarPoint> {
        val dayMap = latestSnapshot?.daySummaries?.associateBy { it.date }.orEmpty()
        val currentWeek = LocalDate.now().with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val actualWeeks = (7 downTo 0).map { currentWeek.minusWeeks(it.toLong()) }.map { weekStart ->
            val total = (0..6).sumOf { dayMap[weekStart.plusDays(it.toLong())]?.totalSteps ?: 0L }
            DualSeriesBarPoint(
                label = weekStart.format(DateTimeFormatter.ofPattern("MM/dd")),
                existingValue = total.toFloat(),
                projectedValue = 0f
            )
        }
        val projectedDays = simulationCoordinator.projectNextDays(config, 28).groupBy {
            it.date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        }
        val projectedWeeks = (1..4).map { currentWeek.plusWeeks(it.toLong()) }.map { weekStart ->
            DualSeriesBarPoint(
                label = weekStart.format(DateTimeFormatter.ofPattern("MM/dd")),
                existingValue = 0f,
                projectedValue = projectedDays[weekStart].orEmpty().sumOf { it.totalSteps }.toFloat()
            )
        }
        return actualWeeks + projectedWeeks
    }

    private fun buildMonthlyOverview(config: SimulationConfig): List<DualSeriesBarPoint> {
        val dayMap = latestSnapshot?.daySummaries?.associateBy { it.date }.orEmpty()
        val currentMonth = LocalDate.now().withDayOfMonth(1)
        val actualMonths = (5 downTo 0).map { currentMonth.minusMonths(it.toLong()) }.map { monthStart ->
            val nextMonth = monthStart.plusMonths(1)
            val total = dayMap.entries
                .filter { it.key >= monthStart && it.key < nextMonth }
                .sumOf { it.value.totalSteps }
            DualSeriesBarPoint(
                label = monthStart.format(DateTimeFormatter.ofPattern("MMM")),
                existingValue = total.toFloat(),
                projectedValue = 0f
            )
        }
        val projectedDays = simulationCoordinator.projectNextDays(config, 120).groupBy {
            it.date.withDayOfMonth(1)
        }
        val projectedMonths = (1..3).map { currentMonth.plusMonths(it.toLong()) }.map { monthStart ->
            DualSeriesBarPoint(
                label = monthStart.format(DateTimeFormatter.ofPattern("MMM")),
                existingValue = 0f,
                projectedValue = projectedDays[monthStart].orEmpty().sumOf { it.totalSteps }.toFloat()
            )
        }
        return actualMonths + projectedMonths
    }

    private fun updateStatus(
        view: View,
        textView: android.widget.TextView,
        ok: Boolean,
        okText: String,
        badText: String
    ) {
        view.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (ok) R.color.status_ok else R.color.status_bad)
        )
        textView.text = if (ok) okText else badText
    }
}

private fun Long.formatThousands(): String = "%,d".format(this)

private enum class ChartMode {
    DETAIL,
    DAILY,
    WEEKLY,
    MONTHLY
}
