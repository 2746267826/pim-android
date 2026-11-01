package com.pim.app.location.passive

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.pim.app.location.quality.LocationQualityGate
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PassiveLocationSourceLifecycleTest {
    private lateinit var context: Context
    private lateinit var source: PassiveLocationSource
    private lateinit var locationManager: LocationManager

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        context = application
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(locationManager).setProviderEnabled(LocationManager.PASSIVE_PROVIDER, true)
        val settings = TrackingSettingsStore(
            context.getSharedPreferences("passive-source-lifecycle", Context.MODE_PRIVATE)
        )
        source = PassiveLocationSource(
            context = context,
            trackingSettingsStore = settings,
            logs = StructuredLogRepository(context, settings)
        )
    }

    @After
    fun tearDown() {
        source.unregister()
    }

    @Test
    fun concurrentRepeatedRegisterKeepsOnePlatformListener() {
        val first = processor()
        val second = processor()

        val firstResult = source.register(first)
        val secondResult = source.register(second)

        assertTrue(firstResult is PassiveRegistrationResult.Registered)
        assertEquals(firstResult, secondResult)
        assertEquals(
            1,
            shadowOf(locationManager).locationUpdateListeners.size
        )
    }

    @Test
    fun callbackQueuedBeforeUnregisterCannotWriteAfterStop() {
        val accepted = CountDownLatch(1)
        val processor = processor(onAccepted = { accepted.countDown() })
        source.register(processor)

        send(provider = "gps", time = 1_000L)
        assertTrue("first callback must be accepted", accepted.await(2, TimeUnit.SECONDS))
        assertEquals(1, processor.countersSnapshot().acceptedCount)

        source.unregister()
        send(provider = "gps", time = 2_000L)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(250L)

        assertEquals(
            "a late platform callback must fail closed after unregister",
            1,
            processor.countersSnapshot().callbackCount
        )
    }

    private fun processor(onAccepted: suspend () -> Unit = {}) = PassiveLocationProcessor(
        qualityGate = LocationQualityGate()
    ).apply {
        this.onAccepted = { onAccepted() }
    }

    private fun send(provider: String, time: Long) {
        shadowOf(locationManager).simulateLocation(
            Location(provider).apply {
                latitude = 31.230416
                longitude = 121.473701
                accuracy = 8f
                this.time = time
            }
        )
        shadowOf(Looper.getMainLooper()).idle()
    }
}
