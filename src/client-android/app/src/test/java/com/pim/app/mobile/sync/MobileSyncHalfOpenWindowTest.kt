package com.pim.app.mobile.sync

import com.pim.app.data.MobileSyncStatus
import com.pim.app.data.MobileUsageEventEntity
import com.pim.app.data.MobileUsageSummaryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSyncHalfOpenWindowTest {
    private val start = 1_750_000_000_000L
    private val minute = 60_000L
    private val hour = 60L * minute

    @Test
    fun `latest point event stays strictly before the batch window end`() {
        val events = listOf(
            event(start, id = 1),
            event(start + hour, id = 2)
        )

        val batch = partitionPendingUsageBatch(events, emptyList(), emptyList()).single()

        assertTrue(batch.windowEndUtc!! > events.maxOf { it.eventTimeUtc })
        assertTrue(batch.windowStartUtc!! <= events.minOf { it.eventTimeUtc })
        assertTrue(batch.windowEndUtc!! - batch.windowStartUtc!! <= USAGE_BATCH_TARGET_WINDOW_MS)
        assertEquals(events.map { it.id }.toSet(), batch.events.map { it.id }.toSet())
    }

    @Test
    fun `point only event gets one millisecond exclusive end without changing source time`() {
        val sourceTime = start + 123L
        val event = event(sourceTime, id = 7)

        val batch = partitionPendingUsageBatch(listOf(event), emptyList(), emptyList()).single()

        assertEquals(sourceTime, batch.events.single().eventTimeUtc)
        assertEquals(sourceTime, batch.windowStartUtc)
        assertEquals(sourceTime + 1L, batch.windowEndUtc)
    }

    @Test
    fun `two hour boundary splits into legal half open windows without loss or duplication`() {
        val events = listOf(
            event(start, id = 1),
            event(start + 2L * hour, id = 2),
            event(start + 2L * hour + minute, id = 3)
        )

        val batches = partitionPendingUsageBatch(events, emptyList(), emptyList())

        assertEquals(2, batches.size)
        assertApiWindowInvariants(batches)
        assertEquals(events.map { it.id }.toSet(), batches.flatMap { it.events }.map { it.id }.toSet())
        assertEquals(events.size, batches.sumOf { it.events.size })
        assertEquals(2, batches[1].events.size)
    }

    @Test
    fun `summary and point at the same millisecond use one millisecond exclusive envelope`() {
        val summary = summary(start, start + hour, id = 10)
        val point = event(start + hour, id = 11)

        val batch = partitionPendingUsageBatch(
            events = listOf(point),
            summaries = listOf(summary),
            apps = emptyList()
        ).single()

        assertEquals(start, batch.windowStartUtc)
        assertEquals(start + hour + 1L, batch.windowEndUtc)
        assertEquals(start + hour, batch.summaries.single().windowEndUtc)
        assertEquals(start + hour, batch.events.single().eventTimeUtc)
        assertApiWindowInvariants(listOf(batch))
    }

    @Test
    fun `atomic twenty five hour summary stays intact while boundary point moves to a legal batch`() {
        val summaryEnd = start + 25L * hour
        val daily = summary(start, summaryEnd, id = 20)
        val boundaryPoint = event(summaryEnd, id = 21)

        val batches = partitionPendingUsageBatch(
            events = listOf(boundaryPoint),
            summaries = listOf(daily),
            apps = emptyList()
        )

        assertEquals(2, batches.size)
        assertApiWindowInvariants(batches)
        val summaryBatch = batches.single { it.summaries.isNotEmpty() }
        val pointBatch = batches.single { it.events.isNotEmpty() }
        assertEquals(daily, summaryBatch.summaries.single())
        assertEquals(start, summaryBatch.windowStartUtc)
        assertEquals(summaryEnd, summaryBatch.windowEndUtc)
        assertEquals(summaryEnd, pointBatch.windowStartUtc)
        assertEquals(summaryEnd + 1L, pointBatch.windowEndUtc)
        assertEquals(boundaryPoint, pointBatch.events.single())
    }

    @Test
    fun `forty hour persisted backlog repartitions deterministically without loss duplication or id drift`() {
        val backlog = (0 until 500).map { index ->
            event(
                timeUtc = start + index * (40L * hour / 499L),
                id = index + 1L
            )
        }

        val first = partitionPendingUsageBatch(backlog, emptyList(), emptyList())
        val second = partitionPendingUsageBatch(backlog, emptyList(), emptyList())

        assertApiWindowInvariants(first)
        assertEquals(backlog.size, first.sumOf { it.events.size })
        assertEquals(backlog.map { it.id }.toSet(), first.flatMap { it.events }.map { it.id }.toSet())
        assertEquals(first.map { it.typedKeys() }, second.map { it.typedKeys() })
        assertEquals(
            first.map { batch ->
                stableBatchId(
                    deviceId = "repartition-device",
                    windowStartUtc = batch.windowStartUtc.toString(),
                    windowEndUtc = batch.windowEndUtc.toString(),
                    items = batch.typedKeys()
                )
            },
            second.map { batch ->
                stableBatchId(
                    deviceId = "repartition-device",
                    windowStartUtc = batch.windowStartUtc.toString(),
                    windowEndUtc = batch.windowEndUtc.toString(),
                    items = batch.typedKeys()
                )
            }
        )
    }

    @Test
    fun `millisecond precision is preserved and longest representable point has no wraparound`() {
        val representable = event(Long.MAX_VALUE - 1L, id = 30)
        val batch = partitionPendingUsageBatch(
            listOf(representable),
            emptyList(),
            emptyList()
        ).single()

        assertEquals(Long.MAX_VALUE - 1L, batch.events.single().eventTimeUtc)
        assertEquals(Long.MAX_VALUE, batch.windowEndUtc)
        assertTrue(batch.events.single().eventTimeUtc < batch.windowEndUtc!!)

        val unrepresentable = event(Long.MAX_VALUE, id = 31)
        assertThrows(IllegalArgumentException::class.java) {
            partitionPendingUsageBatch(listOf(unrepresentable), emptyList(), emptyList())
        }
    }

    private fun assertApiWindowInvariants(batches: List<PendingUsageBatch>) {
        batches.forEach { batch ->
            val windowStart = requireNotNull(batch.windowStartUtc)
            val windowEnd = requireNotNull(batch.windowEndUtc)
            assertTrue(windowStart < windowEnd)
            assertTrue(windowEnd - windowStart <= USAGE_BATCH_MAX_WINDOW_MS)
            batch.events.forEach { event ->
                assertTrue(event.eventTimeUtc >= windowStart)
                assertTrue(event.eventTimeUtc < windowEnd)
            }
            batch.summaries.forEach { summary ->
                assertTrue(summary.windowStartUtc >= windowStart)
                assertTrue(summary.windowEndUtc <= windowEnd)
            }
        }
    }

    private fun event(timeUtc: Long, id: Long) = MobileUsageEventEntity(
        id = id,
        packageName = "com.test",
        className = null,
        eventType = 1,
        eventName = "test",
        eventTimeUtc = timeUtc,
        source = "synthetic",
        sourceWindowStartUtc = timeUtc,
        sourceWindowEndUtc = if (timeUtc == Long.MAX_VALUE) timeUtc else timeUtc + 1,
        collectedAtUtc = timeUtc,
        rawJson = "{}",
        syncStatus = MobileSyncStatus.PENDING
    )

    private fun summary(startUtc: Long, endUtc: Long, id: Long) = MobileUsageSummaryEntity(
        id = id,
        packageName = "com.test",
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
}
