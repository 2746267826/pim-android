package com.pim.app.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.mute.EventMuteCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 叫醒广播接收器（REQ-16）。
 *
 * 系统把精确闹钟派发到这里（`setExactAndAllowWhileIdle`，见 [KeepAliveAlarmScheduler]）。
 * 接收后交给 [KeepAliveCoordinator] 执行完整链路并登记下一次闹钟——
 * 「叫醒一次就不管了」是保活最常见的失败形态，因此这里必须把下一次闹钟续上。
 *
 * 注意：`goAsync()` 用于让广播在异步工作完成前不被回收，这是 `BroadcastReceiver` 里
 * 做少量异步工作的标准做法；工作本身有超时保护（见 [KeepAliveCoordinator]）。
 */
@AndroidEntryPoint
class KeepAliveAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var coordinator: KeepAliveCoordinator

    @Inject
    lateinit var eventMuteCoordinator: EventMuteCoordinator

    @Inject
    lateinit var logs: StructuredLogRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != KeepAliveAlarmScheduler.ACTION_KEEPALIVE_ALARM) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                try {
                    coordinator.onAlarmFired()
                } catch (ex: Exception) {
                    // REQ-28：叫醒链路的失败必须留下可见出口，不能静默。
                    logs.error("keepalive", "处理叫醒广播失败：${ex.message ?: ex::class.java.simpleName}", ex)
                }

                // 工单 04（E3.2）：复用既有的叫醒周期顺带刷新日程静音计划 ——
                // 这正是「不新增常驻服务，也不用第二套闹钟」的落点：计划窗口 24h，
                // 每次叫醒（默认 30 分钟）重建一次，重启后由开机恢复重建（AC-4）。
                //
                // 独立 try + **超时**：广播的 goAsync 窗口约 10s，而刷新要拉日历本 + 最多 5 页日程；
                // 网络慢时必须主动放弃本次刷新（下次叫醒重试），不能让接收器超时被回收
                // （那会连带影响保活链本身，正是工单要求「不能因进程被冻结/杀死而漏开或漏恢复」的场景）。
                try {
                    withTimeout(MUTE_REFRESH_TIMEOUT_MS) {
                        eventMuteCoordinator.refresh("keepalive-wake")
                    }
                } catch (ex: TimeoutCancellationException) {
                    logs.warn(
                        "eventmute",
                        "叫醒周期刷新日程静音计划超时（${MUTE_REFRESH_TIMEOUT_MS}ms），本次跳过，下次叫醒重试"
                    )
                } catch (ex: kotlinx.coroutines.CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    logs.error("eventmute", "叫醒周期刷新日程静音计划失败：${ex.message ?: ""}", ex)
                }
            } finally {
                // 无条件 finish：goAsync() 的广播必须总有出口，否则系统会判为「未完成广播」。
                // 静音刷新既不能吞掉真正的取消，也不能让接收器漏 finish（独立审查 I-2 / I-3）。
                pendingResult.finish()
            }
        }
    }

    companion object {
        /**
         * 叫醒周期内刷新日程静音计划的超时。
         *
         * 取 5s：广播的 goAsync 窗口约 10s，而前面既有保活链（`onAlarmFired`）也要花时间，
         * 留出余量避免接收器整体超时被系统回收。
         */
        internal const val MUTE_REFRESH_TIMEOUT_MS = 5_000L
    }
}
