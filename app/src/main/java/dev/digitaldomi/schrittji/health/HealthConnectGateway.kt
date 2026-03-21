package dev.digitaldomi.schrittji.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import dev.digitaldomi.schrittji.simulation.MinuteStepSlice

class HealthConnectGateway(private val context: Context) {
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getWritePermission(StepsRecord::class)
    )

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    fun permissionsContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract()
    }

    suspend fun hasRequiredPermissions(): Boolean {
        return healthConnectClient.permissionController
            .getGrantedPermissions()
            .containsAll(requiredPermissions)
    }

    suspend fun insertSlices(slices: List<MinuteStepSlice>): Int {
        if (slices.isEmpty()) {
            return 0
        }

        slices.chunked(400).forEach { chunk ->
            healthConnectClient.insertRecords(
                chunk.map { slice ->
                    StepsRecord(
                        metadata = Metadata.manualEntry(),
                        startTime = slice.start.toInstant(),
                        startZoneOffset = slice.start.offset,
                        endTime = slice.end.toInstant(),
                        endZoneOffset = slice.end.offset,
                        count = slice.count
                    )
                }
            )
        }

        return slices.size
    }
}
