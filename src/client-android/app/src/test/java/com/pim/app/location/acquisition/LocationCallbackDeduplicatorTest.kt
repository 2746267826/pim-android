package com.pim.app.location.acquisition

import com.pim.app.location.LocationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationCallbackDeduplicatorTest {

    @Test
    fun `gps and fused views of one fix are delivered once`() {
        val deduplicator = LocationCallbackDeduplicator()

        val result = deduplicator.filterForDelivery(
            listOf(
                snapshot(
                    time = START,
                    elapsed = ELAPSED,
                    provider = "fused",
                    latitude = 31.230416
                ),
                snapshot(
                    time = START + 80L,
                    elapsed = ELAPSED,
                    provider = "gps",
                    latitude = 31.230500
                )
            )
        )

        assertEquals(1, result.delivered.size)
        assertEquals("gps", result.delivered.single().provider)
        assertEquals(
            LocationDuplicateReason.GNSS_FUSED_SAME_MONOTONIC_FIX,
            result.suppressions.single().reason
        )
        assertEquals("fused", result.suppressions.single().suppressed.provider)
        assertEquals(1L, result.cumulativeSuppressedCount)
    }

    @Test
    fun `cross callback gps and fused pair in same second is delivered once`() {
        val deduplicator = LocationCallbackDeduplicator()

        val first = deduplicator.filterForDelivery(
            listOf(snapshot(time = START, elapsed = ELAPSED, provider = "fused"))
        )
        val second = deduplicator.filterForDelivery(
            listOf(
                snapshot(
                    time = START + 80L,
                    elapsed = ELAPSED,
                    provider = "gps",
                    latitude = 31.230486
                )
            )
        )

        assertEquals(1, first.delivered.size)
        assertTrue(
            "same physical sample from another channel must not reach rate accounting",
            second.delivered.isEmpty()
        )
    }

    @Test
    fun `replayed fused view stays suppressed after its gps counterpart was preferred`() {
        val deduplicator = LocationCallbackDeduplicator()
        val fused = snapshot(
            time = START,
            elapsed = ELAPSED,
            provider = "fused",
            latitude = 31.230416
        )

        val first = deduplicator.filterForDelivery(
            listOf(
                fused,
                snapshot(
                    time = START + 80L,
                    elapsed = ELAPSED,
                    provider = "gps",
                    latitude = 31.230500
                )
            )
        )
        val replay = deduplicator.filterForDelivery(listOf(fused))

        assertEquals("gps", first.delivered.single().provider)
        assertTrue(
            "a queued duplicate of the non-preferred channel must remain suppressed",
            replay.delivered.isEmpty()
        )
        assertEquals(LocationDuplicateReason.EXACT_REPLAY, replay.suppressions.single().reason)
        assertEquals(2L, replay.cumulativeSuppressedCount)
    }

    @Test
    fun `distinct same provider fixes one second apart are both retained`() {
        val deduplicator = LocationCallbackDeduplicator()

        val first = deduplicator.filterForDelivery(
            listOf(snapshot(time = START, elapsed = ELAPSED, provider = "gps"))
        )
        val second = deduplicator.filterForDelivery(
            listOf(
                snapshot(
                    time = START + 1_000L,
                    elapsed = ELAPSED + 1_000_000_000L,
                    provider = "gps"
                )
            )
        )

        assertEquals(1, first.delivered.size)
        assertEquals(1, second.delivered.size)
    }

    @Test
    fun `distinct fixes in one platform result are not collapsed by batching`() {
        val deduplicator = LocationCallbackDeduplicator()

        val result = deduplicator.filterForDelivery(
            listOf(
                snapshot(time = START, provider = "gps", latitude = 31.230416),
                snapshot(time = START + 1_000L, provider = "gps", latitude = 31.230500)
            )
        )

        assertEquals(listOf(START, START + 1_000L), result.delivered.map { it.timeMillis })
    }

    @Test
    fun `exact duplicate callback with platform monotonic identity is suppressed`() {
        val deduplicator = LocationCallbackDeduplicator()
        val fix = snapshot(time = START, elapsed = ELAPSED, provider = "gps")

        assertEquals(1, deduplicator.filterForDelivery(listOf(fix)).delivered.size)
        assertTrue(deduplicator.filterForDelivery(listOf(fix)).delivered.isEmpty())
    }

    @Test
    fun `nearby gps and fused fixes with different monotonic identities are both retained`() {
        val deduplicator = LocationCallbackDeduplicator()

        val result = deduplicator.filterForDelivery(
            listOf(
                snapshot(time = START, elapsed = ELAPSED, provider = "gps"),
                snapshot(
                    time = START + 900L,
                    elapsed = ELAPSED + 900_000_000L,
                    provider = "fused",
                    latitude = 31.230500
                )
            )
        )

        assertEquals(
            "physical identity, not a coarse time/distance window, decides duplicate delivery",
            2,
            result.delivered.size
        )
    }

    @Test
    fun `coincident wall time and position require the same monotonic identity`() {
        val deduplicator = LocationCallbackDeduplicator()

        val result = deduplicator.filterForDelivery(
            listOf(
                snapshot(time = START, elapsed = ELAPSED, provider = "gps"),
                snapshot(
                    time = START,
                    elapsed = ELAPSED + 250_000_000L,
                    provider = "fused"
                )
            )
        )

        assertEquals(2, result.delivered.size)
    }

    @Test
    fun `same monotonic identity does not collapse distinct coordinates`() {
        val deduplicator = LocationCallbackDeduplicator()

        val result = deduplicator.filterForDelivery(
            listOf(
                snapshot(
                    time = START,
                    elapsed = ELAPSED,
                    provider = "gps",
                    latitude = 31.230416
                ),
                snapshot(
                    time = START,
                    elapsed = ELAPSED,
                    provider = "gps",
                    latitude = 31.230500
                )
            )
        )

        assertEquals(
            "only exact duplicate coordinates share a fix identity",
            2,
            result.delivered.size
        )
    }

    @Test
    fun `missing or zero monotonic identity is not enough to suppress a callback`() {
        val missing = LocationCallbackDeduplicator()
        val noIdentity = snapshot(time = START, provider = "gps")
        assertEquals(1, missing.filterForDelivery(listOf(noIdentity)).delivered.size)
        assertEquals(1, missing.filterForDelivery(listOf(noIdentity)).delivered.size)

        val zero = LocationCallbackDeduplicator()
        val zeroIdentity = snapshot(time = START, elapsed = 0L, provider = "gps")
        assertEquals(1, zero.filterForDelivery(listOf(zeroIdentity)).delivered.size)
        assertEquals(1, zero.filterForDelivery(listOf(zeroIdentity)).delivered.size)
    }

    @Test
    fun `identity retention remains bounded`() {
        val deduplicator = LocationCallbackDeduplicator()
        val fixes = (0 until 300).map { index ->
            snapshot(
                time = START + index * 1_000L,
                elapsed = ELAPSED + index * 1_000_000_000L,
                provider = "gps"
            )
        }

        fixes.forEach { fix ->
            assertEquals(1, deduplicator.filterForDelivery(listOf(fix)).delivered.size)
        }

        assertEquals(
            "retention must not grow with the lifetime of the provider registration",
            1,
            deduplicator.filterForDelivery(listOf(fixes.first())).delivered.size
        )
    }

    @Test
    fun `gnss and fused pair beyond position delta stays independent`() {
        val deduplicator = LocationCallbackDeduplicator()

        val result = deduplicator.filterForDelivery(
            listOf(
                snapshot(time = START, elapsed = ELAPSED, provider = "fused"),
                snapshot(
                    time = START + 80L,
                    elapsed = ELAPSED,
                    provider = "gps",
                    latitude = 31.23065
                )
            )
        )

        assertEquals(
            "a same-timestamp pair more than 15m apart is not provably one physical fix",
            2,
            result.delivered.size
        )
        assertTrue(result.suppressions.isEmpty())
    }

    @Test
    fun `same wall time and position with different monotonic identity remain independent`() {
        val deduplicator = LocationCallbackDeduplicator()

        val result = deduplicator.filterForDelivery(
            listOf(
                snapshot(time = START, elapsed = ELAPSED, provider = "gps"),
                snapshot(time = START, elapsed = ELAPSED + 1_000_000L, provider = "gps")
            )
        )

        assertEquals(2, result.delivered.size)
        assertTrue(result.suppressions.isEmpty())
    }

    private fun snapshot(
        time: Long,
        elapsed: Long? = null,
        provider: String,
        latitude: Double = 31.230416
    ) = LocationSnapshot(
        latitude = latitude,
        longitude = 121.473701,
        horizontalAccuracyMeters = 8f,
        provider = provider,
        source = "realtime",
        altitudeMeters = 10.0,
        speedMetersPerSecond = 1.4f,
        bearingDegrees = 0f,
        timeMillis = time,
        elapsedRealtimeNanos = elapsed
    )

    private companion object {
        const val START = 1_791_396_035_000L
        const val ELAPSED = 12_345_000_000_000L
    }
}
