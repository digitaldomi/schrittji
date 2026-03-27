package dev.sudominus.schrittji

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_AVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.RangeSlider
import com.google.android.material.snackbar.Snackbar
import dev.sudominus.schrittji.databinding.ActivitySettingsBinding
import dev.sudominus.schrittji.health.HealthConnectGateway
import dev.sudominus.schrittji.simulation.SimulationConfig
import dev.sudominus.schrittji.simulation.SimulationConfigStore
import dev.sudominus.schrittji.simulation.SimulationCoordinator
import dev.sudominus.schrittji.simulation.StepPublishingScheduler
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val simulationCoordinator by lazy {
        SimulationCoordinator(healthConnectGateway, SimulationConfigStore(applicationContext))
    }
    private var suppressSliderCallbacks = false

    private val permissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        showSnackbar("Health Connect permissions updated.")
        lifecycleScope.launch {
            updatePermissionButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.buttonGrantPermission.setOnClickListener {
            permissionsLauncher.launch(healthConnectGateway.requiredPermissions)
        }
        binding.buttonOpenHealthConnect.setOnClickListener { openHealthConnect() }
        binding.buttonOpenHealthBrowser.setOnClickListener {
            startActivity(Intent(this, HealthConnectStepsActivity::class.java))
        }
        binding.buttonSyncNow.setOnClickListener {
            lifecycleScope.launch {
                val config = buildConfigFromInputs() ?: return@launch
                try {
                    val saved = simulationCoordinator.saveConfig(config)
                    if (saved.automationEnabled) {
                        StepPublishingScheduler.schedule(this@SettingsActivity)
                    }
                    val result = simulationCoordinator.publishSinceLast(saved)
                    showSnackbar(result.summary)
                } catch (exception: Exception) {
                    showSnackbar(exception.message ?: "Could not write recent data.")
                }
            }
        }
        binding.buttonSaveSettings.setOnClickListener {
            val config = buildConfigFromInputs() ?: return@setOnClickListener
            simulationCoordinator.saveConfig(config)
            if (config.automationEnabled) {
                StepPublishingScheduler.schedule(this)
            } else {
                StepPublishingScheduler.cancel(this)
            }
            showSnackbar("Settings saved.")
            setResult(RESULT_OK)
            finish()
        }

        wireWorkoutSliders()
        populate(simulationCoordinator.loadConfig())
        lifecycleScope.launch {
            updatePermissionButton()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            updatePermissionButton()
        }
    }

    private fun wireWorkoutSliders() {
        val sliders = listOf(
            binding.sliderRunningSessions to { refreshWorkoutSummaries(isRunning = true) },
            binding.sliderRunningDuration to { refreshWorkoutSummaries(isRunning = true) },
            binding.sliderCyclingSessions to { refreshWorkoutSummaries(isRunning = false, isCycling = true) },
            binding.sliderCyclingDuration to { refreshWorkoutSummaries(isRunning = false, isCycling = true) },
            binding.sliderMindfulnessSessions to { refreshWorkoutSummaries(isMindfulness = true) },
            binding.sliderMindfulnessDuration to { refreshWorkoutSummaries(isMindfulness = true) }
        )
        sliders.forEach { (slider, action) ->
            slider.addOnChangeListener { _, _, _ ->
                if (!suppressSliderCallbacks) action()
            }
        }
    }

    private fun refreshWorkoutSummaries(
        isRunning: Boolean = false,
        isCycling: Boolean = false,
        isMindfulness: Boolean = false
    ) {
        when {
            isRunning -> {
                val (a, b) = binding.sliderRunningSessions.valuesAsPair()
                binding.textRunningSessionsSummary.text =
                    getString(R.string.range_sessions_summary, a, b)
                val (c, d) = binding.sliderRunningDuration.valuesAsPair()
                binding.textRunningDurationSummary.text =
                    getString(R.string.range_minutes_summary, c, d)
            }
            isCycling -> {
                val (a, b) = binding.sliderCyclingSessions.valuesAsPair()
                binding.textCyclingSessionsSummary.text =
                    getString(R.string.range_sessions_summary, a, b)
                val (c, d) = binding.sliderCyclingDuration.valuesAsPair()
                binding.textCyclingDurationSummary.text =
                    getString(R.string.range_minutes_summary, c, d)
            }
            isMindfulness -> {
                val (a, b) = binding.sliderMindfulnessSessions.valuesAsPair()
                binding.textMindfulnessSessionsSummary.text =
                    getString(R.string.range_sessions_summary, a, b)
                val (c, d) = binding.sliderMindfulnessDuration.valuesAsPair()
                binding.textMindfulnessDurationSummary.text =
                    getString(R.string.range_minutes_summary, c, d)
            }
        }
    }

    private suspend fun updatePermissionButton() {
        val allGranted = healthConnectGateway.availability() == SDK_AVAILABLE &&
            healthConnectGateway.hasAllRequestedPermissions()
        binding.buttonGrantPermission.text = if (allGranted) {
            getString(R.string.grant_health_permission_done)
        } else {
            getString(R.string.grant_health_permission)
        }
        val color = ContextCompat.getColor(
            this,
            if (allGranted) R.color.status_ok else R.color.status_warn
        )
        binding.buttonGrantPermission.setTextColor(color)
        binding.buttonGrantPermission.strokeColor = ColorStateList.valueOf(color)
    }

    private fun populate(config: SimulationConfig) {
        suppressSliderCallbacks = true
        try {
            binding.switchBackgroundService.isChecked = config.automationEnabled
            binding.switchDailySteps.isChecked = config.dailyStepsEnabled
            binding.switchRunningEnabled.isChecked = config.runningEnabled
            binding.switchCyclingEnabled.isChecked = config.cyclingEnabled
            binding.switchMindfulnessEnabled.isChecked = config.mindfulnessEnabled
            binding.editMinSteps.setText(config.minimumDailySteps.toString())
            binding.editMaxSteps.setText(config.maximumDailySteps.toString())

            binding.sliderRunningSessions.setValues(
                config.runningMinSessionsPerWeek.toFloat(),
                config.runningMaxSessionsPerWeek.toFloat()
            )
            binding.sliderRunningDuration.setValues(
                config.runningMinDurationMinutes.toFloat(),
                config.runningMaxDurationMinutes.toFloat()
            )
            binding.sliderCyclingSessions.setValues(
                config.cyclingMinSessionsPerWeek.toFloat(),
                config.cyclingMaxSessionsPerWeek.toFloat()
            )
            binding.sliderCyclingDuration.setValues(
                config.cyclingMinDurationMinutes.toFloat(),
                config.cyclingMaxDurationMinutes.toFloat()
            )
            binding.sliderMindfulnessSessions.setValues(
                config.mindfulnessMinSessionsPerWeek.toFloat(),
                config.mindfulnessMaxSessionsPerWeek.toFloat()
            )
            binding.sliderMindfulnessDuration.setValues(
                config.mindfulnessMinDurationMinutes.toFloat(),
                config.mindfulnessMaxDurationMinutes.toFloat()
            )

            refreshWorkoutSummaries(isRunning = true)
            refreshWorkoutSummaries(isCycling = true)
            refreshWorkoutSummaries(isMindfulness = true)
        } finally {
            suppressSliderCallbacks = false
        }
    }

    private fun buildConfigFromInputs(): SimulationConfig? {
        clearErrors()
        val minSteps = binding.editMinSteps.text?.toString()?.trim()?.toIntOrNull()
        val maxSteps = binding.editMaxSteps.text?.toString()?.trim()?.toIntOrNull()

        if (minSteps == null || minSteps !in 1_000..30_000) {
            binding.inputMinSteps.error = "1,000 to 30,000."
            return null
        }
        if (maxSteps == null || maxSteps !in 2_000..35_000 || maxSteps <= minSteps) {
            binding.inputMaxSteps.error = "Must be higher than minimum."
            return null
        }

        val runningMinSessions = binding.sliderRunningSessions.values[0].toInt()
        val runningMaxSessions = binding.sliderRunningSessions.values[1].toInt()
        val runningMinDuration = binding.sliderRunningDuration.values[0].toInt()
        val runningMaxDuration = binding.sliderRunningDuration.values[1].toInt()
        val cyclingMinSessions = binding.sliderCyclingSessions.values[0].toInt()
        val cyclingMaxSessions = binding.sliderCyclingSessions.values[1].toInt()
        val cyclingMinDuration = binding.sliderCyclingDuration.values[0].toInt()
        val cyclingMaxDuration = binding.sliderCyclingDuration.values[1].toInt()
        val mindfulnessMinSessions = binding.sliderMindfulnessSessions.values[0].toInt()
        val mindfulnessMaxSessions = binding.sliderMindfulnessSessions.values[1].toInt()
        val mindfulnessMinDuration = binding.sliderMindfulnessDuration.values[0].toInt()
        val mindfulnessMaxDuration = binding.sliderMindfulnessDuration.values[1].toInt()

        val current = simulationCoordinator.loadConfig()
        return current.copy(
            minimumDailySteps = minSteps,
            maximumDailySteps = maxSteps,
            dailyStepsEnabled = binding.switchDailySteps.isChecked,
            automationEnabled = binding.switchBackgroundService.isChecked,
            runningEnabled = binding.switchRunningEnabled.isChecked,
            cyclingEnabled = binding.switchCyclingEnabled.isChecked,
            mindfulnessEnabled = binding.switchMindfulnessEnabled.isChecked,
            runningMinSessionsPerWeek = runningMinSessions,
            runningMaxSessionsPerWeek = runningMaxSessions,
            runningMinDurationMinutes = runningMinDuration,
            runningMaxDurationMinutes = runningMaxDuration,
            cyclingMinSessionsPerWeek = cyclingMinSessions,
            cyclingMaxSessionsPerWeek = cyclingMaxSessions,
            cyclingMinDurationMinutes = cyclingMinDuration,
            cyclingMaxDurationMinutes = cyclingMaxDuration,
            mindfulnessMinSessionsPerWeek = mindfulnessMinSessions,
            mindfulnessMaxSessionsPerWeek = mindfulnessMaxSessions,
            mindfulnessMinDurationMinutes = mindfulnessMinDuration,
            mindfulnessMaxDurationMinutes = mindfulnessMaxDuration
        )
    }

    private fun clearErrors() {
        binding.inputMinSteps.error = null
        binding.inputMaxSteps.error = null
    }

    private fun openHealthConnect() {
        when (healthConnectGateway.availability()) {
            SDK_AVAILABLE -> launchIntent(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
            SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> launchIntent(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                )
            )

            else -> showSnackbar("Health Connect is not available on this device.")
        }
    }

    private fun launchIntent(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showSnackbar("Could not open Health Connect.")
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}

private fun RangeSlider.valuesAsPair(): Pair<Int, Int> {
    val v = values
    return v[0].toInt() to v[1].toInt()
}
