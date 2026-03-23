package dev.digitaldomi.schrittji

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import dev.digitaldomi.schrittji.chart.BarChartPoint
import dev.digitaldomi.schrittji.chart.DetailComparisonBar
import dev.digitaldomi.schrittji.databinding.ActivityHealthConnectStepsBinding
import dev.digitaldomi.schrittji.databinding.ViewHealthConnectStepsHeaderBinding
import dev.digitaldomi.schrittji.health.HealthConnectGateway
import dev.digitaldomi.schrittji.health.HealthConnectStepDaySummary
import dev.digitaldomi.schrittji.health.HealthConnectStepsSnapshot
import dev.digitaldomi.schrittji.simulation.DayActivityLabels
import dev.digitaldomi.schrittji.simulation.SimulationConfigStore
import dev.digitaldomi.schrittji.simulation.SimulationCoordinator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.launch

class HealthConnectStepsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHealthConnectStepsBinding
    private lateinit var headerBinding: ViewHealthConnectStepsHeaderBinding

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val simulationCoordinator by lazy {
        SimulationCoordinator(healthConnectGateway, SimulationConfigStore(applicationContext))
    }
    private val adapter by lazy { StepRecordAdapter(this) }
    private val formatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm")
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val weekFormatter = DateTimeFormatter.ofPattern("MMM d")

    private var lastSnapshot: HealthConnectStepsSnapshot? = null
    private var detailSelectedDate: LocalDate = LocalDate.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHealthConnectStepsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        headerBinding = ViewHealthConnectStepsHeaderBinding.inflate(layoutInflater, binding.listSteps, false)
        binding.listSteps.addHeaderView(headerBinding.root, null, false)
        binding.listSteps.adapter = adapter

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupChartTabs()
        setupDetailPanel()

        headerBinding.buttonRefresh.setOnClickListener {
            loadData()
        }
        headerBinding.buttonOpenDayDetail.setOnClickListener {
            startActivity(
                DayDetailActivity.newIntent(
                    this,
                    DayDetailSource.HEALTH_CONNECT,
                    detailSelectedDate
                )
            )
        }

        loadData()
    }

    private fun setupChartTabs() {
        val tabLayout = headerBinding.tabsChartPanels
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.chart_tab_detail)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.chart_tab_daily)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.chart_tab_weekly)))
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.chart_tab_monthly)))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                selectChartPanel(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
            }
        })
        selectChartPanel(0)
    }

    private fun selectChartPanel(position: Int) {
        headerBinding.panelDetail.isVisible = position == 0
        headerBinding.panelDaily.isVisible = position == 1
        headerBinding.panelWeekly.isVisible = position == 2
        headerBinding.panelMonthly.isVisible = position == 3
    }

    private fun setupDetailPanel() {
        headerBinding.buttonDetailPreviousDay.setOnClickListener {
            detailSelectedDate = detailSelectedDate.minusDays(1)
            lastSnapshot?.let { renderDetailPanel(it) }
        }
        headerBinding.buttonDetailNextDay.setOnClickListener {
            detailSelectedDate = detailSelectedDate.plusDays(1)
            lastSnapshot?.let { renderDetailPanel(it) }
        }
        headerBinding.buttonDetailToday.setOnClickListener {
            detailSelectedDate = LocalDate.now()
            lastSnapshot?.let { renderDetailPanel(it) }
        }
        headerBinding.chartDetailComparison.onBarClickListener = { bar ->
            lastSnapshot?.let { showDetailBarDialog(bar, it) }
        }
    }

    private fun showDetailBarDialog(bar: DetailComparisonBar, snapshot: HealthConnectStepsSnapshot) {
        val config = simulationCoordinator.loadConfig()
        val labels = simulationCoordinator.dayActivityLabels(detailSelectedDate, config)
        val byDate = snapshot.daySummaries.associateBy { it.date }
        val existingSteps = byDate[detailSelectedDate]?.totalSteps ?: 0L
        val projected = simulationCoordinator.projectDayDetail(config, detailSelectedDate)
        val activityText = buildActivityLines(labels)

        val (titleRes, message) = when (bar) {
            DetailComparisonBar.EXISTING -> {
                R.string.detail_dialog_existing_title to buildString {
                    appendLine(getString(R.string.detail_dialog_steps_line, existingSteps.formatThousands()))
                    appendLine()
                    appendLine(activityText)
                    appendLine()
                    append(getString(R.string.detail_dialog_hc_note))
                }
            }
            DetailComparisonBar.PROJECTED -> {
                R.string.detail_dialog_projected_title to buildString {
                    appendLine(getString(R.string.detail_dialog_steps_line, projected.totalSteps.formatThousands()))
                    appendLine()
                    appendLine(activityText)
                    appendLine()
                    append(getString(R.string.detail_dialog_projection_note))
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(titleRes))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun buildActivityLines(labels: DayActivityLabels): String {
        return buildString {
            if (labels.hasEveningRun) {
                appendLine(getString(R.string.detail_tooltip_run))
            }
            if (labels.hasCyclingStyleOuting) {
                appendLine(getString(R.string.detail_tooltip_cycle))
            }
            if (!labels.hasEveningRun && !labels.hasCyclingStyleOuting) {
                appendLine(getString(R.string.detail_tooltip_mixed_walk))
            }
        }.trim()
    }

    private fun loadData() {
        binding.progressBar.isVisible = true
        binding.textEmptyState.isVisible = false
        headerBinding.buttonRefresh.isEnabled = false

        lifecycleScope.launch {
            try {
                val snapshot = healthConnectGateway.readAllStepsSnapshot()
                lastSnapshot = snapshot
                renderSnapshot(snapshot)
            } catch (exception: Exception) {
                lastSnapshot = null
                Snackbar.make(
                    binding.root,
                    exception.message ?: "Failed to read Health Connect step data.",
                    Snackbar.LENGTH_LONG
                ).show()
                adapter.submit(emptyList())
                binding.textEmptyState.isVisible = true
            } finally {
                headerBinding.buttonRefresh.isEnabled = true
                binding.progressBar.isVisible = false
            }
        }
    }

    private fun renderSnapshot(snapshot: HealthConnectStepsSnapshot) {
        headerBinding.textSummary.text = buildString {
            appendLine("Total visible step records: ${snapshot.totalRecords}")
            appendLine("Total visible steps: ${snapshot.totalSteps.formatThousands()}")
            appendLine("Schrittji records: ${snapshot.ownRecords}")
            appendLine("Schrittji steps: ${snapshot.ownSteps.formatThousands()}")
            appendLine()
            append(
                snapshot.earliestStart?.let {
                    "Earliest start: ${it.format(formatter)}"
                } ?: "Earliest start: none"
            )
            appendLine()
            append(
                snapshot.latestEnd?.let {
                    "Latest end: ${it.format(formatter)}"
                } ?: "Latest end: none"
            )
        }

        headerBinding.textDailySummary.text = if (snapshot.daySummaries.isEmpty()) {
            "No day summaries are available because Health Connect returned no visible step records."
        } else {
            buildRecentDailyPoints(snapshot.daySummaries).joinToString(separator = "\n") { day ->
                "${day.date.format(dayFormatter)} - ${day.totalSteps.formatThousands()} steps in ${day.recordCount} records"
            }
        }
        val weeklyPoints = buildWeeklySummaries(snapshot.daySummaries)
        headerBinding.textWeeklySummary.text = if (weeklyPoints.isEmpty()) {
            "No weekly summaries are available because Health Connect returned no visible step records."
        } else {
            weeklyPoints.joinToString(separator = "\n") { week ->
                "Week of ${week.start.format(weekFormatter)} - ${week.totalSteps.formatThousands()} steps"
            }
        }

        val monthlyPoints = buildMonthlySummaries(snapshot.daySummaries)
        headerBinding.textMonthlySummary.text = if (monthlyPoints.all { it.second == 0L }) {
            "No monthly totals are available because Health Connect returned no visible step records."
        } else {
            monthlyPoints.joinToString(separator = "\n") { (ym, total) ->
                "${ym.format(DateTimeFormatter.ofPattern("MMMM yyyy"))} - ${total.formatThousands()} steps"
            }
        }

        headerBinding.chartDaily.submitPoints(
            buildRecentDailyPoints(snapshot.daySummaries).map { day ->
                BarChartPoint(
                    label = day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3),
                    value = day.totalSteps.toFloat(),
                    emphasized = day.date.dayOfWeek == DayOfWeek.SATURDAY || day.date.dayOfWeek == DayOfWeek.SUNDAY
                )
            }
        )
        headerBinding.chartWeekly.submitPoints(
            weeklyPoints.map { week ->
                BarChartPoint(
                    label = week.start.format(DateTimeFormatter.ofPattern("MM/dd")),
                    value = week.totalSteps.toFloat(),
                    emphasized = false
                )
            }
        )
        headerBinding.chartMonthly.submitPoints(
            monthlyPoints.map { (ym, total) ->
                BarChartPoint(
                    label = ym.format(DateTimeFormatter.ofPattern("MMM yy")),
                    value = total.toFloat(),
                    emphasized = false
                )
            }
        )

        renderDetailPanel(snapshot)

        adapter.submit(snapshot.records)
        binding.textEmptyState.isVisible = snapshot.records.isEmpty()
    }

    private fun renderDetailPanel(snapshot: HealthConnectStepsSnapshot) {
        val config = simulationCoordinator.loadConfig()
        val labels = simulationCoordinator.dayActivityLabels(detailSelectedDate, config)
        val byDate = snapshot.daySummaries.associateBy { it.date }
        val existing = (byDate[detailSelectedDate]?.totalSteps ?: 0L).toFloat()
        val projected = simulationCoordinator.projectDayDetail(config, detailSelectedDate).totalSteps.toFloat()

        headerBinding.textDetailSelectedDate.text = detailSelectedDate.format(dayFormatter)
        headerBinding.buttonDetailToday.isEnabled = detailSelectedDate != LocalDate.now()

        headerBinding.chartDetailComparison.submitData(
            existingSteps = existing,
            projectedSteps = projected,
            showRunIcon = labels.hasEveningRun,
            showCycleIcon = labels.hasCyclingStyleOuting
        )
    }

    private fun buildRecentDailyPoints(daySummaries: List<HealthConnectStepDaySummary>): List<HealthConnectStepDaySummary> {
        val summaryByDate = daySummaries.associateBy { it.date }
        val today = LocalDate.now()
        return (0..6).map { offset ->
            val date = today.minusDays((6 - offset).toLong())
            summaryByDate[date] ?: HealthConnectStepDaySummary(date, 0, 0)
        }
    }

    private fun buildWeeklySummaries(daySummaries: List<HealthConnectStepDaySummary>): List<WeeklyStepSummary> {
        val summaryByDate = daySummaries.associateBy { it.date }
        val currentWeekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0..7).map { offset ->
            val weekStart = currentWeekStart.minusWeeks((7 - offset).toLong())
            val days = (0..6).map { weekStart.plusDays(it.toLong()) }
            WeeklyStepSummary(
                start = weekStart,
                totalSteps = days.sumOf { summaryByDate[it]?.totalSteps ?: 0L }
            )
        }
    }

    private fun buildMonthlySummaries(
        daySummaries: List<HealthConnectStepDaySummary>
    ): List<Pair<YearMonth, Long>> {
        val summaryByDate = daySummaries.associateBy { it.date }
        val today = LocalDate.now()
        return (0..5).map { offset ->
            val ym = YearMonth.from(today.minusMonths(offset.toLong()))
            val first = ym.atDay(1)
            val last = ym.atEndOfMonth()
            var total = 0L
            var d = first
            while (!d.isAfter(last)) {
                total += summaryByDate[d]?.totalSteps ?: 0L
                d = d.plusDays(1)
            }
            ym to total
        }.reversed()
    }
}

private data class WeeklyStepSummary(
    val start: LocalDate,
    val totalSteps: Long
)

private fun Long.formatThousands(): String = "%,d".format(this)
