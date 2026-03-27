package dev.sudominus.schrittji

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dev.sudominus.schrittji.chart.ProjectionTimeline
import dev.sudominus.schrittji.chart.TimelineSeries
import dev.sudominus.schrittji.chart.TimelineBarEntry
import dev.sudominus.schrittji.chart.TimelineWorkoutKind
import dev.sudominus.schrittji.databinding.ActivityDayDetailBinding
import dev.sudominus.schrittji.health.HealthConnectExerciseSession
import dev.sudominus.schrittji.health.HealthConnectGateway
import dev.sudominus.schrittji.health.WorkoutMerge
import dev.sudominus.schrittji.health.HealthConnectStepRecordEntry
import dev.sudominus.schrittji.simulation.SimulationConfigStore
import dev.sudominus.schrittji.simulation.SimulationCoordinator
import dev.sudominus.schrittji.simulation.WorkoutPlan
import dev.sudominus.schrittji.simulation.WorkoutType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
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
        detail: dev.sudominus.schrittji.simulation.ProjectedStepDayDetail,
        hcSessions: List<HealthConnectExerciseSession>,
        exerciseReadError: String?
    ) {
        val today = LocalDate.now(zoneId)
        val showFutureProjection = selectedDate.isAfter(today)
        val isToday = selectedDate == today
        val nowZdt = ZonedDateTime.now(zoneId)
        val nowSecOfDay = nowZdt.toLocalTime().toSecondOfDay().coerceIn(0, 86400)
        val nowMinuteOfDay = nowZdt.hour * 60 + nowZdt.minute + nowZdt.second / 60f

        val slices = detail.slices
        val projectedOnly = when {
            showFutureProjection -> detail.workouts.filter { plan ->
                hcSessions.none { WorkoutMerge.hcMatchesProjectedPlan(it, plan) }
            }
            isToday -> detail.workouts.filter { plan ->
                hcSessions.none { WorkoutMerge.hcMatchesProjectedPlan(it, plan) } &&
                    plan.start.isAfter(nowZdt)
            }
            else -> emptyList()
        }

        val todayProjectedEntries = if (isToday) {
            ProjectionTimeline.splitSlicesAtNow(detail.slices, selectedDate, zoneId, nowSecOfDay)
        } else {
            emptyList()
        }

        val projectedStepsForSummary = when {
            showFutureProjection -> slices.sumOf { it.count }
            isToday -> todayProjectedEntries.sumOf { it.value.toLong() }
            else -> 0L
        }
        val projectedMinuteCount = when {
            showFutureProjection -> slices.size
            isToday -> todayProjectedEntries.size
            else -> 0
        }

        binding.chartDayTimeline.setNowMarkerMinuteOfDay(if (isToday) nowMinuteOfDay else null)
        binding.textSummary.text = buildString {
            appendLine("Source: Preview projection")
            when {
                showFutureProjection -> {
                    appendLine("Generated minute records: ${slices.size}")
                    appendLine("Projected total steps: ${slices.sumOf { it.count }.formatThousands()}")
                }
                isToday -> {
                    appendLine("Projected (rest of day): ${projectedStepsForSummary.formatThousands()} steps")
                    appendLine("Projection minute records: $projectedMinuteCount")
                }
                else -> appendLine(getString(R.string.summary_projection_future_only))
            }
            appendLine(getString(R.string.day_detail_hc_exercise_count, hcSessions.size))
            exerciseReadError?.let {
                appendLine(getString(R.string.hc_day_exercise_read_error, it))
                appendLine(getString(R.string.summary_exercise_read_failed_hint))
            }
        }
        binding.textEntries.text = when {
            showFutureProjection && slices.isNotEmpty() ->
                slices.joinToString(separator = "\n") { slice ->
                    "${slice.start.withZoneSameInstant(zoneId).format(timeFormatter)}-${slice.end.withZoneSameInstant(zoneId).format(timeFormatter)}  ${slice.count.formatThousands()} steps"
                }
            isToday && todayProjectedEntries.isNotEmpty() ->
                todayProjectedEntries.joinToString(separator = "\n") { e ->
                    val zs = selectedDate.atStartOfDay(zoneId).plusSeconds((e.startSecondOfDay ?: 0).toLong())
                    val ze = selectedDate.atStartOfDay(zoneId).plusSeconds((e.endSecondOfDay ?: 0).toLong())
                    "${zs.format(timeFormatter)}-${ze.format(timeFormatter)}  ${e.value.toLong().formatThousands()} steps"
                }
            else -> getString(R.string.day_detail_empty)
        }
        binding.chartDayTimeline.submitEntries(
            when {
                showFutureProjection -> detail.slices.map {
                    ProjectionTimeline.sliceToProjectedEntry(it, selectedDate, zoneId)
                }
                isToday -> todayProjectedEntries
                else -> emptyList()
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
