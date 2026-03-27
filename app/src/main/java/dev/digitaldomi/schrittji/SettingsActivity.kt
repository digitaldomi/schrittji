package dev.digitaldomi.schrittji

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
import com.google.android.material.snackbar.Snackbar
import dev.digitaldomi.schrittji.databinding.ActivitySettingsBinding
import dev.digitaldomi.schrittji.health.HealthConnectGateway
import dev.digitaldomi.schrittji.simulation.SimulationConfig
import dev.digitaldomi.schrittji.simulation.SimulationConfigStore
import dev.digitaldomi.schrittji.simulation.SimulationCoordinator
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var suppressSliderCallbacks = false

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val simulationCoordinator by lazy {
        SimulationCoordinator(healthConnectGateway, SimulationConfigStore(applicationContext))
    }
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
        binding.toolbar.title = ""

        setupActivitySliders()

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
            showSnackbar("Settings saved.")
            setResult(RESULT_OK)
            finish()
        }

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

    private fun setupActivitySliders() {
        val onChange = com.google.android.material.slider.RangeSlider.OnChangeListener { _, _, _ ->
            if (!suppressSliderCallbacks) refreshActivitySliderLabels()
        }
        binding.sliderRunningSessions.addOnChangeListener(onChange)
        binding.sliderCyclingSessions.addOnChangeListener(onChange)
        binding.sliderRunningDuration.addOnChangeListener(onChange)
        binding.sliderCyclingDuration.addOnChangeListener(onChange)
    }

    private fun refreshActivitySliderLabels() {
        val rs = binding.sliderRunningSessions.values.map { it.toInt() }.sorted()
        val rd = binding.sliderRunningDuration.values.map { it.toInt() }.sorted()
        val cs = binding.sliderCyclingSessions.values.map { it.toInt() }.sorted()
        val cd = binding.sliderCyclingDuration.values.map { it.toInt() }.sorted()
        binding.textRunningSessionsValue.text = getString(
            R.string.range_value_sessions,
            rs[0],
            rs[1]
        )
        binding.textRunningDurationValue.text = getString(
            R.string.range_value_minutes,
            rd[0],
            rd[1]
        )
        binding.textCyclingSessionsValue.text = getString(
            R.string.range_value_sessions,
            cs[0],
            cs[1]
        )
        binding.textCyclingDurationValue.text = getString(
            R.string.range_value_minutes,
            cd[0],
            cd[1]
        )
    }

    private fun populate(config: SimulationConfig) {
        suppressSliderCallbacks = true
        binding.switchBackgroundService.isChecked = config.automationEnabled
        binding.switchDailySteps.isChecked = config.dailyStepsEnabled
        binding.switchRunningEnabled.isChecked = config.runningEnabled
        binding.switchCyclingEnabled.isChecked = config.cyclingEnabled
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
        suppressSliderCallbacks = false
        refreshActivitySliderLabels()
    }

    private fun buildConfigFromInputs(): SimulationConfig? {
        clearErrors()
        val minSteps = binding.editMinSteps.text?.toString()?.trim()?.toIntOrNull()
        val maxSteps = binding.editMaxSteps.text?.toString()?.trim()?.toIntOrNull()
        val runningSessions = binding.sliderRunningSessions.values.map { it.toInt() }.sorted()
        val runningDurations = binding.sliderRunningDuration.values.map { it.toInt() }.sorted()
        val cyclingSessions = binding.sliderCyclingSessions.values.map { it.toInt() }.sorted()
        val cyclingDurations = binding.sliderCyclingDuration.values.map { it.toInt() }.sorted()
        val runningMinSessions = runningSessions[0]
        val runningMaxSessions = runningSessions[1]
        val runningMinDuration = runningDurations[0]
        val runningMaxDuration = runningDurations[1]
        val cyclingMinSessions = cyclingSessions[0]
        val cyclingMaxSessions = cyclingSessions[1]
        val cyclingMinDuration = cyclingDurations[0]
        val cyclingMaxDuration = cyclingDurations[1]

        if (minSteps == null || minSteps !in 1_000..30_000) {
            binding.inputMinSteps.error = "1,000 to 30,000."
            return null
        }
        if (maxSteps == null || maxSteps !in 2_000..35_000 || maxSteps <= minSteps) {
            binding.inputMaxSteps.error = "Must be higher than minimum."
            return null
        }

        val current = simulationCoordinator.loadConfig()
        return current.copy(
            minimumDailySteps = minSteps,
            maximumDailySteps = maxSteps,
            dailyStepsEnabled = binding.switchDailySteps.isChecked,
            automationEnabled = binding.switchBackgroundService.isChecked,
            runningEnabled = binding.switchRunningEnabled.isChecked,
            cyclingEnabled = binding.switchCyclingEnabled.isChecked,
            runningMinSessionsPerWeek = runningMinSessions,
            runningMaxSessionsPerWeek = runningMaxSessions,
            runningMinDurationMinutes = runningMinDuration,
            runningMaxDurationMinutes = runningMaxDuration,
            cyclingMinSessionsPerWeek = cyclingMinSessions,
            cyclingMaxSessionsPerWeek = cyclingMaxSessions,
            cyclingMinDurationMinutes = cyclingMinDuration,
            cyclingMaxDurationMinutes = cyclingMaxDuration
        )
    }

    private fun clearErrors() {
        listOf(
            binding.inputMinSteps,
            binding.inputMaxSteps
        ).forEach { it.error = null }
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
