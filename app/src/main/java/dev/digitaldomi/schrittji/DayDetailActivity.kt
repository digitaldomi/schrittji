package dev.digitaldomi.schrittji

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.digitaldomi.schrittji.chart.TimelineSeries
import dev.digitaldomi.schrittji.chart.TimelineBarEntry
import dev.digitaldomi.schrittji.chart.TimelineWorkoutKind
import dev.digitaldomi.schrittji.databinding.ActivityDayDetailBinding
import dev.digitaldomi.schrittji.health.HealthConnectExerciseSession
import dev.digitaldomi.schrittji.health.HealthConnectGateway
import dev.digitaldomi.schrittji.health.WorkoutMerge
import dev.digitaldomi.schrittji.health.HealthConnectStepRecordEntry
import dev.digitaldomi.schrittji.simulation.SimulationConfigStore
import dev.digitaldomi.schrittji.simulation.SimulationCoordinator
import dev.digitaldomi.schrittji.simulation.WorkoutPlan
import dev.digitaldomi.schrittji.simulation.WorkoutType
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.launch

class DayDetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDayDetailBinding

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val simulationCoordinator by lazy {
        SimulationCoordinator(healthConnectGateway, SimulationConfigStore(applicationContext))
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val zoneId = ZoneId.systemDefault()
    private lateinit var source: DayDetailSource
    private var selectedDate: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDayDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        source = DayDetailSource.fromName(intent.getStringExtra(EXTRA_SOURCE))
        selectedDate = intent.getStringExtra(EXTRA_DATE)?.let(LocalDate::parse) ?: LocalDate.now()

        binding.toolbar.title = getString(
            if (source == DayDetailSource.PROJECTION) {
                R.string.day_detail_title_projection
            } else {
                R.string.day_detail_title_health_connect
            }
        )
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.chartDayTimeline.setOnWorkoutTapListener { info ->
            val title = when {
                info.title.isNotBlank() -> info.title
                info.kind == TimelineWorkoutKind.RUNNING -> getString(R.string.workout_title_running)
                info.kind == TimelineWorkoutKind.CYCLING -> getString(R.string.workout_title_cycling)
                else -> getString(R.string.workout_title_mindfulness)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage(info.detail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        binding.buttonPreviousDay.setOnClickListener {
            selectedDate = selectedDate.minusDays(1)
            loadDay()
        }
        binding.buttonNextDay.setOnClickListener {
            selectedDate = selectedDate.plusDays(1)
            loadDay()
        }

        loadDay()
    }

    private fun loadDay() {
        binding.textSelectedDate.text = selectedDate.format(dateFormatter)
        lifecycleScope.launch {
            try {
                when (source) {
                    DayDetailSource.HEALTH_CONNECT -> {
                        val entries = healthConnectGateway.readStepEntriesForDate(selectedDate)
                        val exResult = healthConnectGateway.readExerciseSessionsForDateResult(selectedDate)
                        renderHealthConnectDay(entries, exResult.sessions, exResult.queryError)
                    }

                    DayDetailSource.PROJECTION -> {
                        val detail = simulationCoordinator.projectDayDetail(
                            simulationCoordinator.loadConfig(),
                            selectedDate
                        )
                        val exResult = healthConnectGateway.readExerciseSessionsForDateResult(selectedDate)
                        renderProjectionDay(detail, exResult.sessions, exResult.queryError)
                    }
                }
            } catch (exception: Exception) {
                Snackbar.make(
                    binding.root,
                    exception.message ?: "Failed to load day detail.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun renderHealthConnectDay(
        entries: List<HealthConnectStepRecordEntry>,
        exercises: List<HealthConnectExerciseSession>,
        exerciseReadError: String?
    ) {
        val totalSteps = entries.sumOf { it.count }
        binding.textSummary.text = buildString {
            appendLine("Source: Health Connect")
            appendLine("Visible records: ${entries.size}")
            appendLine("Exercise sessions: ${exercises.size}")
            exerciseReadError?.let {
                appendLine(getString(R.string.hc_day_exercise_read_error, it))
                appendLine(getString(R.string.summary_exercise_read_failed_hint))
            }
            append("Total steps: ${totalSteps.formatThousands()}")
            if (exercises.isEmpty() && exerciseReadError == null) {
                appendLine()
                appendLine()
                append(getString(R.string.hc_day_exercise_zero_hint))
            }
        }
        binding.textEntries.text = if (entries.isEmpty()) {
            getString(R.string.day_detail_empty)
        } else {
            entries.joinToString(separator = "\n") { entry ->
                "${entry.start.format(timeFormatter)}-${entry.end.format(timeFormatter)}  ${entry.count.formatThousands()} steps  |  " +
                    (if (entry.sourcePackage.isBlank()) "Unknown source" else entry.sourcePackage)
            }
        }
        binding.chartDayTimeline.submitEntries(
            entries.map { entry ->
                TimelineBarEntry(
                    startMinute = entry.start.hour * 60 + entry.start.minute,
                    endMinute = entry.end.hour * 60 + entry.end.minute,
                    value = entry.count.toFloat(),
                    series = TimelineSeries.EXISTING,
                    emphasized = entry.isFromSchrittji
                )
            } + exercises.map { session ->
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
            }
        )
    }

    private fun renderProjectionDay(
        detail: dev.digitaldomi.schrittji.simulation.ProjectedStepDayDetail,
        hcSessions: List<HealthConnectExerciseSession>,
        exerciseReadError: String?
    ) {
        val showFutureProjection = selectedDate.isAfter(LocalDate.now())
        val slices = detail.slices
        val totalSteps = slices.sumOf { it.count }
        val projectedOnly = if (showFutureProjection) {
            detail.workouts.filter { plan ->
                hcSessions.none { WorkoutMerge.hcMatchesProjectedPlan(it, plan) }
            }
        } else {
            emptyList()
        }
        binding.textSummary.text = buildString {
            appendLine("Source: Preview projection")
            if (showFutureProjection) {
                appendLine("Generated minute records: ${slices.size}")
                appendLine("Projected total steps: ${totalSteps.formatThousands()}")
            } else {
                appendLine(getString(R.string.summary_projection_future_only))
            }
            appendLine(getString(R.string.day_detail_hc_exercise_count, hcSessions.size))
            exerciseReadError?.let {
                appendLine(getString(R.string.hc_day_exercise_read_error, it))
                appendLine(getString(R.string.summary_exercise_read_failed_hint))
            }
        }
        binding.textEntries.text = when {
            !showFutureProjection -> getString(R.string.day_detail_empty)
            slices.isEmpty() -> getString(R.string.day_detail_empty)
            else ->
                slices.joinToString(separator = "\n") { slice ->
                    "${slice.start.withZoneSameInstant(zoneId).format(timeFormatter)}-${slice.end.withZoneSameInstant(zoneId).format(timeFormatter)}  ${slice.count.formatThousands()} steps"
                }
        }
        binding.chartDayTimeline.submitEntries(
            if (showFutureProjection) {
                slices.map { slice ->
                    TimelineBarEntry(
                        startMinute = slice.start.hour * 60 + slice.start.minute,
                        endMinute = slice.end.hour * 60 + slice.end.minute,
                        value = slice.count.toFloat(),
                        series = TimelineSeries.PROJECTED,
                        emphasized = false
                    )
                }
            } else {
                emptyList()
            } + hcSessions.map { session ->
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
            } + projectedOnly.map { workout ->
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
    }

    private fun defaultWorkoutTitle(type: WorkoutType): String {
        return when (type) {
            WorkoutType.RUNNING -> getString(R.string.workout_title_running)
            WorkoutType.CYCLING -> getString(R.string.workout_title_cycling)
            WorkoutType.MINDFULNESS -> getString(R.string.workout_title_mindfulness)
        }
    }

    private fun buildProjectedWorkoutDetail(workout: WorkoutPlan): String {
        val duration = ChronoUnit.MINUTES.between(workout.start, workout.end).coerceAtLeast(1)
        return buildString {
            appendLine(
                "${workout.start.withZoneSameInstant(zoneId).format(timeFormatter)}–" +
                    workout.end.withZoneSameInstant(zoneId).format(timeFormatter)
            )
            appendLine("Duration: $duration min")
            if (workout.type != WorkoutType.MINDFULNESS) {
                appendLine(String.format(Locale.getDefault(), "%.1f km", workout.distanceMeters / 1000.0))
            }
            workout.notes?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            append(getString(R.string.workout_info_source_projected))
        }
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_DATE = "date"

        fun newIntent(context: Context, source: DayDetailSource, date: LocalDate): Intent {
            return Intent(context, DayDetailActivity::class.java)
                .putExtra(EXTRA_SOURCE, source.name)
                .putExtra(EXTRA_DATE, date.toString())
        }
    }
}

enum class DayDetailSource {
    HEALTH_CONNECT,
    PROJECTION;

    companion object {
        fun fromName(name: String?): DayDetailSource {
            return entries.firstOrNull { it.name == name } ?: PROJECTION
        }
    }
}

private fun Long.formatThousands(): String = "%,d".format(this)

private fun WorkoutType.toTimelineWorkoutKind(): TimelineWorkoutKind {
    return when (this) {
        WorkoutType.RUNNING -> TimelineWorkoutKind.RUNNING
        WorkoutType.CYCLING -> TimelineWorkoutKind.CYCLING
        WorkoutType.MINDFULNESS -> TimelineWorkoutKind.MINDFULNESS
    }
}
