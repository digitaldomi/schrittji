package dev.digitaldomi.schrittji

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_AVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
import androidx.core.content.ContextCompat
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.digitaldomi.schrittji.chart.DualSeriesBarPoint
import dev.digitaldomi.schrittji.chart.TimelineSeries
import dev.digitaldomi.schrittji.chart.TimelineBarEntry
import dev.digitaldomi.schrittji.chart.TimelineWorkoutKind
import dev.digitaldomi.schrittji.databinding.ActivityMainBinding
import dev.digitaldomi.schrittji.health.HealthConnectGateway
import dev.digitaldomi.schrittji.health.HealthConnectStepsSnapshot
import dev.digitaldomi.schrittji.simulation.SimulationConfig
import dev.digitaldomi.schrittji.simulation.SimulationConfigStore
import dev.digitaldomi.schrittji.simulation.SimulationCoordinator
import dev.digitaldomi.schrittji.simulation.StepPublishingScheduler
import dev.digitaldomi.schrittji.simulation.WorkoutPlan
import dev.digitaldomi.schrittji.simulation.WorkoutType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.max
import kotlin.math.round
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val configStore by lazy { SimulationConfigStore(applicationContext) }
    private val simulationCoordinator by lazy {
        SimulationCoordinator(healthConnectGateway, configStore)
    }
    private val formatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm")
    private val workoutTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private var latestSnapshot: HealthConnectStepsSnapshot? = null
    private var chartMode: ChartMode = ChartMode.DAY
    private var selectedProjectionDate: LocalDate = LocalDate.now()
    private var selectedWeekStart: LocalDate = weekStartMonday(LocalDate.now())
    private var statusPanelExpanded: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

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
        binding.buttonOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.panelStatusCollapsed.setOnClickListener {
            statusPanelExpanded = !statusPanelExpanded
            binding.panelStatusExpanded.visibility = if (statusPanelExpanded) View.VISIBLE else View.GONE
            binding.iconStatusExpand.rotation = if (statusPanelExpanded) 180f else 0f
        }
        binding.toggleChartMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            chartMode = when (checkedId) {
                R.id.buttonModeWeek -> ChartMode.WEEK
                else -> ChartMode.DAY
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
        binding.buttonPreviousWeek.setOnClickListener {
            selectedWeekStart = selectedWeekStart.minusWeeks(1)
            renderOverviewChart(simulationCoordinator.loadConfig())
        }
        binding.buttonNextWeek.setOnClickListener {
            selectedWeekStart = selectedWeekStart.plusWeeks(1)
            renderOverviewChart(simulationCoordinator.loadConfig())
        }
        binding.buttonThisWeek.setOnClickListener {
            selectedWeekStart = weekStartMonday(LocalDate.now())
            renderOverviewChart(simulationCoordinator.loadConfig())
        }
        binding.chartProjectionDetail.setOnWorkoutTapListener { info ->
            val title = when {
                info.title.isNotBlank() -> info.title
                info.kind == TimelineWorkoutKind.RUNNING -> getString(R.string.workout_title_running)
                else -> getString(R.string.workout_title_cycling)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(info.detail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private suspend fun refreshUiState() {
        val config = simulationCoordinator.loadConfig()

        val availability = healthConnectGateway.availability()
        val permissionGranted = if (availability == SDK_AVAILABLE) {
            healthConnectGateway.hasCoreHealthPermissions()
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
        val dataFresh = config.lastPublishedEpochMilli?.let {
            Instant.ofEpochMilli(it).isAfter(Instant.now().minusSeconds(45 * 60))
        } == true
        val workManager = WorkManager.getInstance(applicationContext)
        // LiveData + Flow avoids ListenableFuture: core resolves listenablefuture to an empty
        // stub (9999.0-empty), so Guava's ListenableFuture is not on the compile classpath.
        val workerActive = withContext(Dispatchers.Main.immediate) {
            val immediate = workManager
                .getWorkInfosForUniqueWorkLiveData(StepPublishingScheduler.IMMEDIATE_WORK_NAME)
                .asFlow()
                .first()
            val periodic = workManager
                .getWorkInfosForUniqueWorkLiveData(StepPublishingScheduler.PERIODIC_WORK_NAME)
                .asFlow()
                .first()
            (immediate + periodic).any { info -> info.state == WorkInfo.State.RUNNING }
        }
        val updatesOk = dataFresh || (config.automationEnabled && workerActive)
        val updatesOkText = when {
            dataFresh -> getString(R.string.status_updates_ok)
            config.automationEnabled && workerActive -> getString(R.string.status_updates_syncing)
            else -> getString(R.string.status_updates_ok)
        }
        val updatesBadText = when {
            config.automationEnabled ->
                getString(R.string.status_updates_stale_bg_idle)
            else ->
                getString(R.string.status_updates_stale_bg_off)
        }
        updateStatus(
            view = binding.dotUpdates,
            textView = binding.textUpdatesStatus,
            ok = updatesOk,
            okText = updatesOkText,
            badText = updatesBadText
        )
        applyCollapsedStatusSummary(
            availability,
            permissionGranted,
            dataFresh,
            config.automationEnabled,
            workerActive
        )
        binding.panelStatusExpanded.visibility = if (statusPanelExpanded) View.VISIBLE else View.GONE
        binding.iconStatusExpand.rotation = if (statusPanelExpanded) 180f else 0f

        if (binding.toggleChartMode.checkedButtonId == View.NO_ID) {
            binding.toggleChartMode.check(R.id.buttonModeDay)
        } else {
            renderOverviewChart(config)
        }
    }

    private fun renderOverviewChart(config: SimulationConfig) {
        if (chartMode == ChartMode.DAY) {
            lifecycleScope.launch {
                renderProjectionDetail(config)
            }
            return
        }
        lifecycleScope.launch {
            renderWeekView(config)
        }
    }

    private suspend fun renderWeekView(config: SimulationConfig) {
        binding.panelDetailContent.visibility = View.GONE
        binding.panelOverviewContent.visibility = View.VISIBLE
        val today = LocalDate.now()
        val weekStart = selectedWeekStart
        binding.textSelectedWeek.text = formatWeekRangeLabel(weekStart)
        binding.buttonThisWeek.isEnabled = weekStart != weekStartMonday(today)

        val dayMap = latestSnapshot?.daySummaries?.associateBy { it.date }.orEmpty()
        val points = mutableListOf<DualSeriesBarPoint>()
        var hcWorkouts = 0
        var projectedWorkouts = 0

        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            val label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2)
            val detail = simulationCoordinator.projectDayDetail(config, date)
            projectedWorkouts += detail.workouts.size

            val hcSessions = if (healthConnectGateway.hasExerciseReadPermission()) {
                try {
                    healthConnectGateway.readExerciseSessionsForDate(date)
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            hcWorkouts += hcSessions.size

            val existing = when {
                date.isBefore(today) -> (dayMap[date]?.totalSteps ?: 0L).toFloat()
                date == today -> (dayMap[date]?.totalSteps ?: 0L).toFloat()
                else -> 0f
            }
            val projected = when {
                date.isAfter(today) -> detail.totalSteps.toFloat()
                date == today -> max(0L, detail.totalSteps - (dayMap[date]?.totalSteps ?: 0L)).toFloat()
                else -> 0f
            }
            points.add(
                DualSeriesBarPoint(
                    label = label,
                    existingValue = existing,
                    projectedValue = projected,
                    hasRecordedWorkout = hcSessions.isNotEmpty(),
                    hasProjectedWorkout = detail.workouts.isNotEmpty()
                )
            )
        }

        binding.chartOverview.submit(points)
        val dayTotals = points.map { it.existingValue.toLong() + it.projectedValue.toLong() }
        val avg = if (dayTotals.isNotEmpty()) dayTotals.sum().toDouble() / dayTotals.size else 0.0
        val minV = dayTotals.minOrNull() ?: 0L
        val maxV = dayTotals.maxOrNull() ?: 0L
        binding.textChartSummary.text = buildString {
            appendLine(
                getString(
                    R.string.week_summary_steps_range,
                    avg.roundToThousandsLabel(),
                    minV.formatThousands(),
                    maxV.formatThousands()
                )
            )
            appendLine(
                getString(R.string.week_summary_workouts, hcWorkouts, projectedWorkouts)
            )
            latestSnapshot?.latestEnd?.let {
                append("Latest Health Connect end: ${it.format(formatter)}")
            }
        }
    }

    private suspend fun renderProjectionDetail(config: SimulationConfig) {
        binding.panelDetailContent.visibility = View.VISIBLE
        binding.panelOverviewContent.visibility = View.GONE
        binding.textSelectedProjectionDate.text =
            selectedProjectionDate.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        binding.buttonTodayProjectionDay.isEnabled = selectedProjectionDate != LocalDate.now()

        val detail = simulationCoordinator.projectDayDetail(config, selectedProjectionDate)
        val existingEntries = latestSnapshot?.records
            ?.filter { it.start.toLocalDate() == selectedProjectionDate }
            .orEmpty()
        val exerciseSessions = if (healthConnectGateway.hasExerciseReadPermission()) {
            try {
                healthConnectGateway.readExerciseSessionsForDate(selectedProjectionDate)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        binding.chartProjectionDetail.submitEntries(
            existingEntries.map { entry ->
                TimelineBarEntry(
                    startMinute = entry.start.hour * 60 + entry.start.minute,
                    endMinute = entry.end.hour * 60 + entry.end.minute,
                    value = entry.count.toFloat(),
                    series = TimelineSeries.EXISTING,
                    emphasized = false
                )
            } + detail.slices.map { slice ->
                TimelineBarEntry(
                    startMinute = slice.start.hour * 60 + slice.start.minute,
                    endMinute = slice.end.hour * 60 + slice.end.minute,
                    value = slice.count.toFloat(),
                    series = TimelineSeries.PROJECTED,
                    emphasized = false
                )
            } + exerciseSessions.map { session ->
                TimelineBarEntry(
                    startMinute = session.start.hour * 60 + session.start.minute,
                    endMinute = session.end.hour * 60 + session.end.minute,
                    value = 1f,
                    series = TimelineSeries.WORKOUT,
                    workoutKind = session.type.toTimelineWorkoutKind(),
                    workoutTitle = session.title?.takeIf { it.isNotBlank() }
                        ?: defaultWorkoutTitle(session.type),
                    workoutDetail = healthConnectGateway.formatExerciseSessionDetail(session),
                    workoutIsProjected = false
                )
            } + detail.workouts.map { workout ->
                TimelineBarEntry(
                    startMinute = workout.start.hour * 60 + workout.start.minute,
                    endMinute = workout.end.hour * 60 + workout.end.minute,
                    value = 1f,
                    series = TimelineSeries.WORKOUT,
                    workoutKind = workout.type.toTimelineWorkoutKind(),
                    workoutTitle = workout.title,
                    workoutDetail = buildProjectedWorkoutDetail(workout),
                    workoutIsProjected = true
                )
            }
        )
        binding.textProjectionDetailSummary.text = buildDetailSummaryText(
            existingEntries = existingEntries,
            detail = detail,
            exerciseSessions = exerciseSessions
        )
    }

    private fun buildDetailSummaryText(
        existingEntries: List<dev.digitaldomi.schrittji.health.HealthConnectStepRecordEntry>,
        detail: dev.digitaldomi.schrittji.simulation.ProjectedStepDayDetail,
        exerciseSessions: List<dev.digitaldomi.schrittji.health.HealthConnectExerciseSession>
    ): String {
        return buildString {
            append("Existing ")
            append(existingEntries.sumOf { it.count }.formatThousands())
            append(" · Projected ")
            appendLine(detail.totalSteps.formatThousands())
            appendLine("${detail.slices.size} minute records")
            if (exerciseSessions.isNotEmpty()) {
                appendLine("Recorded:")
                exerciseSessions.forEach { session ->
                    val dur = ChronoUnit.MINUTES.between(session.start, session.end).coerceAtLeast(1)
                    appendLine(
                        "· ${session.type.label()} ${session.start.format(workoutTimeFormatter)}–${session.end.format(workoutTimeFormatter)} · ${dur} min"
                    )
                }
            }
            if (detail.workouts.isNotEmpty()) {
                appendLine("Projected:")
                detail.workouts.forEach { workout ->
                    val dur = ChronoUnit.MINUTES.between(workout.start, workout.end).coerceAtLeast(1)
                    val stepsInWorkout = sumStepsInWindow(detail.slices, workout.start, workout.end)
                    appendLine(
                        "· ${workout.type.label()} ${workout.start.format(workoutTimeFormatter)}–${workout.end.format(workoutTimeFormatter)} · ${dur} min · ~${stepsInWorkout.formatThousands()} st · ${String.format(Locale.getDefault(), "%.1f km", workout.distanceMeters / 1000.0)}"
                    )
                }
            }
            if (exerciseSessions.isEmpty() && detail.workouts.isEmpty()) {
                append("No workouts.")
            }
        }
    }

    private fun sumStepsInWindow(
        slices: List<dev.digitaldomi.schrittji.simulation.MinuteStepSlice>,
        start: java.time.ZonedDateTime,
        end: java.time.ZonedDateTime
    ): Long {
        return slices.filter { slice ->
            slice.end.isAfter(start) && slice.start.isBefore(end)
        }.sumOf { it.count }
    }

    private fun WorkoutType.label(): String = when (this) {
        WorkoutType.RUNNING -> getString(R.string.workout_title_running)
        WorkoutType.CYCLING -> getString(R.string.workout_title_cycling)
    }

    private fun defaultWorkoutTitle(type: WorkoutType): String {
        return when (type) {
            WorkoutType.RUNNING -> getString(R.string.workout_title_running)
            WorkoutType.CYCLING -> getString(R.string.workout_title_cycling)
        }
    }

    private fun buildProjectedWorkoutDetail(workout: WorkoutPlan): String {
        val duration = ChronoUnit.MINUTES.between(workout.start, workout.end).coerceAtLeast(1)
        return buildString {
            appendLine(
                "${workout.start.format(workoutTimeFormatter)}–${workout.end.format(workoutTimeFormatter)}"
            )
            appendLine("Duration: $duration min")
            appendLine(String.format(Locale.getDefault(), "%.1f km", workout.distanceMeters / 1000.0))
            if (workout.notes.isNotBlank()) {
                appendLine(workout.notes)
            }
            append(getString(R.string.workout_info_source_projected))
        }
    }

    private fun updateStatus(
        view: View,
        textView: android.widget.TextView,
        ok: Boolean,
        okText: String,
        badText: String
    ) {
        view.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (ok) R.color.status_ok else R.color.status_bad)
        )
        textView.text = if (ok) okText else badText
    }

    private fun applyCollapsedStatusSummary(
        availability: Int,
        permissionGranted: Boolean,
        dataFresh: Boolean,
        automationEnabled: Boolean,
        workerActive: Boolean
    ) {
        val dot = binding.dotStatusSummary
        val label = binding.textStatusSummary
        val updatesGreen = dataFresh || (automationEnabled && workerActive)
        when {
            availability != SDK_AVAILABLE -> {
                dot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_bad)
                )
                label.text = getString(R.string.status_summary_error_health)
            }
            !permissionGranted -> {
                dot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_warn)
                )
                label.text = getString(R.string.status_summary_warning_permissions)
            }
            !updatesGreen -> {
                dot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_warn)
                )
                label.text = when {
                    !automationEnabled ->
                        getString(R.string.status_summary_warning_updates_no_bg)
                    else ->
                        getString(R.string.status_summary_warning_updates_bg_idle)
                }
            }
            else -> {
                dot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_ok)
                )
                label.text = getString(R.string.status_summary_all_ok)
            }
        }
    }
}

private fun Long.formatThousands(): String = "%,d".format(this)

private fun Double.roundToThousandsLabel(): String {
    return round(this).toLong().formatThousands()
}

private fun weekStartMonday(date: LocalDate): LocalDate {
    return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}

private fun formatWeekRangeLabel(weekStart: LocalDate): String {
    val end = weekStart.plusDays(6)
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    return "${weekStart.format(fmt)} – ${end.format(fmt)}"
}

private fun WorkoutType.toTimelineWorkoutKind(): TimelineWorkoutKind {
    return when (this) {
        WorkoutType.RUNNING -> TimelineWorkoutKind.RUNNING
        WorkoutType.CYCLING -> TimelineWorkoutKind.CYCLING
    }
}

private enum class ChartMode {
    DAY,
    WEEK
}
