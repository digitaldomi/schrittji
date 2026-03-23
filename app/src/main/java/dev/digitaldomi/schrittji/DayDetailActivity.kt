package dev.digitaldomi.schrittji

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dev.digitaldomi.schrittji.chart.TimelineSeries
import dev.digitaldomi.schrittji.chart.TimelineBarEntry
import dev.digitaldomi.schrittji.databinding.ActivityDayDetailBinding
import dev.digitaldomi.schrittji.health.HealthConnectGateway
import dev.digitaldomi.schrittji.health.HealthConnectStepRecordEntry
import dev.digitaldomi.schrittji.simulation.MinuteStepSlice
import dev.digitaldomi.schrittji.simulation.SimulationConfigStore
import dev.digitaldomi.schrittji.simulation.SimulationCoordinator
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        binding = ActivityDayDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                    DayDetailSource.HEALTH_CONNECT -> renderHealthConnectDay(
                        healthConnectGateway.readStepEntriesForDate(selectedDate)
                    )

                    DayDetailSource.PROJECTION -> renderProjectionDay(
                        simulationCoordinator.projectDayDetail(
                            simulationCoordinator.loadConfig(),
                            selectedDate
                        )
                    )
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

    private fun renderHealthConnectDay(entries: List<HealthConnectStepRecordEntry>) {
        val totalSteps = entries.sumOf { it.count }
        binding.textSummary.text = buildString {
            appendLine("Source: Health Connect")
            appendLine("Visible records: ${entries.size}")
            append("Total steps: ${totalSteps.formatThousands()}")
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
            }
        )
    }

    private fun renderProjectionDay(detail: dev.digitaldomi.schrittji.simulation.ProjectedStepDayDetail) {
        val slices = detail.slices
        val totalSteps = slices.sumOf { it.count }
        binding.textSummary.text = buildString {
            appendLine("Source: Schrittji projection")
            appendLine("Generated minute records: ${slices.size}")
            append("Projected total steps: ${totalSteps.formatThousands()}")
        }
        binding.textEntries.text = if (slices.isEmpty()) {
            getString(R.string.day_detail_empty)
        } else {
            slices.joinToString(separator = "\n") { slice ->
                "${slice.start.withZoneSameInstant(zoneId).format(timeFormatter)}-${slice.end.withZoneSameInstant(zoneId).format(timeFormatter)}  ${slice.count.formatThousands()} steps"
            }
        }
        binding.chartDayTimeline.submitEntries(
            slices.map { slice ->
                TimelineBarEntry(
                    startMinute = slice.start.hour * 60 + slice.start.minute,
                    endMinute = slice.end.hour * 60 + slice.end.minute,
                    value = slice.count.toFloat(),
                    series = TimelineSeries.PROJECTED,
                    emphasized = false
                )
            } + detail.workouts.map { workout ->
                TimelineBarEntry(
                    startMinute = workout.start.hour * 60 + workout.start.minute,
                    endMinute = workout.end.hour * 60 + workout.end.minute,
                    value = 1f,
                    series = TimelineSeries.WORKOUT,
                    emphasized = false
                )
            }
        )
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
