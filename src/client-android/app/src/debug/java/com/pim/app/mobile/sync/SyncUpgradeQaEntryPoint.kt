package com.pim.app.mobile.sync

import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.mobile.usage.AppMetadataCollector
import com.pim.app.mobile.usage.UsageAccessChecker
import com.pim.app.mobile.usage.UsageEventCollector
import com.pim.core.auth.TokenManager
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.ApiService
import com.pim.core.settings.ServerSettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Debug-only QA hook. The AndroidTest source uses this entry point to obtain the
 * production singleton graph while injecting only the isolated test database.
 */
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
