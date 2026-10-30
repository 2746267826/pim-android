package com.pim.app.mobile.sync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import javax.inject.Provider

import com.pim.app.data.AppDatabase
import com.pim.app.data.MobileAppMetadataEntity
import com.pim.app.data.MobileDataDao
import com.pim.app.data.MobileDeviceProfileEntity
import com.pim.app.data.MobileSyncStatus
import com.pim.app.data.MobileUsageEventEntity
import com.pim.app.data.MobileUsageSummaryEntity
import com.pim.app.forensics.ForensicUploadCoordinator
import com.pim.app.forensics.WakeHeartbeatRecorder
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.mobile.usage.AppMetadataCollector
import com.pim.app.mobile.usage.UsageAccessChecker
import com.pim.app.mobile.usage.UsageEventCollector
import com.pim.core.auth.TokenManager
import com.pim.core.contract.MobileAppMetadataDto
import com.pim.core.contract.MobileDeviceRegisterRequest
import com.pim.core.contract.MobileGapRequest
import com.pim.core.contract.MobileUsageEventDto
import com.pim.core.contract.MobileUsageEventsUploadRequest
import com.pim.core.contract.MobileUsageIngestResult
import com.pim.core.contract.MobileUsageSummaryDto
import com.pim.core.network.ApiService
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.BoundUploadSession
import com.pim.core.network.UploadSessionChangedException
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import com.pim.core.util.toCauseChainMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import retrofit2.HttpException
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class MobileSyncState(
    val phase: String,
    val progressText: String,
    val isInProgress: Boolean = false,
    val outcome: MobileSyncOutcome = MobileSyncOutcome.SUCCESS,
    val acceptedCount: Int = 0,
    val skippedCount: Int = 0,
    val rejectedCount: Int = 0,
    val failedCount: Int = 0,
    val lastError: String? = null,
    val lastErrorDetail: String? = null,
    val pendingQueueCount: Int = 0,
    val gapWindowCount: Int = 0,
    val currentWindowIndex: Int = 0,
    val currentWindowStartUtc: String? = null,
    val currentWindowEndUtc: String? = null,
    val currentEventCount: Int = 0,
    val currentSummaryCount: Int = 0,
    val currentAppMetadataCount: Int = 0,
    val lastBatchId: String? = null,
    val lastBatchStatus: String? = null,
    val heartbeatStatus: String? = null,
    val lastAttemptedUploadAt: String? = null,
    val lastSuccessfulUploadAt: String? = null
)

internal const val MAX_USAGE_BATCHES_PER_RUN = 10
internal const val MAX_USAGE_BATCH_DURATION_MS = 120_000L
/**
 * 单次上传的总条目预算，不是每类各自的预算。
 *
 * API P0-B 兼容上限为 1,500；这里主动取三分之一，既沿用既有 500 测试常量，
 * 也把 #525 中约 293KB 的混合请求压到约百 KB 量级。更低预算不改变 wire 契约。
 */
internal const val USAGE_BATCH_LIMIT = 500
internal const val USAGE_BATCH_TARGET_WINDOW_MS = 2L * 60L * 60L * 1000L
internal const val USAGE_BATCH_MAX_WINDOW_MS = 25L * 60L * 60L * 1000L
internal const val MAX_USAGE_CONTINUATIONS_PER_CHAIN = 2
internal const val USAGE_CONTINUATION_DELAY_SECONDS = 30L
internal const val SERVER_USAGE_MAX_ATTEMPTS = 5
internal const val SERVER_CODE_RETRY_EXHAUSTED = 40903
internal const val SERVER_CODE_PAYLOAD_CONFLICT = 40902
internal const val SERVER_CODE_LEASE_LOST = 40904

/**
 * 单次同步最多补传多少批取证事件。这只是「单次运行的工作量上界」，
 * **不是**取证事件的本地条数上限（R4-P3 明确不设条数上限，靠 30 天时间清理兜底）。
 */
internal const val MAX_FORENSICS_BATCHES_PER_RUN = 10

