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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
    private lateinit var usageServer: RecordingUsageServer
    private var testNowMillis: Long = 1_750_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        usageServer = RecordingUsageServer()

        val authPreferences = context.getSharedPreferences("queued-upload-auth", Context.MODE_PRIVATE)
        val tokenManager = TokenManager(QueuedUploadSecurePreferencesFactory(authPreferences))
        val serverSettingsStore = ServerSettingsStore(context, tokenManager)
        val serverUrl = serverSettingsStore.setBaseUrl(usageServer.baseUrl)
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
            .addInterceptor { chain ->
                if (usageServer.throwSessionChanged) throw UploadSessionChangedException()
                chain.proceed(chain.request())
            }
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
        coordinator.nowUtcMillis = { testNowMillis }
    }

    @After
    fun tearDown() {
        db.close()
        usageServer.close()
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
        assertTrue(usageServer.requests.size >= 2)
        assertTrue(usageServer.requests.all { it.totalCount <= USAGE_BATCH_LIMIT })
        assertEquals(
            expectedKeys,
            usageServer.requests.flatMap { it.keys }.toSet()
        )
        assertEquals(
            expectedKeys.size,
            usageServer.requests.sumOf { it.totalCount }
        )
        assertEquals(0, pendingUsageRemaining(db.mobileDataDao()))
        usageServer.requests.forEach { request ->
            val span = Instant.parse(request.sourceWindowEndUtc).toEpochMilli() -
                Instant.parse(request.sourceWindowStartUtc).toEpochMilli()
            assertTrue(span in 1..USAGE_BATCH_MAX_WINDOW_MS)
        }
        assertEquals(
            1,
            usageServer.requests.count { request ->
                request.fallbackSummaryKeys.contains("$dailySummaryId")
            }
        )
    }

    @Test
    fun `HTTP 202 full envelope with four unknown fields keeps Room pending and recovers on same batch`() = runTest {
        val eventIds = db.mobileDataDao().insertUsageEvents(
            listOf(
                usageEvent(1_750_000_000_000L, "com.processing.one"),
                usageEvent(1_750_000_100_000L, "com.processing.two")
            )
        )
        usageServer.responses += RecordingUsageServer.processing202
        usageServer.responses += RecordingUsageServer.terminal200

        val processing = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "processing-client-device",
                attemptedAt = "2026-10-30T04:00:20Z",
                manualRecoveryRetry = false,
                maxBatchesPerRun = 1
            )
        )

        assertEquals(MobileSyncOutcome.DEFERRED, processing.outcome)
        assertEquals("waiting-not-before", processing.phase)
        assertEquals("server-processing", processing.lastBatchStatus)
        assertEquals("server-processing-202", processing.lastError)
        assertEquals(2, processing.failedCount)
        assertEquals(1, usageServer.requests.size)
        val firstBatchId = usageServer.requests.single().clientBatchId
        assertEquals(2, usageServer.requests.single().totalCount)
        assertEquals(
            eventIds,
            db.mobileDataDao().getUsageEventsBySyncStatus(MobileSyncStatus.PENDING).map { it.id }
        )
        assertEquals(
            listOf(null, null),
            db.mobileDataDao().getUsageEventsBySyncStatus().map { it.lastError }
        )
        assertEquals(2, pendingUsageRemaining(db.mobileDataDao()))

        testNowMillis += 60_000L
        val terminal = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "processing-client-device",
                attemptedAt = "2026-10-30T04:00:35Z",
                manualRecoveryRetry = false,
                maxBatchesPerRun = 1
            )
        )

        assertEquals(MobileSyncOutcome.SUCCESS, terminal.outcome)
        assertEquals(0, pendingUsageRemaining(db.mobileDataDao()))
        assertEquals(2, usageServer.requests.size)
        assertEquals(firstBatchId, usageServer.requests[1].clientBatchId)
        assertEquals(
            usageServer.requests[0].keys,
            usageServer.requests[1].keys
        )
    }

    @Test
    fun `HTTP 202 envelope with null data is an explicit RETRY and keeps pending row`() = runTest {
        val eventId = db.mobileDataDao().insertUsageEvents(
            listOf(usageEvent(1_750_000_200_000L, "com.processing.null.data"))
        ).single()
        usageServer.responses += RecordingUsageServer.nullData202

        val state = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "null-data-device",
                attemptedAt = "2026-10-30T04:00:20Z",
                manualRecoveryRetry = false,
                maxBatchesPerRun = 1
            )
        )

        assertEquals(MobileSyncOutcome.DEFERRED, state.outcome)
        assertEquals("waiting-not-before", state.phase)
        assertEquals("server-processing-202", state.lastError)
        assertEquals(eventId, db.mobileDataDao().getUsageEventsBySyncStatus(MobileSyncStatus.PENDING).single().id)
        assertEquals(1, pendingUsageRemaining(db.mobileDataDao()))
    }

    @Test
    fun `HTTP 202 empty body is classified BLOCKED and keeps the local pending row`() = runTest {
        val eventId = db.mobileDataDao().insertUsageEvents(
            listOf(usageEvent(1_750_000_300_000L, "com.processing.empty.body"))
        ).single()
        usageServer.responses += RecordingUsageServer.emptyBody202

        val state = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "empty-body-device",
                attemptedAt = "2026-10-30T04:00:20Z",
                manualRecoveryRetry = false,
                maxBatchesPerRun = 1
            )
        )

        assertEquals(MobileSyncOutcome.DEFERRED, state.outcome)
        assertEquals("waiting-not-before", state.phase)
        assertTrue(
            "empty-body 202 must retain the Retry-After deadline via the transport signal",
            state.lastErrorDetail.orEmpty().contains("server-processing-202-malformed")
        )
        assertEquals(eventId, db.mobileDataDao().getUsageEventsBySyncStatus(MobileSyncStatus.PENDING).single().id)
        assertEquals(1, pendingUsageRemaining(db.mobileDataDao()))
    }

    @Test
    fun `lease lost retries the same batch id and does not persist a permanent block`() = runTest {
        val eventId = db.mobileDataDao().insertUsageEvents(
            listOf(usageEvent(1_750_000_000_000L, "com.lease.lost"))
        ).single()
        usageServer.responses += RecordingUsageServer.leaseLost409
        usageServer.responses += RecordingUsageServer.terminal200

        val first = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "lease-device",
                attemptedAt = "2026-10-29T00:00:00Z",
                manualRecoveryRetry = false
            )
        )

        assertEquals(MobileSyncOutcome.RETRY, first.outcome)
        assertEquals(1, usageServer.requests.size)
        val firstBatchId = usageServer.requests.single().clientBatchId
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
        assertEquals(2, usageServer.requests.size)
        assertEquals(firstBatchId, usageServer.requests[1].clientBatchId)
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

        assertEquals(1, usageServer.requests.size)
        assertTrue(usageServer.requests.all { it.totalCount <= USAGE_BATCH_LIMIT })
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
        assertEquals(0, usageServer.requests.size)
        assertEquals(eventId, db.mobileDataDao().getUsageEventsBySyncStatus().single().id)
    }

    @Test
    fun `bound session change blocks binding and keeps pending without false acknowledgement`() = runTest {
        val eventId = db.mobileDataDao().insertUsageEvents(
            listOf(usageEvent(1_750_000_000_000L, "com.session.changed"))
        ).single()
        usageServer.throwSessionChanged = true

        val state = checkNotNull(
            coordinator.uploadQueuedUsage(
                deviceId = "binding-device",
                attemptedAt = "2026-10-29T00:00:00Z",
                manualRecoveryRetry = false
            )
        )

        assertEquals("usage-outbox-binding-blocked", state.phase)
        assertEquals(MobileSyncOutcome.BLOCKED, state.outcome)
        assertEquals(0, usageServer.requests.size)
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
        if (method.declaringClass == Any::class.java) {
            when (method.name) {
                "toString" -> "UnusedApiService"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected Object call: ${method.name}")
            }
        } else {
            // Bound usage upload always uses the real REST service, never this global proxy.
            error("Unexpected global API call: ${method.name}")
        }
    } as ApiService

    private class RecordingUsageServer : AutoCloseable {
        private val server = MockWebServer()
        val requests = CopyOnWriteArrayList<RecordedUsageRequest>()
        val responses = CopyOnWriteArrayList<ServerResponse>()
        var throwSessionChanged = false
        private val json = Json { ignoreUnknownKeys = true }
        private val sequence = AtomicInteger(0)

        val baseUrl: String

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    assertEquals("/api/v1/mobile/usage/events", request.requestUrl?.encodedPath)
                    assertEquals("POST", request.method)
                    val bodyText = request.body.readUtf8()
                    val upload = json.decodeFromString<MobileUsageEventsUploadRequest>(bodyText)
                    requests += RecordedUsageRequest(upload)
                    val configured = responses.getOrNull(sequence.getAndIncrement())
                        ?: ServerResponse(200, json.encodeToString(terminal(upload)))
                    val responseBody = configured.body
                        ?: if (configured.generateTerminal) json.encodeToString(terminal(upload)) else null
                    return MockResponse()
                        .setResponseCode(configured.httpStatus)
                        .setHeader("Content-Type", "application/json")
                        .apply {
                            configured.retryAfter?.let { setHeader("Retry-After", it) }
                            responseBody?.let(::setBody)
                        }
                }
            }
            server.start()
            baseUrl = server.url("/api/v1/").toString()
        }

        override fun close() = server.shutdown()

        private fun terminal(upload: MobileUsageEventsUploadRequest): MobileUsageIngestResultApiResponse {
            val itemResults = buildList {
                upload.events.forEach { event -> add(accepted(event.clientItemKey, "usage-event")) }
                upload.fallbackSummaries.forEach { summary ->
                    add(accepted(summary.clientItemKey, "usage-summary"))
                }
                upload.apps.forEach { app ->
                    add(accepted("${app.packageName}@${app.versionCode}", "app-metadata"))
                }
            }
            return MobileUsageIngestResultApiResponse(
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

        data class ServerResponse(
            val httpStatus: Int,
            val body: String?,
            val retryAfter: String? = null,
            val generateTerminal: Boolean = false
        )

        companion object {
            // Intentional future-response compatibility fixture: none of these four fields
            // is read by or added to the currently generated DTO.
            val processing202 = ServerResponse(
                httpStatus = 202,
                body = """
                    {
                      "code": 0,
                      "message": "Batch is already being processed.",
                      "data": {
                        "batchId": "fixture-original",
                        "acceptedCount": 0,
                        "skippedCount": 0,
                        "rejectedCount": 0,
                        "failedCount": 2,
                        "itemResults": [],
                        "processingState": "in-flight",
                        "retryAfterSeconds": 15,
                        "leaseUntilUtc": "2026-10-30T04:15:00Z",
                        "retryAtUtc": null
                      },
                      "timestamp": "2026-10-30T04:00:20Z"
                    }
                """.trimIndent(),
                retryAfter = "15"
            )
            val terminal200 = ServerResponse(httpStatus = 200, body = null, generateTerminal = true)
            val nullData202 = ServerResponse(
                httpStatus = 202,
                body = """{"code":0,"data":null,"message":"accepted without data","timestamp":"2026-10-30T04:00:20Z"}""",
                retryAfter = "15"
            )
            val emptyBody202 = ServerResponse(httpStatus = 202, body = "", retryAfter = "15")
            val leaseLost409 = ServerResponse(
                httpStatus = 409,
                body = """{"code":40904,"message":"lease lost","data":null,"timestamp":"2026-10-29T00:00:00Z"}"""
            )
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
