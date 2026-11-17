package com.pim.app.mobile.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** 生产实现：读 [MobileSyncCoordinator] 已持久化的同步状态（跨进程重启仍然有效）。 */
@Singleton
class SyncStateUploadOutcomeSource @Inject constructor(
    private val coordinator: MobileSyncCoordinator
) : UploadOutcomeSource {
    override fun current(): UploadOutcome {
        val state = coordinator.currentState.value
        return UploadOutcome(
            lastError = state.lastError,
            hasCompletedUpload = state.lastSuccessfulUploadAt != null,
            // 原因由 Coordinator 在每一轮唤醒结束时记账（#569），心跳只负责如实上报。
            lastWakeBlockReason = coordinator.lastWakeBlockReason()
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EndpointHeartbeatModule {
    @Binds
    @Singleton
    abstract fun bindUploadOutcomeSource(impl: SyncStateUploadOutcomeSource): UploadOutcomeSource

    @Binds
    @Singleton
    abstract fun bindEndpointHeartbeatSink(impl: EndpointHeartbeatReporter): EndpointHeartbeatSink
}
