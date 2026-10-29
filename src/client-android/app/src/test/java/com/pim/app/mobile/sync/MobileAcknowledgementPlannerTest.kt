package com.pim.app.mobile.sync

import com.pim.app.data.MobileAppMetadataEntity
import com.pim.app.data.MobileUsageEventEntity
import com.pim.app.data.MobileUsageSummaryEntity
import com.pim.core.contract.MobileIngestItemResult
import com.pim.core.contract.MobileUsageIngestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileAcknowledgementPlannerTest {
    @Test
    fun partialResponseSeparatesConfirmedRetryAndDeadLetterKeys() {
        val plan: MobileAcknowledgementPlan = MobileAcknowledgementPlanner.plan(
            sentKeys = setOf("11", "12", "13", "14"),
            response = MobileUsageIngestResult(
                batchId = "batch-1",
                acceptedCount = 1,
                skippedCount = 1,
                rejectedCount = 1,
                failedCount = 1,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "11",
                        entityType = "usage-event",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "12",
                        entityType = "usage-event",
                        outcome = "rejected",
                        code = "invalid-time",
                        message = "bad time",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "13",
                        entityType = "usage-event",
                        outcome = "failed",
                        code = "temporary",
                        message = "retry",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "14",
                        entityType = "usage-event",
                        outcome = "skipped",
                        code = "duplicate",
                        message = "duplicate",
                    )
                )
            )
        )

        assertEquals(setOf("11", "14"), plan.confirmedKeys)
        assertEquals(setOf("12"), plan.deadLetterKeys)
        assertEquals(setOf("13"), plan.retryKeys)
        assertNull(plan.failureCode)
    }

    @Test
    fun typedItemsWithSameClientKeyArePlannedIndependently() {
        val event = MobileAcknowledgementItem("usage-event", "1")
        val summary = MobileAcknowledgementItem("usage-summary", "1")
        val app = MobileAcknowledgementItem("app-metadata", "1")

        val plan: MobileTypedAcknowledgementPlan = MobileAcknowledgementPlanner.planTyped(
            sentItems = setOf(event, summary, app),
            response = MobileUsageIngestResult(
                skippedCount = 0,
                batchId = "batch-typed",
                acceptedCount = 1,
                rejectedCount = 1,
                failedCount = 1,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-event",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-summary",
                        outcome = "rejected",
                        code = "invalid-time",
                        message = "bad time",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "app-metadata",
                        outcome = "failed",
                        code = "temporary",
                        message = "retry",
                    )
                )
            )
        )

        assertEquals(setOf(event), plan.confirmedItems)
        assertEquals(setOf(summary), plan.deadLetterItems)
        assertEquals(setOf(app), plan.retryItems)
        assertNull(plan.failureCode)
    }

    @Test
    fun missingTypedResultRetriesOnlyTheMissingItem() {
        val event = MobileAcknowledgementItem("usage-event", "1")
        val summary = MobileAcknowledgementItem("usage-summary", "2")

        val plan = MobileAcknowledgementPlanner.planTyped(
            sentItems = setOf(event, summary),
            response = MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                batchId = "batch-missing",
                acceptedCount = 1,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-event",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    )
                )
            )
        )

        assertEquals(setOf(event), plan.confirmedItems)
        assertEquals(setOf(summary), plan.retryItems)
        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.deadLetterItems)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun duplicateResultForSameTypedItemRetriesThatItem() {
        val event = MobileAcknowledgementItem("usage-event", "1")
        val summary = MobileAcknowledgementItem("usage-summary", "2")

        val plan = MobileAcknowledgementPlanner.planTyped(
            sentItems = setOf(event, summary),
            response = MobileUsageIngestResult(
                skippedCount = 0,
                failedCount = 0,
                batchId = "batch-duplicate",
                acceptedCount = 2,
                rejectedCount = 1,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-event",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-event",
                        outcome = "rejected",
                        code = "invalid-time",
                        message = "bad time",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "2",
                        entityType = "usage-summary",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    )
                )
            )
        )

        assertEquals(setOf(summary), plan.confirmedItems)
        assertEquals(setOf(event), plan.retryItems)
        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.deadLetterItems)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun unknownOutcomeRetriesOnlyThatTypedItem() {
        val event = MobileAcknowledgementItem("usage-event", "1")
        val summary = MobileAcknowledgementItem("usage-summary", "2")

        val plan = MobileAcknowledgementPlanner.planTyped(
            sentItems = setOf(event, summary),
            response = MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                batchId = "batch-outcome",
                acceptedCount = 1,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-event",
                        outcome = "mystery",
                        code = "mystery",
                        message = "unknown",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "2",
                        entityType = "usage-summary",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    )
                )
            )
        )

        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.confirmedItems)
        assertEquals(setOf(event, summary), plan.retryItems)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun explicitItemResultsMustMatchEveryAggregateCount() {
        val event = MobileAcknowledgementItem("usage-event", "1")
        val itemResults = listOf(
            MobileIngestItemResult(
                clientItemKey = "1",
                entityType = "usage-event",
                outcome = "accepted",
                code = "accepted",
                message = "OK",
            )
        )
        val inconsistentResponses = listOf(
            MobileUsageIngestResult(
                acceptedCount = 0,
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,batchId = "all-zero", itemResults = itemResults),
            MobileUsageIngestResult(
                acceptedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                batchId = "wrong-outcome-count",
                skippedCount = 1,
                itemResults = itemResults
            ),
            MobileUsageIngestResult(
                skippedCount = 0,
                failedCount = 0,
                batchId = "hidden-rejection",
                acceptedCount = 1,
                rejectedCount = 1,
                itemResults = itemResults
            ),
            MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                batchId = "hidden-failure",
                acceptedCount = 1,
                failedCount = 1,
                itemResults = itemResults
            )
        )

        for (response in inconsistentResponses) {
            val plan = MobileAcknowledgementPlanner.planTyped(setOf(event), response)

            assertEquals(response.batchId, emptySet<MobileAcknowledgementItem>(), plan.confirmedItems)
            assertEquals(response.batchId, setOf(event), plan.retryItems)
            assertEquals(response.batchId, "server-ack-ambiguous", plan.failureCode)
        }
    }

    @Test
    fun mismatchedEntityTypeInvalidatesTheTypedResponse() {
        val event = MobileAcknowledgementItem("usage-event", "1")

        val plan = MobileAcknowledgementPlanner.planTyped(
            sentItems = setOf(event),
            response = MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                batchId = "batch-mismatch",
                acceptedCount = 1,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-summary",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    )
                )
            )
        )

        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.confirmedItems)
        assertEquals(setOf(event), plan.retryItems)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun unknownEntityTypeInvalidatesTheTypedResponse() {
        val event = MobileAcknowledgementItem("usage-event", "1")

        val plan = MobileAcknowledgementPlanner.planTyped(
            sentItems = setOf(event),
            response = MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                batchId = "batch-unknown-type",
                acceptedCount = 1,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "future-entity",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    )
                )
            )
        )

        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.confirmedItems)
        assertEquals(setOf(event), plan.retryItems)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun unexpectedExtraTypedResultRetriesEverySentItem() {
        val event = MobileAcknowledgementItem("usage-event", "1")
        val summary = MobileAcknowledgementItem("usage-summary", "2")

        val plan = MobileAcknowledgementPlanner.planTyped(
            sentItems = setOf(event, summary),
            response = MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                batchId = "batch-extra",
                acceptedCount = 3,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-event",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "2",
                        entityType = "usage-summary",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "3",
                        entityType = "app-metadata",
                        outcome = "accepted",
                        code = "accepted",
                        message = "unexpected",
                    )
                )
            )
        )

        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.confirmedItems)
        assertEquals(setOf(event, summary), plan.retryItems)
        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.deadLetterItems)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun typedAggregateOnlySuccessConfirmsEverySentItem() {
        val sentItems = setOf(
            MobileAcknowledgementItem("usage-event", "1"),
            MobileAcknowledgementItem("usage-summary", "1")
        )

        val plan = MobileAcknowledgementPlanner.planTyped(
            sentItems = sentItems,
            response = MobileUsageIngestResult(
                rejectedCount = 0,
                failedCount = 0,
                itemResults = emptyList(),
                batchId = "batch-aggregate-typed",
                acceptedCount = 1,
                skippedCount = 1
            )
        )

        assertEquals(sentItems, plan.confirmedItems)
        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.retryItems)
        assertNull(plan.failureCode)
    }

    @Test
    fun ambiguousTypedAggregateOnlyResponseRetriesEverySentItem() {
        val sentItems = setOf(
            MobileAcknowledgementItem("usage-event", "1"),
            MobileAcknowledgementItem("usage-summary", "1")
        )

        val plan = MobileAcknowledgementPlanner.planTyped(
            sentItems = sentItems,
            response = MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                itemResults = emptyList(),
                batchId = "batch-aggregate-typed",
                acceptedCount = 1
            )
        )

        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.confirmedItems)
        assertEquals(sentItems, plan.retryItems)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun legacyBareKeyPathRejectsCrossTypeDuplicates() {
        val plan = MobileAcknowledgementPlanner.plan(
            sentKeys = setOf("1"),
            response = MobileUsageIngestResult(
                skippedCount = 0,
                failedCount = 0,
                batchId = "batch-legacy-cross-type",
                acceptedCount = 1,
                rejectedCount = 1,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-event",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-summary",
                        outcome = "rejected",
                        code = "invalid-time",
                        message = "bad time",
                    )
                )
            )
        )

        assertEquals(emptySet<String>(), plan.confirmedKeys)
        assertEquals(setOf("1"), plan.retryKeys)
        assertEquals(emptySet<String>(), plan.deadLetterKeys)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun legacyBareKeyPathRejectsMismatchedEntityType() {
        val plan = MobileAcknowledgementPlanner.plan(
            sentKeys = setOf("1"),
            response = MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                batchId = "batch-legacy-mismatch",
                acceptedCount = 1,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-summary",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    )
                )
            )
        )

        assertEquals(emptySet<String>(), plan.confirmedKeys)
        assertEquals(setOf("1"), plan.retryKeys)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun legacyBareKeyPathFlagsUnexpectedExtraResult() {
        val plan = MobileAcknowledgementPlanner.plan(
            sentKeys = setOf("1"),
            response = MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                batchId = "batch-legacy-extra",
                acceptedCount = 2,
                itemResults = listOf(
                    MobileIngestItemResult(
                        clientItemKey = "1",
                        entityType = "usage-event",
                        outcome = "accepted",
                        code = "accepted",
                        message = "OK",
                    ),
                    MobileIngestItemResult(
                        clientItemKey = "2",
                        entityType = "usage-event",
                        outcome = "accepted",
                        code = "accepted",
                        message = "unexpected",
                    )
                )
            )
        )

        assertEquals(emptySet<String>(), plan.confirmedKeys)
        assertEquals(setOf("1"), plan.retryKeys)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun aggregateOnlySuccessConfirmsEverySentKey() {
        val plan = MobileAcknowledgementPlanner.plan(
            sentKeys = setOf("11", "12", "13"),
            response = MobileUsageIngestResult(
                rejectedCount = 0,
                failedCount = 0,
                itemResults = emptyList(),
                batchId = "batch-legacy",
                acceptedCount = 2,
                skippedCount = 1
            )
        )

        assertEquals(setOf("11", "12", "13"), plan.confirmedKeys)
        assertEquals(emptySet<String>(), plan.deadLetterKeys)
        assertEquals(emptySet<String>(), plan.retryKeys)
        assertNull(plan.failureCode)
    }

    @Test
    fun ambiguousAggregateOnlyResponseRetainsEveryKeyForRetry() {
        val sentKeys = setOf("11", "12", "13")

        val plan = MobileAcknowledgementPlanner.plan(
            sentKeys = sentKeys,
            response = MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                itemResults = emptyList(),
                batchId = "batch-legacy",
                acceptedCount = 2
            )
        )

        assertEquals(emptySet<String>(), plan.confirmedKeys)
        assertEquals(emptySet<String>(), plan.deadLetterKeys)
        assertEquals(sentKeys, plan.retryKeys)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun allZeroEmptyAcknowledgementRetainsEveryTypedItemForRetry() {
        val sentItems = setOf(
            MobileAcknowledgementItem("usage-event", "11"),
            MobileAcknowledgementItem("usage-summary", "12"),
            MobileAcknowledgementItem("app-metadata", "com.example@1")
        )

        val plan = MobileAcknowledgementPlanner.planTyped(
            sentItems = sentItems,
            response = MobileUsageIngestResult(
                skippedCount = 0,
                rejectedCount = 0,
                failedCount = 0,
                itemResults = emptyList(),
                batchId = "batch-zero-empty",
                acceptedCount = 0
            )
        )

        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.confirmedItems)
        assertEquals(emptySet<MobileAcknowledgementItem>(), plan.deadLetterItems)
        assertEquals(sentItems, plan.retryItems)
        assertEquals("server-ack-ambiguous", plan.failureCode)
    }

    @Test
    fun uploadMappingsUseStableRoomAndPackageVersionKeys() {
        val event = MobileUsageEventEntity(
            id = 11,
            packageName = "com.example.messages",
            eventType = 7,
            eventName = "USER_INTERACTION",
            eventTimeUtc = 1_000,
            source = "usage-events",
            sourceWindowStartUtc = 900,
            sourceWindowEndUtc = 1_100,
            collectedAtUtc = 1_010,
            rawJson = "{}"
        )
        val summary = MobileUsageSummaryEntity(
            id = 12,
            packageName = "com.example.messages",
            windowStartUtc = 900,
            windowEndUtc = 1_100,
            totalTimeForegroundMs = 100,
            lastTimeUsedUtc = 1_000,
            firstTimeStampUtc = 900,
            lastTimeStampUtc = 1_100,
            source = "usage_stats_fallback",
            sourceWindowStartUtc = 900,
            sourceWindowEndUtc = 1_100,
            collectedAtUtc = 1_010,
            rawJson = "{}"
        )
        val app = MobileAppMetadataEntity(
            packageName = "com.example.messages",
            label = "Messages",
            versionName = "1.2.3",
            versionCode = 123,
            firstInstallTimeUtc = 100,
            lastUpdateTimeUtc = 200,
            isSystemApp = false,
            category = null,
            installerPackageName = "com.android.vending",
            collectedAtUtc = 1_010,
            rawJson = "{}"
        )

        assertEquals("11", event.toDto().clientItemKey)
        assertEquals("12", summary.toDto().clientItemKey)
        assertEquals("com.example.messages@123", app.toDto().clientItemKey)
    }
}
