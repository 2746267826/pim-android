package com.pim.app.mobile.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.pim.app.TestPimApp
import com.pim.app.settings.TrackingSettings
import com.pim.app.settings.TrackingSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class MobileSyncSchedulerTest {

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        context.getSharedPreferences("scheduler_test", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun periodicRequestUsesConnectedWhenUnmeteredFalse() {
        val settings = TrackingSettings.defaults().copy(syncOnUnmeteredOnly = false)
        assertEquals(NetworkType.CONNECTED, MobileSyncScheduler.resolvePeriodicNetworkType(settings))
    }

    @Test
    fun periodicRequestUsesUnmeteredWhenUnmeteredTrue() {
        val settings = TrackingSettings.defaults().copy(syncOnUnmeteredOnly = true)
        assertEquals(NetworkType.UNMETERED, MobileSyncScheduler.resolvePeriodicNetworkType(settings))
    }

    @Test
    fun immediateRequestInputDataContainsAllowMeteredOnce() {
        val data = MobileSyncScheduler.buildImmediateInputData(true)
        assertEquals(true, data.getBoolean("allow_metered_once", false))
    }

    @Test
    fun immediateRequestInputDataDefaultsToFalse() {
        val data = MobileSyncScheduler.buildImmediateInputData(false)
        assertEquals(false, data.getBoolean("allow_metered_once", true))
        assertEquals(0, data.getInt(MobileSyncScheduler.CONTINUATION_DEPTH_KEY, -1))
        assertEquals(false, data.getBoolean(MobileSyncScheduler.MANUAL_RECOVERY_RETRY_KEY, true))
    }

    @Test
    fun immediateRequestUsesConnectedWhenUnmeteredTrueAndOverrideTrue() {
        val settings = TrackingSettings.defaults().copy(syncOnUnmeteredOnly = true)
        assertEquals(NetworkType.CONNECTED, MobileSyncScheduler.resolveImmediateNetworkType(settings, allowMeteredOnce = true))
    }

    @Test
    fun immediateRequestUsesUnmeteredWhenUnmeteredTrueAndOverrideFalse() {
        val settings = TrackingSettings.defaults().copy(syncOnUnmeteredOnly = true)
        assertEquals(NetworkType.UNMETERED, MobileSyncScheduler.resolveImmediateNetworkType(settings, allowMeteredOnce = false))
    }

    @Test
    fun immediateRequestUsesConnectedWhenUnmeteredFalseRegardlessOfOverride() {
        val settings = TrackingSettings.defaults().copy(syncOnUnmeteredOnly = false)
        assertEquals(NetworkType.CONNECTED, MobileSyncScheduler.resolveImmediateNetworkType(settings, allowMeteredOnce = true))
        assertEquals(NetworkType.CONNECTED, MobileSyncScheduler.resolveImmediateNetworkType(settings, allowMeteredOnce = false))
    }

    @Test
    fun consecutiveEnsurePeriodicLeavesSingleActive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = TrackingSettingsStore(
            context.getSharedPreferences("scheduler_test", Context.MODE_PRIVATE)
        )
        val scheduler = MobileSyncScheduler(context, store)

        scheduler.ensurePeriodic()
        scheduler.ensurePeriodic()
        scheduler.ensurePeriodic()

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(MobileSyncScheduler.PERIODIC_NAME).get()
        val active = workInfos.filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, active.size)
    }

    @Test
    fun consecutiveEnqueueNowLeavesSingleActive() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = TrackingSettingsStore(
            context.getSharedPreferences("scheduler_test", Context.MODE_PRIVATE)
        )
        val scheduler = MobileSyncScheduler(context, store)

        scheduler.enqueueNow()
        scheduler.enqueueNow()
        scheduler.enqueueNow()

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(MobileSyncScheduler.NOW_NAME).get()
        val active = workInfos.filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, active.size)
    }

    @Test
    fun enqueueContinuationEnqueuesWorkUnderNowName() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = TrackingSettingsStore(
            context.getSharedPreferences("scheduler_test", Context.MODE_PRIVATE)
        )
        val scheduler = MobileSyncScheduler(context, store)

        scheduler.enqueueContinuation()

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(MobileSyncScheduler.NOW_NAME).get()
        val active = workInfos.filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, active.size)
    }

    @Test
    fun enqueueNotBeforeUsesItsOwnUniqueWorkWithPositiveDelay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = TrackingSettingsStore(
            context.getSharedPreferences("scheduler_test", Context.MODE_PRIVATE)
        )
        val scheduler = MobileSyncScheduler(context, store)

        scheduler.enqueueNotBefore(delaySeconds = 20L)

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(MobileSyncScheduler.NOT_BEFORE_NAME).get()
        val active = workInfos.filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, active.size)
        val request = MobileSyncScheduler.buildImmediateRequest(
            networkType = NetworkType.CONNECTED,
            allowMeteredOnce = false,
            initialDelaySeconds = 20L
        )
        assertEquals(20_000L, request.workSpec.initialDelay)

        // A second deadline replaces the pending one instead of stacking runs.
        scheduler.enqueueNotBefore(delaySeconds = 30L)
        assertEquals(
            1,
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(MobileSyncScheduler.NOT_BEFORE_NAME).get()
                .count { it.state == WorkInfo.State.ENQUEUED }
        )
    }

    // --- buildPeriodicRequest ---

    @Test
    fun periodicRequestHas15MinuteInterval() {
        val request = MobileSyncScheduler.buildPeriodicRequest(NetworkType.CONNECTED)
        assertEquals(15, TimeUnit.MILLISECONDS.toMinutes(request.workSpec.intervalDuration))
    }

    @Test
    fun periodicRequestHasCorrectNetworkConstraint() {
        val request = MobileSyncScheduler.buildPeriodicRequest(NetworkType.UNMETERED)
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun periodicRequestHasExponentialBackoff30s() {
        val request = MobileSyncScheduler.buildPeriodicRequest(NetworkType.CONNECTED)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30, TimeUnit.MILLISECONDS.toSeconds(request.workSpec.backoffDelayDuration))
    }

    // --- buildImmediateRequest ---

    @Test
    fun immediateRequestHasCorrectConstraints() {
        val request = MobileSyncScheduler.buildImmediateRequest(NetworkType.CONNECTED, allowMeteredOnce = true)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun immediateRequestHasExponentialBackoff30s() {
        val request = MobileSyncScheduler.buildImmediateRequest(NetworkType.CONNECTED, allowMeteredOnce = true)
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30, TimeUnit.MILLISECONDS.toSeconds(request.workSpec.backoffDelayDuration))
    }

    @Test
    fun continuationRequestHasExplicitDelayDepthAndExponentialBackoff() {
        val request = MobileSyncScheduler.buildContinuationRequest(
            networkType = NetworkType.CONNECTED,
            continuationDepth = 1
        )

        assertEquals(USAGE_CONTINUATION_DELAY_SECONDS, TimeUnit.MILLISECONDS.toSeconds(request.workSpec.initialDelay))
        assertEquals(
            1,
            request.workSpec.input.getInt(MobileSyncScheduler.CONTINUATION_DEPTH_KEY, -1)
        )
        assertEquals(
            false,
            request.workSpec.input.getBoolean(MobileSyncScheduler.MANUAL_RECOVERY_RETRY_KEY, true)
        )
        assertEquals(BackoffPolicy.EXPONENTIAL, request.workSpec.backoffPolicy)
        assertEquals(30, TimeUnit.MILLISECONDS.toSeconds(request.workSpec.backoffDelayDuration))
    }

    @Test
    fun manualSyncRequestCarriesExplicitRecoveryIntent() {
        val request = MobileSyncScheduler.buildImmediateRequest(
            networkType = NetworkType.CONNECTED,
            allowMeteredOnce = false,
            manualRecoveryRetry = true
        )

        assertEquals(
            true,
            request.workSpec.input.getBoolean(MobileSyncScheduler.MANUAL_RECOVERY_RETRY_KEY, false)
        )
        assertEquals(0L, request.workSpec.initialDelay)
    }

    @Test
    fun legacyClaimRequestCarriesExpectedOwnerAndServerBinding() {
        val claim = LegacyOutboxClaimRequest(
            serverIdentity = "https://pim.example",
            ownerId = "11111111-1111-1111-1111-111111111111"
        )
        val request = MobileSyncScheduler.buildImmediateRequest(
            networkType = NetworkType.CONNECTED,
            allowMeteredOnce = false,
            legacyOutboxClaim = claim
        )

        assertEquals(
            claim.serverIdentity,
            request.workSpec.input.getString(MobileSyncScheduler.LEGACY_CLAIM_SERVER_KEY)
        )
        assertEquals(
            claim.ownerId,
            request.workSpec.input.getString(MobileSyncScheduler.LEGACY_CLAIM_OWNER_KEY)
        )
    }

    // --- resolveExistingWorkPolicy ---

    @Test
    fun defaultEnqueueNowUsesReplacePolicy() {
        assertEquals(ExistingWorkPolicy.REPLACE, MobileSyncScheduler.resolveExistingWorkPolicy(false))
    }

    @Test
    fun allowMeteredOnceEnqueueNowUsesReplacePolicy() {
        assertEquals(ExistingWorkPolicy.REPLACE, MobileSyncScheduler.resolveExistingWorkPolicy(true))
    }
}
