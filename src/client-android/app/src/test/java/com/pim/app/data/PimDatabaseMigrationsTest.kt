package com.pim.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class PimDatabaseMigrationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-test-${UUID.randomUUID()}"
    private val currentDbName = "current-schema-${UUID.randomUUID()}"
    private val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
        context.deleteDatabase(currentDbName)
    }

    @Test
    fun migrateFrom2To3_preservesOldData_addsNewColumnsWithDefaults() {
        helper.createDatabase(dbName, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO mobile_location_points
                    (latitude, longitude, altitude_meters, accuracy_meters,
                     speed_meters_per_second, bearing_degrees, provider,
                     recorded_at_utc, source, collected_at_utc, raw_json,
                     sync_status, last_error, created_at_utc, updated_at_utc)
                VALUES
                    (37.7749, -122.4194, 10.5, 5.0, 1.2, 45.0, 'gps',
                     1000000, 'test', 1000000, '{}',
                     'pending', null, 1000000, 1000000)
                """.trimIndent()
            )
        }
        helper.runMigrationsAndValidate(dbName, 3, true, PimDatabaseMigrations.MIGRATION_2_3).use { db ->
            val cursor = db.query("SELECT * FROM mobile_location_points")
            cursor.use {
                it.moveToFirst()
                assertEquals(37.7749, it.getDouble(it.getColumnIndexOrThrow("latitude")), 0.0001)
                assertEquals(-122.4194, it.getDouble(it.getColumnIndexOrThrow("longitude")), 0.0001)
                assertEquals(10.5, it.getDouble(it.getColumnIndexOrThrow("altitude_meters")), 0.0001)
                assertEquals(1000000L, it.getLong(it.getColumnIndexOrThrow("recorded_at_utc")))
                assertEquals("test", it.getString(it.getColumnIndexOrThrow("source")))
                assertTrue(it.isNull(it.getColumnIndexOrThrow("submitted_at_utc")))
                assertEquals(
                    "PowerSavingNormal",
                    it.getString(it.getColumnIndexOrThrow("policy_mode"))
                )
                assertEquals(0, it.getInt(it.getColumnIndexOrThrow("schedule_low_frequency")))
                assertTrue(it.isNull(it.getColumnIndexOrThrow("motion_state")))
                assertEquals("[]", it.getString(it.getColumnIndexOrThrow("quality_flags")))
            }
        }
    }

    @Test
    fun migrateFrom2To3_newTablesExistAndCanBeUsed() {
        helper.createDatabase(dbName, 2).use { }
        helper.runMigrationsAndValidate(dbName, 3, true, PimDatabaseMigrations.MIGRATION_2_3).use { db ->
            db.execSQL(
                """
                INSERT INTO mobile_location_dropped_diagnostics
                    (recorded_at_utc, provider, accuracy_meters, policy_mode, reason, created_at_utc)
                VALUES (1000, 'gps', 10.0, 'Active', 'test_drop', 1000)
                """.trimIndent()
            )
            var cursor = db.query("SELECT COUNT(*) FROM mobile_location_dropped_diagnostics")
            cursor.use {
                it.moveToFirst()
                assertEquals(1L, it.getLong(0))
            }
            db.execSQL(
                """
                INSERT INTO mobile_location_policy_transitions
                    (from_mode, to_mode, reason, occurred_at_utc)
                VALUES ('PowerSavingNormal', 'Active', 'test_transition', 1000)
                """.trimIndent()
            )
            cursor = db.query("SELECT COUNT(*) FROM mobile_location_policy_transitions")
            cursor.use {
                it.moveToFirst()
                assertEquals(1L, it.getLong(0))
            }
            cursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='mobile_location_dropped_diagnostics'"
            )
            val droppedIndices = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) { droppedIndices.add(it.getString(0)) }
            }
            assertTrue(droppedIndices.contains("index_mobile_location_dropped_diagnostics_recorded_at_utc"))
            cursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='mobile_location_policy_transitions'"
            )
            val transitionIndices = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) { transitionIndices.add(it.getString(0)) }
            }
            assertTrue(transitionIndices.contains("index_mobile_location_policy_transitions_occurred_at_utc"))
        }
    }

    @Test
    fun currentV5Schema_hasVersion5And11TablesIncludingForensicEvents() {
        val roomDatabase = Room.databaseBuilder(context, AppDatabase::class.java, currentDbName)
            .addMigrations(*PimDatabaseMigrations.ALL)
            .allowMainThreadQueries()
            .build()
        try {
            val db = roomDatabase.openHelper.writableDatabase
            var cursor = db.query("PRAGMA user_version")
            cursor.use {
                it.moveToFirst()
                assertEquals(5L, it.getLong(0))
            }
            cursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'room%' AND name NOT LIKE 'sqlite%' AND name NOT LIKE 'android%'"
            )
            val tables = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) { tables.add(it.getString(0)) }
            }
            assertEquals(11, tables.size)
            assertTrue(tables.containsAll(
                listOf(
                    "app_usage", "mobile_usage_events", "mobile_usage_summaries",
                    "mobile_app_metadata", "mobile_location_points",
                    "mobile_location_dropped_diagnostics", "mobile_location_policy_transitions",
                    "mobile_sync_batches", "mobile_logs", "mobile_device_profile",
                    // 阶段一取证（REQ-1 ~ REQ-6）：只新增这一张表。
                    "mobile_forensic_events"
                )
            ))
        } finally {
            roomDatabase.close()
        }
    }

    @Test
    fun migrateFrom3To4_addsForensicTableAndKeepsExistingTablesUntouched() {
        // 升级路径：v3 已有设备上的既有数据必须原样保留，新表可写可查。
        helper.createDatabase(dbName, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO mobile_location_dropped_diagnostics
                    (recorded_at_utc, provider, accuracy_meters, policy_mode, reason, created_at_utc)
                VALUES (1000000, 'gps', 55.0, 'PowerSavingNormal', 'horizontal-accuracy-too-low', 1000000)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(dbName, 4, true, PimDatabaseMigrations.MIGRATION_3_4).use { db ->
            var cursor = db.query("SELECT COUNT(*) FROM mobile_location_dropped_diagnostics")
            cursor.use {
                it.moveToFirst()
                assertEquals(1L, it.getLong(0))
            }

            db.execSQL(
                """
                INSERT INTO mobile_forensic_events
                    (event_type, occurred_at_utc, client_item_key, payload_json, sync_status, last_error, created_at_utc)
                VALUES ('heartbeat', 1000000, 'heartbeat-1000', '{}', 'pending', null, 1000000)
                """.trimIndent()
            )
            cursor = db.query(
                "SELECT event_type, sync_status FROM mobile_forensic_events WHERE client_item_key = 'heartbeat-1000'"
            )
            cursor.use {
                it.moveToFirst()
                assertEquals("heartbeat", it.getString(0))
                assertEquals("pending", it.getString(1))
            }

            // 幂等键必须是唯一索引，否则 AC-3.3 / AC-5.2 的去重无处落地。
            val duplicate = runCatching {
                db.execSQL(
                    """
                    INSERT INTO mobile_forensic_events
                        (event_type, occurred_at_utc, client_item_key, payload_json, sync_status, last_error, created_at_utc)
                    VALUES ('heartbeat', 1000001, 'heartbeat-1000', '{}', 'pending', null, 1000001)
                    """.trimIndent()
                )
            }
            assertTrue(duplicate.isFailure)
        }
    }

    @Test
    fun migrateFrom4To5_addsIdempotencyKeyWithoutDeletingExistingDroppedRows() {
        helper.createDatabase(dbName, 4).use { db ->
            repeat(2) {
                db.execSQL(
                    """
                    INSERT INTO mobile_location_dropped_diagnostics
                        (recorded_at_utc, provider, accuracy_meters, policy_mode, reason, created_at_utc)
                    VALUES (1791396035696, 'network', 30.0, 'PowerSavingNormal',
                            'passive-horizontal-accuracy-too-low', 1791396035696)
                    """.trimIndent()
                )
            }
        }

        helper.runMigrationsAndValidate(
            dbName,
            5,
            true,
            PimDatabaseMigrations.MIGRATION_4_5
        ).use { db ->
            db.query("SELECT COUNT(*) FROM mobile_location_dropped_diagnostics").use {
                it.moveToFirst()
                assertEquals("migration must not destroy pre-existing diagnostics", 2L, it.getLong(0))
            }
            db.query(
                "SELECT COUNT(*) FROM mobile_location_dropped_diagnostics WHERE fix_key IS NOT NULL"
            ).use {
                it.moveToFirst()
                assertEquals("legacy rows remain untouched until a new write", 0L, it.getLong(0))
            }
            db.query(
                """
                SELECT
                    COUNT(*) AS total_count,
                    COALESCE(SUM(CASE WHEN fix_key IS NOT NULL THEN 1 ELSE 0 END), 0) AS keyed_count,
                    COALESCE(SUM(CASE WHEN fix_key IS NULL THEN 1 ELSE 0 END), 0) AS unkeyed_count,
                    COUNT(DISTINCT fix_key) AS distinct_keyed_count
                FROM mobile_location_dropped_diagnostics
                """.trimIndent()
            ).use {
                it.moveToFirst()
                assertEquals(2L, it.getLong(it.getColumnIndexOrThrow("total_count")))
                assertEquals(0L, it.getLong(it.getColumnIndexOrThrow("keyed_count")))
                assertEquals(2L, it.getLong(it.getColumnIndexOrThrow("unkeyed_count")))
                assertEquals(0L, it.getLong(it.getColumnIndexOrThrow("distinct_keyed_count")))
            }

            db.execSQL(
                """
                INSERT INTO mobile_location_dropped_diagnostics
                    (fix_key, recorded_at_utc, provider, accuracy_meters, policy_mode, reason, created_at_utc)
                VALUES ('same-fix', 1000, 'gps', 10.0, 'Active', 'drop', 1000)
                """.trimIndent()
            )
            val duplicate = runCatching {
                db.execSQL(
                    """
                    INSERT INTO mobile_location_dropped_diagnostics
                        (fix_key, recorded_at_utc, provider, accuracy_meters, policy_mode, reason, created_at_utc)
                    VALUES ('same-fix', 1000, 'gps', 10.0, 'Walking', 'drop', 1001)
                    """.trimIndent()
                )
            }
            assertTrue("fix_key unique index must reject a duplicate write", duplicate.isFailure)
            db.query(
                """
                SELECT
                    COUNT(*) AS total_count,
                    COALESCE(SUM(CASE WHEN fix_key IS NOT NULL THEN 1 ELSE 0 END), 0) AS keyed_count,
                    COALESCE(SUM(CASE WHEN fix_key IS NULL THEN 1 ELSE 0 END), 0) AS unkeyed_count,
                    COUNT(DISTINCT fix_key) AS distinct_keyed_count
                FROM mobile_location_dropped_diagnostics
                """.trimIndent()
            ).use {
                it.moveToFirst()
                assertEquals(3L, it.getLong(it.getColumnIndexOrThrow("total_count")))
                assertEquals(1L, it.getLong(it.getColumnIndexOrThrow("keyed_count")))
                assertEquals(2L, it.getLong(it.getColumnIndexOrThrow("unkeyed_count")))
                assertEquals(1L, it.getLong(it.getColumnIndexOrThrow("distinct_keyed_count")))
            }
        }
    }
}
