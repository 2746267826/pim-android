package com.pim.app.mobile.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pim.app.data.AppDatabase
import com.pim.app.data.MobileAppMetadataEntity
import com.pim.app.data.MobileSyncStatus
import com.pim.app.data.MobileUsageEventEntity
import com.pim.app.data.PimDatabaseMigrations
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.mobile.usage.AppMetadataCollector
import com.pim.app.mobile.usage.UsageEventCollector
import com.pim.app.mobile.usage.UsageAccessChecker
import com.pim.app.ui.components.LegacyOutboxClaimDialog
import com.pim.app.ui.theme.PimTheme
import com.pim.core.auth.TokenManager
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.ApiService
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room/DAO/Coordinator integration with a synthetic local HTTP peer.
 *
 * The fixture intentionally uses the production coordinator, DAO, owner/server binding,
 * OkHttp/Retrofit stack, TokenManager, and stable-batch logic. Only the 40-hour data,
 * credentials, timestamps, and HTTP peer are synthetic.
 *
 * The full syncOnOpen claim branch is gated by Android UsageStats availability. This
 * bounded suite exercises the production binding decision and then seeds the same
 * persisted owner/server keys before starting the real outbox upload.
 */
@RunWith(AndroidJUnit4::class)
class MobileSyncUpgradeE2ETest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var deps: SyncDepsEntryPoint

    @Test
    fun migratedV3QueueNeedsExactClaimAndCancelKeepsRows() = runBlocking {
        val harness = newHarness("v3_claim") { database ->
            insertV3Event(database, V3_EVENT_TIME_UTC, "com.example.e2e.legacy")
            insertV3Summary(database, V3_EVENT_TIME_UTC, V3_EVENT_TIME_UTC + ONE_HOUR_MS)
            insertV3App(database, V3_EVENT_TIME_UTC)
        }
        try {
            assertEquals(1, harness.db.mobileDataDao().getUsageEventsBySyncStatus().size)
            assertEquals(1, harness.db.mobileDataDao().getUsageSummariesBySyncStatus().size)
            assertEquals(1, harness.db.mobileDataDao().getAppMetadataBySyncStatus().size)

            harness.clearBinding()
            harness.saveOwner(OWNER_A, TOKEN_A)

            val blocked = harness.coordinator.uploadQueuedUsage(
                deviceId = TEST_DEVICE_ID,
                attemptedAt = nowIso(),
                manualRecoveryRetry = false,
                maxBatchesPerRun = 1,
                maxDurationMs = 5_000
            )
            assertNotNull(blocked)
            assertEquals("usage-outbox-binding-blocked", blocked?.phase)
            assertEquals(0, harness.mock.usageRequests().size)
            assertNull(harness.binding())
            assertEquals(3, harness.pendingUsageCount())
            assertEquals(1, harness.db.mobileDataDao().getUsageEventsBySyncStatus(MobileSyncStatus.PENDING).size)
            assertEquals(1, harness.db.mobileDataDao().getUsageSummariesBySyncStatus(MobileSyncStatus.PENDING).size)
            assertEquals(1, harness.db.mobileDataDao().getAppMetadataBySyncStatus(MobileSyncStatus.PENDING).size)

            val preview = harness.coordinator.legacyOutboxRecoveryPreview()
            assertNotNull(preview)
            assertEquals(3, preview?.pendingCount)
            assertEquals(OWNER_A, preview?.ownerId)
            assertEquals(harness.origin, preview?.serverIdentity)
            val exactPreview = requireNotNull(preview)
            val cancelled = AtomicBoolean(false)
            val confirmed = AtomicBoolean(false)

            composeRule.setContent {
                PimTheme {
                    LegacyOutboxClaimDialog(
                        preview = exactPreview,
                        onConfirm = { confirmed.set(true) },
                        onDismiss = { cancelled.set(true) }
                    )
                }
            }
            composeRule.onNodeWithTag("legacy-outbox-claim-dialog").assertIsDisplayed()
            composeRule.onNodeWithText("取消", useUnmergedTree = true).performClick()
            composeRule.waitForIdle()

            assertTrue(cancelled.get())
            assertFalse(confirmed.get())
            assertEquals(0, harness.mock.usageRequests().size)
            assertNull(harness.binding())
            assertEquals(1, harness.db.mobileDataDao().getUsageEventsBySyncStatus(MobileSyncStatus.PENDING).size)

            val decision = resolveUsageOutboxBinding(
                stored = null,
                current = UsageOutboxOwnerBinding(harness.origin, OWNER_A),
                pendingCount = 3,
                claim = LegacyOutboxClaimRequest(harness.origin, OWNER_A)
            )
            assertTrue(decision.shouldBind)
            assertEquals(UsageOutboxBindingAction.ALLOWED, decision.action)
            // Same two keys the coordinator persists after a successful explicit claim.
            harness.saveBinding(OWNER_A)

            val sent = harness.coordinator.uploadQueuedUsage(
                deviceId = TEST_DEVICE_ID,
                attemptedAt = nowIso(),
                manualRecoveryRetry = false,
                maxBatchesPerRun = 3,
                maxDurationMs = 5_000
            )
            assertNotNull(sent)
            assertTrue(harness.mock.usageRequests().isNotEmpty())
            assertEquals("Bearer $TOKEN_A", harness.mock.usageRequests().first().authorization)
            assertEquals(0, harness.pendingUsageCount())
        } finally {
            harness.close()
        }
    }

    @Test
    fun fortyHourQueueIsConservedInHalfOpenBoundedBatchesIncludingLastPoint() = runBlocking {
        val harness = newHarness("forty_hours")
        try {
            val startUtc = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli()
            val eventTimes = (0..40 step 2).map { hour -> startUtc + hour * ONE_HOUR_MS }
            val eventIds = harness.db.mobileDataDao().insertUsageEvents(
                eventTimes.map { time ->
                    mobileEvent(packageName = "com.example.e2e.queue", eventTimeUtc = time)
                }
            )
            harness.db.mobileDataDao().upsertAppMetadata(
                listOf(
                    MobileAppMetadataEntity(
                        packageName = "com.example.e2e.queue",
                        label = "E2E Queue",
                        versionName = "1.0",
                        versionCode = 7,
                        firstInstallTimeUtc = startUtc,
                        lastUpdateTimeUtc = startUtc,
                        isSystemApp = false,
                        collectedAtUtc = startUtc,
                        rawJson = """{"fixture":"forty-hours"}"""
                    )
                )
            )
            harness.saveOwner(OWNER_A, TOKEN_A)
            harness.saveBinding(OWNER_A)

            val state = harness.coordinator.uploadQueuedUsage(
                deviceId = TEST_DEVICE_ID,
                attemptedAt = nowIso(),
                manualRecoveryRetry = false,
                maxBatchesPerRun = 30,
                maxDurationMs = 120_000
            )
            assertNotNull(state)
            assertEquals(0, harness.pendingUsageCount())

            val requests = harness.mock.usageRequests()
            assertTrue("expected bounded multi-batch drain", requests.size >= 11)
            val seenEventKeys = mutableListOf<String>()
            for (request in requests) {
                val body = JSONObject(request.body)
                val start = Instant.parse(body.getString("sourceWindowStartUtc")).toEpochMilli()
                val end = Instant.parse(body.getString("sourceWindowEndUtc")).toEpochMilli()
                assertTrue("window must be half-open: $start/$end", end > start)
                assertTrue("window must be <= 2h: ${end - start}", end - start <= TWO_HOURS_MS)
                val events = body.getJSONArray("events")
                for (index in 0 until events.length()) {
                    seenEventKeys += events.getJSONObject(index).getString("clientItemKey")
                }
            }

            val expectedKeys = eventIds.map { it.toString() }.toSet()
            assertEquals(expectedKeys, seenEventKeys.toSet())
            assertEquals(eventIds.size, seenEventKeys.size)
            val lastKey = eventIds.last().toString()
            val lastBodyEvents = JSONObject(requests.last().body).getJSONArray("events")
            assertTrue(
                "the latest point must be present in the final request",
                (0 until lastBodyEvents.length()).any {
                    lastBodyEvents.getJSONObject(it).getString("clientItemKey") == lastKey
                }
            )
            assertEquals(eventIds.size + 1, state?.acceptedCount)
        } finally {
            harness.close()
        }
    }

    @Test
    fun lostResponseReplayReusesBatchIdAndDoesNotDuplicateRows() = runBlocking {
        val harness = newHarness("lost_response")
        try {
            val startUtc = Instant.parse("2026-10-03T00:00:00Z").toEpochMilli()
            harness.db.mobileDataDao().insertUsageEvents(
                (0..2).map { index ->
                    mobileEvent(
                        packageName = "com.example.e2e.replay",
                        eventTimeUtc = startUtc + index * 5L * 60_000L
                    )
                }
            )
            harness.db.mobileDataDao().upsertAppMetadata(
                listOf(
                    MobileAppMetadataEntity(
                        packageName = "com.example.e2e.replay",
                        label = "Replay",
                        versionCode = 1,
                        firstInstallTimeUtc = startUtc,
                        lastUpdateTimeUtc = startUtc,
                        isSystemApp = false,
                        collectedAtUtc = startUtc,
                        rawJson = """{"fixture":"replay"}"""
                    )
                )
            )
            harness.saveOwner(OWNER_A, TOKEN_A)
            harness.saveBinding(OWNER_A)
            harness.mock.mode = MockMode.FAIL_ONCE

            val first = harness.coordinator.uploadQueuedUsage(
                deviceId = TEST_DEVICE_ID,
                attemptedAt = nowIso(),
                manualRecoveryRetry = false,
                maxBatchesPerRun = 1,
                maxDurationMs = 5_000
            )
            assertNotNull(first)
            assertEquals(MobileSyncOutcome.RETRY, first?.outcome)
            assertEquals(4, harness.pendingUsageCount())

            harness.mock.mode = MockMode.ACK
            val second = harness.coordinator.uploadQueuedUsage(
                deviceId = TEST_DEVICE_ID,
                attemptedAt = nowIso(),
                manualRecoveryRetry = false,
                maxBatchesPerRun = 1,
                maxDurationMs = 5_000
            )
            assertNotNull(second)
            assertEquals(0, harness.pendingUsageCount())
            assertEquals(first?.lastBatchId, second?.lastBatchId)

            val requests = harness.mock.usageRequests()
            assertEquals(2, requests.size)
            assertEquals(
                JSONObject(requests[0].body).getString("clientBatchId"),
                JSONObject(requests[1].body).getString("clientBatchId")
            )
            assertEquals(usageKeys(requests[0].body), usageKeys(requests[1].body))
        } finally {
            harness.close()
        }
    }

    @Test
    fun unauthorizedThenOwnerSwitchKeepsQueueAndNeverUsesNewToken() = runBlocking {
        val harness = newHarness("owner_switch")
        try {
            val startUtc = Instant.parse("2026-10-04T00:00:00Z").toEpochMilli()
            harness.db.mobileDataDao().insertUsageEvents(
                listOf(
                    mobileEvent("com.example.e2e.owner", startUtc),
                    mobileEvent("com.example.e2e.owner", startUtc + 10L * 60_000L)
                )
            )
            harness.saveOwner(OWNER_A, TOKEN_A)
            harness.saveBinding(OWNER_A)
            harness.mock.mode = MockMode.UNAUTHORIZED

            val first = harness.coordinator.uploadQueuedUsage(
                deviceId = TEST_DEVICE_ID,
                attemptedAt = nowIso(),
                manualRecoveryRetry = false,
                maxBatchesPerRun = 1,
                maxDurationMs = 5_000
            )
            assertNotNull(first)
            assertEquals(MobileSyncOutcome.BLOCKED, first?.outcome)
            assertEquals(2, harness.pendingUsageCount())

            harness.saveOwner(OWNER_B, TOKEN_B)
            val second = harness.coordinator.uploadQueuedUsage(
                deviceId = TEST_DEVICE_ID,
                attemptedAt = nowIso(),
                manualRecoveryRetry = false,
                maxBatchesPerRun = 1,
                maxDurationMs = 5_000
            )
            assertNotNull(second)
            assertEquals("usage-outbox-binding-blocked", second?.phase)
            assertEquals(2, harness.pendingUsageCount())
            val requests = harness.mock.usageRequests()
            assertEquals(1, requests.size)
            assertEquals("Bearer $TOKEN_A", requests.single().authorization)
            assertFalse(requests.single().authorization.contains(TOKEN_B))
        } finally {
            harness.close()
        }
    }

    private fun newHarness(
        name: String,
        seedV3: (SupportSQLiteDatabase) -> Unit = {}
    ): Harness {
        if (!::deps.isInitialized) {
            deps = EntryPointAccessors.fromApplication(context, SyncDepsEntryPoint::class.java)
        }
        val dbName = "e2e_${name}_${System.nanoTime()}"
        context.deleteDatabase(dbName)
        createV3Database(dbName, seedV3)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*PimDatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        // Force the real 3 -> 4 migration before any product DAO call.
        db.openHelper.writableDatabase

        val mock = MockApiServer()
        val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        val prefsSnapshot = prefs.all.toMap()
        prefs.edit().clear().commit()

        val settingsStore = deps.serverSettingsStore()
        val tokenManager = deps.tokenManager()
        val oldBaseUrl = settingsStore.getBaseUrl()
        val oldSession = tokenManager.snapshot()
        settingsStore.setBaseUrl(mock.baseUrl)

        val coordinator = MobileSyncCoordinator(
            context = context,
            api = deps.apiService(),
            apiClientProvider = deps.apiClientProvider(),
            tokenManager = tokenManager,
            usageAccessChecker = deps.usageAccessChecker(),
            usageEventCollector = deps.usageEventCollector(),
            appMetadataCollector = deps.appMetadataCollector(),
            database = db,
            logs = deps.structuredLogRepository(),
            heartbeatReporter = deps.mobileHeartbeatReporter(),
            serverSettingsStore = settingsStore,
            locationUploadCoordinator = deps.locationUploadCoordinator(),
            forensicUploadCoordinator = deps.forensicUploadCoordinator(),
            wakeHeartbeatRecorder = deps.wakeHeartbeatRecorder(),
            syncScheduler = object : Provider<MobileSyncScheduler> {
                override fun get(): MobileSyncScheduler = deps.mobileSyncScheduler()
            },
            deviceIdentityProvider = deps.deviceIdentityProvider()
        )

        return Harness(
            context = context,
            dbName = dbName,
            db = db,
            mock = mock,
            prefs = prefs,
            prefsSnapshot = prefsSnapshot,
            settingsStore = settingsStore,
            oldBaseUrl = oldBaseUrl,
            tokenManager = tokenManager,
            oldSession = oldSession,
            coordinator = coordinator,
            origin = mock.origin
        )
    }

    private fun createV3Database(name: String, seed: (SupportSQLiteDatabase) -> Unit) {
        @Suppress("DEPRECATION")
        val helper = MigrationTestHelper(instrumentation, AppDatabase::class.java)
        val database = helper.createDatabase(name, 3)
        try {
            seed(database)
        } finally {
            database.close()
        }
    }

    private fun mobileEvent(
        packageName: String,
        eventTimeUtc: Long
    ) = MobileUsageEventEntity(
        packageName = packageName,
        className = "com.example.e2e.Activity",
        eventType = 1,
        eventName = "ACTIVITY_RESUMED",
        eventTimeUtc = eventTimeUtc,
        source = "usage-stats",
        sourceWindowStartUtc = eventTimeUtc,
        sourceWindowEndUtc = eventTimeUtc + 1,
        collectedAtUtc = eventTimeUtc,
        rawJson = """{"fixture":"mobile-sync-e2e"}""",
        syncStatus = MobileSyncStatus.PENDING
    )

    private fun insertV3Event(database: SupportSQLiteDatabase, eventTimeUtc: Long, packageName: String) {
        database.execSQL(
            """
            INSERT INTO mobile_usage_events
            (package_name, class_name, event_type, event_name, event_time_utc, source,
             source_window_start_utc, source_window_end_utc, collected_at_utc, raw_json,
             sync_status, last_error, created_at_utc, updated_at_utc)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                packageName, "com.example.e2e.Activity", 1, "ACTIVITY_RESUMED",
                eventTimeUtc, "usage-stats", eventTimeUtc, eventTimeUtc + 1,
                eventTimeUtc, """{"fixture":"v3-event"}""", MobileSyncStatus.PENDING,
                null, eventTimeUtc, eventTimeUtc
            )
        )
    }

    private fun insertV3Summary(database: SupportSQLiteDatabase, startUtc: Long, endUtc: Long) {
        database.execSQL(
            """
            INSERT INTO mobile_usage_summaries
            (package_name, window_start_utc, window_end_utc, total_time_foreground_ms,
             last_time_used_utc, first_time_stamp_utc, last_time_stamp_utc, source,
             source_window_start_utc, source_window_end_utc, collected_at_utc, raw_json,
             sync_status, last_error, created_at_utc, updated_at_utc)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "com.example.e2e.legacy", startUtc, endUtc, endUtc - startUtc,
                endUtc, startUtc, endUtc, "usage-stats", startUtc, endUtc, endUtc,
                """{"fixture":"v3-summary"}""", MobileSyncStatus.PENDING, null,
                startUtc, endUtc
            )
        )
    }

    private fun insertV3App(database: SupportSQLiteDatabase, collectedAtUtc: Long) {
        database.execSQL(
            """
            INSERT INTO mobile_app_metadata
            (package_name, label, version_name, version_code, first_install_time_utc,
             last_update_time_utc, is_system_app, category, installer_package_name,
             collected_at_utc, raw_json, sync_status, last_error, created_at_utc, updated_at_utc)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "com.example.e2e.legacy", "Legacy", "1.0", 1, collectedAtUtc,
                collectedAtUtc, 0, null, null, collectedAtUtc,
                """{"fixture":"v3-app"}""", MobileSyncStatus.PENDING, null,
                collectedAtUtc, collectedAtUtc
            )
        )
    }

    private fun usageKeys(body: String): Set<String> {
        val objectBody = JSONObject(body)
        val keys = linkedSetOf<String>()
        val events = objectBody.getJSONArray("events")
        for (index in 0 until events.length()) {
            keys += "usage-event:${events.getJSONObject(index).getString("clientItemKey")}"
        }
        val summaries = objectBody.optJSONArray("fallbackSummaries") ?: JSONArray()
        for (index in 0 until summaries.length()) {
            keys += "usage-summary:${summaries.getJSONObject(index).getString("clientItemKey")}"
        }
        val apps = objectBody.getJSONArray("apps")
        for (index in 0 until apps.length()) {
            keys += "app-metadata:${apps.getJSONObject(index).getString("clientItemKey")}"
        }
        return keys
    }

    private fun nowIso(): String = Instant.now().toString()

    private class Harness(
        private val context: Context,
        private val dbName: String,
        val db: AppDatabase,
        val mock: MockApiServer,
        private val prefs: SharedPreferences,
        private val prefsSnapshot: Map<String, *>,
        private val settingsStore: ServerSettingsStore,
        private val oldBaseUrl: String,
        val tokenManager: TokenManager,
        private val oldSession: com.pim.core.auth.AuthSessionSnapshot,
        val coordinator: MobileSyncCoordinator,
        val origin: String
    ) {
        fun saveOwner(ownerId: String, token: String) {
            assertTrue(
                tokenManager.saveOwned(
                    accessToken = token,
                    refreshToken = "$token-refresh",
                    expiresAtUtcMillis = System.currentTimeMillis() + ONE_HOUR_MS,
                    serverIdentity = origin,
                    ownerId = ownerId,
                    ownerName = "E2E $ownerId"
                )
            )
        }

        fun clearBinding() {
            prefs.edit()
                .remove("usage_outbox_server_identity")
                .remove("usage_outbox_owner_id")
                .commit()
        }

        fun saveBinding(ownerId: String) {
            prefs.edit()
                .putString("usage_outbox_server_identity", origin)
                .putString("usage_outbox_owner_id", ownerId)
                .commit()
        }

        fun binding(): Pair<String, String>? {
            val server = prefs.getString("usage_outbox_server_identity", null) ?: return null
            val owner = prefs.getString("usage_outbox_owner_id", null) ?: return null
            return server to owner
        }

        fun pendingUsageCount(): Int = runBlocking {
            db.mobileDataDao().pendingUsageEventCount().first() +
                db.mobileDataDao().pendingUsageSummaryCount().first() +
                db.mobileDataDao().pendingAppMetadataCount().first()
        }

        fun close() {
            runCatching { db.close() }
            runCatching { mock.close() }
            prefs.edit().clear().apply {
                prefsSnapshot.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
            runCatching {
                if (oldBaseUrl.isBlank()) {
                    context.getSharedPreferences("pim_server_settings", Context.MODE_PRIVATE)
                        .edit()
                        .remove("server_base_url")
                        .commit()
                } else {
                    settingsStore.setBaseUrl(oldBaseUrl)
                }
            }
            runCatching {
                tokenManager.clear()
                val tokens = oldSession.tokens
                val oldServerIdentity = oldSession.serverIdentity
                val oldOwnerId = oldSession.ownerId
                if (tokens != null && oldServerIdentity != null) {
                    if (oldOwnerId != null) {
                        tokenManager.saveOwned(
                            tokens.accessToken,
                            tokens.refreshToken,
                            tokens.expiresAtUtcMillis,
                            oldServerIdentity,
                            oldOwnerId,
                            oldSession.ownerName
                        )
                    } else {
                        tokenManager.save(
                            tokens.accessToken,
                            tokens.refreshToken,
                            tokens.expiresAtUtcMillis,
                            oldServerIdentity
                        )
                    }
                }
            }
            context.deleteDatabase(dbName)
        }
    }

    private enum class MockMode { ACK, FAIL_ONCE, UNAUTHORIZED }

    private data class UsageRequest(
        val body: String,
        val authorization: String
    )

    private class MockApiServer {
        private val server = MockWebServer()
        private val requests = Collections.synchronizedList(mutableListOf<UsageRequest>())
        private val usageAttempts = AtomicInteger(0)

        @Volatile
        var mode: MockMode = MockMode.ACK

        val baseUrl: String
        val origin: String

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path.endsWith("/mobile/usage/events")) {
                        val body = request.body.readUtf8()
                        requests += UsageRequest(body, request.getHeader("Authorization").orEmpty())
                        val attempt = usageAttempts.incrementAndGet()
                        if (mode == MockMode.UNAUTHORIZED) {
                            return jsonResponse(401, """{"message":"unauthorized"}""")
                        }
                        if (mode == MockMode.FAIL_ONCE && attempt == 1) {
                            return jsonResponse(500, """{"message":"lost-response"}""")
                        }
                        return jsonResponse(200, acknowledged(body))
                    }
                    if (path.endsWith("/auth/refresh")) {
                        return jsonResponse(401, """{"message":"refresh rejected"}""")
                    }
                    return jsonResponse(200, """{"code":0,"data":null,"message":"ok","timestamp":"2026-10-30T00:00:00Z"}""")
                }
            }
            server.start()
            baseUrl = server.url("/api/v1/").toString()
            origin = PimServerEndpoints.from(baseUrl).trustedOrigin
        }

        fun usageRequests(): List<UsageRequest> = synchronized(requests) { requests.toList() }

        fun close() {
            server.shutdown()
        }

        private fun acknowledged(body: String): String {
            val request = JSONObject(body)
            val itemResults = JSONArray()
            val events = request.getJSONArray("events")
            for (index in 0 until events.length()) {
                itemResults.put(
                    itemResult(
                        entityType = "usage-event",
                        clientItemKey = events.getJSONObject(index).optString("clientItemKey")
                    )
                )
            }
            val summaries = request.optJSONArray("fallbackSummaries") ?: JSONArray()
            for (index in 0 until summaries.length()) {
                itemResults.put(
                    itemResult(
                        entityType = "usage-summary",
                        clientItemKey = summaries.getJSONObject(index).optString("clientItemKey")
                    )
                )
            }
            val apps = request.getJSONArray("apps")
            for (index in 0 until apps.length()) {
                val app = apps.getJSONObject(index)
                itemResults.put(
                    itemResult(
                        entityType = "app-metadata",
                        clientItemKey = app.optString("clientItemKey")
                            .ifBlank { "${app.getString("packageName")}@${app.getLong("versionCode")}" }
                    )
                )
            }
            return JSONObject()
                .put("code", 0)
                .put(
                    "data",
                    JSONObject()
                        .put("acceptedCount", itemResults.length())
                        .put("batchId", request.optString("clientBatchId"))
                        .put("failedCount", 0)
                        .put("itemResults", itemResults)
                        .put("rejectedCount", 0)
                        .put("skippedCount", 0)
                )
                .put("message", "ok")
                .put("timestamp", "2026-10-30T00:00:00Z")
                .toString()
        }

        private fun itemResult(entityType: String, clientItemKey: String): JSONObject =
            JSONObject()
                .put("clientItemKey", clientItemKey)
                .put("code", "accepted")
                .put("entityType", entityType)
                .put("message", "accepted")
                .put("outcome", "accepted")

        private fun jsonResponse(code: Int, body: String): MockResponse =
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
    }

    companion object {
        private const val TEST_DEVICE_ID = "android-e2e-synthetic"
        private const val OWNER_A = "11111111-1111-4111-8111-111111111111"
        private const val OWNER_B = "22222222-2222-4222-8222-222222222222"
        private const val TOKEN_A = "e2e-owner-a-token"
        private const val TOKEN_B = "e2e-owner-b-token"
        private const val ONE_HOUR_MS = 60L * 60L * 1000L
        private const val TWO_HOURS_MS = 2L * ONE_HOUR_MS
        private const val V3_EVENT_TIME_UTC = 1_790_000_000_000L
        private const val SYNC_PREFS_NAME = "pim_mobile_sync_state"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SyncDepsEntryPoint {
    fun apiService(): ApiService
    fun apiClientProvider(): ApiClientProvider
    fun tokenManager(): TokenManager
    fun usageAccessChecker(): UsageAccessChecker
    fun usageEventCollector(): UsageEventCollector
    fun appMetadataCollector(): AppMetadataCollector
    fun structuredLogRepository(): StructuredLogRepository
    fun mobileHeartbeatReporter(): MobileHeartbeatReporter
    fun serverSettingsStore(): ServerSettingsStore
    fun locationUploadCoordinator(): LocationUploadCoordinator
    fun forensicUploadCoordinator(): com.pim.app.forensics.ForensicUploadCoordinator
    fun wakeHeartbeatRecorder(): com.pim.app.forensics.WakeHeartbeatRecorder
    fun mobileSyncScheduler(): MobileSyncScheduler
    fun deviceIdentityProvider(): DeviceIdentityProvider
}
