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
    private val logs: StructuredLogRepository,
    private val nowUtcMillis: () -> Long
) {
    @Inject
    constructor(
        apiService: ApiService,
        settings: EventMuteSettingsAccessor,
        schedulePort: EventMuteSchedulePort,
        device: EventMuteDevicePort,
        logs: StructuredLogRepository
    ) : this(apiService, settings, schedulePort, device, logs, System::currentTimeMillis)

    /**
     * 设备级开关（E3.4 / AC-7）：打开后立即刷新计划；关闭时**取消全部登记**并只留下「已关闭」状态，
     * 本机不再有任何本功能引起的改动（已进入的静音会话按「不碰设备」的原则不再回写）。
     */
    suspend fun setFollowRuleEnabled(enabled: Boolean) {
        settings.setFollowRuleEnabled(enabled)
        if (enabled) {
            refresh("user-enabled")
        } else {
            schedulePort.cancelAll()
            settings.recordRefresh(0, null, nowUtcMillis(), null)
            logs.info(LOG_TAG, "已关闭「本设备遵循日程静音规则」：取消全部闹钟登记")
        }
    }

    /** 拉取日程、算出计划并整体重新登记；任何失败都记录成可见状态，不抛出。 */
    suspend fun refresh(reason: String) {
        val now = nowUtcMillis()
        if (!settings.isFollowRuleEnabled()) {
            schedulePort.cancelAll()
            settings.recordRefresh(0, null, now, null)
            return
        }

        try {
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
            when (result) {
                is EventMuteAlarmScheduler.Result.Scheduled ->
                    settings.recordRefresh(plan.size, result.firstAtUtcMillis, now, null)
                EventMuteAlarmScheduler.Result.PermissionMissing -> {
                    // E3.2 的降级行为：不登记、也不退回不精确的 set()，而是给出可见提示
                    settings.recordRefresh(
                        plan.size,
                        null,
                        now,
                        "未授予「闹钟和提醒」权限，日程静音无法准时生效"
                    )
                    logs.warn(LOG_TAG, "刷新日程静音计划失败：缺少精确闹钟权限（$reason）")
                }
                is EventMuteAlarmScheduler.Result.Failed -> {
                    settings.recordRefresh(plan.size, null, now, result.message)
                    logs.warn(LOG_TAG, "刷新日程静音计划失败：${result.message}（$reason）")
                }
            }
            logs.info(LOG_TAG, "已刷新日程静音计划（$reason）：候选 ${events.size} 条，登记 ${plan.size} 条")
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            // 网络/鉴权失败不能让整条链挂掉：记成状态，下一次叫醒再试
            settings.recordError(ex.message ?: ex::class.java.simpleName)
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
