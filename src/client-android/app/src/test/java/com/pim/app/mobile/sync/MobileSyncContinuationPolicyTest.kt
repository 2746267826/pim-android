package com.pim.app.mobile.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSyncContinuationPolicyTest {
    @Test
    fun `manual recovery uses documented server attempt and error codes`() {
        assertEquals(5, SERVER_USAGE_MAX_ATTEMPTS)
        assertEquals(40902, SERVER_CODE_PAYLOAD_CONFLICT)
        assertEquals(40903, SERVER_CODE_RETRY_EXHAUSTED)
        assertEquals(40904, SERVER_CODE_LEASE_LOST)
    }

    @Test
    fun `fully acknowledged success with remaining backlog may continue within depth limit`() {
        val state = MobileSyncState(
            phase = "catching-up",
            progressText = "remaining",
            outcome = MobileSyncOutcome.SUCCESS,
            acceptedCount = 100
        )

        assertTrue(shouldEnqueueUsageContinuation(state, continuationDepth = 0, didWork = true))
        assertTrue(shouldEnqueueUsageContinuation(state, continuationDepth = 1, didWork = true))
        assertFalse(shouldEnqueueUsageContinuation(state, continuationDepth = 2, didWork = true))
    }

    @Test
    fun `ambiguous empty ack never enqueues immediate continuation`() {
        val state = MobileSyncState(
            phase = "old-queue-upload-failed",
            progressText = "ambiguous",
            outcome = MobileSyncOutcome.RETRY,
            failedCount = 500,
            pendingQueueCount = 500
        )

        assertFalse(shouldEnqueueUsageContinuation(state, continuationDepth = 0, didWork = true))
    }

    @Test
    fun `catching up with no acknowledged progress never enqueues continuation`() {
        val state = MobileSyncState(
            phase = "catching-up",
            progressText = "remaining",
            outcome = MobileSyncOutcome.SUCCESS,
            failedCount = 0,
            pendingQueueCount = 500
        )

        assertFalse(shouldEnqueueUsageContinuation(state, continuationDepth = 0, didWork = true))
    }

    @Test
    fun `network or server retry outcome does not enqueue continuation`() {
        val state = MobileSyncState(
            phase = "upload-failed",
            progressText = "retry",
            outcome = MobileSyncOutcome.RETRY,
            acceptedCount = 0
        )

        assertFalse(shouldEnqueueUsageContinuation(state, continuationDepth = 0, didWork = true))
    }

    @Test
    fun `lock-contended wake with stale success state never enqueues continuation`() {
        val staleSuccess = MobileSyncState(
            phase = "catching-up",
            progressText = "同步正在进行中。",
            outcome = MobileSyncOutcome.SUCCESS,
            acceptedCount = 500
        )

        assertFalse(
            shouldEnqueueUsageContinuation(
                staleSuccess,
                continuationDepth = 0,
                didWork = false
            )
        )
    }

    @Test
    fun `usage upload blocked phase wins over location and failed-count phases`() {
        assertEquals(
            "upload-blocked",
            resolvePostUsageSyncPhase(
                usagePhase = "upload-blocked",
                locationPhase = "location-upload-failed",
                failedCount = 500,
                remaining = 500
            )
        )
        assertEquals(
            "usage-outbox-binding-blocked",
            resolvePostUsageSyncPhase(
                usagePhase = "usage-outbox-binding-blocked",
                locationPhase = "location-uploaded",
                failedCount = 500,
                remaining = 500
            )
        )
    }

    @Test
    fun `usage outbox binding blocks unknown legacy and cross account while allowing empty rebind`() {
        val ownerA = UsageOutboxOwnerBinding(
            serverIdentity = "https://pim.example",
            ownerId = "11111111-1111-1111-1111-111111111111"
        )
        val ownerB = ownerA.copy(ownerId = "22222222-2222-2222-2222-222222222222")

        assertEquals(
            UsageOutboxBindingAction.ALLOWED,
            evaluateUsageOutboxBinding(ownerA, ownerA, pendingCount = 10)
        )
        assertEquals(
            UsageOutboxBindingAction.UNBOUND_LEGACY_QUEUE,
            evaluateUsageOutboxBinding(null, ownerA, pendingCount = 10)
        )
        assertEquals(
            UsageOutboxBindingAction.BINDING_MISMATCH,
            evaluateUsageOutboxBinding(ownerA, ownerB, pendingCount = 10)
        )
        assertEquals(
            UsageOutboxBindingAction.BIND_CURRENT,
            evaluateUsageOutboxBinding(ownerA, ownerB, pendingCount = 0)
        )
        assertEquals(
            UsageOutboxBindingAction.OWNER_MISSING,
            evaluateUsageOutboxBinding(null, ownerA.copy(ownerId = ""), pendingCount = 0)
        )
    }

    @Test
    fun `legacy claim binds only matching current identity and never rewrites bound owner`() {
        val ownerA = UsageOutboxOwnerBinding(
            serverIdentity = "https://pim.example",
            ownerId = "11111111-1111-1111-1111-111111111111"
        )
        val ownerB = ownerA.copy(ownerId = "22222222-2222-2222-2222-222222222222")
        val otherServer = ownerA.copy(serverIdentity = "https://other.example")
        val claimA = LegacyOutboxClaimRequest(ownerA.serverIdentity, ownerA.ownerId)

        assertEquals(
            UsageOutboxBindingDecision(UsageOutboxBindingAction.UNBOUND_LEGACY_QUEUE, false),
            resolveUsageOutboxBinding(null, ownerA, pendingCount = 10, claim = null)
        )
        assertEquals(
            UsageOutboxBindingDecision(UsageOutboxBindingAction.ALLOWED, true),
            resolveUsageOutboxBinding(null, ownerA, pendingCount = 10, claim = claimA)
        )
        assertEquals(
            UsageOutboxBindingDecision(UsageOutboxBindingAction.BINDING_MISMATCH, false),
            resolveUsageOutboxBinding(null, ownerB, pendingCount = 10, claim = claimA)
        )
        assertEquals(
            UsageOutboxBindingDecision(UsageOutboxBindingAction.BINDING_MISMATCH, false),
            resolveUsageOutboxBinding(null, otherServer, pendingCount = 10, claim = claimA)
        )
        assertEquals(
            UsageOutboxBindingDecision(UsageOutboxBindingAction.BINDING_MISMATCH, false),
            resolveUsageOutboxBinding(
                stored = ownerA,
                current = ownerB,
                pendingCount = 10,
                claim = LegacyOutboxClaimRequest(ownerB.serverIdentity, ownerB.ownerId)
            )
        )
    }

    @Test
    fun `manual retry starts one new generation only for explicit retry exhausted code`() {
        val binding = UsageBatchRecoveryBinding(
            serverIdentity = "https://pim.example",
            ownerId = "11111111-1111-1111-1111-111111111111",
            payloadFingerprint = "payload-a"
        )
        val blocked = UsageBatchRecoveryState(
            blockedBy409 = true,
            blockReason = UsageBatchBlockReason.RETRY_EXHAUSTED,
            lastTerminalError = "terminal",
            binding = binding
        )

        val recovered = advanceUsageBatchRecoveryOnManualRetry(blocked, binding)

        assertTrue(recovered.batchIdRotated)
        assertEquals(1, recovered.state.recoveryEpoch)
        assertEquals(0, recovered.state.noProgressAttempts)
        assertFalse(recovered.state.blockedBy409)
        assertEquals(null, recovered.state.blockReason)
    }

    @Test
    fun `manual retry does not rotate payload lease unknown or cross binding conflict`() {
        val binding = UsageBatchRecoveryBinding(
            serverIdentity = "https://pim.example",
            ownerId = "11111111-1111-1111-1111-111111111111",
            payloadFingerprint = "payload-a"
        )
        val payloadConflict = UsageBatchRecoveryState(
            blockedBy409 = true,
            blockReason = UsageBatchBlockReason.PAYLOAD_CONFLICT,
            binding = binding
        )
        val leaseLost = payloadConflict.copy(blockReason = UsageBatchBlockReason.LEASE_LOST)
        val unknown = payloadConflict.copy(blockReason = UsageBatchBlockReason.UNKNOWN_CONFLICT)
        val exhausted = payloadConflict.copy(
            blockReason = UsageBatchBlockReason.RETRY_EXHAUSTED
        )
        val otherOwner = binding.copy(ownerId = "22222222-2222-2222-2222-222222222222")
        val otherServer = binding.copy(serverIdentity = "https://other.example")
        val otherPayload = binding.copy(payloadFingerprint = "payload-b")

        assertFalse(advanceUsageBatchRecoveryOnManualRetry(payloadConflict, binding).batchIdRotated)
        assertFalse(advanceUsageBatchRecoveryOnManualRetry(leaseLost, binding).batchIdRotated)
        assertFalse(advanceUsageBatchRecoveryOnManualRetry(unknown, binding).batchIdRotated)
        assertFalse(advanceUsageBatchRecoveryOnManualRetry(exhausted, otherServer).batchIdRotated)
        assertFalse(advanceUsageBatchRecoveryOnManualRetry(exhausted, otherOwner).batchIdRotated)
        assertFalse(advanceUsageBatchRecoveryOnManualRetry(exhausted, otherPayload).batchIdRotated)
    }

    @Test
    fun `lease lost remains retryable and reuses the same stable batch generation`() {
        val binding = UsageBatchRecoveryBinding(
            serverIdentity = "https://pim.example",
            ownerId = "11111111-1111-1111-1111-111111111111",
            payloadFingerprint = "payload-a"
        )
        val initial = UsageBatchRecoveryState(binding = binding)

        val retryable = usageBatchRecoveryStateAfterConflict(
            state = initial,
            reason = UsageBatchBlockReason.LEASE_LOST,
            message = "lease lost"
        )

        assertFalse(retryable.blockedBy409)
        assertEquals(UsageBatchBlockReason.LEASE_LOST, retryable.blockReason)
        assertEquals(0, retryable.recoveryEpoch)
        assertEquals(1, retryable.noProgressAttempts)
        assertFalse(
            advanceUsageBatchRecoveryOnManualRetry(retryable, binding).batchIdRotated
        )

        val legacyLatched = retryable.copy(blockedBy409 = true)
        assertFalse(normalizeUsageBatchRecoveryState(legacyLatched).blockedBy409)

        val items = setOf(MobileAcknowledgementItem("usage-event", "7"))
        assertEquals(
            stableBatchId("device", "start", "end", items, recoveryEpoch = 0),
            stableBatchId(
                "device",
                "start",
                "end",
                items,
                recoveryEpoch = normalizeUsageBatchRecoveryState(legacyLatched).recoveryEpoch
            )
        )
    }

    @Test
    fun `payload and unknown conflicts stay latched while retry exhausted alone rotates`() {
        val binding = UsageBatchRecoveryBinding(
            serverIdentity = "https://pim.example",
            ownerId = "11111111-1111-1111-1111-111111111111",
            payloadFingerprint = "payload-a"
        )
        val initial = UsageBatchRecoveryState(binding = binding)

        val payload = usageBatchRecoveryStateAfterConflict(
            initial,
            UsageBatchBlockReason.PAYLOAD_CONFLICT,
            "payload conflict"
        )
        val unknown = usageBatchRecoveryStateAfterConflict(
            initial,
            UsageBatchBlockReason.UNKNOWN_CONFLICT,
            "unknown conflict"
        )
        val exhausted = usageBatchRecoveryStateAfterConflict(
            initial,
            UsageBatchBlockReason.RETRY_EXHAUSTED,
            "retry exhausted"
        )

        assertTrue(payload.blockedBy409)
        assertTrue(unknown.blockedBy409)
        assertTrue(exhausted.blockedBy409)
        assertFalse(advanceUsageBatchRecoveryOnManualRetry(payload, binding).batchIdRotated)
        assertFalse(advanceUsageBatchRecoveryOnManualRetry(unknown, binding).batchIdRotated)
        assertTrue(advanceUsageBatchRecoveryOnManualRetry(exhausted, binding).batchIdRotated)
    }

    @Test
    fun `recovery epoch changes batch id while preserving deterministic item signature`() {
        val items = setOf(
            MobileAcknowledgementItem("usage-event", "1"),
            MobileAcknowledgementItem("usage-summary", "2")
        )
        val original = stableBatchId("device", "start", "end", items)
        val repeated = stableBatchId("device", "start", "end", items)
        val recovered = stableBatchId(
            "device",
            "start",
            "end",
            items,
            recoveryEpoch = 1,
            payloadFingerprint = "payload-a"
        )

        assertEquals(original, repeated)
        assertNotEquals(original, recovered)
    }
}
