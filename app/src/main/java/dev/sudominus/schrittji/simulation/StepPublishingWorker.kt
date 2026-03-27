package dev.sudominus.schrittji.simulation

import android.content.Context
import androidx.health.connect.client.HealthConnectClient.Companion.SDK_AVAILABLE
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.concurrent.futures.await
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.sudominus.schrittji.health.HealthConnectGateway
import java.time.Duration
import java.util.concurrent.TimeUnit

class StepPublishingWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val configStore = SimulationConfigStore(applicationContext)
        val healthConnectGateway = HealthConnectGateway(applicationContext)
        val coordinator = SimulationCoordinator(healthConnectGateway, configStore)
        val config = coordinator.loadConfig()

        if (!config.automationEnabled) {
            return Result.success()
        }

        if (healthConnectGateway.availability() != SDK_AVAILABLE) {
            configStore.setLastSummary(
                "Background top-ups are paused because Health Connect is not available."
            )
            return Result.success()
        }

        if (!healthConnectGateway.hasCoreHealthPermissions()) {
            configStore.setLastSummary(
                "Background top-ups are paused until Schrittji regains Health Connect write permission."
            )
            return Result.success()
        }

        return try {
            coordinator.publishSinceLast(config, Duration.ofMinutes(30))
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object StepPublishingScheduler {
    private const val PERIODIC_WORK_NAME = "schrittji-step-periodic"
    private const val IMMEDIATE_WORK_NAME = "schrittji-step-immediate"

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val periodicRequest = PeriodicWorkRequestBuilder<StepPublishingWorker>(15, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
        kick(context)
    }

    fun kick(context: Context) {
        val immediateRequest = OneTimeWorkRequestBuilder<StepPublishingWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            immediateRequest
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }

    /** True when the periodic WorkManager job is enqueued or running (may still be delayed by Doze). */
    suspend fun isPeriodicScheduled(context: Context): Boolean {
        return try {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(PERIODIC_WORK_NAME)
                .await()
            infos.any { info ->
                info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING
            }
        } catch (_: Exception) {
            false
        }
    }
}
