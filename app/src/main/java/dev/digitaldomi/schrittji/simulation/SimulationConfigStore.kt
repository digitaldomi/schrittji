package dev.digitaldomi.schrittji.simulation

import android.content.Context
import java.security.SecureRandom
import java.time.Instant

data class SimulationConfig(
    val minimumDailySteps: Int = 7_500,
    val maximumDailySteps: Int = 22_000,
    val backfillDays: Int = 14,
    val automationEnabled: Boolean = false,
    val randomSeed: Long = 0L,
    val lastPublishedEpochMilli: Long? = null,
    val lastSummary: String = "No Health Connect data has been written yet.",
    val lastGeneratedDetails: String = "No generated Health Connect step batches yet."
)

class SimulationConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    fun load(): SimulationConfig {
        val rawLastPublished = preferences.getLong(KEY_LAST_PUBLISHED, Long.MIN_VALUE)
        return SimulationConfig(
            minimumDailySteps = preferences.getInt(KEY_MINIMUM_DAILY_STEPS, 7_500),
            maximumDailySteps = preferences.getInt(KEY_MAXIMUM_DAILY_STEPS, 22_000),
            backfillDays = preferences.getInt(KEY_BACKFILL_DAYS, 14),
            automationEnabled = preferences.getBoolean(KEY_AUTOMATION_ENABLED, false),
            randomSeed = preferences.getLong(KEY_RANDOM_SEED, 0L),
            lastPublishedEpochMilli = rawLastPublished.takeUnless { it == Long.MIN_VALUE },
            lastSummary = preferences.getString(
                KEY_LAST_SUMMARY,
                "No Health Connect data has been written yet."
            ) ?: "No Health Connect data has been written yet.",
            lastGeneratedDetails = preferences.getString(
                KEY_LAST_GENERATED_DETAILS,
                "No generated Health Connect step batches yet."
            ) ?: "No generated Health Connect step batches yet."
        )
    }

    fun save(config: SimulationConfig): SimulationConfig {
        val stableSeed = config.randomSeed.takeUnless { it == 0L } ?: secureRandom.nextLong()
        preferences.edit()
            .putInt(KEY_MINIMUM_DAILY_STEPS, config.minimumDailySteps)
            .putInt(KEY_MAXIMUM_DAILY_STEPS, config.maximumDailySteps)
            .putInt(KEY_BACKFILL_DAYS, config.backfillDays)
            .putBoolean(KEY_AUTOMATION_ENABLED, config.automationEnabled)
            .putLong(KEY_RANDOM_SEED, stableSeed)
            .putString(KEY_LAST_SUMMARY, config.lastSummary)
            .putString(KEY_LAST_GENERATED_DETAILS, config.lastGeneratedDetails)
            .apply()

        config.lastPublishedEpochMilli?.let {
            preferences.edit().putLong(KEY_LAST_PUBLISHED, it).apply()
        }

        if (config.lastPublishedEpochMilli == null) {
            preferences.edit().remove(KEY_LAST_PUBLISHED).apply()
        }

        return config.copy(randomSeed = stableSeed)
    }

    fun setAutomationEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTOMATION_ENABLED, enabled).apply()
    }

    fun setLastPublished(instant: Instant?) {
        val editor = preferences.edit()
        if (instant == null) {
            editor.remove(KEY_LAST_PUBLISHED)
        } else {
            editor.putLong(KEY_LAST_PUBLISHED, instant.toEpochMilli())
        }
        editor.apply()
    }

    fun setLastSummary(summary: String) {
        preferences.edit().putString(KEY_LAST_SUMMARY, summary).apply()
    }

    fun setLastGeneratedDetails(details: String) {
        preferences.edit().putString(KEY_LAST_GENERATED_DETAILS, details).apply()
    }

    companion object {
        private const val PREFS_NAME = "schrittji_simulation"
        private const val KEY_MINIMUM_DAILY_STEPS = "minimum_daily_steps"
        private const val KEY_MAXIMUM_DAILY_STEPS = "maximum_daily_steps"
        private const val KEY_BACKFILL_DAYS = "backfill_days"
        private const val KEY_AUTOMATION_ENABLED = "automation_enabled"
        private const val KEY_RANDOM_SEED = "random_seed"
        private const val KEY_LAST_PUBLISHED = "last_published"
        private const val KEY_LAST_SUMMARY = "last_summary"
        private const val KEY_LAST_GENERATED_DETAILS = "last_generated_details"
    }
}
