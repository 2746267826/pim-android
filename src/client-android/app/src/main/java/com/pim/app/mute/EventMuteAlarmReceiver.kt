package com.pim.app.mute

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pim.app.mobile.logs.StructuredLogRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 工单 04（E3.2）日程静音闹钟的接收器。
 *
 * 由 [EventMuteAlarmScheduler] 用 `setExactAndAllowWhileIdle` 登记，系统在精确时刻派发到这里。
 * 收到的是一小段同步工作（改铃声模式/免打扰过滤器 + 落盘），用 `goAsync()` 保证广播不被提前回收。
 *
 * 与 keepalive 的叫醒接收器是**两个独立的 action/接收器**，互不覆盖（见调度器的注释）。
 */
@AndroidEntryPoint
class EventMuteAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var executor: EventMuteExecutor

    @Inject
    lateinit var logs: StructuredLogRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != EventMuteAlarmScheduler.ACTION_EVENT_MUTE) return

        val eventId = intent.getStringExtra(EventMuteAlarmScheduler.EXTRA_EVENT_ID)
        val action = intent.getStringExtra(EventMuteAlarmScheduler.EXTRA_ACTION)
            ?.let { runCatching { MuteAction.valueOf(it) }.getOrNull() }
        val eventEnd = if (intent.hasExtra(EventMuteAlarmScheduler.EXTRA_EVENT_END)) {
            intent.getLongExtra(EventMuteAlarmScheduler.EXTRA_EVENT_END, Long.MIN_VALUE)
        } else {
            null
        }
        // 参数缺失是**不该发生**的情况（登记方一定写入 extras）；统一在协程里处理，
        // 因为结构化日志仓库是 suspend 的，而 onReceive 不是。
        val missingParameters = eventId.isNullOrEmpty() || action == null

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                if (missingParameters) {
                    logs.warn(EventMuteExecutor.LOG_TAG, "日程静音闹钟缺少必要参数，已忽略")
                    return@launch
                }
                executor.onAlarm(eventId!!, action!!, eventEnd)
            } catch (ex: Exception) {
                // E3.3：失败必须留下可见出口，不能静默
                logs.error(
                    EventMuteExecutor.LOG_TAG,
                    "处理日程静音闹钟失败：${ex.message ?: ex::class.java.simpleName}",
                    ex
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
