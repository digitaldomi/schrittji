package dev.sudominus.schrittji.health

import android.content.Context
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import dev.sudominus.schrittji.R
import dev.sudominus.schrittji.simulation.MinuteStepSlice
import dev.sudominus.schrittji.simulation.WorkoutPlan
import dev.sudominus.schrittji.simulation.WorkoutType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class HealthConnectStepRecordEntry(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    val count: Long,
    val sourcePackage: String,
    val isFromSchrittji: Boolean
)

data class HealthConnectExerciseSession(
    val start: ZonedDateTime,
    val end: ZonedDateTime,
    /** Classified workout type; [WorkoutType.RUNNING] is used when HC returns an unmapped type id. */
    val type: WorkoutType,
    val title: String?,
    val notes: String?,
    /** Package name of the app that wrote this session (empty if unknown). */
    val dataOriginPackage: String,
    val isFromSchrittji: Boolean
)

data class HealthConnectStepDaySummary(
    val date: LocalDate,
    val totalSteps: Long,
    val recordCount: Int
)

/** Result of reading exercise sessions for a calendar day (may include [queryError] if HC read failed). */
data class ExerciseSessionsReadResult(
    val sessions: List<HealthConnectExerciseSession>,
    val queryError: String? = null
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
    private val exerciseTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Steps: passive capture from a wrist device (avoids manual-entry labeling in Health Connect). */
    private val stepsRecordMetadata: Metadata by lazy {
        Metadata.autoRecorded(Device(type = Device.TYPE_WATCH))
    }

    /** Workouts: user-started session on phone (generic device, no app name in record fields). */
    private val workoutSessionMetadata: Metadata by lazy {
        Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE))
    }

    /** Distance / calories linked to the same workout window. */
    private val workoutCompanionMetadata: Metadata by lazy {
        Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE))
    }

    /**
     * Permissions needed for steps, writing workouts, and **reading** exercise sessions back
     * (charts and day detail). Exercise read is required for sessions to appear in the app;
     * write alone does not grant read on Health Connect.
     */
    private val coreHealthPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
    )

    private val exerciseReadPermission: String =
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)

    /** Grant flow: steps + exercise session read/write (read is required to show workouts in charts). */
    val requiredPermissions: Set<String> = coreHealthPermissions

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)

    fun permissionsContract(): ActivityResultContract<Set<String>, Set<String>> {
        return PermissionController.createRequestPermissionResultContract()
    }

    suspend fun hasCoreHealthPermissions(): Boolean {
        return healthConnectClient.permissionController
            .getGrantedPermissions()
            .containsAll(coreHealthPermissions)
    }

    suspend fun hasExerciseReadPermission(): Boolean {
        return exerciseReadPermission in healthConnectClient.permissionController.getGrantedPermissions()
    }

    suspend fun hasAllRequestedPermissions(): Boolean {
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
                        metadata = stepsRecordMetadata,
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

    suspend fun insertWorkouts(workouts: List<WorkoutPlan>): Int {
        if (workouts.isEmpty()) {
            return 0
        }

        val records = workouts.flatMap { workout ->
            val exerciseType = when (workout.type) {
                WorkoutType.RUNNING -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
                WorkoutType.CYCLING -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
                WorkoutType.MINDFULNESS -> ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING
            }
            buildList {
                add(
                    ExerciseSessionRecord(
                        startTime = workout.start.toInstant(),
                        startZoneOffset = workout.start.offset,
                        endTime = workout.end.toInstant(),
                        endZoneOffset = workout.end.offset,
                        metadata = workoutSessionMetadata,
                        exerciseType = exerciseType,
                        title = workout.title,
                        notes = workout.notes
                    )
                )
                if (workout.distanceMeters > 0.5) {
                    add(
                        DistanceRecord(
                            startTime = workout.start.toInstant(),
                            startZoneOffset = workout.start.offset,
                            endTime = workout.end.toInstant(),
                            endZoneOffset = workout.end.offset,
                            distance = Length.meters(workout.distanceMeters),
                            metadata = workoutCompanionMetadata
                        )
                    )
                }
                add(
                    TotalCaloriesBurnedRecord(
                        startTime = workout.start.toInstant(),
                        startZoneOffset = workout.start.offset,
                        endTime = workout.end.toInstant(),
                        endZoneOffset = workout.end.offset,
                        energy = Energy.kilocalories(workout.kilocalories),
                        metadata = workoutCompanionMetadata
                    )
                )
            }
        }

        records.chunked(300).forEach { chunk ->
            healthConnectClient.insertRecords(chunk)
        }

        return workouts.size
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

    suspend fun readStepEntriesForDate(date: LocalDate): List<HealthConnectStepRecordEntry> {
        val allRecords = mutableListOf<HealthConnectStepRecordEntry>()
        var pageToken: String? = null
        val start = date.atStartOfDay(zoneId).toInstant()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant()

        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    ascendingOrder = true,
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

        return allRecords.sortedBy { it.start.toInstant() }
    }

    suspend fun readExerciseSessionsForDate(date: LocalDate): List<HealthConnectExerciseSession> {
        return readExerciseSessionsForDateResult(date).sessions
    }

    /**
     * Reads [ExerciseSessionRecord]s that overlap [date] in [zoneId].
     * Uses a **widened** HC query window (±1 calendar day) then filters by overlap, so sessions
     * are not missed when stored instants sit near zone boundaries or HC uses exclusive ranges.
     */
    suspend fun readExerciseSessionsForDateResult(date: LocalDate): ExerciseSessionsReadResult {
        val dayStart = date.atStartOfDay(zoneId)
        val dayEnd = date.plusDays(1).atStartOfDay(zoneId)
        val queryStart = dayStart.minusDays(1).toInstant()
        val queryEnd = dayEnd.plusDays(1).toInstant()

        return try {
            val allSessions = mutableListOf<HealthConnectExerciseSession>()
            var pageToken: String? = null

            do {
                val response = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = ExerciseSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(queryStart, queryEnd),
                        ascendingOrder = true,
                        pageSize = 1_000,
                        pageToken = pageToken
                    )
                )
                allSessions += response.records.map { record ->
                    val pkg = record.metadata.dataOrigin.packageName.orEmpty()
                    val zStart = ZonedDateTime.ofInstant(record.startTime, zoneId)
                    val zEnd = ZonedDateTime.ofInstant(record.endTime, zoneId)
                    val mapped = mapExerciseTypeToWorkoutType(
                        exerciseType = record.exerciseType,
                        title = record.title,
                        notes = record.notes
                    )
                    HealthConnectExerciseSession(
                        start = zStart,
                        end = zEnd,
                        type = mapped,
                        title = record.title,
                        notes = record.notes,
                        dataOriginPackage = pkg,
                        isFromSchrittji = pkg == context.packageName
                    )
                }
                pageToken = response.pageToken
            } while (pageToken != null)

            val overlapping = allSessions.filter { session ->
                session.start.isBefore(dayEnd) && session.end.isAfter(dayStart)
            }
            ExerciseSessionsReadResult(
                sessions = overlapping.sortedBy { it.start.toInstant() },
                queryError = null
            )
        } catch (e: Exception) {
            Log.e("SchrittjiHC", "readExerciseSessionsForDate failed for $date", e)
            ExerciseSessionsReadResult(
                sessions = emptyList(),
                queryError = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private fun mapExerciseTypeToWorkoutType(
        exerciseType: Int,
        title: String?,
        notes: String?
    ): WorkoutType {
        when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> return WorkoutType.RUNNING
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> return WorkoutType.CYCLING
            ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING,
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
            ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> return WorkoutType.MINDFULNESS
        }
        val blob = "${title.orEmpty()} ${notes.orEmpty()}".lowercase(Locale.ROOT)
        if ("run" in blob || "lauf" in blob || "jog" in blob) return WorkoutType.RUNNING
        if ("bike" in blob || "cycling" in blob || "radfahren" in blob || "fahrrad" in blob) {
            return WorkoutType.CYCLING
        }
        if ("mindful" in blob || "yoga" in blob || "pilates" in blob || "meditat" in blob) {
            return WorkoutType.MINDFULNESS
        }
        // Never drop a session: unknown/new HC exercise type IDs still render as cardio (running).
        return WorkoutType.RUNNING
    }

    fun formatExerciseSessionDetail(session: HealthConnectExerciseSession): String {
        val duration = ChronoUnit.MINUTES.between(session.start, session.end).coerceAtLeast(1)
        return buildString {
            appendLine(
                "${session.start.format(exerciseTimeFormatter)}–${session.end.format(exerciseTimeFormatter)}"
            )
            appendLine("Duration: $duration min")
            session.notes?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            append(context.getString(R.string.workout_hc_source_health_connect))
        }
    }
}
