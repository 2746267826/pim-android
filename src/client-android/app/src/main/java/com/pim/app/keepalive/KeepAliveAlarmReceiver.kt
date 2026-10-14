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
import kotlinx.coroutines.launch

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
                coordinator.onAlarmFired()
            } catch (ex: Exception) {
                // REQ-28：叫醒链路的失败必须留下可见出口，不能静默。
                logs.error("keepalive", "处理叫醒广播失败：${ex.message ?: ex::class.java.simpleName}", ex)
            }

            // 工单 04（E3.2）：复用既有的叫醒周期顺带刷新日程静音计划 ——
            // 这正是「不新增常驻服务，也不用第二套闹钟」的落点：计划窗口 24h，
            // 每次叫醒（默认 30 分钟）重建一次，重启后由开机恢复重建（AC-4）。
            // 独立 try：静音刷新失败绝不影响保活链本身的语义。
            try {
                eventMuteCoordinator.refresh("keepalive-wake")
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                logs.error("eventmute", "叫醒周期刷新日程静音计划失败：${ex.message ?: ""}", ex)
            }

            pendingResult.finish()
        }
    }
}
