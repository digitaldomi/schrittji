package dev.digitaldomi.schrittji.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import dev.digitaldomi.schrittji.simulation.MinuteStepSlice
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class HealthConnectStepRecordEntry(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val count: Long,
    val sourcePackage: String,
    val isFromSchrittji: Boolean
)

data class HealthConnectStepDaySummary(
    val date: LocalDate,
    val totalSteps: Long,
    val recordCount: Int
)

data class HealthConnectStepsSnapshot(
    val totalRecords: Int,
    val totalSteps: Long,
    val ownRecords: Int,
    val ownSteps: Long,
    val earliestStart: ZonedDateTime?,
    val latestEnd: ZonedDateTime?,
    val daySummaries: List<HealthConnectStepDaySummary>,
    val records: List<HealthConnectStepRecordEntry>
)

class HealthConnectGateway(private val context: Context) {
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }
    private val zoneId: ZoneId = ZoneId.systemDefault()

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
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

    suspend fun readAllStepsSnapshot(): HealthConnectStepsSnapshot {
        val allRecords = mutableListOf<HealthConnectStepRecordEntry>()
        var pageToken: String? = null

        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, Instant.now().plusSeconds(60)),
                    ascendingOrder = false,
                    pageSize = 1_000,
                    pageToken = pageToken
                )
            )

            allRecords += response.records.map { record ->
                val sourcePackage = record.metadata.dataOrigin.packageName.orEmpty()
                HealthConnectStepRecordEntry(
                    start = ZonedDateTime.ofInstant(record.startTime, zoneId),
                    end = ZonedDateTime.ofInstant(record.endTime, zoneId),
                    count = record.count,
                    sourcePackage = sourcePackage,
                    isFromSchrittji = sourcePackage == context.packageName
                )
            }
            pageToken = response.pageToken
        } while (pageToken != null)

        val orderedRecords = allRecords.sortedByDescending { it.start.toInstant() }
        val ownRecords = orderedRecords.filter { it.isFromSchrittji }
        val daySummaries = orderedRecords
            .groupBy { it.start.toLocalDate() }
            .toSortedMap(compareByDescending { it })
            .map { (date, records) ->
                HealthConnectStepDaySummary(
                    date = date,
                    totalSteps = records.sumOf { it.count },
                    recordCount = records.size
                )
            }

        return HealthConnectStepsSnapshot(
            totalRecords = orderedRecords.size,
            totalSteps = orderedRecords.sumOf { it.count },
            ownRecords = ownRecords.size,
            ownSteps = ownRecords.sumOf { it.count },
            earliestStart = orderedRecords.minByOrNull { it.start.toInstant() }?.start,
            latestEnd = orderedRecords.maxByOrNull { it.end.toInstant() }?.end,
            daySummaries = daySummaries,
            records = orderedRecords
        )
    }
}
