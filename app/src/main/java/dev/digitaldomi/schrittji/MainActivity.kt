package dev.digitaldomi.schrittji

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_AVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dev.digitaldomi.schrittji.databinding.ActivityMainBinding
import dev.digitaldomi.schrittji.health.HealthConnectGateway
import dev.digitaldomi.schrittji.simulation.SimulationConfig
import dev.digitaldomi.schrittji.simulation.SimulationConfigStore
import dev.digitaldomi.schrittji.simulation.SimulationCoordinator
import dev.digitaldomi.schrittji.simulation.SimulationProfile
import dev.digitaldomi.schrittji.simulation.StepPublishingScheduler
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val healthConnectGateway by lazy { HealthConnectGateway(applicationContext) }
    private val configStore by lazy { SimulationConfigStore(applicationContext) }
    private val simulationCoordinator by lazy {
        SimulationCoordinator(healthConnectGateway, configStore)
    }
    private val formatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm")
    private val permissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        lifecycleScope.launch {
            refreshUiState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupProfileSpinner()
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

    private fun setupProfileSpinner() {
        val labels = SimulationProfile.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerProfile.adapter = adapter
    }

    private fun setupListeners() {
        binding.buttonGrantPermission.setOnClickListener {
            permissionsLauncher.launch(healthConnectGateway.requiredPermissions)
        }

        binding.buttonOpenHealthConnect.setOnClickListener {
            openHealthConnect()
        }

        binding.buttonViewHealthConnectSteps.setOnClickListener {
            launchAction {
                if (!ensureReadyForHealthConnectData()) {
                    return@launchAction
                }
                startActivity(Intent(this@MainActivity, HealthConnectStepsActivity::class.java))
            }
        }

        binding.buttonSaveSettings.setOnClickListener {
            launchAction {
                val config = buildConfigFromInputs() ?: return@launchAction
                val saved = simulationCoordinator.saveConfig(config)
                syncAutomation(saved)
                showSnackbar(
                    if (saved.automationEnabled) {
                        "Settings saved and continuous publishing is scheduled."
                    } else {
                        "Settings saved."
                    }
                )
            }
        }

        binding.buttonBackfill.setOnClickListener {
            launchAction {
                val config = buildConfigFromInputs() ?: return@launchAction
                if (!ensureReadyForHealthConnectData()) {
                    return@launchAction
                }
                val saved = simulationCoordinator.saveConfig(config)
                syncAutomation(saved)
                val result = simulationCoordinator.publishBackfill(saved)
                showSnackbar(result.summary)
            }
        }

        binding.buttonPublishNow.setOnClickListener {
            launchAction {
                val config = buildConfigFromInputs() ?: return@launchAction
                if (!ensureReadyForHealthConnectData()) {
                    return@launchAction
                }
                val saved = simulationCoordinator.saveConfig(config)
                syncAutomation(saved)
                val result = simulationCoordinator.publishSinceLast(saved)
                showSnackbar(result.summary)
            }
        }

        binding.buttonStopAutomation.setOnClickListener {
            launchAction {
                val updated = simulationCoordinator.disableAutomation()
                StepPublishingScheduler.cancel(this@MainActivity)
                binding.switchAutomation.isChecked = false
                showSnackbar("Continuous publishing disabled.")
                populateInputs(updated)
            }
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        setBusy(true)
        lifecycleScope.launch {
            try {
                block()
            } catch (exception: Exception) {
                showSnackbar(exception.message ?: "Operation failed.")
            } finally {
                setBusy(false)
                refreshUiState()
            }
        }
    }

    private suspend fun refreshUiState() {
        val config = simulationCoordinator.loadConfig()
        populateInputs(config)

        val availability = healthConnectGateway.availability()
        val permissionGranted = if (availability == SDK_AVAILABLE) {
            healthConnectGateway.hasRequiredPermissions()
        } else {
            false
        }

        binding.textHealthStatus.text = when (availability) {
            SDK_AVAILABLE -> "Health Connect is available on this device."
            SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect needs to be installed or updated before Schrittji can publish steps."
            SDK_UNAVAILABLE -> "Health Connect is not supported on this Android version."
            else -> "Health Connect status is unknown."
        }

        binding.textPermissionStatus.text = if (permissionGranted) {
            "Step read/write permission is granted. Schrittji can write generated records and read Health Connect data for verification."
        } else {
            "Step read/write permission is not granted yet. Grant access before backfilling, publishing, or opening the Health Connect data view."
        }

        binding.textAutomationStatus.text = buildString {
            if (config.automationEnabled) {
                appendLine("Enabled: Schrittji schedules an Android WorkManager periodic job.")
                appendLine("Efficiency: Android batches that work with other background tasks, so it is much lighter than an always-on foreground loop.")
                appendLine("Cadence: the app asks for roughly 15-minute top-ups, but Android may shift the exact run time for battery efficiency.")
                append("Behavior: each background run generates the missing step minutes since the last successful publish and writes them to Health Connect.")
            } else {
                appendLine("Disabled: no automatic background step injection is scheduled right now.")
                appendLine("If you enable it, Schrittji uses Android WorkManager rather than an always-running service.")
                append("When the scheduler runs, it fills the missing time window and writes those steps to Health Connect.")
            }
            appendLine()
            appendLine()
            append(
                config.lastPublishedEpochMilli?.let {
                    "Last successful publish upper bound: ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(formatter)}"
                } ?: "Last successful publish upper bound: none yet."
            )
        }

        binding.textLastPublish.text = config.lastSummary
        binding.textGeneratedData.text = config.lastGeneratedDetails
        binding.buttonGrantPermission.isEnabled = availability == SDK_AVAILABLE && !permissionGranted
        binding.buttonViewHealthConnectSteps.isEnabled = availability == SDK_AVAILABLE && permissionGranted
    }

    private fun populateInputs(config: SimulationConfig) {
        binding.spinnerProfile.setSelection(
            SimulationProfile.entries.indexOf(config.profile).coerceAtLeast(0),
            false
        )
        binding.editMinSteps.setText(config.minimumDailySteps.toString())
        binding.editMaxSteps.setText(config.maximumDailySteps.toString())
        binding.editBackfillDays.setText(config.backfillDays.toString())
        binding.switchAutomation.isChecked = config.automationEnabled
    }

    private fun buildConfigFromInputs(): SimulationConfig? {
        clearValidationErrors()

        val minimumSteps = binding.editMinSteps.text?.toString()?.trim()?.toIntOrNull()
        val maximumSteps = binding.editMaxSteps.text?.toString()?.trim()?.toIntOrNull()
        val backfillDays = binding.editBackfillDays.text?.toString()?.trim()?.toIntOrNull()

        if (minimumSteps == null || minimumSteps !in 1_000..40_000) {
            binding.inputMinSteps.error = "Choose a value between 1,000 and 40,000."
            return null
        }

        if (maximumSteps == null || maximumSteps !in 1_500..50_000) {
            binding.inputMaxSteps.error = "Choose a value between 1,500 and 50,000."
            return null
        }

        if (minimumSteps >= maximumSteps) {
            binding.inputMaxSteps.error = "Maximum daily steps must be higher than the minimum."
            return null
        }

        if (backfillDays == null || backfillDays !in 1..60) {
            binding.inputBackfillDays.error = "Choose a history window between 1 and 60 days."
            return null
        }

        val existing = simulationCoordinator.loadConfig()
        return existing.copy(
            profile = SimulationProfile.entries[binding.spinnerProfile.selectedItemPosition],
            minimumDailySteps = minimumSteps,
            maximumDailySteps = maximumSteps,
            backfillDays = backfillDays,
            automationEnabled = binding.switchAutomation.isChecked
        )
    }

    private fun clearValidationErrors() {
        binding.inputMinSteps.error = null
        binding.inputMaxSteps.error = null
        binding.inputBackfillDays.error = null
    }

    private suspend fun ensureReadyForHealthConnectData(): Boolean {
        val availability = healthConnectGateway.availability()
        if (availability != SDK_AVAILABLE) {
            showSnackbar("Health Connect is not ready yet. Install or update it first.")
            return false
        }
        if (!healthConnectGateway.hasRequiredPermissions()) {
            showSnackbar("Grant Schrittji step read/write permission in Health Connect first.")
            return false
        }
        return true
    }

    private fun syncAutomation(config: SimulationConfig) {
        if (config.automationEnabled) {
            StepPublishingScheduler.schedule(this)
        } else {
            StepPublishingScheduler.cancel(this)
        }
    }

    private fun openHealthConnect() {
        val availability = healthConnectGateway.availability()
        when (availability) {
            SDK_AVAILABLE -> {
                val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                launchIntent(intent)
            }

            SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                val marketIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                )
                if (!launchIntent(marketIntent)) {
                    launchIntent(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                        )
                    )
                }
            }

            else -> {
                showSnackbar("Health Connect is not supported on this device.")
            }
        }
    }

    private fun launchIntent(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun setBusy(isBusy: Boolean) {
        binding.buttonGrantPermission.isEnabled = !isBusy
        binding.buttonOpenHealthConnect.isEnabled = !isBusy
        binding.buttonViewHealthConnectSteps.isEnabled = !isBusy
        binding.buttonSaveSettings.isEnabled = !isBusy
        binding.buttonBackfill.isEnabled = !isBusy
        binding.buttonPublishNow.isEnabled = !isBusy
        binding.buttonStopAutomation.isEnabled = !isBusy
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
