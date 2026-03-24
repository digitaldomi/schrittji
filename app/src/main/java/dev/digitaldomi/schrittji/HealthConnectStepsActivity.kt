package dev.digitaldomi.schrittji

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dev.digitaldomi.schrittji.chart.BarChartPoint
import dev.digitaldomi.schrittji.databinding.ActivityHealthConnectStepsBinding
import dev.digitaldomi.schrittji.databinding.ViewHealthConnectStepsHeaderBinding
import dev.digitaldomi.schrittji.health.HealthConnectGateway
import dev.digitaldomi.schrittji.health.HealthConnectStepDaySummary
import dev.digitaldomi.schrittji.health.HealthConnectStepsSnapshot
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.launch

class HealthConnectStepsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHealthConnectStepsBinding
    private lateinit var headerBinding: ViewHealthConnectStepsHeaderBinding

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val adapter by lazy { StepRecordAdapter(this) }
    private val formatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm")
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val weekFormatter = DateTimeFormatter.ofPattern("MMM d")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityHealthConnectStepsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        headerBinding = ViewHealthConnectStepsHeaderBinding.inflate(layoutInflater, binding.listSteps, false)
        binding.listSteps.addHeaderView(headerBinding.root, null, false)
        binding.listSteps.adapter = adapter

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        headerBinding.buttonRefresh.setOnClickListener {
            loadData()
        }
        headerBinding.buttonOpenDayDetail.setOnClickListener {
            startActivity(
                DayDetailActivity.newIntent(
                    this,
                    DayDetailSource.HEALTH_CONNECT,
                    LocalDate.now()
                )
            )
        }

        loadData()
    }

    private fun loadData() {
        binding.progressBar.isVisible = true
        binding.textEmptyState.isVisible = false
        headerBinding.buttonRefresh.isEnabled = false

        lifecycleScope.launch {
            try {
                val snapshot = healthConnectGateway.readAllStepsSnapshot()
                renderSnapshot(snapshot)
            } catch (exception: Exception) {
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

        adapter.submit(snapshot.records)
        binding.textEmptyState.isVisible = snapshot.records.isEmpty()
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
}

private data class WeeklyStepSummary(
    val start: LocalDate,
    val totalSteps: Long
)

private fun Long.formatThousands(): String = "%,d".format(this)
