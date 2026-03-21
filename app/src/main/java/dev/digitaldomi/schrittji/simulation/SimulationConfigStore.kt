package dev.digitaldomi.schrittji.simulation

import android.content.Context
import java.security.SecureRandom
import java.time.Instant

enum class SimulationProfile(val displayName: String) {
    OFFICE_COMMUTER("Office commuter"),
    HYBRID_ERRANDS("Hybrid errands"),
    ACTIVE_SOCIAL("Active social");

    companion object {
        fun fromName(name: String?): SimulationProfile {
            return entries.firstOrNull { it.name == name } ?: OFFICE_COMMUTER
        }
    }
}

data class SimulationConfig(
    val profile: SimulationProfile = SimulationProfile.OFFICE_COMMUTER,
    val minimumDailySteps: Int = 6_800,
    val maximumDailySteps: Int = 11_800,
    val backfillDays: Int = 14,
    val automationEnabled: Boolean = false,
    val randomSeed: Long = 0L,
    val lastPublishedEpochMilli: Long? = null,
    val lastSummary: String = "No Health Connect data has been written yet."
)

class SimulationConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    fun load(): SimulationConfig {
        val rawLastPublished = preferences.getLong(KEY_LAST_PUBLISHED, Long.MIN_VALUE)
        return SimulationConfig(
            profile = SimulationProfile.fromName(preferences.getString(KEY_PROFILE, null)),
            minimumDailySteps = preferences.getInt(KEY_MIN_STEPS, 6_800),
            maximumDailySteps = preferences.getInt(KEY_MAX_STEPS, 11_800),
            backfillDays = preferences.getInt(KEY_BACKFILL_DAYS, 14),
            automationEnabled = preferences.getBoolean(KEY_AUTOMATION_ENABLED, false),
            randomSeed = preferences.getLong(KEY_RANDOM_SEED, 0L),
            lastPublishedEpochMilli = rawLastPublished.takeUnless { it == Long.MIN_VALUE },
            lastSummary = preferences.getString(
                KEY_LAST_SUMMARY,
                "No Health Connect data has been written yet."
            ) ?: "No Health Connect data has been written yet."
        )
    }

    fun save(config: SimulationConfig): SimulationConfig {
        val stableSeed = config.randomSeed.takeUnless { it == 0L } ?: secureRandom.nextLong()
        preferences.edit()
            .putString(KEY_PROFILE, config.profile.name)
            .putInt(KEY_MIN_STEPS, config.minimumDailySteps)
            .putInt(KEY_MAX_STEPS, config.maximumDailySteps)
            .putInt(KEY_BACKFILL_DAYS, config.backfillDays)
            .putBoolean(KEY_AUTOMATION_ENABLED, config.automationEnabled)
            .putLong(KEY_RANDOM_SEED, stableSeed)
            .putString(KEY_LAST_SUMMARY, config.lastSummary)
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

    companion object {
        private const val PREFS_NAME = "schrittji_simulation"
        private const val KEY_PROFILE = "profile"
        private const val KEY_MIN_STEPS = "minimum_daily_steps"
        private const val KEY_MAX_STEPS = "maximum_daily_steps"
        private const val KEY_BACKFILL_DAYS = "backfill_days"
        private const val KEY_AUTOMATION_ENABLED = "automation_enabled"
        private const val KEY_RANDOM_SEED = "random_seed"
        private const val KEY_LAST_PUBLISHED = "last_published"
        private const val KEY_LAST_SUMMARY = "last_summary"
    }
}
