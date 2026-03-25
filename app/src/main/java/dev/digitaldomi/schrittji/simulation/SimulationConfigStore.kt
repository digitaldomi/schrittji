package dev.digitaldomi.schrittji.simulation

import android.content.Context
import java.security.SecureRandom
import java.time.Instant

data class SimulationConfig(
    val minimumDailySteps: Int = 7_500,
    val maximumDailySteps: Int = 22_000,
    val dailyStepsEnabled: Boolean = true,
    val backfillDays: Int = 14,
    val automationEnabled: Boolean = false,
    val runningEnabled: Boolean = true,
    val cyclingEnabled: Boolean = true,
    val runningMinSessionsPerWeek: Int = 1,
    val runningMaxSessionsPerWeek: Int = 2,
    val runningMinDurationMinutes: Int = 26,
    val runningMaxDurationMinutes: Int = 48,
    val cyclingMinSessionsPerWeek: Int = 0,
    val cyclingMaxSessionsPerWeek: Int = 1,
    val cyclingMinDurationMinutes: Int = 40,
    val cyclingMaxDurationMinutes: Int = 95,
    val mindfulnessEnabled: Boolean = true,
    val mindfulnessMinSessionsPerWeek: Int = 0,
    val mindfulnessMaxSessionsPerWeek: Int = 2,
    val mindfulnessMinDurationMinutes: Int = 10,
    val mindfulnessMaxDurationMinutes: Int = 35,
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
            dailyStepsEnabled = preferences.getBoolean(KEY_DAILY_STEPS_ENABLED, true),
            backfillDays = preferences.getInt(KEY_BACKFILL_DAYS, 14),
            automationEnabled = preferences.getBoolean(KEY_AUTOMATION_ENABLED, false),
            runningEnabled = preferences.getBoolean(KEY_RUNNING_ENABLED, true),
            cyclingEnabled = preferences.getBoolean(KEY_CYCLING_ENABLED, true),
            runningMinSessionsPerWeek = preferences.getInt(KEY_RUNNING_MIN_SESSIONS, 1),
            runningMaxSessionsPerWeek = preferences.getInt(KEY_RUNNING_MAX_SESSIONS, 2),
            runningMinDurationMinutes = preferences.getInt(KEY_RUNNING_MIN_DURATION, 26),
            runningMaxDurationMinutes = preferences.getInt(KEY_RUNNING_MAX_DURATION, 48),
            cyclingMinSessionsPerWeek = preferences.getInt(KEY_CYCLING_MIN_SESSIONS, 0),
            cyclingMaxSessionsPerWeek = preferences.getInt(KEY_CYCLING_MAX_SESSIONS, 1),
            cyclingMinDurationMinutes = preferences.getInt(KEY_CYCLING_MIN_DURATION, 40),
            cyclingMaxDurationMinutes = preferences.getInt(KEY_CYCLING_MAX_DURATION, 95),
            mindfulnessEnabled = preferences.getBoolean(KEY_MINDFULNESS_ENABLED, true),
            mindfulnessMinSessionsPerWeek = preferences.getInt(KEY_MINDFULNESS_MIN_SESSIONS, 0),
            mindfulnessMaxSessionsPerWeek = preferences.getInt(KEY_MINDFULNESS_MAX_SESSIONS, 2),
            mindfulnessMinDurationMinutes = preferences.getInt(KEY_MINDFULNESS_MIN_DURATION, 10),
            mindfulnessMaxDurationMinutes = preferences.getInt(KEY_MINDFULNESS_MAX_DURATION, 35),
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
            .putBoolean(KEY_DAILY_STEPS_ENABLED, config.dailyStepsEnabled)
            .putInt(KEY_BACKFILL_DAYS, config.backfillDays)
            .putBoolean(KEY_AUTOMATION_ENABLED, config.automationEnabled)
            .putBoolean(KEY_RUNNING_ENABLED, config.runningEnabled)
            .putBoolean(KEY_CYCLING_ENABLED, config.cyclingEnabled)
            .putInt(KEY_RUNNING_MIN_SESSIONS, config.runningMinSessionsPerWeek)
            .putInt(KEY_RUNNING_MAX_SESSIONS, config.runningMaxSessionsPerWeek)
            .putInt(KEY_RUNNING_MIN_DURATION, config.runningMinDurationMinutes)
            .putInt(KEY_RUNNING_MAX_DURATION, config.runningMaxDurationMinutes)
            .putInt(KEY_CYCLING_MIN_SESSIONS, config.cyclingMinSessionsPerWeek)
            .putInt(KEY_CYCLING_MAX_SESSIONS, config.cyclingMaxSessionsPerWeek)
            .putInt(KEY_CYCLING_MIN_DURATION, config.cyclingMinDurationMinutes)
            .putInt(KEY_CYCLING_MAX_DURATION, config.cyclingMaxDurationMinutes)
            .putBoolean(KEY_MINDFULNESS_ENABLED, config.mindfulnessEnabled)
            .putInt(KEY_MINDFULNESS_MIN_SESSIONS, config.mindfulnessMinSessionsPerWeek)
            .putInt(KEY_MINDFULNESS_MAX_SESSIONS, config.mindfulnessMaxSessionsPerWeek)
            .putInt(KEY_MINDFULNESS_MIN_DURATION, config.mindfulnessMinDurationMinutes)
            .putInt(KEY_MINDFULNESS_MAX_DURATION, config.mindfulnessMaxDurationMinutes)
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
        private const val KEY_DAILY_STEPS_ENABLED = "daily_steps_enabled"
        private const val KEY_BACKFILL_DAYS = "backfill_days"
        private const val KEY_RUNNING_ENABLED = "running_enabled"
        private const val KEY_CYCLING_ENABLED = "cycling_enabled"
        private const val KEY_AUTOMATION_ENABLED = "automation_enabled"
        private const val KEY_RUNNING_MIN_SESSIONS = "running_min_sessions"
        private const val KEY_RUNNING_MAX_SESSIONS = "running_max_sessions"
        private const val KEY_RUNNING_MIN_DURATION = "running_min_duration"
        private const val KEY_RUNNING_MAX_DURATION = "running_max_duration"
        private const val KEY_CYCLING_MIN_SESSIONS = "cycling_min_sessions"
        private const val KEY_CYCLING_MAX_SESSIONS = "cycling_max_sessions"
        private const val KEY_CYCLING_MIN_DURATION = "cycling_min_duration"
        private const val KEY_CYCLING_MAX_DURATION = "cycling_max_duration"
        private const val KEY_MINDFULNESS_ENABLED = "mindfulness_enabled"
        private const val KEY_MINDFULNESS_MIN_SESSIONS = "mindfulness_min_sessions"
        private const val KEY_MINDFULNESS_MAX_SESSIONS = "mindfulness_max_sessions"
        private const val KEY_MINDFULNESS_MIN_DURATION = "mindfulness_min_duration"
        private const val KEY_MINDFULNESS_MAX_DURATION = "mindfulness_max_duration"
        private const val KEY_RANDOM_SEED = "random_seed"
        private const val KEY_LAST_PUBLISHED = "last_published"
        private const val KEY_LAST_SUMMARY = "last_summary"
        private const val KEY_LAST_GENERATED_DETAILS = "last_generated_details"
    }
}
