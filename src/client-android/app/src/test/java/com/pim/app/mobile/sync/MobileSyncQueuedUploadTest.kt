package com.pim.app.mobile.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.app.data.AppDatabase
import com.pim.app.data.MobileAppMetadataEntity
import com.pim.app.data.MobileSyncStatus
import com.pim.app.data.MobileUsageEventEntity
import com.pim.app.data.MobileUsageSummaryEntity
import com.pim.app.forensics.AndroidHeartbeatSnapshotReader
import com.pim.app.forensics.ForensicContext
import com.pim.app.forensics.ForensicContextSource
import com.pim.app.forensics.ForensicLedger
import com.pim.app.forensics.ForensicSentinelStore
import com.pim.app.forensics.ForensicUploadCoordinator
import com.pim.app.forensics.WakeHeartbeatRecorder
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.mobile.usage.AppMetadataCollector
import com.pim.app.mobile.usage.UsageAccessChecker
import com.pim.app.mobile.usage.UsageEventCollection
import com.pim.app.mobile.usage.UsageEventCollector
import com.pim.app.settings.TrackingSettingsStore
import com.pim.core.auth.SecurePreferencesFactory
import com.pim.core.auth.TokenManager
import com.pim.core.contract.MobileIngestItemResult
import com.pim.core.contract.MobileUsageEventsUploadRequest
import com.pim.core.contract.MobileUsageIngestResult
import com.pim.core.contract.MobileUsageIngestResultApiResponse
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.ApiService
import com.pim.core.network.UploadSessionChangedException
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class MobileSyncQueuedUploadTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var coordinator: MobileSyncCoordinator
    private lateinit var uploadInterceptor: RecordingUsageUploadInterceptor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        uploadInterceptor = RecordingUsageUploadInterceptor()

        val authPreferences = context.getSharedPreferences("queued-upload-auth", Context.MODE_PRIVATE)
        val tokenManager = TokenManager(QueuedUploadSecurePreferencesFactory(authPreferences))
        val serverSettingsStore = ServerSettingsStore(context, tokenManager)
        val serverUrl = serverSettingsStore.setBaseUrl("https://pim.example/api/v1/")
        val serverIdentity = PimServerEndpoints.from(serverUrl).trustedOrigin
        assertTrue(
            tokenManager.saveOwned(
                accessToken = "owner-a-token",
                refreshToken = "owner-a-refresh",
                expiresAtUtcMillis = System.currentTimeMillis() + 3_600_000L,
                serverIdentity = serverIdentity,
                ownerId = OWNER_ID,
                ownerName = "Owner A"
            )
        )
        context.getSharedPreferences("pim_mobile_sync_state", Context.MODE_PRIVATE)
            .edit()
            .putString("usage_outbox_server_identity", serverIdentity)
            .putString("usage_outbox_owner_id", OWNER_ID)
            .commit()

        val trackPrefs = context.getSharedPreferences("queued-upload-tracking", Context.MODE_PRIVATE)
        trackPrefs.edit().clear().commit()
        val trackSettings = TrackingSettingsStore(trackPrefs)
        val logs = StructuredLogRepository(context, trackSettings) { System.currentTimeMillis() }
        val globalApi = unusedApi()
        val json = Json { ignoreUnknownKeys = true }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(uploadInterceptor)
            .build()
        val apiClientProvider = ApiClientProvider(okHttpClient, json, serverSettingsStore)
        val deviceIdentityProvider = DeviceIdentityProvider(context)

        coordinator = MobileSyncCoordinator(
            context = context,
            api = globalApi,
            apiClientProvider = apiClientProvider,
            tokenManager = tokenManager,
            usageAccessChecker = UsageAccessChecker(context),
            usageEventCollector = UsageEventCollector(context, UsageAccessChecker(context)),
            appMetadataCollector = AppMetadataCollector(context),
            database = db,
            logs = logs,
            heartbeatReporter = MobileHeartbeatReporter(context, globalApi),
            serverSettingsStore = serverSettingsStore,
            locationUploadCoordinator = LocationUploadCoordinator(
                context,
                db,
                globalApi,
                deviceIdentityProvider
            ),
            forensicUploadCoordinator = ForensicUploadCoordinator(
                context = context,
                api = globalApi,
                dao = db.forensicEventDao(),
                logs = logs,
                sentinelStore = ForensicSentinelStore(context),
                deviceIdentityProvider = deviceIdentityProvider
            ),
            wakeHeartbeatRecorder = WakeHeartbeatRecorder(
                ledger = ForensicLedger(db.forensicEventDao(), logs),
                contextReader = QueuedUploadForensicContextSource(ForensicContext()),
                snapshotReader = AndroidHeartbeatSnapshotReader(context),
                logs = logs
            ),
            syncScheduler = { MobileSyncScheduler(context, trackSettings) },
            deviceIdentityProvider = deviceIdentityProvider
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `fresh mixed collection uses bounded Room batches without loss or duplicate`() = runTest {
        val start = 1_750_000_000_000L
        val minute = 60_000L
        val hour = 60L * minute
        val events = List(251) { index ->
            usageEvent(
                timeUtc = start + index * minute,
                packageName = "com.fresh.${index % 37}"
            )
        }
        val summaries = buildList {
            add(usageSummary(start, start + 25L * hour, "com.daily"))
            repeat(124) { index ->
                add(
                    usageSummary(
                        startUtc = start + index * minute,
                        endUtc = start + index * minute + minute,
                        packageName = "com.summary.$index"
                    )
                )
            }
        }
        val apps = List(125) { index ->
            usageApp("com.meta.$index", start + index * minute)
        }
        val collection = UsageEventCollection(
            events = events,
            summaries = summaries,
            source = "synthetic-gap-collection",
            sourceWindowStartUtc = start,
            sourceWindowEndUtc = start + 2L * hour,
            collectedAtUtc = start + 2L * hour
        )

        val expectedKeys = insertCollectedUsage(collection, apps)
        val dailySummaryId = db.mobileDataDao()
            .getUsageSummariesBySyncStatus()
            .single { it.packageName == "com.daily" }
            .id
        val state = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "fresh-device",
                attemptedAt = "2026-10-29T00:00:00Z",
                manualRecoveryRetry = false
            )
        )

        assertTrue(state.outcome == MobileSyncOutcome.SUCCESS)
        assertTrue(uploadInterceptor.requests.size >= 2)
        assertTrue(uploadInterceptor.requests.all { it.totalCount <= USAGE_BATCH_LIMIT })
        assertEquals(
            expectedKeys,
            uploadInterceptor.requests.flatMap { it.keys }.toSet()
        )
        assertEquals(
            expectedKeys.size,
            uploadInterceptor.requests.sumOf { it.totalCount }
        )
        assertEquals(0, pendingUsageRemaining(db.mobileDataDao()))
        uploadInterceptor.requests.forEach { request ->
            val span = Instant.parse(request.sourceWindowEndUtc).toEpochMilli() -
                Instant.parse(request.sourceWindowStartUtc).toEpochMilli()
            assertTrue(span in 1..USAGE_BATCH_MAX_WINDOW_MS)
        }
        assertEquals(
            1,
            uploadInterceptor.requests.count { request ->
                request.fallbackSummaryKeys.contains("$dailySummaryId")
            }
        )
    }

    @Test
    fun `lease lost retries the same batch id and does not persist a permanent block`() = runTest {
        val eventId = db.mobileDataDao().insertUsageEvents(
            listOf(usageEvent(1_750_000_000_000L, "com.lease.lost"))
        ).single()
        uploadInterceptor.leaseLostOnce = true

        val first = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "lease-device",
                attemptedAt = "2026-10-29T00:00:00Z",
                manualRecoveryRetry = false
            )
        )

        assertEquals(MobileSyncOutcome.RETRY, first.outcome)
        assertEquals(1, uploadInterceptor.requests.size)
        val firstBatchId = uploadInterceptor.requests.single().clientBatchId
        assertEquals(1, pendingUsageRemaining(db.mobileDataDao()))
        assertEquals(
            eventId,
            db.mobileDataDao().getUsageEventsBySyncStatus().single().id
        )
        val syncPrefs = context.getSharedPreferences("pim_mobile_sync_state", Context.MODE_PRIVATE)
        assertFalse(
            syncPrefs.all.any { (key, value) ->
                key.endsWith("_blocked") && value == true
            }
        )

        val second = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "lease-device",
                attemptedAt = "2026-10-29T00:00:30Z",
                manualRecoveryRetry = false
            )
        )

        assertEquals(MobileSyncOutcome.SUCCESS, second.outcome)
        assertEquals(2, uploadInterceptor.requests.size)
        assertEquals(firstBatchId, uploadInterceptor.requests[1].clientBatchId)
        assertEquals(0, pendingUsageRemaining(db.mobileDataDao()))
    }

    @Test
    fun `max batches per run leaves unsent rows pending for a later run`() = runTest {
        db.mobileDataDao().insertUsageEvents(
            List(501) { index ->
                usageEvent(
                    timeUtc = 1_750_000_000_000L + index,
                    packageName = "com.max.$index"
                )
            }
        )

        val state = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "max-batch-device",
                attemptedAt = "2026-10-29T00:00:00Z",
                manualRecoveryRetry = false,
                maxBatchesPerRun = 1
            )
        )

        assertEquals(1, uploadInterceptor.requests.size)
        assertTrue(uploadInterceptor.requests.all { it.totalCount <= USAGE_BATCH_LIMIT })
        assertEquals(1, pendingUsageRemaining(db.mobileDataDao()))
        assertEquals("catching-up", state.phase)
        assertEquals(MobileSyncOutcome.SUCCESS, state.outcome)
    }

    @Test
    fun `unrepresentable Long MAX point blocks upload and preserves pending row`() = runTest {
        val eventId = db.mobileDataDao().insertUsageEvents(
            listOf(usageEvent(Long.MAX_VALUE, "com.max.timestamp"))
        ).single()

        val state = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "max-timestamp-device",
                attemptedAt = "2026-10-29T00:00:00Z",
                manualRecoveryRetry = false
            )
        )

        assertEquals("upload-blocked", state.phase)
        assertEquals(MobileSyncOutcome.BLOCKED, state.outcome)
        assertEquals(1, pendingUsageRemaining(db.mobileDataDao()))
        assertEquals(0, uploadInterceptor.requests.size)
        assertEquals(eventId, db.mobileDataDao().getUsageEventsBySyncStatus().single().id)
    }

    @Test
    fun `bound session change blocks binding and keeps pending without false acknowledgement`() = runTest {
        val eventId = db.mobileDataDao().insertUsageEvents(
            listOf(usageEvent(1_750_000_000_000L, "com.session.changed"))
        ).single()
        uploadInterceptor.throwSessionChanged = true

        val state = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "binding-device",
                attemptedAt = "2026-10-29T00:00:00Z",
                manualRecoveryRetry = false
            )
        )

        assertEquals("usage-outbox-binding-blocked", state.phase)
        assertEquals(MobileSyncOutcome.BLOCKED, state.outcome)
        assertEquals(0, uploadInterceptor.requests.size)
        assertEquals(1, pendingUsageRemaining(db.mobileDataDao()))
        assertEquals(
            eventId,
            db.mobileDataDao().getUsageEventsBySyncStatus().single().id
        )
        val syncPrefs = context.getSharedPreferences("pim_mobile_sync_state", Context.MODE_PRIVATE)
        assertFalse(syncPrefs.all.values.any { it == "owner-a-token" })
        assertFalse(syncPrefs.all.keys.any { it.contains("token", ignoreCase = true) })
    }

    private suspend fun insertCollectedUsage(
        collection: UsageEventCollection,
        apps: List<MobileAppMetadataEntity>
    ): Set<String> {
        val dao = db.mobileDataDao()
        val eventIds = dao.insertUsageEvents(collection.events)
        val summaryIds = dao.insertUsageSummaries(collection.summaries)
        dao.upsertAppMetadata(apps)
        return buildSet {
            eventIds.forEach { add("usage-event:$it") }
            summaryIds.forEach { add("usage-summary:$it") }
            apps.forEach { add("app-metadata:${it.packageName}@${it.versionCode}") }
        }
    }

    private fun unusedApi(): ApiService = Proxy.newProxyInstance(
        ApiService::class.java.classLoader,
        arrayOf(ApiService::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "UnusedApiService"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected global API call: ${method.name}")
        }
    } as ApiService

    private class RecordingUsageUploadInterceptor : Interceptor {
        val requests = CopyOnWriteArrayList<RecordedUsageRequest>()
        var leaseLostOnce = false
        var throwSessionChanged = false
        private val json = Json { ignoreUnknownKeys = true }

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            assertEquals("/api/v1/mobile/usage/events", request.url.encodedPath)
            assertEquals("POST", request.method)
            if (throwSessionChanged) {
                throw UploadSessionChangedException()
            }
            val bodyText = Buffer().also { buffer ->
                checkNotNull(request.body).writeTo(buffer)
            }.readUtf8()
            val upload = json.decodeFromString<MobileUsageEventsUploadRequest>(bodyText)
            requests += RecordedUsageRequest(upload)
            if (leaseLostOnce) {
                leaseLostOnce = false
                return jsonResponse(
                    request,
                    409,
                    """{"code":40904,"message":"lease lost","data":null,"timestamp":"2026-10-29T00:00:00Z"}"""
                )
            }
            val itemResults = buildList {
                upload.events.forEach { event ->
                    add(accepted(event.clientItemKey, "usage-event"))
                }
                upload.fallbackSummaries.forEach { summary ->
                    add(accepted(summary.clientItemKey, "usage-summary"))
                }
                upload.apps.forEach { app ->
                    add(
                        accepted(
                            "${app.packageName}@${app.versionCode}",
                            "app-metadata"
                        )
                    )
                }
            }
            val response = MobileUsageIngestResultApiResponse(
                code = 0,
                data = MobileUsageIngestResult(
                    acceptedCount = itemResults.size,
                    batchId = upload.clientBatchId,
                    failedCount = 0,
                    itemResults = itemResults,
                    rejectedCount = 0,
                    skippedCount = 0
                ),
                message = "ok",
                timestamp = "2026-10-29T00:00:00Z"
            )
            return jsonResponse(request, 200, json.encodeToString(response))
        }

        private fun accepted(key: String?, entityType: String): MobileIngestItemResult {
            return MobileIngestItemResult(
                clientItemKey = checkNotNull(key),
                code = "0",
                entityType = entityType,
                message = "ok",
                outcome = "accepted"
            )
        }

        private fun jsonResponse(
            request: okhttp3.Request,
            status: Int,
            body: String
        ): Response {
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 200) "OK" else "Conflict")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private data class RecordedUsageRequest(
        val clientBatchId: String,
        val sourceWindowStartUtc: String,
        val sourceWindowEndUtc: String,
        val keys: Set<String>,
        val fallbackSummaryKeys: Set<String>
    ) {
        val totalCount: Int = keys.size

        constructor(upload: MobileUsageEventsUploadRequest) : this(
            clientBatchId = upload.clientBatchId,
            sourceWindowStartUtc = upload.sourceWindowStartUtc,
            sourceWindowEndUtc = upload.sourceWindowEndUtc,
            keys = buildSet {
                upload.events.forEach { add("usage-event:${it.clientItemKey}") }
                upload.fallbackSummaries.forEach { add("usage-summary:${it.clientItemKey}") }
                upload.apps.forEach {
                    add("app-metadata:${it.packageName}@${it.versionCode}")
                }
            },
            fallbackSummaryKeys = upload.fallbackSummaries
                .mapNotNullTo(linkedSetOf()) { it.clientItemKey }
        )
    }

    private class QueuedUploadSecurePreferencesFactory(
        private val preferences: SharedPreferences
    ) : SecurePreferencesFactory {
        override fun open(): SharedPreferences = preferences
    }

    private class QueuedUploadForensicContextSource(
        private val value: ForensicContext
    ) : ForensicContextSource {
        override fun read(): ForensicContext = value
    }

    companion object {
        private const val OWNER_ID = "11111111-1111-1111-1111-111111111111"

        private fun usageEvent(timeUtc: Long, packageName: String) = MobileUsageEventEntity(
            packageName = packageName,
            className = null,
            eventType = 1,
            eventName = "test",
            eventTimeUtc = timeUtc,
            source = "synthetic",
            sourceWindowStartUtc = timeUtc,
            sourceWindowEndUtc = timeUtc + 1,
            collectedAtUtc = timeUtc,
            rawJson = "{}",
            syncStatus = MobileSyncStatus.PENDING
        )

        private fun usageSummary(
            startUtc: Long,
            endUtc: Long,
            packageName: String
        ) = MobileUsageSummaryEntity(
            packageName = packageName,
            windowStartUtc = startUtc,
            windowEndUtc = endUtc,
            totalTimeForegroundMs = 1,
            lastTimeUsedUtc = endUtc,
            firstTimeStampUtc = startUtc,
            lastTimeStampUtc = endUtc,
            source = "synthetic",
            sourceWindowStartUtc = startUtc,
            sourceWindowEndUtc = endUtc,
            collectedAtUtc = endUtc,
            rawJson = "{}",
            syncStatus = MobileSyncStatus.PENDING
        )

        private fun usageApp(packageName: String, collectedAtUtc: Long) =
            MobileAppMetadataEntity(
                packageName = packageName,
                label = packageName,
                versionCode = 1,
                firstInstallTimeUtc = 0,
                lastUpdateTimeUtc = 0,
                isSystemApp = false,
                collectedAtUtc = collectedAtUtc,
                rawJson = "{}",
                syncStatus = MobileSyncStatus.PENDING
            )
    }
}
