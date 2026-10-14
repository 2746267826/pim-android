package com.pim.app.mobile.sync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.core.auth.TokenManager
import com.pim.core.contract.EndpointHeartbeatRequest
import com.pim.core.network.ApiService
import com.pim.core.settings.ServerSettingsStore
import com.pim.core.util.toCauseChainMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/** 端点心跳的上报结果（测试与调用方都读它，避免用"有没有抛异常"当结果）。 */
enum class EndpointHeartbeatOutcome {
    /** 真的发出去了，并且服务端接受了。 */
    SENT,

    /** 发出去了但失败（服务端 4xx/5xx 或传输层失败）。 */
    FAILED,

    /** 距上次真实上报还没到最小间隔，本次不发。 */
    SKIPPED_NOT_DUE,

    /** 没有可用登录令牌，本次**不发**（也不在服务端留下"刚活着"的假记录）。 */
    SKIPPED_UNAUTHENTICATED,
}

/** 端点心跳的内存状态（供状态页/测试读取；不上报给服务端）。 */
data class EndpointHeartbeatStatus(
    val lastAttemptAtMillis: Long? = null,
    val lastSuccessAtMillis: Long? = null,
    val lastError: String? = null,
    val consecutiveFailures: Int = 0,
    val lastReportedUploadStatus: String? = null,
)

/** 采集上传的结果（`uploadStatus` 口径的来源）。 */
data class UploadOutcome(val lastError: String?, val hasCompletedUpload: Boolean)

/**
 * 端点心跳的上报入口（端口）：服务的采集循环只依赖这个接口，
 * 因此"服务有没有在既有节拍上问"与"问了之后怎么发"可以分别验证。
 */
interface EndpointHeartbeatSink {
    suspend fun reportIfDue(collectionCacheCount: Int?): EndpointHeartbeatOutcome
}

/** 上传结果来源。生产读同步状态；测试给桩，不必装配整个同步编排。 */
fun interface UploadOutcomeSource {
    fun current(): UploadOutcome
}

/**
 * 端点心跳上报（工单 06 / G2）：`POST /api/v1/endpoints/{deviceId}/heartbeat`。
 *
 * <para>
 * 端点页（`GET /api/v1/endpoints`）与「端点外壳」调试页的数据**只**来自这张表；
 * 在此之前没有任何客户端在生产路径上调过它，于是页面永远是空的。
 * </para>
 *
 * 三条硬约束（对应 G2.2 / G2.3）：
 *  * **不新增常驻服务**：由既有的 `ForegroundLocationService`（前台定位服务）在它自己的
 *    采集循环里调用，不注册新的定时器/闹钟；
 *  * **最小间隔**：`MIN_INTERVAL_MILLIS` 内只发一次，避免在系统限制下反复唤醒网络；
 *  * **不伪装**：请求体里没有时间字段，`last_heartbeat_at` 由服务端按收到时刻写；
 *    没登录就不发（也不记为成功），失败只记账、不抛异常。
 */
