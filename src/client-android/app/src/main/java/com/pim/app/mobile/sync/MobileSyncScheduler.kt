package com.pim.app.mobile.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.pim.app.settings.TrackingSettings
import com.pim.app.settings.TrackingSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MobileSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackingSettingsStore: TrackingSettingsStore
) {
    fun ensurePeriodic() {
        val settings = trackingSettingsStore.read()
        val networkType = resolvePeriodicNetworkType(settings)
        val request = buildPeriodicRequest(networkType)
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun enqueueNow(
        allowMeteredOnce: Boolean = false,
        manualRecoveryRetry: Boolean = false,
        legacyOutboxClaim: LegacyOutboxClaimRequest? = null
    ) {
        val settings = trackingSettingsStore.read()
        val networkType = resolveImmediateNetworkType(settings, allowMeteredOnce)
        val request = buildImmediateRequest(
            networkType = networkType,
            allowMeteredOnce = allowMeteredOnce,
            manualRecoveryRetry = manualRecoveryRetry,
            legacyOutboxClaim = legacyOutboxClaim
        )
        val policy = resolveExistingWorkPolicy(allowMeteredOnce)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW_NAME, policy, request)
    }

    fun enqueueContinuation(continuationDepth: Int = 1) {
        val settings = trackingSettingsStore.read()
        val networkType = resolveImmediateNetworkType(settings, allowMeteredOnce = false)
        val request = buildContinuationRequest(networkType, continuationDepth)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueNotBefore(delaySeconds: Long) {
        val bounded = delaySeconds.coerceIn(1L, USAGE_NOT_BEFORE_MAX_DELAY_SECONDS)
        val settings = trackingSettingsStore.read()
        val request = buildImmediateRequest(
            networkType = resolveImmediateNetworkType(settings, allowMeteredOnce = false),
            allowMeteredOnce = false,
            continuationDepth = 0,
            initialDelaySeconds = bounded
        )
        // A replacement prevents expired 202 responses from building an unbounded chain.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelOldWork() {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("pim_upload")
        workManager.cancelUniqueWork("pim_location_upload")
        workManager.cancelUniqueWork("pim_mobile_background_sync")
        workManager.cancelUniqueWork("pim_endpoint_upload")
    }

    companion object {
        const val PERIODIC_NAME = "pim_mobile_sync_periodic"
        const val NOW_NAME = "pim_mobile_sync_now"
        const val USAGE_NOT_BEFORE_MAX_DELAY_SECONDS = 24L * 60L * 60L

        fun resolvePeriodicNetworkType(settings: TrackingSettings): NetworkType {
            return if (settings.syncOnUnmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        }

        fun resolveImmediateNetworkType(settings: TrackingSettings, allowMeteredOnce: Boolean): NetworkType {
            return if (settings.syncOnUnmeteredOnly && !allowMeteredOnce) NetworkType.UNMETERED else NetworkType.CONNECTED
        }

        fun buildImmediateInputData(
            allowMeteredOnce: Boolean,
            continuationDepth: Int = 0,
            manualRecoveryRetry: Boolean = false,
            legacyOutboxClaim: LegacyOutboxClaimRequest? = null
        ): Data {
            return workDataOf(
                "allow_metered_once" to allowMeteredOnce,
                CONTINUATION_DEPTH_KEY to continuationDepth.coerceAtLeast(0),
                MANUAL_RECOVERY_RETRY_KEY to manualRecoveryRetry,
                LEGACY_CLAIM_SERVER_KEY to legacyOutboxClaim?.serverIdentity.orEmpty(),
                LEGACY_CLAIM_OWNER_KEY to legacyOutboxClaim?.ownerId.orEmpty()
            )
        }

        fun buildPeriodicRequest(networkType: NetworkType): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
            return PeriodicWorkRequestBuilder<MobileSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        }

        fun buildImmediateRequest(
            networkType: NetworkType,
            allowMeteredOnce: Boolean,
            continuationDepth: Int = 0,
            initialDelaySeconds: Long = 0,
            manualRecoveryRetry: Boolean = false,
            legacyOutboxClaim: LegacyOutboxClaimRequest? = null
        ): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .build()
            val inputData = buildImmediateInputData(
                allowMeteredOnce = allowMeteredOnce,
                continuationDepth = continuationDepth,
                manualRecoveryRetry = manualRecoveryRetry,
                legacyOutboxClaim = legacyOutboxClaim
            )
            val builder = OneTimeWorkRequestBuilder<MobileSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(inputData)
            if (initialDelaySeconds > 0) {
                builder.setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            }
            return builder.build()
        }

        fun buildContinuationRequest(
            networkType: NetworkType,
            continuationDepth: Int
        ): OneTimeWorkRequest {
            return buildImmediateRequest(
                networkType = networkType,
                allowMeteredOnce = false,
                continuationDepth = continuationDepth,
                initialDelaySeconds = USAGE_CONTINUATION_DELAY_SECONDS,
                manualRecoveryRetry = false,
                legacyOutboxClaim = null
            )
        }

        fun resolveExistingWorkPolicy(@Suppress("UNUSED_PARAMETER") allowMeteredOnce: Boolean = false): ExistingWorkPolicy {
            return ExistingWorkPolicy.REPLACE
        }

        const val CONTINUATION_DEPTH_KEY = "continuation_depth"
        const val MANUAL_RECOVERY_RETRY_KEY = "manual_recovery_retry"
        const val LEGACY_CLAIM_SERVER_KEY = "legacy_claim_server"
        const val LEGACY_CLAIM_OWNER_KEY = "legacy_claim_owner"
    }
}
