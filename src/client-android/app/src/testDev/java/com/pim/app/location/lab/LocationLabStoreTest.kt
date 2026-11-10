package com.pim.app.location.lab

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.locationtest.DemoRuntime
import com.pim.locationtest.LocationSample
import com.pim.locationtest.SensorEvidence
import com.pim.locationtest.SensorWindow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class LocationLabStoreTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("local-location-lab", Context.MODE_PRIVATE).edit().clear().commit()
        LocationLabStore(context).csvFile.delete()
    }
    @After fun cleanup() { LocationLabStore(context).csvFile.delete() }

    @Test fun anonymousSessionPersistsAlignedMetricsWithoutCoordinates() {
        val store = LocationLabStore(context)
        val now = android.os.SystemClock.elapsedRealtime()
        store.begin(now)
        store.attach(now)
        DemoRuntime.begin("fixture", true)
        DemoRuntime.accept(LocationSample(now + 5_000, 37.123456, 127.654321, 5f, 20f, 1.5f))
        store.record(now + 5_100, "location", mapOf("accel" to SensorWindow(
            count = 251, totalCount = 251, coveredMillis = 5_000, ageMillis = 100,
            standardDeviation = 0.01, evidence = SensorEvidence.Quiet
        )), "accel:registered;gyro:absent;linear:register_false", now + 5_000)
        val csv = store.csvFile.readText()
        assertTrue(csv.contains("accel_coverage_ms"))
        assertTrue(csv.contains("\"5100\""))
        assertTrue(csv.contains("\"5000\""))
        assertTrue(csv.contains("\"Quiet\""))
        assertTrue(csv.contains("\"Unknown\""))
        assertFalse(csv.contains("37.123456"))
        assertFalse(csv.contains("127.654321"))
        assertFalse(csv.contains("latitude"))
        assertFalse(csv.contains("longitude"))
    }

    @Test fun reopeningStoreRetainsRowsAndRestartsAsANewUnknownSegment() {
        val first = LocationLabStore(context)
        first.begin(1_000)
        first.attach(1_000)
        first.event(1_050, "screen off")
        val restored = LocationLabStore(context)
        assertTrue(restored.requested)
        assertFalse(restored.attached)
        assertTrue(restored.csvFile.readText().contains("screen off"))
        restored.attach(8_000)
        assertEquals(2, restored.generation)
        restored.stop(9_000, "用户停止")
        val stopped = LocationLabStore(context)
        assertFalse(stopped.requested)
        assertTrue(stopped.report().contains("用户停止"))
        assertTrue(stopped.csvFile.readText().contains("screen off"))
    }

    @Test fun noSensorCallbackProducesUnknownRowsAndNotAStaticVerdict() {
        val store = LocationLabStore(context)
        store.begin(0)
        store.attach(0)
        DemoRuntime.begin("fixture", true)
        store.record(10_000, "heartbeat", emptyMap(), "accel:registered")
        assertTrue(store.csvFile.readText().contains("\"Unknown\""))
        assertFalse(store.latestStatus.contains("静止已确认"))
        assertTrue(store.latestStatus.contains("证据不足"))
    }
}