@Singleton
class EndpointHeartbeatReporter internal constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val identityProvider: DeviceIdentityProvider,
    private val uploadOutcomeSource: UploadOutcomeSource,
    private val tokenManager: TokenManager,
    private val serverSettingsStore: ServerSettingsStore,
    private val logs: StructuredLogRepository,
    private val nowUtcMillis: () -> Long
) : EndpointHeartbeatSink {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        api: ApiService,
        identityProvider: DeviceIdentityProvider,
        uploadOutcomeSource: UploadOutcomeSource,
        tokenManager: TokenManager,
        serverSettingsStore: ServerSettingsStore,
        logs: StructuredLogRepository
    ) : this(
        context,
        api,
        identityProvider,
        uploadOutcomeSource,
        tokenManager,
        serverSettingsStore,
        logs,
        System::currentTimeMillis
    )

    private val sendGate = Mutex()
    private val statusLock = Any()
    private val _status = MutableStateFlow(EndpointHeartbeatStatus())

    /** 端点心跳的当前状态（最近尝试/最近成功/连续失败/原因）。 */
    val status: StateFlow<EndpointHeartbeatStatus> = _status.asStateFlow()

    /** 「没有令牌所以没发」这类跳过日志的节流时刻：避免每 30 秒刷一行。 */
    private var lastSkipLogAtMillis: Long? = null

    /**
     * 到点就上报一次；没到点 / 没登录则**不发**并如实返回原因。
     *
     * 并发调用由 [sendGate] 串行化：第二次进入时第一次已经把"尝试时刻"写好了，
     * 因此会直接走 [EndpointHeartbeatOutcome.SKIPPED_NOT_DUE]，不会打出两倍的请求。
     */
    override suspend fun reportIfDue(collectionCacheCount: Int?): EndpointHeartbeatOutcome = sendGate.withLock {
        val now = nowUtcMillis()
        val lastAttempt = _status.value.lastAttemptAtMillis
        if (lastAttempt != null && now - lastAttempt < MIN_INTERVAL_MILLIS) {
            return@withLock EndpointHeartbeatOutcome.SKIPPED_NOT_DUE
        }

        if (!hasUsableToken()) {
            logSkip(now)
            return@withLock EndpointHeartbeatOutcome.SKIPPED_UNAUTHENTICATED
        }

        send(now, collectionCacheCount)
    }

    private suspend fun send(now: Long, collectionCacheCount: Int?): EndpointHeartbeatOutcome {
        val identity = identityProvider.current()
        val outcome = uploadOutcomeSource.current()
        val uploadStatus = resolveUploadStatus(outcome.lastError, outcome.hasCompletedUpload)
        val request = EndpointHeartbeatRequest(
            platform = PLATFORM,
            appVersion = appVersionName(),
            uploadStatus = uploadStatus,
            collectionCacheCount = collectionCacheCount
        )
        recordAttempt(now)

        return try {
            api.sendEndpointHeartbeat(identity.deviceId, request)
            recordSuccess(now, uploadStatus)
            logs.info(
                TAG,
                "端点心跳已上报：device=${identity.deviceId} status=$uploadStatus " +
                    "cache=${collectionCacheCount ?: "-"}"
            )
            EndpointHeartbeatOutcome.SENT
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            // 失败必须可见：记账 + 写结构化日志（状态页日志区可见），且**不**把这次算成上报过。
            val detail = describe(ex)
            val streak = recordFailure(now, detail)
            logs.warn(TAG, "端点心跳上报失败（连续 $streak 次）：$detail")
            EndpointHeartbeatOutcome.FAILED
        }
    }

    private fun hasUsableToken(): Boolean {
        val token = runCatching {
            tokenManager.getAccessTokenForServer(serverSettingsStore.getBaseUrl())
        }.getOrNull()
        return !token.isNullOrBlank()
    }

    private suspend fun logSkip(now: Long) {
        val last = lastSkipLogAtMillis
        if (last != null && now - last < MIN_INTERVAL_MILLIS) return
        lastSkipLogAtMillis = now
        logs.info(TAG, "端点心跳跳过：没有可用登录令牌（未上报，服务端仍显示上一次心跳时刻）")
    }

    private fun recordAttempt(now: Long) {
        synchronized(statusLock) {
            _status.value = _status.value.copy(lastAttemptAtMillis = now)
        }
    }

    private fun recordSuccess(now: Long, uploadStatus: String) {
        synchronized(statusLock) {
            _status.value = _status.value.copy(
                lastSuccessAtMillis = now,
                lastError = null,
                consecutiveFailures = 0,
                lastReportedUploadStatus = uploadStatus
            )
        }
    }

    private fun recordFailure(now: Long, detail: String): Int = synchronized(statusLock) {
        val streak = _status.value.consecutiveFailures + 1
        _status.value = _status.value.copy(
            lastError = detail,
            consecutiveFailures = streak,
            lastAttemptAtMillis = now
        )
        streak
    }

    private fun describe(ex: Exception): String = when (ex) {
        is HttpException -> "HTTP ${ex.code()} ${ex.message()}"
        else -> "${ex::class.java.simpleName}: ${ex.toCauseChainMessage()}"
    }

    private fun appVersionName(): String = appVersionName(context)

    companion object {
        private const val TAG = "endpoint-heartbeat"

        /** 契约 `platform`：服务端只认 `android` / `windows`。 */
        const val PLATFORM = "android"

        /** 最小上报间隔：比守护进程心跳（2 分钟）宽，够反映"设备最后一次活着"。 */
        const val MIN_INTERVAL_MILLIS = 5 * 60 * 1000L

        const val HEALTHY_STATUS = "Healthy"
        const val WARNING_STATUS = "Warning"
        const val UNKNOWN_STATUS = "Unknown"

        /**
         * 上传状态口径，与服务端/端点页在用的词表一致（`Healthy` 绿，其余黄；
         * 服务端 Today 区块把 `Warning`/`Critical` 当异常）：
         *  * 有未清除的上传错误 → `Warning`；
         *  * 成功上传过、当前无错误 → `Healthy`；
         *  * 还没成功上传过 → `Unknown`（不把"还没开始"写成"健康"）。
         *
         * 积压条数走独立的 `collectionCacheCount`，不重复编码进状态串。
         */
        fun resolveUploadStatus(lastUploadError: String?, hasCompletedUpload: Boolean): String = when {
            !lastUploadError.isNullOrBlank() -> WARNING_STATUS
            hasCompletedUpload -> HEALTHY_STATUS
            else -> UNKNOWN_STATUS
        }
    }
}

/** 本应用版本名（取不到时 `unknown`，绝不编一个版本号）。 */
internal fun appVersionName(context: Context): String {
    return try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}
