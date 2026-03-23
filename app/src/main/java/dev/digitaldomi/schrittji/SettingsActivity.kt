package dev.digitaldomi.schrittji

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val simulationCoordinator by lazy {
        SimulationCoordinator(healthConnectGateway, SimulationConfigStore(applicationContext))
    }
    private val permissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        showSnackbar("Health Connect permissions updated.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
    }

    private fun populate(config: SimulationConfig) {
        binding.editMinSteps.setText(config.minimumDailySteps.toString())
        binding.editMaxSteps.setText(config.maximumDailySteps.toString())
        binding.switchAutomation.isChecked = config.automationEnabled
        binding.editRunningMinSessions.setText(config.runningMinSessionsPerWeek.toString())
        binding.editRunningMaxSessions.setText(config.runningMaxSessionsPerWeek.toString())
        binding.editRunningMinDuration.setText(config.runningMinDurationMinutes.toString())
        binding.editRunningMaxDuration.setText(config.runningMaxDurationMinutes.toString())
        binding.editCyclingMinSessions.setText(config.cyclingMinSessionsPerWeek.toString())
        binding.editCyclingMaxSessions.setText(config.cyclingMaxSessionsPerWeek.toString())
        binding.editCyclingMinDuration.setText(config.cyclingMinDurationMinutes.toString())
        binding.editCyclingMaxDuration.setText(config.cyclingMaxDurationMinutes.toString())
    }

    private fun buildConfigFromInputs(): SimulationConfig? {
        clearErrors()
        val minSteps = binding.editMinSteps.text?.toString()?.trim()?.toIntOrNull()
        val maxSteps = binding.editMaxSteps.text?.toString()?.trim()?.toIntOrNull()
        val runningMinSessions = binding.editRunningMinSessions.text?.toString()?.trim()?.toIntOrNull()
        val runningMaxSessions = binding.editRunningMaxSessions.text?.toString()?.trim()?.toIntOrNull()
        val runningMinDuration = binding.editRunningMinDuration.text?.toString()?.trim()?.toIntOrNull()
        val runningMaxDuration = binding.editRunningMaxDuration.text?.toString()?.trim()?.toIntOrNull()
        val cyclingMinSessions = binding.editCyclingMinSessions.text?.toString()?.trim()?.toIntOrNull()
        val cyclingMaxSessions = binding.editCyclingMaxSessions.text?.toString()?.trim()?.toIntOrNull()
        val cyclingMinDuration = binding.editCyclingMinDuration.text?.toString()?.trim()?.toIntOrNull()
        val cyclingMaxDuration = binding.editCyclingMaxDuration.text?.toString()?.trim()?.toIntOrNull()

        if (minSteps == null || minSteps !in 1_000..30_000) {
            binding.inputMinSteps.error = "1,000 to 30,000."
            return null
        }
        if (maxSteps == null || maxSteps !in 2_000..35_000 || maxSteps <= minSteps) {
            binding.inputMaxSteps.error = "Must be higher than minimum."
            return null
        }
        if (!validateSessionRange(binding.inputRunningMinSessions, binding.inputRunningMaxSessions, runningMinSessions, runningMaxSessions, 0, 7)) {
            return null
        }
        if (!validateSessionRange(binding.inputCyclingMinSessions, binding.inputCyclingMaxSessions, cyclingMinSessions, cyclingMaxSessions, 0, 7)) {
            return null
        }
        if (!validateDurationRange(binding.inputRunningMinDuration, binding.inputRunningMaxDuration, runningMinDuration, runningMaxDuration)) {
            return null
        }
        if (!validateDurationRange(binding.inputCyclingMinDuration, binding.inputCyclingMaxDuration, cyclingMinDuration, cyclingMaxDuration)) {
            return null
        }

        val current = simulationCoordinator.loadConfig()
        return current.copy(
            minimumDailySteps = minSteps,
            maximumDailySteps = maxSteps,
            automationEnabled = binding.switchAutomation.isChecked,
            runningMinSessionsPerWeek = runningMinSessions!!,
            runningMaxSessionsPerWeek = runningMaxSessions!!,
            runningMinDurationMinutes = runningMinDuration!!,
            runningMaxDurationMinutes = runningMaxDuration!!,
            cyclingMinSessionsPerWeek = cyclingMinSessions!!,
            cyclingMaxSessionsPerWeek = cyclingMaxSessions!!,
            cyclingMinDurationMinutes = cyclingMinDuration!!,
            cyclingMaxDurationMinutes = cyclingMaxDuration!!
        )
    }

    private fun validateSessionRange(
        minLayout: com.google.android.material.textfield.TextInputLayout,
        maxLayout: com.google.android.material.textfield.TextInputLayout,
        minValue: Int?,
        maxValue: Int?,
        lowerBound: Int,
        upperBound: Int
    ): Boolean {
        if (minValue == null || maxValue == null || minValue !in lowerBound..upperBound || maxValue !in lowerBound..upperBound || maxValue < minValue) {
            minLayout.error = "$lowerBound to $upperBound."
            maxLayout.error = "Must be >= min."
            return false
        }
        return true
    }

    private fun validateDurationRange(
        minLayout: com.google.android.material.textfield.TextInputLayout,
        maxLayout: com.google.android.material.textfield.TextInputLayout,
        minValue: Int?,
        maxValue: Int?
    ): Boolean {
        if (minValue == null || maxValue == null || minValue !in 10..240 || maxValue !in 10..240 || maxValue < minValue) {
            minLayout.error = "10 to 240."
            maxLayout.error = "Must be >= min."
            return false
        }
        return true
    }

    private fun clearErrors() {
        listOf(
            binding.inputMinSteps,
            binding.inputMaxSteps,
            binding.inputRunningMinSessions,
            binding.inputRunningMaxSessions,
            binding.inputRunningMinDuration,
            binding.inputRunningMaxDuration,
            binding.inputCyclingMinSessions,
            binding.inputCyclingMaxSessions,
            binding.inputCyclingMinDuration,
            binding.inputCyclingMaxDuration
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
