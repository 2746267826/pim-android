package com.pim.app.mute

import com.pim.core.network.ApiService
import com.pim.app.mobile.logs.StructuredLogRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * 工单 04（E3.1 / E3.2 / E3.4）：日程静音计划的编排。
 *
 * **由谁在何时触发（E3.1 要求明确写清）**：
 * 不引入新的常驻进程，也不复活 `PimDaemonService`（它在 manifest 里从未注册，属死代码）。
 * 采用「既有 [com.pim.app.location.service.ForegroundLocationService] 承载进程 + 既有 keepalive
 * 精确闹钟链驱动刷新」的组合：
 * - 进程存活：沿用既有的前台采集服务（用户已授权、已有常驻通知），不新增第二条前台服务；
 * - 计划刷新：开机/应用更新后由 `StartupRecoveryReceiver` 刷新一次；此后每次 keepalive 叫醒
 *   （默认 30 分钟，见 [com.pim.app.keepalive.KeepAliveAlarmScheduler]）顺带刷新 —— 计划窗口是
 *   24 小时，因此即使中间若干次刷新失败也不会漏掉当天日程；
 * - 精确触发：日程开始/结束各自登记一条 `setExactAndAllowWhileIdle`（见 [EventMuteAlarmScheduler]），
 *   重启后由开机刷新重建（AC-4）。
 *
 * 不选「复活守护程序」的理由：它需要新的前台服务 + 常驻通知 + 生命周期管理，而它要解决的
 * 「进程别被杀」既有链已经在做；再加一层只会增加耗电与通知噪音，还会带来第二套闹钟登记的风险。
 */
