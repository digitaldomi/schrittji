package dev.digitaldomi.schrittji

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dev.digitaldomi.schrittji.databinding.ActivityHealthConnectStepsBinding
import dev.digitaldomi.schrittji.databinding.ViewHealthConnectStepsHeaderBinding
import dev.digitaldomi.schrittji.health.HealthConnectGateway
import dev.digitaldomi.schrittji.health.HealthConnectStepsSnapshot
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class HealthConnectStepsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityHealthConnectStepsBinding
    private lateinit var headerBinding: ViewHealthConnectStepsHeaderBinding

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val adapter by lazy { StepRecordAdapter(this) }
    private val formatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm")
    private val dayFormatter = DateTimeFormatter.ofPattern("EEE, MMM d")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHealthConnectStepsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        headerBinding = ViewHealthConnectStepsHeaderBinding.inflate(layoutInflater, binding.listSteps, false)
        binding.listSteps.addHeaderView(headerBinding.root, null, false)
        binding.listSteps.adapter = adapter

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        headerBinding.buttonRefresh.setOnClickListener {
            loadData()
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
            snapshot.daySummaries.joinToString(separator = "\n") { day ->
                "${day.date.format(dayFormatter)} - ${day.totalSteps.formatThousands()} steps in ${day.recordCount} records"
            }
        }

        adapter.submit(snapshot.records)
        binding.textEmptyState.isVisible = snapshot.records.isEmpty()
    }
}

private fun Long.formatThousands(): String = "%,d".format(this)
