package com.pim.app.mute

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pim.app.mobile.logs.StructuredLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工单 04（E3.2）日程静音的精确闹钟登记。
 *
 * **与既有 keepalive 闹钟链的关系（不冲突是硬要求）**：
 * - 两者的 `action` 不同：本类是 [ACTION_EVENT_MUTE]，keepalive 是
 *   `KeepAliveAlarmScheduler.ACTION_KEEPALIVE_ALARM`；
 * - 两者的 requestCode 区间不同：本类用 [REQUEST_CODE_BASE]（7400）起的**保留区间**按槽位分配，
 *   keepalive 固定用 7301。`PendingIntent` 的等价性只看 (action, requestCode, Intent 数据)，
 *   因此两套登记在系统里是彼此独立的条目，不会互相覆盖或互相取消。
 * - 同样只用 `setExactAndAllowWhileIdle`（不用 `setAlarmClock`），与既有链保持一致的省电语义。
 *
 * 槽位分配：闹钟计划每次刷新都是**整体重建**，所以按「计划下标」分配槽位即可保证唯一；
 * 同一槽位用 `FLAG_UPDATE_CURRENT` 覆盖旧的 PendingIntent（含新的 extras），不会残留两份。
 */
@Singleton
class EventMuteAlarmScheduler internal constructor(
    private val alarmManager: AlarmManager?,
    private val appContext: Context,
    private val logs: StructuredLogRepository,
    private val canScheduleExactAlarms: () -> Boolean
) : EventMuteSchedulePort {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        logs: StructuredLogRepository
    ) : this(
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager,
        appContext = context.applicationContext,
        logs = logs,
        canScheduleExactAlarms = { defaultCanScheduleExactAlarms(context) }
    )

    sealed interface Result {
        data class Scheduled(val count: Int, val firstAtUtcMillis: Long?) : Result
        data object PermissionMissing : Result
        data class Failed(val message: String) : Result
    }

    /** 是否有精确闹钟授权（未授权时降级行为见 [schedule]）。 */
    override fun hasExactAlarmPermission(): Boolean = try {
        canScheduleExactAlarms()
    } catch (_: Exception) {
        false
    }

    /**
     * 用给定计划**整体替换**已登记的闹钟。
     *
     * 降级行为（E3.2 要求写明）：
     * - 精确闹钟权限被拒：不登记任何闹钟并返回 [Result.PermissionMissing]，由上层点亮可见提示 ——
     *   绝不退回 `set()`（那会在 Doze 下漂移到几十分钟，等于功能失效却看不出来）。
     * - 厂商省电策略（OPPO/ColorOS 等）：闹钟本身仍由系统在精确时刻派发，但进程被冻结/被杀时
     *   广播可能被延后；本项目已有 `ColorOsGuidanceCatalog` 的引导与既有 keepalive 链兜底，
     *   本类不重复造引导，只在失败时返回 [Result.Failed] 让上层记录。
     */
    override suspend fun schedule(plan: List<PlannedMuteAlarm>): Result {
        val manager = alarmManager ?: return Result.Failed("系统未提供闹钟服务")
        if (!hasExactAlarmPermission()) {
            logs.warn(LOG_TAG, "无法登记日程静音闹钟：未获得「闹钟和提醒」权限")
            return Result.PermissionMissing
        }

        cancelAll()

        var first: Long? = null
        var count = 0
        for ((index, alarm) in plan.withIndex()) {
            if (index >= MAX_SLOTS) break
            val requestCode = requestCodeFor(index, alarm.action)
            val pendingIntent = buildPendingIntent(appContext, requestCode, alarm)
                ?: return Result.Failed("无法创建日程静音闹钟的 PendingIntent")
            try {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    alarm.triggerAtUtcMillis,
                    pendingIntent
                )
                count++
                if (first == null || alarm.triggerAtUtcMillis < first) first = alarm.triggerAtUtcMillis
            } catch (ex: SecurityException) {
                logs.warn(LOG_TAG, "登记日程静音闹钟被系统拒绝：${ex.message ?: ""}")
                return Result.PermissionMissing
            } catch (ex: Exception) {
                logs.error(LOG_TAG, "登记日程静音闹钟失败：${ex.message ?: ex::class.java.simpleName}", ex)
                return Result.Failed(ex.message ?: ex::class.java.simpleName)
            }
        }

        logs.info(LOG_TAG, "已登记日程静音闹钟 $count 条（最近一次：${first ?: "无"}）")
        return Result.Scheduled(count, first)
    }

    /** 取消本类名下的全部槽位；不影响 keepalive 的 7301。 */
    override suspend fun cancelAll() {
        for (slot in 0 until MAX_SLOTS * 2) {
            try {
                val pendingIntent = buildPendingIntent(
                    appContext,
                    REQUEST_CODE_BASE + slot,
                    alarm = null,
                    flags = PendingIntent.FLAG_NO_CREATE
                ) ?: continue
                alarmManager?.cancel(pendingIntent)
            } catch (ex: Exception) {
                logs.warn(LOG_TAG, "取消日程静音闹钟失败：${ex.message ?: ""}")
            }
        }
    }

    companion object {
        const val LOG_TAG = "eventmute"

        /** 日程静音闹钟的广播 action（与 keepalive 的 action 不同，互不干扰）。 */
        const val ACTION_EVENT_MUTE = "com.pim.app.mute.action.EVENT_MUTE"

        /** 槽位 requestCode 基址；7301 是 keepalive 的固定值，这里从 7400 起预留。 */
        const val REQUEST_CODE_BASE = 7400

        /** 槽位数：计划里最多 [EventMutePlanner.MAX_ALARMS] 条，每条一个槽。 */
        const val MAX_SLOTS = EventMutePlanner.MAX_ALARMS

        const val EXTRA_EVENT_ID = "com.pim.app.mute.extra.EVENT_ID"
        const val EXTRA_ACTION = "com.pim.app.mute.extra.ACTION"
        const val EXTRA_EVENT_END = "com.pim.app.mute.extra.EVENT_END"

        /** 第 index 条计划用的 requestCode（START/END 各占一个槽序号）。 */
        fun requestCodeFor(index: Int, action: MuteAction): Int =
            REQUEST_CODE_BASE + index * 2 + if (action == MuteAction.START) 0 else 1

        internal fun buildPendingIntent(
            context: Context,
            requestCode: Int,
            alarm: PlannedMuteAlarm?,
            flags: Int = PendingIntent.FLAG_UPDATE_CURRENT
        ): PendingIntent? {
            val intent = Intent(context, EventMuteAlarmReceiver::class.java)
                .setAction(ACTION_EVENT_MUTE)
            if (alarm != null) {
                intent.putExtra(EXTRA_EVENT_ID, alarm.eventId)
                intent.putExtra(EXTRA_ACTION, alarm.action.name)
                alarm.eventEndUtcMillis?.let { intent.putExtra(EXTRA_EVENT_END, it) }
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                flags or PendingIntent.FLAG_IMMUTABLE
            )
        }

        internal fun defaultCanScheduleExactAlarms(context: Context): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                    ?.canScheduleExactAlarms() ?: false
            } else {
                true
            }
    }
}