@Singleton
class EventMuteCoordinator internal constructor(
    private val apiService: ApiService,
    private val settings: EventMuteSettingsAccessor,
    private val schedulePort: EventMuteSchedulePort,
    private val device: EventMuteDevicePort,
    private val executor: EventMuteExecutor,
    private val logs: StructuredLogRepository,
    private val nowUtcMillis: () -> Long
) {
    @Inject
    constructor(
        apiService: ApiService,
        settings: EventMuteSettingsAccessor,
        schedulePort: EventMuteSchedulePort,
        device: EventMuteDevicePort,
        executor: EventMuteExecutor,
        logs: StructuredLogRepository
    ) : this(apiService, settings, schedulePort, device, executor, logs, System::currentTimeMillis)

    /**
     * 设备级开关（E3.4 / AC-7）：打开后立即刷新计划；关闭时**取消全部登记**并只留下「已关闭」状态，
     * 未来的日程不再影响本机。已进入的静音会话仍按快照收尾（Issue #25 路径 1）：
     * 收尾失败时 executor 保留会话并给出可见提示。
     */
    suspend fun setFollowRuleEnabled(enabled: Boolean) {
        settings.setFollowRuleEnabled(enabled)
        if (enabled) {
            refresh("user-enabled")
        } else {
            settings.recordRefresh(0, null, nowUtcMillis(), null)
            // 关闭开关立即收尾（不等真实 end）：未来不再静音，已进入的会话现在就还。
            // 恢复失败时 executor 保留会话与可见提示，之后每次 refresh 仍可重试。
            executor.finishNow()
            schedulePort.cancelAll()
            logs.info(LOG_TAG, "已关闭「本设备遵循日程静音规则」：取消全部闹钟登记")
        }
    }

    /** 拉取日程、算出计划并整体重新登记；任何失败都记录成可见状态，不抛出。 */
    suspend fun refresh(reason: String) {
        val now = nowUtcMillis()
        if (!settings.isFollowRuleEnabled()) {
            executor.finishNow()
            schedulePort.cancelAll()
            return
        }

        try {
            // 本地已知的结束/待重试先处理，不让网络失败挡住设备恢复。
            executor.reconcileExpiredSession()
            val calendars = apiService.getCalendars().data.orEmpty()
            val defaultMutedByCalendar = calendars.associate { it.id to it.defaultMuted }

            val from = Instant.ofEpochMilli(now - EventMutePlanner.LOOKBACK_HOURS * 3_600_000L).toString()
            val to = Instant.ofEpochMilli(
                now + EventMutePlanner.HORIZON_HOURS * 3_600_000L
            ).toString()

            val events = mutableListOf<UpcomingMuteEvent>()
            var page = 1
            while (page <= MAX_PAGES) {
                val response = apiService.getEvents(
                    start = from,
                    end = to,
                    page = page,
                    pageSize = PAGE_SIZE
                )
                val paged = response.data ?: break
                events += paged.items.mapNotNull { item ->
                    val start = parseInstantMillis(item.dtStart) ?: return@mapNotNull null
                    val end = parseInstantMillis(item.dtEnd) ?: return@mapNotNull null
                    if (item.isCancelled == true) return@mapNotNull null
                    UpcomingMuteEvent(
                        eventId = item.id,
                        title = item.title,
                        startUtcMillis = start,
                        endUtcMillis = end,
                        effectiveMuted = EventMutePlanner.isEffectivelyMuted(
                            eventIsMuted = item.isMuted,
                            calendarDefaultMuted = defaultMutedByCalendar[item.calendarId]
                        )
                    )
                }
                if (page >= paged.totalPages) break
                page++
            }

            val plan = EventMutePlanner.plan(events, now)
            val result = schedulePort.schedule(plan)
            var scheduleError: String? = null
            when (result) {
                is EventMuteAlarmScheduler.Result.Scheduled ->
                    settings.recordRefresh(plan.size, result.firstAtUtcMillis, now, null)
                EventMuteAlarmScheduler.Result.PermissionMissing -> {
                    // E3.2 的降级行为：不登记、也不退回不精确的 set()，而是给出可见提示
                    scheduleError = "未授予「闹钟和提醒」权限，日程静音无法准时生效"
                    settings.recordRefresh(
                        plan.size,
                        null,
                        now,
                        scheduleError
                    )
                    logs.warn(LOG_TAG, "刷新日程静音计划失败：缺少精确闹钟权限（$reason）")
                }
                is EventMuteAlarmScheduler.Result.Failed -> {
                    scheduleError = result.message
                    settings.recordRefresh(plan.size, null, now, result.message)
                    logs.warn(LOG_TAG, "刷新日程静音计划失败：${result.message}（$reason）")
                }
            }
            // 只用取到的正向事实补 end，未取到不能当成已结束。对账最后写状态，避免
            // 「刷新成功」擦掉恢复失败/旧会话结束时间待核实的提示。
            executor.reconcileWithKnownEnds(events.associate { it.eventId to it.endUtcMillis })
            scheduleError?.let { error ->
                settings.recordError(listOfNotNull(settings.readPersistedStatus().lastError, error).distinct().joinToString("；"))
            }
            logs.info(LOG_TAG, "已刷新日程静音计划（$reason）：候选 ${events.size} 条，登记 ${plan.size} 条")
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            // 网络/鉴权失败不能让整条链挂掉：记成状态，下一次叫醒再试
            val recoveryError = if (executor.hasPendingRestore()) "静音还原尚未完成，将在下次刷新重试" else null
            settings.recordError(listOfNotNull(recoveryError, ex.message ?: ex::class.java.simpleName).joinToString("；"))
            logs.warn(LOG_TAG, "刷新日程静音计划失败（$reason）：${ex.message ?: ""}")
        }
    }

    /** 设置页/诊断用状态：持久化状态 + 实时权限（勿扰访问、精确闹钟）。 */
    fun status(): EventMuteStatus {
        val persisted = settings.readPersistedStatus()
        return EventMuteStatus(
            followRuleEnabled = settings.isFollowRuleEnabled(),
            doNotDisturbAccessGranted = device.hasDoNotDisturbAccess(),
            exactAlarmGranted = schedulePort.hasExactAlarmPermission(),
            plannedAlarmCount = persisted.plannedAlarmCount,
            nextAlarmAtUtcMillis = persisted.nextAlarmAtUtcMillis,
            activeMuteEventIds = persisted.activeMuteEventIds,
            lastRefreshAtUtcMillis = persisted.lastRefreshAtUtcMillis,
            lastError = persisted.lastError
        )
    }

    private fun parseInstantMillis(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        null
    }

    companion object {
        const val LOG_TAG = "eventmute"

        /** 单页事件数（契约 pageSize 上限 100）。 */
        const val PAGE_SIZE = 100

        /** 最多翻页数：24 小时窗口内 500 条足够，避免异常数据把刷新拖死。 */
        const val MAX_PAGES = 5
    }
}