@Singleton
class MobileSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val apiClientProvider: ApiClientProvider,
    private val tokenManager: TokenManager,
    private val usageAccessChecker: UsageAccessChecker,
    private val usageEventCollector: UsageEventCollector,
    private val appMetadataCollector: AppMetadataCollector,
    private val database: AppDatabase,
    private val logs: StructuredLogRepository,
    private val heartbeatReporter: MobileHeartbeatReporter,
    private val serverSettingsStore: ServerSettingsStore,
    private val locationUploadCoordinator: LocationUploadCoordinator,
    private val forensicUploadCoordinator: ForensicUploadCoordinator,
    private val wakeHeartbeatRecorder: WakeHeartbeatRecorder,
    private val syncScheduler: Provider<MobileSyncScheduler>,
    private val deviceIdentityProvider: DeviceIdentityProvider
) {
    private val mobileDataDao = database.mobileDataDao()
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(readPersistedState())
    val currentState: StateFlow<MobileSyncState> = _state.asStateFlow()

    suspend fun legacyOutboxRecoveryPreview(): UsageOutboxRecoveryPreview? {
        val pendingCount = pendingUsageRemaining(mobileDataDao)
        if (pendingCount == 0) return null
        val current = currentUsageOutboxOwnerBinding()
        val action = evaluateUsageOutboxBinding(
            stored = storedUsageOutboxOwnerBinding(),
            current = current,
            pendingCount = pendingCount
        )
        if (action != UsageOutboxBindingAction.UNBOUND_LEGACY_QUEUE) return null
        val range = mobileDataDao.pendingUsageTimeRange()
        return UsageOutboxRecoveryPreview(
            serverIdentity = current.serverIdentity,
            ownerId = current.ownerId,
            ownerName = tokenManager.snapshot().ownerName,
            pendingCount = pendingCount,
            earliestUtc = range.earliestUtc,
            latestUtc = range.latestUtc
        )
    }

    suspend fun syncOnOpen(
        continuationDepth: Int = 0,
        manualRecoveryRetry: Boolean = false,
        legacyOutboxClaim: LegacyOutboxClaimRequest? = null
    ): MobileSyncState {
        // REQ-3 / 缺陷 #345：周期同步也是一次唤醒，必须写一条心搏。
        // 放在乐观锁**之前**，是因为"唤醒发生了"与"本次同步是否真的跑起来"是两件事：
        // 排队等锁而被跳过的那次唤醒同样是真实唤醒，也要被记下来。
        // 所有同步结局（未配置服务器、缺令牌、缺权限、正常同步、同步失败）都覆盖到。
        // 同一秒内重复唤醒由台账去重（AC-3.3）；写心搏失败只记日志，绝不改变同步结果（AC-3.3）。
        recordWakeHeartbeat()

        if (!syncMutex.tryLock()) {
            val running = _state.value.copy(
                isInProgress = true,
                progressText = "同步正在进行中。"
            )
            persistState(running)
            // This wake performed no sync work; never use stale success state to schedule continuation.
            return running
        }

        val resultState = try {
            runSyncOnOpen(manualRecoveryRetry, legacyOutboxClaim)
        } finally {
            syncMutex.unlock()
        }

        val safeContinuationDepth = continuationDepth.coerceAtLeast(0)
        if (shouldEnqueueUsageContinuation(resultState, safeContinuationDepth, didWork = true)) {
            syncScheduler.get().enqueueContinuation(safeContinuationDepth + 1)
        }

        return resultState
    }

    /**
     * 记录一次同步唤醒。**不新增任何轮询或闹钟**（AC-29.1）：这里只被既有的周期同步作业
     * 与用户触发的立即同步调用，自身不调度任何东西。
     */
    private suspend fun recordWakeHeartbeat() {
        try {
            wakeHeartbeatRecorder.record()
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logs.warn("forensics", "同步唤醒写心搏失败：${ex.message ?: ""}")
        }
    }

    fun refreshPersistedState() {
        _state.value = readPersistedState()
    }

    private suspend fun runSyncOnOpen(
        manualRecoveryRetry: Boolean,
        legacyOutboxClaim: LegacyOutboxClaimRequest?
    ): MobileSyncState {
        val attemptedAt = nowIso()
        val serverUrl = configuredServerUrl()
        val deviceIdentity = deviceIdentity()
        val hasToken = !tokenManager
            .getAccessTokenForServer(serverSettingsStore.getBaseUrl())
            .isNullOrBlank()
        val hasUsageAccess = usageAccessChecker.hasUsageAccess()

        if (serverUrl.isBlank()) {
            return finishWithLocalError(
                deviceId = deviceIdentity.deviceId,
                serverUrl = serverUrl,
                usagePermissionGranted = hasUsageAccess,
                attemptedAt = attemptedAt,
                phase = "server-missing",
                message = "服务器地址未配置，已跳过同步。"
            )
        }

        if (!hasToken) {
            logs.warn("mobile-sync", "缺少登录令牌，已跳过同步。")
            val authMissing = state(
                phase = "auth-missing",
                progressText = "缺少登录令牌，已跳过同步。请登录后重新同步。",
                outcome = MobileSyncOutcome.BLOCKED,
                lastError = "缺少登录令牌",
                lastAttemptedUploadAt = attemptedAt
            )
            persistState(authMissing)
            return authMissing
        }

        if (!hasUsageAccess) {
            logs.warn("mobile-sync", "缺少应用使用情况权限，已跳过使用记录同步。")
            val missingPermissionState = state(
                phase = "usage-permission-missing",
                progressText = "缺少应用使用情况权限，已跳过同步。",
                outcome = MobileSyncOutcome.BLOCKED,
                skippedCount = 1,
                lastError = "缺少应用使用情况权限",
                lastAttemptedUploadAt = attemptedAt
            )
            persistState(missingPermissionState)
            val locationState = uploadQueuedLocations(missingPermissionState, attemptedAt)
            // AC-5.3：取证上报与使用记录权限无关，缺权限时也照常补传，且失败不影响定位补传。
            uploadForensics()
            sendHeartbeat(deviceIdentity.deviceId, serverUrl, false, locationState)
            return locationState
        }

        return try {
            logs.info(
                "mobile-sync",
                "开始执行打开 App 后的手机同步。",
                mapOf("deviceId" to deviceIdentity.deviceId, "serverUrl" to serverUrl)
            )

            val preparing = state(
                phase = "preparing",
                progressText = "正在注册设备并准备同步。",
                isInProgress = true,
                pendingQueueCount = pendingQueueCount(),
                lastAttemptedUploadAt = attemptedAt
            )
            persistState(preparing)

            val profile = buildDeviceProfile(deviceIdentity, nowUtc = System.currentTimeMillis())
            mobileDataDao.upsertDeviceProfile(profile)
            registerDevice(deviceIdentity, profile)
            mobileDataDao.updateDeviceProfileSyncStatus(syncStatus = MobileSyncStatus.SYNCED)

            var current = preparing
            val outboxBindingAction = ensureUsageOutboxBinding(legacyOutboxClaim)
            if (outboxBindingAction != UsageOutboxBindingAction.ALLOWED) {
                val bindingMessage = usageOutboxBindingMessage(outboxBindingAction)
                val blocked = current.copy(
                    phase = "usage-outbox-binding-blocked",
                    progressText = bindingMessage,
                    outcome = MobileSyncOutcome.BLOCKED,
                    failedCount = pendingUsageRemaining(mobileDataDao),
                    lastError = bindingMessage,
                    lastErrorDetail = bindingMessage,
                    pendingQueueCount = pendingQueueCount(),
                    lastAttemptedUploadAt = attemptedAt
                )
                val locationState = uploadQueuedLocations(blocked, attemptedAt)
                val finalState = blocked.copy(
                    acceptedCount = blocked.acceptedCount + locationState.acceptedCount,
                    rejectedCount = blocked.rejectedCount + locationState.rejectedCount,
                    pendingQueueCount = pendingQueueCount(),
                    lastAttemptedUploadAt = attemptedAt
                )
                uploadForensics()
                persistState(finalState)
                sendHeartbeat(deviceIdentity.deviceId, serverUrl, true, finalState)
                return finalState
            }
            val oldQueueState = uploadQueuedUsage(
                deviceId = deviceIdentity.deviceId,
                attemptedAt = attemptedAt,
                manualRecoveryRetry = manualRecoveryRetry
            )
            var lastUsageUploadState = oldQueueState
            if (oldQueueState != null) {
                current = current.merge(oldQueueState)
                val remaining = pendingUsageRemaining(mobileDataDao)
                if (oldQueueState.outcome == MobileSyncOutcome.RETRY || remaining > 0) {
                    current = uploadQueuedLocations(current, attemptedAt)
                    val isTrueFailure = oldQueueState.outcome == MobileSyncOutcome.RETRY || current.failedCount > 0
                    val outcome = when {
                        oldQueueState.outcome == MobileSyncOutcome.BLOCKED -> MobileSyncOutcome.BLOCKED
                        isTrueFailure -> MobileSyncOutcome.RETRY
                        else -> MobileSyncOutcome.SUCCESS
                    }
                    val finalRemaining = pendingUsageRemaining(mobileDataDao)
                    val finalState = current.copy(
                        phase = resolvePostUsageSyncPhase(
                            usagePhase = oldQueueState.phase,
                            locationPhase = current.phase,
                            failedCount = current.failedCount,
                            remaining = finalRemaining
                        ),
                        progressText = when {
                            oldQueueState.phase == "upload-blocked" ||
                                oldQueueState.phase == "usage-outbox-binding-blocked" ->
                                oldQueueState.progressText
                            current.phase == "location-upload-failed" -> current.progressText
                            finalRemaining > 0 && !isTrueFailure -> "正在补传（剩 $finalRemaining 条）。"
                            oldQueueState.progressText.isNotBlank() -> oldQueueState.progressText
                            else -> "手机同步已完成，但部分使用记录待重试。"
                        },
                        outcome = outcome,
                        isInProgress = false,
                        pendingQueueCount = pendingQueueCount(),
                        lastAttemptedUploadAt = attemptedAt
                    )
                    persistState(finalState)
                    sendHeartbeat(deviceIdentity.deviceId, serverUrl, true, finalState)
                    return finalState
                }
            }

            val rangeEndUtc = System.currentTimeMillis()
            val rangeStartUtc = rangeEndUtc - FOURTEEN_DAYS_MS
            val gapChecking = state(
                phase = "gap-checking",
                progressText = "正在询问服务器缺失时间窗。",
                isInProgress = true,
                pendingQueueCount = pendingQueueCount(),
                lastAttemptedUploadAt = attemptedAt
            )
            persistState(oldQueueState?.let { gapChecking.merge(it) } ?: gapChecking)
            val gapResponse = api.getMobileGaps(
                MobileGapRequest(
                    deviceId = deviceIdentity.deviceId,
                    rangeStartUtc = iso(rangeStartUtc),
                    rangeEndUtc = iso(rangeEndUtc),
                    capabilityJson = capabilityJson(hasUsageAccess)
                )
            )

            val gapData = gapResponse.data
            if (gapResponse.code != 0 || gapData == null) {
                throw IllegalStateException(gapResponse.message.orEmpty().ifBlank { "服务器缺口查询失败。" })
            }

            val serverWindows = gapData.windows.mapNotNull { window ->
                val originalStart = parseIsoMillis(window.windowStartUtc)
                val originalEnd = parseIsoMillis(window.windowEndUtc)
                clampGapWindow(
                    windowStartUtc = originalStart,
                    windowEndUtc = originalEnd,
                    maxBackfillStartUtc = parseIsoMillis(gapData.maxBackfillStartUtc),
                    nowUtc = rangeEndUtc
                )?.also { clamped ->
                    if (clamped.windowStartUtc != originalStart || clamped.windowEndUtc != originalEnd) {
                        logs.warn(
                            "mobile-sync",
                            "服务器缺口窗口已按 Android 14 天补全上限裁剪。",
                            mapOf(
                                "originalStartUtc" to window.windowStartUtc,
                                "originalEndUtc" to window.windowEndUtc,
                                "clampedStartUtc" to iso(clamped.windowStartUtc),
                                "clampedEndUtc" to iso(clamped.windowEndUtc)
                            )
                        )
                    }
                }
            }
            val windows = serverWindows.flatMap { window ->
                splitGapWindowForUpload(window.windowStartUtc, window.windowEndUtc)
            }
            logs.info(
                "mobile-sync",
                "服务器返回 ${serverWindows.size} 个缺口窗口，已拆为 ${windows.size} 个上传窗口。",
                mapOf("serverWindowCount" to serverWindows.size, "uploadWindowCount" to windows.size)
            )

            current = state(
                phase = "collecting",
                progressText = "正在采集服务器要求补全的窗口。",
                isInProgress = true,
                gapWindowCount = windows.size,
                pendingQueueCount = pendingQueueCount(),
                lastAttemptedUploadAt = attemptedAt
            )
            current = oldQueueState?.let { current.merge(it) } ?: current
            persistState(current)

            for ((index, window) in windows.withIndex()) {
                val windowStartUtc = iso(window.windowStartUtc)
                val windowEndUtc = iso(window.windowEndUtc)
                current = current.copy(
                    phase = "collecting",
                    progressText = "正在采集第 ${index + 1}/${windows.size} 个窗口。",
                    isInProgress = true,
                    currentWindowIndex = index + 1,
                    currentWindowStartUtc = windowStartUtc,
                    currentWindowEndUtc = windowEndUtc,
                    gapWindowCount = windows.size,
                    pendingQueueCount = pendingQueueCount(),
                    lastAttemptedUploadAt = attemptedAt
                )
                persistState(current)

                logs.info(
                    "mobile-sync",
                    "正在采集服务器缺口窗口的使用记录。",
                    mapOf(
                        "windowStartUtc" to windowStartUtc,
                        "windowEndUtc" to windowEndUtc
                    )
                )

                val collection = usageEventCollector.collectUsage(window.windowStartUtc, window.windowEndUtc)
                mobileDataDao.insertUsageEvents(collection.events)
                mobileDataDao.insertUsageSummaries(collection.summaries)
                val packageNames = packageNames(collection.events, collection.summaries)
                val appMetadata = appMetadataCollector.collectForPackages(packageNames)
                if (appMetadata.isNotEmpty()) {
                    mobileDataDao.upsertAppMetadata(appMetadata)
                }
                if (isEmptyGapWindowUpload(collection.events, collection.summaries, appMetadata)) {
                    logs.info(
                        "mobile-sync",
                        "缺口窗口未采集到任何条目，跳过上传（不产生空载批次，窗口保持真实缺口状态）。",
                        mapOf(
                            "windowStartUtc" to windowStartUtc,
                            "windowEndUtc" to windowEndUtc
                        )
                    )
                    continue
                }

                current = current.copy(
                    phase = "uploading",
                    progressText = "正在上传第 ${index + 1}/${windows.size} 个窗口。",
                    isInProgress = true,
                    currentWindowIndex = index + 1,
                    currentWindowStartUtc = windowStartUtc,
                    currentWindowEndUtc = windowEndUtc,
                    currentEventCount = collection.events.size,
                    currentSummaryCount = collection.summaries.size,
                    currentAppMetadataCount = appMetadata.size,
                    pendingQueueCount = pendingQueueCount(),
                    lastAttemptedUploadAt = attemptedAt
                )
                persistState(current)

                val uploadState = uploadQueuedUsage(
                    deviceId = deviceIdentity.deviceId,
                    attemptedAt = attemptedAt,
                    manualRecoveryRetry = manualRecoveryRetry
                )
                if (uploadState == null) {
                    logs.warn(
                        "mobile-sync",
                        "缺口窗口已落本地，但分区上传未取得可发送批次。",
                        mapOf(
                            "windowStartUtc" to windowStartUtc,
                            "windowEndUtc" to windowEndUtc
                        )
                    )
                    continue
                }
                lastUsageUploadState = uploadState

                val merged = current.merge(uploadState)
                val hasUploadErrors = merged.failedCount > 0 || merged.lastError != null
                current = merged.copy(
                    phase = when {
                        uploadState.phase == "upload-blocked" ||
                            uploadState.phase == "usage-outbox-binding-blocked" ||
                            uploadState.phase == "old-queue-upload-failed" ||
                            uploadState.phase == "upload-failed" -> uploadState.phase
                        uploadState.phase == "catching-up" -> "catching-up"
                        hasUploadErrors -> "upload-failed"
                        else -> "uploading"
                    },
                    progressText = when {
                        hasUploadErrors || uploadState.phase == "catching-up" ->
                            merged.lastError ?: uploadState.progressText
                        else -> "第 ${index + 1}/${windows.size} 个窗口上传完成。"
                    },
                    isInProgress = true,
                    gapWindowCount = windows.size,
                    currentWindowIndex = index + 1,
                    currentWindowStartUtc = windowStartUtc,
                    currentWindowEndUtc = windowEndUtc,
                    currentEventCount = collection.events.size,
                    currentSummaryCount = collection.summaries.size,
                    currentAppMetadataCount = appMetadata.size,
                    lastBatchId = uploadState.lastBatchId,
                    lastBatchStatus = uploadState.lastBatchStatus,
                    pendingQueueCount = pendingQueueCount(),
                    lastAttemptedUploadAt = attemptedAt
                )
                persistState(current)

                if (uploadState.outcome != MobileSyncOutcome.SUCCESS ||
                    pendingUsageRemaining(mobileDataDao) > 0
                ) {
                    break
                }
            }

            current = uploadQueuedLocations(current, attemptedAt)

            // 取证事件与丢弃原因统计顺带在既有同步周期内上传（REQ-5 / REQ-9 AC-9.2）。
            // 它有自己的失败出口（本地留存 + 结构化日志），不会把既有同步判成失败（AC-5.3）。
            uploadForensics()

            val finalRemaining = pendingUsageRemaining(mobileDataDao)
            val finalPhase = resolvePostUsageSyncPhase(
                usagePhase = lastUsageUploadState?.phase.orEmpty(),
                locationPhase = current.phase,
                failedCount = current.failedCount,
                remaining = finalRemaining
            )
            val completed = current.copy(
                phase = finalPhase,
                progressText = when (finalPhase) {
                    "catching-up" -> "正在补传（剩 $finalRemaining 条）。"
                    "upload-blocked",
                    "usage-outbox-binding-blocked",
                    "old-queue-upload-failed",
                    "upload-failed" -> current.progressText
                    "completed-with-errors" -> "手机同步已完成，但部分上传失败。"
                    else -> "手机同步已完成。"
                },
                isInProgress = false,
                pendingQueueCount = pendingQueueCount(),
                lastSuccessfulUploadAt = if (
                    current.outcome == MobileSyncOutcome.SUCCESS &&
                    current.failedCount == 0 &&
                    finalRemaining == 0
                ) {
                    nowIso()
                } else {
                    current.lastSuccessfulUploadAt
                }
            )
            persistState(completed)
            sendHeartbeat(deviceIdentity.deviceId, serverUrl, true, completed)
            logs.info(
                "mobile-sync",
                "手机同步已完成。",
                mapOf(
                    "acceptedCount" to completed.acceptedCount,
                    "skippedCount" to completed.skippedCount,
                    "rejectedCount" to completed.rejectedCount,
                    "failedCount" to completed.failedCount
                )
            )
            completed
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val previous = _state.value
            val detail = ex.toCauseChainMessage()
            val outcome = MobileSyncErrorClassifier.classify(ex)
            var failed = previous.copy(
                phase = "failed",
                progressText = "手机同步失败。",
                isInProgress = false,
                outcome = outcome,
                failedCount = maxOf(1, previous.failedCount),
                lastError = ex.message ?: ex::class.java.simpleName,
                lastErrorDetail = detail,
                pendingQueueCount = pendingQueueCount(),
                lastAttemptedUploadAt = attemptedAt
            )
            logs.error("mobile-sync", "手机同步失败：$detail", ex)
            try {
                failed = uploadQueuedLocations(failed, attemptedAt)
            } catch (locEx: Exception) {
                if (locEx is CancellationException) throw locEx
                logs.warn("mobile-sync", "在异常处理流程中上传位置也失败", mapOf("error" to (locEx.message ?: "")))
            }
            persistState(failed)
            sendHeartbeat(deviceIdentity.deviceId, serverUrl, true, failed)
            failed
        }
    }

    /**
     * 把本地待传的取证事件与丢弃原因统计补齐上报（REQ-5 / REQ-9）。
     *
     * 循环直到队列排空（或达到单次运行的批次数上限），因此断网期间积压的事件会在恢复联网后的
     * 第一个同步周期内全部到达，满足 AC-5.1 的"三个周期内全部到达"。
     * 任何异常都在 [ForensicUploadCoordinator] 内部收敛：这里只记日志，绝不把同步判成失败（AC-5.3）。
     */
    private suspend fun uploadForensics() {
        var uploaded = 0
        var batches = 0
        while (batches < MAX_FORENSICS_BATCHES_PER_RUN) {
            currentCoroutineContext().ensureActive()
            batches++
            val count = try {
                forensicUploadCoordinator.uploadPending()
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logs.warn(
                    "forensics-sync",
                    "取证数据上报异常（已本地留存，下个周期重试）：${ex.message ?: ""}"
                )
                return
            }
            uploaded += count
            if (count == 0) break
        }

        if (uploaded > 0) {
            val remaining = forensicUploadCoordinator.pendingCount()
            logs.info(
                "forensics-sync",
                "本轮取证数据上报 $uploaded 条，剩余待传 $remaining 条。"
            )
        }
    }

    /**
     * 上传本地积压的定位点。
     *
     * 走**批量端点**并**循环到队列排空**（上限 [MAX_LOCATION_BATCHES_PER_RUN] 批，每批
     * [LOCATION_UPLOAD_BATCH_LIMIT] 条）——断网/高频采集期间积压的点在恢复联网后的第一个
     * 同步周期内追平，而不是每个 15 分钟周期只吃一批。出现可重试失败时停止本轮，
     * 保持既有节奏由 WorkManager 重试。
     */
    private suspend fun uploadQueuedLocations(
        current: MobileSyncState,
        attemptedAt: String
    ): MobileSyncState {
        val updates = locationUploadCoordinator.uploadPendingUntilDrained()
        if (updates.syncedIds.isEmpty() && updates.failedIds.isEmpty()) {
            val idle = current.copy(
                pendingQueueCount = pendingQueueCount(),
                lastAttemptedUploadAt = attemptedAt
            )
            persistState(idle)
            return idle
        }

        val syncedCount = updates.syncedIds.size
        val retryableCount = updates.retryableFailedIds.size
        val rejectedCount = updates.failedIds.size - retryableCount
        val hasRetryable = updates.shouldRetry || retryableCount > 0
        val next = current.copy(
            phase = when {
                hasRetryable -> "location-upload-failed"
                current.phase == "usage-permission-missing" -> current.phase
                else -> "location-uploaded"
            },
            progressText = when {
                hasRetryable -> "定位队列上传失败，已安排网络重试。"
                updates.truncated -> "定位队列本轮已同步 $syncedCount 条，仍有积压待继续追平。"
                current.phase == "usage-permission-missing" ->
                    "${current.progressText} 定位队列已同步 $syncedCount 条。"
                else -> "定位队列已同步 $syncedCount 条。"
            },
            outcome = if (hasRetryable) MobileSyncOutcome.RETRY else current.outcome,
            acceptedCount = current.acceptedCount + syncedCount,
            rejectedCount = current.rejectedCount + rejectedCount,
            failedCount = current.failedCount + retryableCount,
            lastError = if (hasRetryable) {
                updates.retryableFirstError() ?: updates.failedReason ?: current.lastError
            } else current.lastError,
            lastErrorDetail = if (hasRetryable) {
                updates.retryableFirstError() ?: updates.failedReason ?: current.lastErrorDetail
            } else current.lastErrorDetail,
            pendingQueueCount = pendingQueueCount(),
            lastAttemptedUploadAt = attemptedAt
        )
        persistState(next)

        val details = mapOf(
            "syncedCount" to syncedCount,
            "rejectedCount" to rejectedCount,
            "retryableCount" to retryableCount,
            "truncated" to updates.truncated
        )
        if (hasRetryable) {
            logs.warn("mobile-location-sync", "定位队列上传未完成，已安排 WorkManager 重试。", details)
        } else {
            logs.info("mobile-location-sync", "定位队列上传完成。", details)
        }
        return next
    }

    private suspend fun uploadWindow(
        deviceId: String,
        windowStartUtc: String,
        windowEndUtc: String,
        events: List<MobileUsageEventEntity>,
        summaries: List<MobileUsageSummaryEntity>,
        apps: List<MobileAppMetadataEntity>,
        eventIds: List<Long>,
        summaryIds: List<Long>,
        manualRecoveryRetry: Boolean
    ): MobileSyncState {
        val sentItems = linkedSetOf<MobileAcknowledgementItem>().apply {
            eventIds.forEach { add(MobileAcknowledgementItem("usage-event", it.toString())) }
            summaryIds.forEach { add(MobileAcknowledgementItem("usage-summary", it.toString())) }
            apps.forEach { add(MobileAcknowledgementItem("app-metadata", "${it.packageName}@${it.versionCode}")) }
        }
        val storedOutboxBinding = storedUsageOutboxOwnerBinding()
        if (storedOutboxBinding == null || !usageOutboxBindingAllowsCurrentUpload()) {
            val message = usageOutboxBindingMessage(UsageOutboxBindingAction.BINDING_MISMATCH)
            markUsageFailed(eventIds, summaryIds, apps, message)
            return state(
                phase = "usage-outbox-binding-blocked",
                progressText = message,
                outcome = MobileSyncOutcome.BLOCKED,
                failedCount = maxOf(1, sentItems.size),
                lastError = message,
                lastErrorDetail = message,
                currentWindowStartUtc = windowStartUtc,
                currentWindowEndUtc = windowEndUtc,
                currentEventCount = events.size,
                currentSummaryCount = summaries.size,
                currentAppMetadataCount = apps.size,
                lastBatchStatus = "outbox-binding-blocked",
                pendingQueueCount = pendingQueueCount()
            )
        }
        val usageUploadApi = apiClientProvider.mobileUsageUploadApiService(
            BoundUploadSession(
                serverIdentity = storedOutboxBinding.serverIdentity,
                ownerId = storedOutboxBinding.ownerId
            )
        )
        val payloadFingerprint = usageBatchPayloadFingerprint(events, summaries, apps)
        val recoveryBinding = usageBatchRecoveryBinding(payloadFingerprint)
        val baseBatchId = stableBatchId(
            deviceId = deviceId,
            windowStartUtc = windowStartUtc,
            windowEndUtc = windowEndUtc,
            items = sentItems,
            payloadFingerprint = payloadFingerprint
        )
        val recoveryDecision = if (manualRecoveryRetry) {
            prepareManualUsageBatchRecovery(baseBatchId, recoveryBinding)
        } else {
            val state = readUsageBatchRecoveryState(baseBatchId, recoveryBinding)
            UsageBatchRecoveryDecision(state = state, batchIdRotated = false)
        }
        val recoveryState = recoveryDecision.state
        if (recoveryState.blockedBy409) {
            val terminalMessage = recoveryState.lastTerminalError
                ?: "服务端以 409 阻止了该批次。"
            val userGuidance = when (recoveryState.blockReason) {
                UsageBatchBlockReason.RETRY_EXHAUSTED ->
                    "服务端已耗尽该批次重试次数。本地数据仍保留，可点击“立即同步”生成新的批次重试。"
                UsageBatchBlockReason.PAYLOAD_CONFLICT ->
                    "服务端检测到批次内容冲突。本地数据仍保留，需确认账号/服务器与批次内容后处理，不能自动换批次绕过。"
                UsageBatchBlockReason.LEASE_LOST ->
                    "服务端批次租约状态冲突。本地数据仍保留，请稍后手动同步或联系管理员。"
                UsageBatchBlockReason.UNKNOWN_CONFLICT, null ->
                    "服务端阻止了该批次。本地数据仍保留，请确认账号/服务器后手动同步，未知冲突不会自动换批次。"
            }
            logs.warn(
                "mobile-sync",
                terminalMessage,
                mapOf(
                    "baseBatchId" to baseBatchId,
                    "manualRecoveryRetry" to manualRecoveryRetry
                )
            )
            return state(
                phase = "upload-blocked",
                progressText = userGuidance,
                outcome = MobileSyncOutcome.BLOCKED,
                failedCount = sentItems.size,
                lastError = terminalMessage,
                lastErrorDetail = terminalMessage,
                currentWindowStartUtc = windowStartUtc,
                currentWindowEndUtc = windowEndUtc,
                currentEventCount = events.size,
                currentSummaryCount = summaries.size,
                currentAppMetadataCount = apps.size,
                lastBatchId = baseBatchId,
                lastBatchStatus = "server-blocked",
                pendingQueueCount = pendingQueueCount()
            )
        }
        if (recoveryDecision.batchIdRotated) {
            logs.info(
                "mobile-sync",
                "用户手动重试已终止批次，使用新的幂等 generation 上传仍在本地队列中的同一批数据。",
                mapOf(
                    "baseBatchId" to baseBatchId,
                    "recoveryEpoch" to recoveryState.recoveryEpoch
                )
            )
        }
        val batchId = stableBatchId(
            deviceId = deviceId,
            windowStartUtc = windowStartUtc,
            windowEndUtc = windowEndUtc,
            items = sentItems,
            payloadFingerprint = payloadFingerprint,
            recoveryEpoch = recoveryState.recoveryEpoch
        )
        val request = MobileUsageEventsUploadRequest(
            deviceId = deviceId,
            clientBatchId = batchId,
            sourceWindowStartUtc = windowStartUtc,
            sourceWindowEndUtc = windowEndUtc,
            apps = apps.map { it.toDto() },
            events = events.mapIndexed { index, event -> event.toDto(eventIds[index].toString()) },
            fallbackSummaries = summaries.mapIndexed { index, summary ->
                summary.toDto(summaryIds[index].toString())
            }
        )

        val response = try {
            usageUploadApi.uploadMobileUsage(request)
        } catch (ex: UploadSessionChangedException) {
            val message = "本地 usage 队列的 owner/server 在发送前已变化，已保留 pending，未发送到新账号。"
            markUsageFailed(eventIds, summaryIds, apps, message)
            logs.warn(
                "mobile-sync",
                message,
                mapOf(
                    "windowStartUtc" to windowStartUtc,
                    "windowEndUtc" to windowEndUtc
                )
            )
            return state(
                phase = "usage-outbox-binding-blocked",
                progressText = message,
                outcome = MobileSyncOutcome.BLOCKED,
                failedCount = maxOf(1, sentItems.size),
                lastError = message,
                lastErrorDetail = message,
                currentWindowStartUtc = windowStartUtc,
                currentWindowEndUtc = windowEndUtc,
                currentEventCount = events.size,
                currentSummaryCount = summaries.size,
                currentAppMetadataCount = apps.size,
                lastBatchId = batchId,
                lastBatchStatus = "outbox-session-changed",
                pendingQueueCount = pendingQueueCount()
            )
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            val conflict = (ex as? HttpException)?.takeIf { it.code() == 409 }
            val isConflict = conflict != null
            val serverCode = conflict?.let(::usageBatchHttpErrorCode)
            val blockReason = if (isConflict) {
                classifyUsageBatchConflictCode(serverCode)
            } else {
                null
            }
            val outcome = if (blockReason == UsageBatchBlockReason.LEASE_LOST) {
                MobileSyncOutcome.RETRY
            } else {
                MobileSyncErrorClassifier.classify(ex)
            }
            val rawMessage = ex.toCauseChainMessage()
            val message = if (serverCode != null) {
                "$rawMessage (server-code=$serverCode)"
            } else {
                rawMessage
            }
            if (isConflict && blockReason != null) {
                recordUsageBatchConflict(
                    baseBatchId = baseBatchId,
                    binding = recoveryBinding,
                    reason = blockReason,
                    message = message
                )
            }
            markUsageFailed(eventIds, summaryIds, apps, message)
            logs.warn(
                "mobile-sync",
                "使用记录上传异常：$message",
                mapOf("windowStartUtc" to windowStartUtc, "windowEndUtc" to windowEndUtc, "message" to message)
            )
            return state(
                phase = "upload-failed",
                progressText = "使用记录上传失败：$message",
                outcome = outcome,
                failedCount = maxOf(1, sentItems.size),
                lastError = ex.message ?: ex::class.java.simpleName,
                lastErrorDetail = message,
                currentWindowStartUtc = windowStartUtc,
                currentWindowEndUtc = windowEndUtc,
                currentEventCount = events.size,
                currentSummaryCount = summaries.size,
                currentAppMetadataCount = apps.size,
                lastBatchId = batchId,
                lastBatchStatus = when {
                    blockReason == UsageBatchBlockReason.LEASE_LOST -> "lease-lost-retry"
                    outcome == MobileSyncOutcome.BLOCKED -> "server-blocked"
                    else -> "failed"
                },
                pendingQueueCount = pendingQueueCount()
            )
        }
        val ingest = response.data
        if (response.code != 0 || ingest == null) {
            val message = response.message.orEmpty().ifBlank { "Usage upload failed." }
            markUsageFailed(eventIds, summaryIds, apps, message)
            logs.warn(
                "mobile-sync",
                "Usage upload failed.",
                mapOf("windowStartUtc" to windowStartUtc, "windowEndUtc" to windowEndUtc, "message" to message)
            )
            return state(
                phase = "upload-failed",
                progressText = message,
                outcome = MobileSyncOutcome.RETRY,
                failedCount = maxOf(1, sentItems.size),
                lastError = message,
                lastErrorDetail = message,
                currentWindowStartUtc = windowStartUtc,
                currentWindowEndUtc = windowEndUtc,
                currentEventCount = events.size,
                currentSummaryCount = summaries.size,
                currentAppMetadataCount = apps.size,
                lastBatchId = batchId,
                lastBatchStatus = "failed",
                pendingQueueCount = pendingQueueCount()
            )
        }

        val acknowledgement = processUsageAcknowledgements(mobileDataDao, sentItems, ingest)
        val hasAcknowledgedProgress =
            acknowledgement.confirmedItems.isNotEmpty() ||
                acknowledgement.deadLetterItems.isNotEmpty()
        if (hasAcknowledgedProgress) {
            clearUsageBatchRecoveryState(baseBatchId, recoveryBinding)
        } else {
            recordUsageBatchNoProgress(baseBatchId, recoveryBinding)
        }
        logs.info(
            "mobile-sync",
            "使用记录窗口已上传。",
            mapOf(
                "windowStartUtc" to windowStartUtc,
                "windowEndUtc" to windowEndUtc,
                "acceptedCount" to ingest.acceptedCount,
                "skippedCount" to ingest.skippedCount,
                "rejectedCount" to ingest.rejectedCount,
                "failedCount" to ingest.failedCount
            )
        )

        return ingest.toState(
            batchId = batchId,
            windowStartUtc = windowStartUtc,
            windowEndUtc = windowEndUtc,
            eventCount = events.size,
            summaryCount = summaries.size,
            appMetadataCount = apps.size,
            acknowledgement = acknowledgement
        )
    }

    private suspend fun registerDevice(
        identity: MobileDeviceIdentity,
        profile: MobileDeviceProfileEntity
    ) {
        val response = api.registerMobileDevice(
            MobileDeviceRegisterRequest(
                deviceId = identity.deviceId,
                androidIdHash = identity.androidIdHash,
                displayName = displayName(profile),
                manufacturer = profile.manufacturer,
                brand = profile.brand,
                model = profile.model,
                androidVersion = profile.androidVersion,
                sdkInt = profile.sdkInt,
                appVersion = profile.appVersionName ?: "unknown",
                metadataJson = profile.rawJson
            )
        )

        if (response.code != 0 || response.data == null) {
            val message = response.message.orEmpty().ifBlank { "Device registration failed." }
            mobileDataDao.updateDeviceProfileSyncStatus(
                syncStatus = MobileSyncStatus.FAILED,
                lastError = message
            )
            throw IllegalStateException(message)
        }

        logs.info("mobile-sync", "Android 设备已注册。", mapOf("deviceId" to identity.deviceId))
    }

    private suspend fun markUsageFailed(
        eventIds: List<Long>,
        summaryIds: List<Long>,
        apps: List<MobileAppMetadataEntity>,
        message: String
    ) {
        if (eventIds.isNotEmpty()) {
            mobileDataDao.updateUsageEventSyncStatus(eventIds, MobileSyncStatus.PENDING, message)
        }
        if (summaryIds.isNotEmpty()) {
            mobileDataDao.updateUsageSummarySyncStatus(summaryIds, MobileSyncStatus.PENDING, message)
        }
        val packageNames = apps.map { it.packageName }
        if (packageNames.isNotEmpty()) {
            mobileDataDao.updateAppMetadataSyncStatus(packageNames, MobileSyncStatus.PENDING, message)
        }
    }

    private suspend fun finishWithLocalError(
        deviceId: String,
        serverUrl: String,
        usagePermissionGranted: Boolean,
        attemptedAt: String,
        phase: String,
        message: String
    ): MobileSyncState {
        logs.warn("mobile-sync", message)
        val failed = state(
            phase = phase,
            progressText = message,
            outcome = MobileSyncOutcome.BLOCKED,
            lastError = message,
            lastErrorDetail = message,
            pendingQueueCount = pendingQueueCount(),
            lastAttemptedUploadAt = attemptedAt
        )
        sendHeartbeat(deviceId, serverUrl, usagePermissionGranted, failed)
        persistState(failed)
        return failed
    }

    private suspend fun sendHeartbeat(
        deviceId: String,
        serverUrl: String,
        usagePermissionGranted: Boolean,
        state: MobileSyncState
    ) {
        try {
            heartbeatReporter.report(deviceId, serverUrl, usagePermissionGranted, state)
            persistState(state.copy(heartbeatStatus = "心跳上报成功"))
            logs.info("mobile-heartbeat", "Android 心跳已上报。", mapOf("phase" to state.phase))
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val detail = ex.toCauseChainMessage()
            persistState(state.copy(heartbeatStatus = "心跳上报失败", lastErrorDetail = detail))
            logs.error("mobile-heartbeat", "Android 心跳上报失败：$detail", ex)
        }
    }

    private suspend fun pendingQueueCount(): Int {
        return mobileDataDao.pendingUsageEventCount().first() +
            mobileDataDao.pendingUsageSummaryCount().first() +
            mobileDataDao.pendingAppMetadataCount().first() +
            mobileDataDao.pendingLocationPointCount().first()
    }

    private fun buildDeviceProfile(
        identity: MobileDeviceIdentity,
        nowUtc: Long
    ): MobileDeviceProfileEntity {
        val version = appVersion()
        val rawJson = JSONObject()
            .put("deviceId", identity.deviceId)
            .put("androidIdHash", identity.androidIdHash ?: JSONObject.NULL)
            .put("manufacturer", Build.MANUFACTURER ?: "")
            .put("brand", Build.BRAND ?: "")
            .put("model", Build.MODEL ?: "")
            .put("hardware", Build.HARDWARE ?: "")
            .put("androidVersion", Build.VERSION.RELEASE ?: "")
            .put("sdkInt", Build.VERSION.SDK_INT)
            .put("appVersionName", version.first ?: JSONObject.NULL)
            .put("appVersionCode", version.second ?: JSONObject.NULL)
            .put("collectedAtUtc", nowUtc)
            .toString()

        return MobileDeviceProfileEntity(
            deviceId = identity.deviceId,
            manufacturer = Build.MANUFACTURER ?: "",
            brand = Build.BRAND ?: "",
            model = Build.MODEL ?: "",
            hardware = Build.HARDWARE ?: "",
            androidVersion = Build.VERSION.RELEASE ?: "",
            sdkInt = Build.VERSION.SDK_INT,
            appVersionName = version.first,
            appVersionCode = version.second,
            collectedAtUtc = nowUtc,
            rawJson = rawJson
        )
    }

    private fun deviceIdentity(): MobileDeviceIdentity = deviceIdentityProvider.current()

    private fun configuredServerUrl(): String {
        return normalizeServerUrl(serverSettingsStore.getBaseUrl())
    }

    private fun normalizeServerUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return try {
            val uri = URI(trimmed)
            if (uri.host.equals("localhost", ignoreCase = true)) {
                URI(uri.scheme, uri.userInfo, "127.0.0.1", uri.port, uri.path, uri.query, uri.fragment)
                    .toString()
                    .trimEnd('/')
            } else {
                trimmed
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun capabilityJson(usagePermissionGranted: Boolean): String {
        return JSONObject()
            .put("usageEvents", usagePermissionGranted)
            .put("usageStatsFallback", usagePermissionGranted)
            .put("appMetadata", true)
            .put("maxBackfillDays", 14)
            .put("client", "android")
            .toString()
    }

    private fun persistState(state: MobileSyncState) {
        prefs.edit()
            .putString("phase", state.phase)
            .putString("progress_text", state.progressText)
            .putBoolean("is_in_progress", state.isInProgress)
            .putString("outcome", state.outcome.name)
            .putInt("accepted_count", state.acceptedCount)
            .putInt("skipped_count", state.skippedCount)
            .putInt("rejected_count", state.rejectedCount)
            .putInt("failed_count", state.failedCount)
            .putString("last_error", state.lastError)
            .putString("last_error_detail", state.lastErrorDetail)
            .putInt("pending_queue_count", state.pendingQueueCount)
            .putInt("gap_window_count", state.gapWindowCount)
            .putInt("current_window_index", state.currentWindowIndex)
            .putString("current_window_start_utc", state.currentWindowStartUtc)
            .putString("current_window_end_utc", state.currentWindowEndUtc)
            .putInt("current_event_count", state.currentEventCount)
            .putInt("current_summary_count", state.currentSummaryCount)
            .putInt("current_app_metadata_count", state.currentAppMetadataCount)
            .putString("last_batch_id", state.lastBatchId)
            .putString("last_batch_status", state.lastBatchStatus)
            .putString("heartbeat_status", state.heartbeatStatus)
            .putString("last_attempted_upload_at", state.lastAttemptedUploadAt)
            .putString("last_successful_upload_at", state.lastSuccessfulUploadAt)
            .commit()
        _state.value = state
    }

    private fun readPersistedState(): MobileSyncState {
        return MobileSyncState(
            phase = prefs.getString("phase", null) ?: "waiting",
            progressText = prefs.getString("progress_text", null) ?: "打开 App 后会自动同步一次。",
            isInProgress = prefs.getBoolean("is_in_progress", false),
            outcome = try { MobileSyncOutcome.valueOf(prefs.getString("outcome", "SUCCESS") ?: "SUCCESS") } catch (_: Exception) { MobileSyncOutcome.SUCCESS },
            acceptedCount = prefs.getInt("accepted_count", 0),
            skippedCount = prefs.getInt("skipped_count", 0),
            rejectedCount = prefs.getInt("rejected_count", 0),
            failedCount = prefs.getInt("failed_count", 0),
            lastError = prefs.getString("last_error", null),
            lastErrorDetail = prefs.getString("last_error_detail", null),
            pendingQueueCount = prefs.getInt("pending_queue_count", 0),
            gapWindowCount = prefs.getInt("gap_window_count", 0),
            currentWindowIndex = prefs.getInt("current_window_index", 0),
            currentWindowStartUtc = prefs.getString("current_window_start_utc", null),
            currentWindowEndUtc = prefs.getString("current_window_end_utc", null),
            currentEventCount = prefs.getInt("current_event_count", 0),
            currentSummaryCount = prefs.getInt("current_summary_count", 0),
            currentAppMetadataCount = prefs.getInt("current_app_metadata_count", 0),
            lastBatchId = prefs.getString("last_batch_id", null),
            lastBatchStatus = prefs.getString("last_batch_status", null),
            heartbeatStatus = prefs.getString("heartbeat_status", null),
            lastAttemptedUploadAt = prefs.getString("last_attempted_upload_at", null),
            lastSuccessfulUploadAt = prefs.getString("last_successful_upload_at", null)
        )
    }

    private fun previousSuccessfulUploadAt(): String? {
        return prefs.getString("last_successful_upload_at", null)
    }

    private fun state(
        phase: String,
        progressText: String,
        isInProgress: Boolean = false,
        outcome: MobileSyncOutcome = MobileSyncOutcome.SUCCESS,
        acceptedCount: Int = 0,
        skippedCount: Int = 0,
        rejectedCount: Int = 0,
        failedCount: Int = 0,
        lastError: String? = null,
        lastErrorDetail: String? = null,
        pendingQueueCount: Int = 0,
        gapWindowCount: Int = 0,
        currentWindowIndex: Int = 0,
        currentWindowStartUtc: String? = null,
        currentWindowEndUtc: String? = null,
        currentEventCount: Int = 0,
        currentSummaryCount: Int = 0,
        currentAppMetadataCount: Int = 0,
        lastBatchId: String? = null,
        lastBatchStatus: String? = null,
        heartbeatStatus: String? = null,
        lastAttemptedUploadAt: String? = null,
        lastSuccessfulUploadAt: String? = null
    ): MobileSyncState {
        return MobileSyncState(
            phase = phase,
            progressText = progressText,
            isInProgress = isInProgress,
            outcome = outcome,
            acceptedCount = acceptedCount,
            skippedCount = skippedCount,
            rejectedCount = rejectedCount,
            failedCount = failedCount,
            lastError = lastError,
            lastErrorDetail = lastErrorDetail,
            pendingQueueCount = pendingQueueCount,
            gapWindowCount = gapWindowCount,
            currentWindowIndex = currentWindowIndex,
            currentWindowStartUtc = currentWindowStartUtc,
            currentWindowEndUtc = currentWindowEndUtc,
            currentEventCount = currentEventCount,
            currentSummaryCount = currentSummaryCount,
            currentAppMetadataCount = currentAppMetadataCount,
            lastBatchId = lastBatchId,
            lastBatchStatus = lastBatchStatus,
            heartbeatStatus = heartbeatStatus,
            lastAttemptedUploadAt = lastAttemptedUploadAt,
            lastSuccessfulUploadAt = lastSuccessfulUploadAt ?: previousSuccessfulUploadAt()
        )
    }

    internal suspend fun uploadQueuedUsage(
        deviceId: String,
        attemptedAt: String,
        manualRecoveryRetry: Boolean,
        maxBatchesPerRun: Int = MAX_USAGE_BATCHES_PER_RUN,
        maxDurationMs: Long = MAX_USAGE_BATCH_DURATION_MS
    ): MobileSyncState? {
        val initialBatch = try {
            loadPendingUsageBatch(mobileDataDao, USAGE_BATCH_LIMIT)
        } catch (ex: UsageWindowOverflowException) {
            return blockedUsageWindowState(attemptedAt)
        }
        if (initialBatch.totalCount == 0) return null

        var batchCount = 0
        val startTime = SystemClock.elapsedRealtime()
        var accumulatedState: MobileSyncState? = null

        while (batchCount < maxBatchesPerRun &&
            (SystemClock.elapsedRealtime() - startTime) < maxDurationMs
        ) {
            currentCoroutineContext().ensureActive()
            val batch = try {
                if (batchCount == 0) {
                    initialBatch
                } else {
                    loadPendingUsageBatch(mobileDataDao, USAGE_BATCH_LIMIT)
                }
            } catch (ex: UsageWindowOverflowException) {
                return blockedUsageWindowState(attemptedAt)
            }
            if (batch.totalCount == 0) break
            batchCount++

            val unsupportedSummaries = batch.summaries.filter {
                !it.hasSupportedUsageBatchWindow()
            }
            if (unsupportedSummaries.isNotEmpty()) {
                val summaryIds = unsupportedSummaries.map { it.id }
                val message = "client-summary-window-outside-25h-contract"
                mobileDataDao.updateUsageSummarySyncStatus(
                    ids = summaryIds,
                    syncStatus = MobileSyncStatus.REJECTED,
                    lastError = message
                )
                val rejectedState = state(
                    phase = "uploaded",
                    progressText = "已拒绝 ${summaryIds.size} 条超出协议窗口的汇总记录并继续处理其他队列。",
                    outcome = MobileSyncOutcome.SUCCESS,
                    rejectedCount = summaryIds.size,
                    lastError = message,
                    lastBatchStatus = "client-rejected",
                    pendingQueueCount = pendingQueueCount(),
                    lastAttemptedUploadAt = attemptedAt
                )
                accumulatedState = accumulatedState?.merge(rejectedState) ?: rejectedState
                logs.warn(
                    "mobile-sync",
                    "本地汇总窗口超出 API 25h 契约，已保留原始行并标记 rejected，避免阻塞整条队列。",
                    mapOf("summaryIds" to summaryIds)
                )
                continue
            }

            val windowStart = iso(batch.windowStartUtc!!)
            val windowEnd = iso(batch.windowEndUtc!!)

            val uploadState = uploadWindow(
                deviceId = deviceId,
                windowStartUtc = windowStart,
                windowEndUtc = windowEnd,
                events = batch.events,
                summaries = batch.summaries,
                apps = batch.apps,
                eventIds = batch.events.map { it.id },
                summaryIds = batch.summaries.map { it.id },
                manualRecoveryRetry = manualRecoveryRetry
            )

            accumulatedState = accumulatedState?.merge(uploadState) ?: uploadState
            val remaining = pendingUsageRemaining(mobileDataDao)

            logs.info(
                "mobile-sync",
                "使用记录批次上传完成（第 $batchCount 批，已接收 ${accumulatedState.acceptedCount}，剩余 $remaining 条）。",
                mapOf(
                    "batchNumber" to batchCount,
                    "batchId" to (uploadState.lastBatchId ?: ""),
                    "acceptedCount" to uploadState.acceptedCount,
                    "skippedCount" to uploadState.skippedCount,
                    "rejectedCount" to uploadState.rejectedCount,
                    "failedCount" to uploadState.failedCount,
                    "remaining" to remaining
                )
            )

            if (uploadState.outcome == MobileSyncOutcome.RETRY || uploadState.failedCount > 0) {
                val failedState = accumulatedState.copy(
                    phase = when (uploadState.phase) {
                        "upload-blocked",
                        "usage-outbox-binding-blocked" -> uploadState.phase
                        else -> "old-queue-upload-failed"
                    },
                    progressText = uploadState.lastError ?: "旧队列上传失败，已安排重试。",
                    outcome = uploadState.outcome,
                    pendingQueueCount = pendingQueueCount(),
                    lastAttemptedUploadAt = attemptedAt
                )
                persistState(failedState)
                return failedState
            }

            if (remaining > 0) {
                val catchingUp = accumulatedState.copy(
                    phase = "catching-up",
                    progressText = "正在补传（剩 $remaining 条）。",
                    outcome = MobileSyncOutcome.SUCCESS,
                    pendingQueueCount = pendingQueueCount(),
                    lastAttemptedUploadAt = attemptedAt
                )
                persistState(catchingUp)
                accumulatedState = catchingUp
            }
        }

        val remaining = pendingUsageRemaining(mobileDataDao)
        if (remaining > 0) {
            val catchingUp = (accumulatedState ?: state(
                phase = "catching-up",
                progressText = "正在补传（剩 $remaining 条）。",
                outcome = MobileSyncOutcome.SUCCESS,
                pendingQueueCount = pendingQueueCount(),
                lastAttemptedUploadAt = attemptedAt
            )).copy(
                phase = "catching-up",
                progressText = "正在补传（剩 $remaining 条）。",
                outcome = MobileSyncOutcome.SUCCESS,
                pendingQueueCount = pendingQueueCount(),
                lastAttemptedUploadAt = attemptedAt
            )
            persistState(catchingUp)
            return catchingUp
        }

        return accumulatedState
    }

    private suspend fun blockedUsageWindowState(attemptedAt: String): MobileSyncState {
        val pending = pendingUsageRemaining(mobileDataDao)
        val message = "usage-timestamp-cannot-form-half-open-window"
        val blocked = state(
            phase = "upload-blocked",
            progressText = "本地存在无法表示为半开窗口的毫秒时间戳，已停止上传并保留 pending 数据。",
            outcome = MobileSyncOutcome.BLOCKED,
            failedCount = pending,
            lastError = message,
            lastErrorDetail = message,
            lastBatchStatus = "local-window-blocked",
            pendingQueueCount = pending,
            lastAttemptedUploadAt = attemptedAt
        )
        logs.warn(
            "mobile-sync",
            "本地使用记录无法形成半开窗口，已阻止上传且未删除 pending 数据。",
            mapOf("reason" to message)
        )
        persistState(blocked)
        return blocked
    }

    private fun usageBatchRecoveryBinding(payloadFingerprint: String): UsageBatchRecoveryBinding {
        val identity = currentUsageOutboxOwnerBinding()
        return UsageBatchRecoveryBinding(
            serverIdentity = identity.serverIdentity,
            ownerId = identity.ownerId,
            payloadFingerprint = payloadFingerprint
        )
    }

    private fun currentUsageOutboxOwnerBinding(): UsageOutboxOwnerBinding {
        val session = tokenManager.snapshot()
        val configuredOrigin = runCatching {
            PimServerEndpoints.from(configuredServerUrl()).trustedOrigin
        }.getOrDefault(configuredServerUrl())
        return UsageOutboxOwnerBinding(
            serverIdentity = session.serverIdentity ?: configuredOrigin,
            ownerId = session.ownerId.orEmpty()
        )
    }

    private fun storedUsageOutboxOwnerBinding(): UsageOutboxOwnerBinding? {
        val server = prefs.getString(USAGE_OUTBOX_SERVER_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val owner = prefs.getString(USAGE_OUTBOX_OWNER_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return UsageOutboxOwnerBinding(serverIdentity = server, ownerId = owner)
    }

    private suspend fun ensureUsageOutboxBinding(
        claim: LegacyOutboxClaimRequest?
    ): UsageOutboxBindingAction {
        val current = currentUsageOutboxOwnerBinding()
        val decision = resolveUsageOutboxBinding(
            stored = storedUsageOutboxOwnerBinding(),
            current = current,
            pendingCount = pendingUsageRemaining(mobileDataDao),
            claim = claim
        )
        if (decision.shouldBind) {
            prefs.edit()
                .putString(USAGE_OUTBOX_SERVER_KEY, current.serverIdentity)
                .putString(USAGE_OUTBOX_OWNER_KEY, current.ownerId)
                .apply()
        }
        return if (decision.shouldBind) {
            UsageOutboxBindingAction.ALLOWED
        } else {
            decision.action
        }
    }

    private fun usageOutboxBindingAllowsCurrentUpload(): Boolean {
        return storedUsageOutboxOwnerBinding() == currentUsageOutboxOwnerBinding()
    }

    private fun usageOutboxBindingMessage(action: UsageOutboxBindingAction): String {
        return when (action) {
            UsageOutboxBindingAction.OWNER_MISSING ->
                "当前登录缺少账号归属信息，已停止上传本地使用记录。请重新登录原账号后重试。"
            UsageOutboxBindingAction.UNBOUND_LEGACY_QUEUE ->
                "本地待传使用记录缺少账号/服务器归属，已保留并停止上传，不会自动认领给当前账号。"
            UsageOutboxBindingAction.BINDING_MISMATCH ->
                "本地待传使用记录属于另一账号或服务器，已停止上传并保留原队列。请切回原绑定后重试。"
            UsageOutboxBindingAction.BIND_CURRENT,
            UsageOutboxBindingAction.ALLOWED ->
                "本地使用记录队列与当前账号/服务器绑定一致。"
        }
    }

    private fun readUsageBatchRecoveryState(
        baseBatchId: String,
        binding: UsageBatchRecoveryBinding
    ): UsageBatchRecoveryState {
        val storage = usageBatchRecoveryStorageKey(baseBatchId, binding)
        val storedBinding = UsageBatchRecoveryBinding(
            serverIdentity = prefs.getString("${storage}_server", null).orEmpty(),
            ownerId = prefs.getString("${storage}_owner", null).orEmpty(),
            payloadFingerprint = prefs.getString("${storage}_payload", null).orEmpty()
        )
        if (storedBinding != binding) {
            return UsageBatchRecoveryState(binding = binding)
        }
        return normalizeUsageBatchRecoveryState(UsageBatchRecoveryState(
            recoveryEpoch = prefs.getInt("${storage}_epoch", 0),
            noProgressAttempts = prefs.getInt("${storage}_attempts", 0),
            blockedBy409 = prefs.getBoolean("${storage}_blocked", false),
            blockReason = prefs.getString("${storage}_reason", null)
                ?.let { stored ->
                    UsageBatchBlockReason.values().firstOrNull { it.name == stored }
                },
            lastTerminalError = prefs.getString("${storage}_error", null),
            binding = binding
        ))
    }

    private fun recordUsageBatchNoProgress(
        baseBatchId: String,
        binding: UsageBatchRecoveryBinding
    ) {
        val current = readUsageBatchRecoveryState(baseBatchId, binding)
        writeUsageBatchRecoveryState(
            baseBatchId,
            current.copy(
                noProgressAttempts = minOf(
                    current.noProgressAttempts + 1,
                    SERVER_USAGE_MAX_ATTEMPTS
                )
            )
        )
    }

    private fun recordUsageBatchConflict(
        baseBatchId: String,
        binding: UsageBatchRecoveryBinding,
        reason: UsageBatchBlockReason,
        message: String
    ) {
        val current = readUsageBatchRecoveryState(baseBatchId, binding)
        writeUsageBatchRecoveryState(
            baseBatchId,
            usageBatchRecoveryStateAfterConflict(current, reason, message)
        )
    }

    private fun prepareManualUsageBatchRecovery(
        baseBatchId: String,
        binding: UsageBatchRecoveryBinding
    ): UsageBatchRecoveryDecision {
        val current = readUsageBatchRecoveryState(baseBatchId, binding)
        val decision = advanceUsageBatchRecoveryOnManualRetry(current, binding)
        if (decision.batchIdRotated) {
            writeUsageBatchRecoveryState(baseBatchId, decision.state)
        }
        return decision
    }

    private fun writeUsageBatchRecoveryState(
        baseBatchId: String,
        state: UsageBatchRecoveryState
    ) {
        val binding = state.binding ?: return
        val storage = usageBatchRecoveryStorageKey(baseBatchId, binding)
        prefs.edit()
            .putString("${storage}_server", binding.serverIdentity)
            .putString("${storage}_owner", binding.ownerId)
            .putString("${storage}_payload", binding.payloadFingerprint)
            .putInt("${storage}_epoch", state.recoveryEpoch)
            .putInt("${storage}_attempts", state.noProgressAttempts)
            .putBoolean("${storage}_blocked", state.blockedBy409)
            .putString("${storage}_reason", state.blockReason?.name)
            .putString("${storage}_error", state.lastTerminalError)
            .apply()
    }

    private fun clearUsageBatchRecoveryState(
        baseBatchId: String,
        binding: UsageBatchRecoveryBinding
    ) {
        val storage = usageBatchRecoveryStorageKey(baseBatchId, binding)
        prefs.edit()
            .remove("${storage}_server")
            .remove("${storage}_owner")
            .remove("${storage}_payload")
            .remove("${storage}_epoch")
            .remove("${storage}_attempts")
            .remove("${storage}_blocked")
            .remove("${storage}_reason")
            .remove("${storage}_error")
            .apply()
    }

    private fun usageBatchRecoveryStorageKey(
        baseBatchId: String,
        binding: UsageBatchRecoveryBinding
    ): String {
        val bindingHash = sha256Hex(
            "$baseBatchId|${binding.serverIdentity}|${binding.ownerId}|${binding.payloadFingerprint}"
        )
        return "usage_batch_recovery:$bindingHash"
    }

    private fun displayName(profile: MobileDeviceProfileEntity): String {
        return listOf(profile.manufacturer, profile.model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android device" }
    }

    private fun appVersion(): Pair<String?, Long?> {
        return try {
            val info = packageInfo(context.packageManager, context.packageName)
            info.versionName to versionCode(info)
        } catch (_: Exception) {
            null to null
        }
    }

    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    }

    private fun versionCode(packageInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }


    companion object {
        private const val FOURTEEN_DAYS_MS = 14L * 24L * 60L * 60L * 1000L
        private const val PREFS_NAME = "pim_mobile_sync_state"
        private const val USAGE_OUTBOX_SERVER_KEY = "usage_outbox_server_identity"
        private const val USAGE_OUTBOX_OWNER_KEY = "usage_outbox_owner_id"
    }
}

internal fun sha256Hex(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(Locale.US, it) }
}

internal fun stableBatchId(
    deviceId: String,
    windowStartUtc: String,
    windowEndUtc: String,
    items: Collection<MobileAcknowledgementItem> = emptyList(),
    recoveryEpoch: Int = 0,
    payloadFingerprint: String = ""
): String {
    val itemsSignature = items.asSequence()
        .map { "${it.entityType}:${it.clientItemKey}" }
        .sorted()
        .joinToString(",")
    val recoverySignature = if (recoveryEpoch > 0) "|recovery:$recoveryEpoch" else ""
    val payloadSignature = if (payloadFingerprint.isNotBlank()) "|payload:$payloadFingerprint" else ""
    return "android-${sha256Hex("$deviceId|$windowStartUtc|$windowEndUtc|$itemsSignature$payloadSignature$recoverySignature").take(24)}"
}

private const val MAX_UPLOAD_WINDOW_MS = USAGE_BATCH_TARGET_WINDOW_MS

private data class ClampedGapWindow(
    val windowStartUtc: Long,
    val windowEndUtc: Long
)

data class UploadWindow(
    val windowStartUtc: Long,
    val windowEndUtc: Long
)

private fun clampGapWindow(
    windowStartUtc: Long,
    windowEndUtc: Long,
    maxBackfillStartUtc: Long,
    nowUtc: Long
): ClampedGapWindow? {
    val effectiveStart = maxOf(
        windowStartUtc,
        maxBackfillStartUtc,
        nowUtc - 14L * 24L * 60L * 60L * 1000L
    )
    val effectiveEnd = minOf(windowEndUtc, nowUtc)
    return if (effectiveStart < effectiveEnd) {
        ClampedGapWindow(effectiveStart, effectiveEnd)
    } else {
        null
    }
}

/**
 * 空载判定：缺口窗口没有采集到任何条目（事件 / 汇总 / 应用元数据）时不上传。
 * 服务端不为空载请求创建批次行（EPIC #254 S11 空转批次），空窗口也不再被记为"已覆盖"；
 * 此时上传空批次只会白白消耗一次网络往返，真实的无数据窗口应由缺口服务如实回报。
 * 客户端每次同步都按滚动 14 天窗口向服务器要缺口（无本地游标），
 * 若靠空批次"填坑"，服务端停止为空载建行后同一窗口会被每次同步重复扫描上传——因此必须在源头跳过。
 */
internal fun isEmptyGapWindowUpload(
    events: Collection<*>,
    summaries: Collection<*>,
    apps: Collection<*>
): Boolean = events.isEmpty() && summaries.isEmpty() && apps.isEmpty()

fun splitGapWindowForUpload(
    windowStartUtc: Long,
    windowEndUtc: Long
): List<UploadWindow> {
    if (windowStartUtc >= windowEndUtc) {
        return emptyList()
    }

    val windows = mutableListOf<UploadWindow>()
    var start = windowStartUtc
    while (start < windowEndUtc) {
        val end = minOf(start + MAX_UPLOAD_WINDOW_MS, windowEndUtc)
        windows.add(UploadWindow(start, end))
        start = end
    }

    return windows
}

internal fun MobileUsageIngestResult.toState(
    batchId: String,
    windowStartUtc: String,
    windowEndUtc: String,
    eventCount: Int,
    summaryCount: Int,
    appMetadataCount: Int,
    acknowledgement: MobileTypedAcknowledgementPlan? = null
): MobileSyncState {
    val retryCount = acknowledgement?.retryItems?.size ?: failedCount
    val isFullyAcknowledged = when {
        acknowledgement == null -> failedCount == 0
        acknowledgement.failureCode != null -> false
        acknowledgement.retryItems.isNotEmpty() -> false
        else -> acknowledgement.confirmedItems.size + acknowledgement.deadLetterItems.size ==
            eventCount + summaryCount + appMetadataCount
    }
    val acknowledgementError = acknowledgement
        ?.itemErrors
        ?.values
        ?.firstOrNull()
    return MobileSyncState(
        phase = "uploaded",
        progressText = "使用记录批次已上传。",
        outcome = if (isFullyAcknowledged) MobileSyncOutcome.SUCCESS else MobileSyncOutcome.RETRY,
        acceptedCount = acceptedCount,
        skippedCount = skippedCount,
        rejectedCount = rejectedCount,
        failedCount = if (isFullyAcknowledged) 0 else maxOf(1, retryCount),
        lastError = if (isFullyAcknowledged) null else acknowledgementError ?: "server-ack-incomplete",
        lastErrorDetail = if (isFullyAcknowledged) null else acknowledgementError ?: "server-ack-incomplete",
        currentWindowStartUtc = windowStartUtc,
        currentWindowEndUtc = windowEndUtc,
        currentEventCount = eventCount,
        currentSummaryCount = summaryCount,
        currentAppMetadataCount = appMetadataCount,
        lastBatchId = batchId,
        lastBatchStatus = if (isFullyAcknowledged) "completed" else "completed-with-errors"
    )
}

internal fun MobileSyncState.merge(other: MobileSyncState): MobileSyncState {
    return copy(
        outcome = sortedMergeOutcome(outcome, other.outcome),
        acceptedCount = acceptedCount + other.acceptedCount,
        skippedCount = skippedCount + other.skippedCount,
        rejectedCount = rejectedCount + other.rejectedCount,
        failedCount = failedCount + other.failedCount,
        lastError = other.lastError ?: lastError,
        lastErrorDetail = other.lastErrorDetail ?: lastErrorDetail,
        lastBatchId = other.lastBatchId ?: lastBatchId,
        lastBatchStatus = other.lastBatchStatus ?: lastBatchStatus
    )
}

internal fun sortedMergeOutcome(a: MobileSyncOutcome, b: MobileSyncOutcome): MobileSyncOutcome {
    return when {
        a == MobileSyncOutcome.RETRY || b == MobileSyncOutcome.RETRY -> MobileSyncOutcome.RETRY
        a == MobileSyncOutcome.BLOCKED || b == MobileSyncOutcome.BLOCKED -> MobileSyncOutcome.BLOCKED
        else -> MobileSyncOutcome.SUCCESS
    }
}

internal fun shouldEnqueueUsageContinuation(
    state: MobileSyncState,
    continuationDepth: Int,
    didWork: Boolean,
    maxContinuationDepth: Int = MAX_USAGE_CONTINUATIONS_PER_CHAIN
): Boolean {
    val acknowledgedProgress = state.acceptedCount + state.skippedCount + state.rejectedCount
    return didWork &&
        state.phase == "catching-up" &&
        state.outcome == MobileSyncOutcome.SUCCESS &&
        acknowledgedProgress > 0 &&
        continuationDepth < maxContinuationDepth
}

internal fun resolvePostUsageSyncPhase(
    usagePhase: String,
    locationPhase: String,
    failedCount: Int,
    remaining: Int
): String {
    return when {
        usagePhase == "upload-blocked" ||
            usagePhase == "usage-outbox-binding-blocked" -> usagePhase
        locationPhase == "location-upload-failed" -> locationPhase
        usagePhase == "old-queue-upload-failed" || usagePhase == "upload-failed" -> usagePhase
        failedCount > 0 -> "completed-with-errors"
        remaining > 0 -> "catching-up"
        else -> "completed"
    }
}

internal data class UsageBatchRecoveryBinding(
    val serverIdentity: String,
    val ownerId: String,
    val payloadFingerprint: String
)

internal data class UsageOutboxOwnerBinding(
    val serverIdentity: String,
    val ownerId: String
)

data class UsageOutboxRecoveryPreview(
    val serverIdentity: String,
    val ownerId: String,
    val ownerName: String?,
    val pendingCount: Int,
    val earliestUtc: Long?,
    val latestUtc: Long?
)

data class LegacyOutboxClaimRequest(
    val serverIdentity: String,
    val ownerId: String
) {
    init {
        require(serverIdentity.isNotBlank())
        require(ownerId.isNotBlank())
    }
}

internal enum class UsageOutboxBindingAction {
    BIND_CURRENT,
    ALLOWED,
    OWNER_MISSING,
    UNBOUND_LEGACY_QUEUE,
    BINDING_MISMATCH
}

internal data class UsageOutboxBindingDecision(
    val action: UsageOutboxBindingAction,
    val shouldBind: Boolean
)

internal fun evaluateUsageOutboxBinding(
    stored: UsageOutboxOwnerBinding?,
    current: UsageOutboxOwnerBinding?,
    pendingCount: Int
): UsageOutboxBindingAction {
    if (current == null || current.serverIdentity.isBlank() || current.ownerId.isBlank()) {
        return UsageOutboxBindingAction.OWNER_MISSING
    }
    if (pendingCount == 0) return UsageOutboxBindingAction.BIND_CURRENT
    if (stored == null) return UsageOutboxBindingAction.UNBOUND_LEGACY_QUEUE
    return if (stored == current) {
        UsageOutboxBindingAction.ALLOWED
    } else {
        UsageOutboxBindingAction.BINDING_MISMATCH
    }
}

internal fun resolveUsageOutboxBinding(
    stored: UsageOutboxOwnerBinding?,
    current: UsageOutboxOwnerBinding?,
    pendingCount: Int,
    claim: LegacyOutboxClaimRequest?
): UsageOutboxBindingDecision {
    if (current == null || current.serverIdentity.isBlank() || current.ownerId.isBlank()) {
        return UsageOutboxBindingDecision(UsageOutboxBindingAction.OWNER_MISSING, false)
    }
    if (pendingCount == 0) {
        return UsageOutboxBindingDecision(UsageOutboxBindingAction.BIND_CURRENT, true)
    }
    if (stored == current) {
        return UsageOutboxBindingDecision(UsageOutboxBindingAction.ALLOWED, false)
    }
    if (stored != null) {
        return UsageOutboxBindingDecision(UsageOutboxBindingAction.BINDING_MISMATCH, false)
    }
    if (claim == null) {
        return UsageOutboxBindingDecision(UsageOutboxBindingAction.UNBOUND_LEGACY_QUEUE, false)
    }
    val claimMatches = claim.serverIdentity == current.serverIdentity && claim.ownerId == current.ownerId
    return if (claimMatches) {
        UsageOutboxBindingDecision(UsageOutboxBindingAction.ALLOWED, true)
    } else {
        UsageOutboxBindingDecision(UsageOutboxBindingAction.BINDING_MISMATCH, false)
    }
}

internal enum class UsageBatchBlockReason {
    RETRY_EXHAUSTED,
    PAYLOAD_CONFLICT,
    LEASE_LOST,
    UNKNOWN_CONFLICT
}

internal data class UsageBatchRecoveryState(
    val recoveryEpoch: Int = 0,
    val noProgressAttempts: Int = 0,
    val blockedBy409: Boolean = false,
    val blockReason: UsageBatchBlockReason? = null,
    val lastTerminalError: String? = null,
    val binding: UsageBatchRecoveryBinding? = null
)

internal data class UsageBatchRecoveryDecision(
    val state: UsageBatchRecoveryState,
    val batchIdRotated: Boolean
)

internal fun normalizeUsageBatchRecoveryState(
    state: UsageBatchRecoveryState
): UsageBatchRecoveryState {
    return if (
        state.blockedBy409 &&
        state.blockReason == UsageBatchBlockReason.LEASE_LOST
    ) {
        // Older builds latched this transient conflict. Keep diagnostics, but allow the
        // same stable batch id to make the next bounded network attempt.
        state.copy(blockedBy409 = false)
    } else {
        state
    }
}

internal fun usageBatchRecoveryStateAfterConflict(
    state: UsageBatchRecoveryState,
    reason: UsageBatchBlockReason,
    message: String
): UsageBatchRecoveryState {
    return state.copy(
        noProgressAttempts = minOf(
            state.noProgressAttempts + 1,
            SERVER_USAGE_MAX_ATTEMPTS
        ),
        blockedBy409 = reason != UsageBatchBlockReason.LEASE_LOST,
        blockReason = reason,
        lastTerminalError = message.take(500)
    )
}

internal fun advanceUsageBatchRecoveryOnManualRetry(
    state: UsageBatchRecoveryState,
    binding: UsageBatchRecoveryBinding
): UsageBatchRecoveryDecision {
    val bindingMatches = state.binding == binding
    val canRecover = state.blockedBy409 &&
        bindingMatches &&
        binding.ownerId.isNotBlank() &&
        state.blockReason == UsageBatchBlockReason.RETRY_EXHAUSTED
    return if (canRecover) {
        UsageBatchRecoveryDecision(
            state = UsageBatchRecoveryState(
                recoveryEpoch = state.recoveryEpoch + 1,
                noProgressAttempts = 0,
                blockedBy409 = false,
                blockReason = null,
                lastTerminalError = null,
                binding = binding
            ),
            batchIdRotated = true
        )
    } else {
        UsageBatchRecoveryDecision(state = state, batchIdRotated = false)
    }
}

internal fun classifyUsageBatchConflictCode(code: Int?): UsageBatchBlockReason {
    return when (code) {
        SERVER_CODE_RETRY_EXHAUSTED -> UsageBatchBlockReason.RETRY_EXHAUSTED
        SERVER_CODE_PAYLOAD_CONFLICT -> UsageBatchBlockReason.PAYLOAD_CONFLICT
        SERVER_CODE_LEASE_LOST -> UsageBatchBlockReason.LEASE_LOST
        else -> UsageBatchBlockReason.UNKNOWN_CONFLICT
    }
}

internal fun usageBatchHttpErrorCode(exception: HttpException): Int? {
    val body = exception.response()?.errorBody()?.string() ?: return null
    return runCatching {
        JSONObject(body).optInt("code", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
    }.getOrNull()
}

internal fun usageBatchPayloadFingerprint(
    events: List<MobileUsageEventEntity>,
    summaries: List<MobileUsageSummaryEntity>,
    apps: List<MobileAppMetadataEntity>
): String {
    val canonical = buildString {
        fun appendValue(value: Any?) {
            val text = value?.toString().orEmpty()
            append(text.length).append(':').append(text).append('|')
        }

        events.sortedBy { it.id }.forEach { event ->
            appendValue("event")
            appendValue(event.id)
            appendValue(event.packageName)
            appendValue(event.className)
            appendValue(event.eventType)
            appendValue(event.eventName)
            appendValue(event.eventTimeUtc)
            appendValue(event.source)
            appendValue(event.sourceWindowStartUtc)
            appendValue(event.sourceWindowEndUtc)
            appendValue(event.collectedAtUtc)
            appendValue(event.rawJson)
        }
        summaries.sortedBy { it.id }.forEach { summary ->
            appendValue("summary")
            appendValue(summary.id)
            appendValue(summary.packageName)
            appendValue(summary.windowStartUtc)
            appendValue(summary.windowEndUtc)
            appendValue(summary.totalTimeForegroundMs)
            appendValue(summary.lastTimeUsedUtc)
            appendValue(summary.firstTimeStampUtc)
            appendValue(summary.lastTimeStampUtc)
            appendValue(summary.source)
            appendValue(summary.sourceWindowStartUtc)
            appendValue(summary.sourceWindowEndUtc)
            appendValue(summary.collectedAtUtc)
            appendValue(summary.rawJson)
        }
        apps.sortedBy { it.packageName }.forEach { app ->
            appendValue("app")
            appendValue(app.packageName)
            appendValue(app.label)
            appendValue(app.versionName)
            appendValue(app.versionCode)
            appendValue(app.firstInstallTimeUtc)
            appendValue(app.lastUpdateTimeUtc)
            appendValue(app.isSystemApp)
            appendValue(app.category)
            appendValue(app.installerPackageName)
            appendValue(app.collectedAtUtc)
            appendValue(app.rawJson)
        }
    }
    return sha256Hex(canonical)
}

internal fun MobileUsageEventEntity.toDto(clientItemKey: String = id.toString()): MobileUsageEventDto {
    return MobileUsageEventDto(
        packageName = packageName,
        eventType = eventName,
        eventTimestampUtc = iso(eventTimeUtc),
        className = className,
        collectedAtUtc = iso(collectedAtUtc),
        rawJson = rawJson,
        clientItemKey = clientItemKey
    )
}

internal fun MobileUsageSummaryEntity.toDto(clientItemKey: String = id.toString()): MobileUsageSummaryDto {
    return MobileUsageSummaryDto(
        packageName = packageName,
        windowStartUtc = iso(windowStartUtc),
        windowEndUtc = iso(windowEndUtc),
        totalTimeForegroundMs = totalTimeForegroundMs,
        lastTimeUsedUtc = iso(lastTimeUsedUtc),
        sourceKind = source.replace('_', '-'),
        rawJson = rawJson,
        clientItemKey = clientItemKey
    )
}

internal fun MobileAppMetadataEntity.toDto(): MobileAppMetadataDto {
    val categoryName = androidCategoryName(category)
    return MobileAppMetadataDto(
        packageName = packageName,
        displayName = label,
        versionName = versionName,
        versionCode = versionCode,
        isSystemApp = isSystemApp,
        categoryName = categoryName,
        installerPackageName = installerPackageName,
        firstInstallTimeUtc = iso(firstInstallTimeUtc),
        lastUpdateTimeUtc = iso(lastUpdateTimeUtc),
        rawJson = mergeCategoryName(rawJson, categoryName),
        // 契约 `MobileAppMetadataDto` 没有 collectedAtUtc 字段（旧手写模型自造），不再发送。
        clientItemKey = "$packageName@$versionCode"
    )
}

private fun androidCategoryName(category: Int?): String? {
    if (category == null) return null
    return when (category) {
        ApplicationInfo.CATEGORY_GAME -> "game"
        ApplicationInfo.CATEGORY_AUDIO -> "audio"
        ApplicationInfo.CATEGORY_VIDEO -> "video"
        ApplicationInfo.CATEGORY_IMAGE -> "camera"
        ApplicationInfo.CATEGORY_SOCIAL -> "social"
        ApplicationInfo.CATEGORY_NEWS -> "news"
        ApplicationInfo.CATEGORY_MAPS -> "maps"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "productivity"
        else -> null
    }
}

private fun mergeCategoryName(rawJson: String, categoryName: String?): String {
    if (categoryName.isNullOrBlank()) return rawJson
    return try {
        JSONObject(rawJson)
            .put("categoryName", categoryName)
            .toString()
    } catch (_: Exception) {
        JSONObject()
            .put("categoryName", categoryName)
            .toString()
    }
}

private fun packageNames(
    events: List<MobileUsageEventEntity>,
    summaries: List<MobileUsageSummaryEntity>
): Set<String> {
    return (events.map { it.packageName } + summaries.map { it.packageName })
        .filter { it.isNotBlank() }
        .toSet()
}

private fun nowIso(): String = iso(System.currentTimeMillis())

private fun iso(epochMillis: Long): String {
    return Instant.ofEpochMilli(epochMillis).toString()
}

private fun parseIsoMillis(value: String): Long {
    return Instant.parse(value).toEpochMilli()
}

data class PendingUsageBatch(
    val events: List<MobileUsageEventEntity>,
    val summaries: List<MobileUsageSummaryEntity>,
    val apps: List<MobileAppMetadataEntity>,
    val windowStartUtc: Long?,
    val windowEndUtc: Long?
) {
    val totalCount: Int get() = events.size + summaries.size + apps.size

    internal fun typedKeys(): Set<MobileAcknowledgementItem> {
        return buildSet {
            events.forEach { add(MobileAcknowledgementItem("usage-event", it.id.toString())) }
            summaries.forEach { add(MobileAcknowledgementItem("usage-summary", it.id.toString())) }
            apps.forEach {
                add(MobileAcknowledgementItem("app-metadata", "${it.packageName}@${it.versionCode}"))
            }
        }
    }
}

internal suspend fun loadPendingUsageBatch(
    dao: MobileDataDao,
    limit: Int
): PendingUsageBatch {
    val events = loadPriorityBatch(dao::getUsageEventsBySyncStatus, limit)
    val summaries = loadPriorityBatch(dao::getUsageSummariesBySyncStatus, limit)
    val apps = loadPriorityBatch(dao::getAppMetadataBySyncStatus, limit)

    return partitionPendingUsageBatch(events, summaries, apps, limit).firstOrNull()
        ?: PendingUsageBatch(emptyList(), emptyList(), emptyList(), null, null)
}

internal fun MobileUsageSummaryEntity.hasSupportedUsageBatchWindow(): Boolean {
    if (windowEndUtc <= windowStartUtc) return false
    return try {
        usageWindowSpan(windowStartUtc, windowEndUtc) <= USAGE_BATCH_MAX_WINDOW_MS
    } catch (_: UsageWindowOverflowException) {
        false
    }
}

/**
 * Deterministically partitions a bounded candidate pool into upload batches.
 *
 * Events and app metadata are point-like. Summaries are atomic intervals: a legitimate
 * daily fallback summary must be sent whole even when its span exceeds the normal 2h
 * timeline target. A batch may therefore exceed 2h only because one of its summaries
 * genuinely does; it may never bridge unrelated 40h-apart point records.
 */
internal fun partitionPendingUsageBatch(
    events: List<MobileUsageEventEntity>,
    summaries: List<MobileUsageSummaryEntity>,
    apps: List<MobileAppMetadataEntity>,
    maxItems: Int = USAGE_BATCH_LIMIT,
    targetWindowMs: Long = USAGE_BATCH_TARGET_WINDOW_MS
): List<PendingUsageBatch> {
    require(maxItems > 0) { "maxItems must be positive" }
    require(targetWindowMs > 0) { "targetWindowMs must be positive" }

    val entries = buildList {
        summaries.forEach { add(UsageQueueEntry.Summary(it)) }
        events.forEach { add(UsageQueueEntry.Event(it)) }
        apps.forEach { add(UsageQueueEntry.App(it)) }
    }.sortedWith(
        compareBy<UsageQueueEntry> { it.statusPriority }
            .thenBy { it.startUtc }
            .thenBy { it.kindOrder }
            .thenBy { it.endUtc }
            .thenBy { it.stableKey }
    )

    if (entries.isEmpty()) return emptyList()

    val batches = mutableListOf<MutableUsageBatch>()
    var current = MutableUsageBatch(maxItems, targetWindowMs)
    batches += current
    for (entry in entries) {
        if (!current.canAccept(entry)) {
            current = MutableUsageBatch(maxItems, targetWindowMs)
            batches += current
        }
        current.add(entry)
    }

    return batches
        .filter { it.totalCount > 0 }
        .map { it.toPendingUsageBatch() }
}

private enum class UsageQueueKind(val order: Int) {
    Summary(0),
    Event(1),
    App(2)
}

internal class UsageWindowOverflowException(message: String) : IllegalArgumentException(message)

private sealed class UsageQueueEntry(
    val kind: UsageQueueKind,
    val startUtc: Long,
    val endUtc: Long,
    val syncStatus: String,
    val stableKey: String
) {
    val kindOrder: Int get() = kind.order
    val statusPriority: Int get() = if (syncStatus == MobileSyncStatus.PENDING) 0 else 1

    class Event(val row: MobileUsageEventEntity) : UsageQueueEntry(
        kind = UsageQueueKind.Event,
        startUtc = row.eventTimeUtc,
        endUtc = pointExclusiveEnd(row.eventTimeUtc),
        syncStatus = row.syncStatus,
        stableKey = row.id.toString()
    )

    class Summary(val row: MobileUsageSummaryEntity) : UsageQueueEntry(
        kind = UsageQueueKind.Summary,
        startUtc = row.windowStartUtc,
        endUtc = row.windowEndUtc,
        syncStatus = row.syncStatus,
        stableKey = row.id.toString()
    )

    class App(val row: MobileAppMetadataEntity) : UsageQueueEntry(
        kind = UsageQueueKind.App,
        startUtc = row.collectedAtUtc,
        endUtc = pointExclusiveEnd(row.collectedAtUtc),
        syncStatus = row.syncStatus,
        stableKey = "${row.packageName}@${row.versionCode}"
    )
}

private fun pointExclusiveEnd(timestampUtc: Long): Long {
    if (timestampUtc == Long.MAX_VALUE) {
        throw UsageWindowOverflowException(
            "timestamp cannot be represented as a half-open window end"
        )
    }
    return timestampUtc + 1L
}

private fun usageWindowSpan(startUtc: Long, endUtc: Long): Long {
    if (endUtc <= startUtc) return 0L
    return try {
        Math.subtractExact(endUtc, startUtc)
    } catch (_: ArithmeticException) {
        throw UsageWindowOverflowException("window span exceeds representable Long range")
    }
}

private class MutableUsageBatch(
    private val maxItems: Int,
    private val targetWindowMs: Long
) {
    val events = mutableListOf<MobileUsageEventEntity>()
    val summaries = mutableListOf<MobileUsageSummaryEntity>()
    val apps = mutableListOf<MobileAppMetadataEntity>()
    private var windowStartUtc: Long? = null
    private var windowEndUtc: Long? = null

    val totalCount: Int get() = events.size + summaries.size + apps.size

    fun canAccept(entry: UsageQueueEntry): Boolean {
        if (totalCount >= maxItems) return false
        val currentStart = windowStartUtc ?: return true
        val currentEnd = windowEndUtc ?: return true
        val nextStart = minOf(currentStart, entry.startUtc)
        val nextEnd = maxOf(currentEnd, entry.endUtc)
        val nextSpan = usageWindowSpan(nextStart, nextEnd)
        if (nextSpan > USAGE_BATCH_MAX_WINDOW_MS) return false
        val currentSpan = usageWindowSpan(currentStart, currentEnd)
        return nextSpan <= targetWindowMs || nextSpan <= currentSpan
    }

    fun add(entry: UsageQueueEntry) {
        windowStartUtc = windowStartUtc?.let { minOf(it, entry.startUtc) } ?: entry.startUtc
        windowEndUtc = windowEndUtc?.let { maxOf(it, entry.endUtc) } ?: entry.endUtc
        when (entry) {
            is UsageQueueEntry.Event -> events += entry.row
            is UsageQueueEntry.Summary -> summaries += entry.row
            is UsageQueueEntry.App -> apps += entry.row
        }
    }

    fun toPendingUsageBatch(): PendingUsageBatch {
        val start = windowStartUtc
        val rawEnd = windowEndUtc
        val end = if (start != null && rawEnd != null) {
            if (rawEnd > start) rawEnd else pointExclusiveEnd(start)
        } else {
            rawEnd
        }
        return PendingUsageBatch(events, summaries, apps, start, end)
    }
}

internal suspend fun pendingUsageRemaining(dao: MobileDataDao): Int {
    return dao.pendingUsageEventCount().first() +
        dao.pendingUsageSummaryCount().first() +
        dao.pendingAppMetadataCount().first()
}

private suspend fun <T> loadPriorityBatch(
    loader: suspend (syncStatus: String, limit: Int) -> List<T>,
    limit: Int
): List<T> {
    val pending = loader(MobileSyncStatus.PENDING, limit)
    if (pending.size >= limit) return pending
    val failed = loader(MobileSyncStatus.FAILED, limit - pending.size)
    return pending + failed
}
