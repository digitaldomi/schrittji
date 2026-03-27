package dev.sudominus.schrittji

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_AVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.R as MaterialR
import dev.sudominus.schrittji.chart.DualSeriesBarPoint
import dev.sudominus.schrittji.chart.ProjectionTimeline
import dev.sudominus.schrittji.chart.TimelineSeries
import dev.sudominus.schrittji.chart.TimelineBarEntry
import dev.sudominus.schrittji.chart.TimelineWorkoutKind
import dev.sudominus.schrittji.databinding.ActivityMainBinding
import dev.sudominus.schrittji.health.HealthConnectExerciseSession
import dev.sudominus.schrittji.health.HealthConnectGateway
import dev.sudominus.schrittji.health.HealthConnectStepRecordEntry
import dev.sudominus.schrittji.health.WorkoutMerge
import dev.sudominus.schrittji.health.HealthConnectStepsSnapshot
import dev.sudominus.schrittji.simulation.SimulationConfig
import dev.sudominus.schrittji.simulation.SimulationConfigStore
import dev.sudominus.schrittji.simulation.SimulationCoordinator
import dev.sudominus.schrittji.simulation.StepPublishingScheduler
import dev.sudominus.schrittji.simulation.ProjectedStepDayDetail
import dev.sudominus.schrittji.simulation.WorkoutPlan
import dev.sudominus.schrittji.simulation.WorkoutType
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
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val configStore by lazy { SimulationConfigStore(applicationContext) }
    private val simulationCoordinator by lazy {
        SimulationCoordinator(healthConnectGateway, configStore)
    }
    private val zoneId: ZoneId = ZoneId.systemDefault()
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
        if (config.automationEnabled && !StepPublishingScheduler.isPeriodicScheduled(this)) {
            StepPublishingScheduler.schedule(this)
        }
        val lastPublishedFresh = config.lastPublishedEpochMilli?.let {
            Instant.ofEpochMilli(it).isAfter(Instant.now().minusSeconds(45 * 60))
        } == true
        val automationOn = config.automationEnabled
        val workerScheduled = StepPublishingScheduler.isPeriodicScheduled(this)
        val updatesOk = lastPublishedFresh || (automationOn && workerScheduled)
        val updatesOkText = if (lastPublishedFresh) {
            getString(R.string.status_updates_ok_recent)
        } else {
            getString(R.string.status_updates_ok_background_stale)
        }
        val updatesBadText = when {
            !automationOn -> getString(R.string.status_updates_bad_no_automation)
            else -> getString(R.string.status_updates_bad_automation_not_running)
        }
        updateStatus(
            view = binding.dotUpdates,
            textView = binding.textUpdatesStatus,
            ok = updatesOk,
            okText = updatesOkText,
            badText = updatesBadText
        )
        applyCollapsedStatusSummary(availability, permissionGranted, updatesOk)
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
        var hcCardio = 0
        var hcMindfulness = 0
        var projectedCardio = 0
        var projectedMindfulness = 0
        var exerciseReadError: String? = null

        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            val label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2)
            val detail = simulationCoordinator.projectDayDetail(config, date)
            val hcResult = healthConnectGateway.readExerciseSessionsForDateResult(date)
            if (exerciseReadError == null && hcResult.queryError != null) {
                exerciseReadError = hcResult.queryError
            }
            val hcSessions = hcResult.sessions
            val showFutureProjection = date.isAfter(today)
            val projectedPlans = if (showFutureProjection) {
                detail.workouts.filter { plan ->
                    hcSessions.none { WorkoutMerge.hcMatchesProjectedPlan(it, plan) }
                }
            } else {
                emptyList()
            }
            projectedCardio += projectedPlans.count { it.type != WorkoutType.MINDFULNESS }
            projectedMindfulness += projectedPlans.count { it.type == WorkoutType.MINDFULNESS }
            hcCardio += hcSessions.count { it.type != WorkoutType.MINDFULNESS }
            hcMindfulness += hcSessions.count { it.type == WorkoutType.MINDFULNESS }

            val existing = when {
                date.isBefore(today) -> (dayMap[date]?.totalSteps ?: 0L).toFloat()
                date == today -> (dayMap[date]?.totalSteps ?: 0L).toFloat()
                else -> 0f
            }
            val projected = when {
                date.isAfter(today) -> detail.totalSteps.toFloat()
                else -> 0f
            }
            points.add(
                DualSeriesBarPoint(
                    label = label,
                    existingValue = existing,
                    projectedValue = projected,
                    hasRecordedCardioWorkout = hcSessions.any { it.type != WorkoutType.MINDFULNESS },
                    hasRecordedMindfulnessWorkout = hcSessions.any { it.type == WorkoutType.MINDFULNESS },
                    hasProjectedCardioWorkout = projectedPlans.any { it.type != WorkoutType.MINDFULNESS },
                    hasProjectedMindfulnessWorkout = projectedPlans.any { it.type == WorkoutType.MINDFULNESS }
                )
            )
        }

        binding.chartOverview.submit(points)
        val dayTotals = points.map { it.existingValue.toLong() + it.projectedValue.toLong() }
        val avg = if (dayTotals.isNotEmpty()) dayTotals.sum().toDouble() / dayTotals.size else 0.0
        val minV = dayTotals.minOrNull() ?: 0L
        val maxV = dayTotals.maxOrNull() ?: 0L
        populateWeekSummary(
            avgLabel = avg.roundToThousandsLabel(),
            minV = minV,
            maxV = maxV,
            hcCardio = hcCardio,
            hcMindfulness = hcMindfulness,
            projectedCardio = projectedCardio,
            projectedMindfulness = projectedMindfulness,
            exerciseReadError = exerciseReadError,
            latestEndText = latestSnapshot?.latestEnd?.let { "Latest Health Connect end: ${it.format(formatter)}" }
        )
    }

    private suspend fun renderProjectionDetail(config: SimulationConfig) {
        binding.panelDetailContent.visibility = View.VISIBLE
        binding.panelOverviewContent.visibility = View.GONE
        binding.textSelectedProjectionDate.text =
            selectedProjectionDate.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        binding.buttonTodayProjectionDay.isEnabled = selectedProjectionDate != LocalDate.now()

        val detail = simulationCoordinator.projectDayDetail(config, selectedProjectionDate)
        val today = LocalDate.now(zoneId)
        val showFutureProjection = selectedProjectionDate.isAfter(today)
        val isToday = selectedProjectionDate == today
        val nowZdt = java.time.ZonedDateTime.now(zoneId)
        val nowMinuteOfDay = nowZdt.hour * 60 + nowZdt.minute + nowZdt.second / 60f
        val nowSecOfDay = nowZdt.toLocalTime().toSecondOfDay().coerceIn(0, 86400)

        val existingEntries = latestSnapshot?.records
            ?.filter { it.start.toLocalDate() == selectedProjectionDate }
            .orEmpty()
        val hcExerciseResult = healthConnectGateway.readExerciseSessionsForDateResult(selectedProjectionDate)
        val exerciseSessions = hcExerciseResult.sessions
        val projectedWorkoutsOnly = when {
            showFutureProjection -> detail.workouts.filter { plan ->
                exerciseSessions.none { WorkoutMerge.hcMatchesProjectedPlan(it, plan) }
            }
            isToday -> detail.workouts.filter { plan ->
                exerciseSessions.none { WorkoutMerge.hcMatchesProjectedPlan(it, plan) } &&
                    plan.start.isAfter(nowZdt)
            }
            else -> emptyList()
        }

        val todayProjectedEntries = if (isToday) {
            ProjectionTimeline.splitSlicesAtNow(detail.slices, selectedProjectionDate, zoneId, nowSecOfDay)
        } else {
            emptyList()
        }

        binding.chartProjectionDetail.setNowMarkerMinuteOfDay(
            if (isToday) nowMinuteOfDay else null
        )
        binding.chartProjectionDetail.submitEntries(
            existingEntries.map { entry ->
                val startSec = entry.start.toLocalTime().toSecondOfDay()
                val endSec = entry.end.toLocalTime().toSecondOfDay().coerceAtLeast(startSec + 1)
                val (effStartSec, effEndSec, effValue) = if (isToday) {
                    val cut = nowSecOfDay
                    if (endSec <= cut) {
                        Triple(startSec, endSec, entry.count.toFloat())
                    } else if (startSec >= cut) {
                        Triple(0, 0, 0f)
                    } else {
                        val span = (endSec - startSec).coerceAtLeast(1)
                        val portion = (cut - startSec).toFloat() / span.toFloat()
                        Triple(startSec, cut, entry.count.toFloat() * portion)
                    }
                } else {
                    Triple(startSec, endSec, entry.count.toFloat())
                }
                if (effEndSec <= effStartSec || effValue <= 0f) {
                    null
                } else {
                    val zs = selectedProjectionDate.atStartOfDay(zoneId).plusSeconds(effStartSec.toLong())
                    val ze = selectedProjectionDate.atStartOfDay(zoneId).plusSeconds(effEndSec.toLong())
                    TimelineBarEntry(
                        startMinute = zs.hour * 60 + zs.minute,
                        endMinute = ze.hour * 60 + ze.minute,
                        value = effValue,
                        series = TimelineSeries.EXISTING,
                        emphasized = false,
                        startSecondOfDay = effStartSec,
                        endSecondOfDay = effEndSec
                    )
                }
            }.filterNotNull() + when {
                showFutureProjection -> detail.slices.map {
                    ProjectionTimeline.sliceToProjectedEntry(it, selectedProjectionDate, zoneId)
                }
                isToday -> todayProjectedEntries
                else -> emptyList()
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
            } + projectedWorkoutsOnly.map { workout ->
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
        val projectedStepsForSummary = when {
            showFutureProjection -> detail.totalSteps
            isToday -> todayProjectedEntries.sumOf { it.value.toLong() }
            else -> 0L
        }
        populateDayDetailSummary(
            existingEntries = existingEntries,
            detail = detail,
            projectedStepsForSummary = projectedStepsForSummary,
            exerciseSessions = exerciseSessions,
            projectedWorkoutsOnly = projectedWorkoutsOnly,
            hcExerciseReadError = hcExerciseResult.queryError,
            includeSimulatedProjection = showFutureProjection || isToday
        )
    }

    private fun populateDayDetailSummary(
        existingEntries: List<HealthConnectStepRecordEntry>,
        detail: ProjectedStepDayDetail,
        projectedStepsForSummary: Long,
        exerciseSessions: List<HealthConnectExerciseSession>,
        projectedWorkoutsOnly: List<WorkoutPlan>,
        hcExerciseReadError: String?,
        includeSimulatedProjection: Boolean
    ) {
        val container = binding.containerProjectionDetailSummary
        container.removeAllViews()
        val inflater = layoutInflater
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        fun addSpacer() {
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * resources.displayMetrics.density).toInt()
            )
            container.addView(v)
        }

        fun addStatRow(dotColor: Int, label: String, value: String, showValue: Boolean = true) {
            val row = inflater.inflate(R.layout.item_summary_stat_row, container, false)
            row.findViewById<View>(R.id.dotStat).backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, dotColor))
            row.findViewById<TextView>(R.id.textStatLabel).text = label
            val valueTv = row.findViewById<TextView>(R.id.textStatValue)
            if (showValue) {
                valueTv.text = value
                valueTv.visibility = View.VISIBLE
            } else {
                valueTv.visibility = View.GONE
            }
            container.addView(row)
        }

        fun addWorkoutRow(drawableRes: Int, iconTint: Int, line: String) {
            val row = inflater.inflate(R.layout.item_summary_workout_row, container, false)
            val icon = row.findViewById<ImageView>(R.id.iconWorkout)
            icon.setImageResource(drawableRes)
            icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, iconTint))
            row.findViewById<TextView>(R.id.textWorkoutLine).text = line
            container.addView(row)
        }

        val existingSteps = existingEntries.sumOf { it.count }
        addStatRow(
            R.color.chart_existing,
            getString(R.string.summary_section_recorded),
            getString(
                R.string.summary_recorded_overview,
                existingSteps.formatThousands(),
                exerciseSessions.size
            )
        )
        exerciseSessions.forEach { session ->
            val dur = ChronoUnit.MINUTES.between(session.start, session.end).coerceAtLeast(1)
            val line =
                "${session.start.format(workoutTimeFormatter)}–${session.end.format(workoutTimeFormatter)} · $dur min"
            val (dr, tint) = when (session.type) {
                WorkoutType.RUNNING ->
                    R.drawable.ic_workout_run to R.color.chart_workout
                WorkoutType.CYCLING ->
                    R.drawable.ic_workout_cycle to R.color.chart_workout
                WorkoutType.MINDFULNESS ->
                    R.drawable.ic_workout_mindfulness to R.color.chart_workout_mindfulness
            }
            addWorkoutRow(dr, tint, line)
        }

        if (includeSimulatedProjection) {
            addSpacer()
            addStatRow(
                R.color.chart_projected,
                getString(R.string.summary_section_projected),
                getString(
                    R.string.summary_projected_overview,
                    projectedStepsForSummary.formatThousands(),
                    projectedWorkoutsOnly.size
                )
            )
            projectedWorkoutsOnly.forEach { workout ->
                val dur = ChronoUnit.MINUTES.between(workout.start, workout.end).coerceAtLeast(1)
                val stepsInWorkout = sumStepsInWindow(detail.slices, workout.start, workout.end)
                val extra = if (workout.type == WorkoutType.MINDFULNESS) {
                    ""
                } else {
                    " · ~${stepsInWorkout.formatThousands()} st · " +
                        String.format(Locale.getDefault(), "%.1f km", workout.distanceMeters / 1000.0)
                }
                val line =
                    "${workout.start.format(workoutTimeFormatter)}–${workout.end.format(workoutTimeFormatter)} · $dur min$extra"
                val (dr, tint) = when (workout.type) {
                    WorkoutType.RUNNING ->
                        R.drawable.ic_workout_run to R.color.chart_workout_projected
                    WorkoutType.CYCLING ->
                        R.drawable.ic_workout_cycle to R.color.chart_workout_projected
                    WorkoutType.MINDFULNESS ->
                        R.drawable.ic_workout_mindfulness to R.color.chart_workout_mindfulness_projected
                }
                addWorkoutRow(dr, tint, line)
            }
        } else {
            addSpacer()
            val tv = TextView(this)
            tv.layoutParams = lp
            tv.setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyMedium)
            tv.setTextColor(ContextCompat.getColor(this, R.color.brand_text))
            tv.text = getString(R.string.summary_projection_future_only)
            container.addView(tv)
        }

        hcExerciseReadError?.let {
            addSpacer()
            val err = TextView(this)
            err.layoutParams = lp
            err.setTextAppearance(MaterialR.style.TextAppearance_Material3_BodySmall)
            err.setTextColor(ContextCompat.getColor(this, R.color.brand_text))
            err.text = getString(R.string.summary_exercise_read_failed, it) + "\n" +
                getString(R.string.summary_exercise_read_failed_hint)
            container.addView(err)
        }
    }

    private fun populateWeekSummary(
        avgLabel: String,
        minV: Long,
        maxV: Long,
        hcCardio: Int,
        hcMindfulness: Int,
        projectedCardio: Int,
        projectedMindfulness: Int,
        exerciseReadError: String?,
        latestEndText: String?
    ) {
        val container = binding.containerChartSummary
        container.removeAllViews()
        val inflater = layoutInflater
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        fun addSpacer() {
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (8 * resources.displayMetrics.density).toInt()
            )
            container.addView(v)
        }

        val stepsTv = TextView(this)
        stepsTv.layoutParams = lp
        stepsTv.setTextAppearance(MaterialR.style.TextAppearance_Material3_BodyLarge)
        stepsTv.setTextColor(ContextCompat.getColor(this, R.color.brand_text))
        stepsTv.text = getString(
            R.string.week_summary_steps_range,
            avgLabel,
            minV.formatThousands(),
            maxV.formatThousands()
        )
        container.addView(stepsTv)

        addSpacer()
        val counts = inflater.inflate(R.layout.item_summary_week_workout_counts, container, false)
        counts.findViewById<TextView>(R.id.textHcRun).text = hcCardio.toString()
        counts.findViewById<TextView>(R.id.textHcMind).text = hcMindfulness.toString()
        counts.findViewById<TextView>(R.id.textProjRun).text = projectedCardio.toString()
        counts.findViewById<TextView>(R.id.textProjMind).text = projectedMindfulness.toString()
        counts.findViewById<ImageView>(R.id.iconHcRun).apply {
            setImageResource(R.drawable.ic_workout_run)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.chart_workout))
        }
        counts.findViewById<ImageView>(R.id.iconProjRun).apply {
            setImageResource(R.drawable.ic_workout_run)
            imageTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.chart_workout_projected))
        }
        counts.findViewById<ImageView>(R.id.iconHcMind).imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.chart_workout_mindfulness))
        counts.findViewById<ImageView>(R.id.iconProjMind).imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.chart_workout_mindfulness_projected))
        container.addView(counts)

        exerciseReadError?.let {
            addSpacer()
            val err = TextView(this)
            err.layoutParams = lp
            err.setTextAppearance(MaterialR.style.TextAppearance_Material3_BodySmall)
            err.setTextColor(ContextCompat.getColor(this, R.color.brand_text))
            err.text = getString(R.string.summary_exercise_read_failed, it) + "\n" +
                getString(R.string.summary_exercise_read_failed_hint)
            container.addView(err)
        }
        latestEndText?.let {
            addSpacer()
            val foot = TextView(this)
            foot.layoutParams = lp
            foot.setTextAppearance(MaterialR.style.TextAppearance_Material3_BodySmall)
            foot.setTextColor(ContextCompat.getColor(this, R.color.brand_text))
            foot.alpha = 0.85f
            foot.text = it
            container.addView(foot)
        }
    }

    private fun sumStepsInWindow(
        slices: List<dev.sudominus.schrittji.simulation.MinuteStepSlice>,
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
        WorkoutType.MINDFULNESS -> getString(R.string.workout_title_mindfulness)
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
                "${workout.start.format(workoutTimeFormatter)}–${workout.end.format(workoutTimeFormatter)}"
            )
            appendLine("Duration: $duration min")
            if (workout.type != WorkoutType.MINDFULNESS) {
                appendLine(String.format(Locale.getDefault(), "%.1f km", workout.distanceMeters / 1000.0))
            }
            workout.notes?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
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
        updatesOk: Boolean
    ) {
        val dot = binding.dotStatusSummary
        val label = binding.textStatusSummary
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
            !updatesOk -> {
                dot.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.status_warn)
                )
                label.text = getString(R.string.status_summary_warning_updates)
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
        WorkoutType.MINDFULNESS -> TimelineWorkoutKind.MINDFULNESS
    }
}

private enum class ChartMode {
    DAY,
    WEEK
}
