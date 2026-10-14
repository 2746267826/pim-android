package com.pim.app.mute.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pim.app.mute.EventMuteCoordinator
import com.pim.app.mute.EventMuteStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 工单 04（E3.4）设置页「日程静音」分区的显示状态。
 *
 * 与 ViewModel 解耦的纯数据，便于在 Compose/Robolectric 测试里直接构造各种状态
 * （未授权、权限缺失、正在静音、已关闭…）。
 */
data class EventMuteSectionState(
    val followRuleEnabled: Boolean,
    val doNotDisturbAccessGranted: Boolean,
    val exactAlarmGranted: Boolean,
    val plannedAlarmCount: Int,
    val nextAlarmAtUtcMillis: Long?,
    val isMutingNow: Boolean,
    val lastError: String?
)

fun EventMuteStatus.toSectionState(): EventMuteSectionState = EventMuteSectionState(
    followRuleEnabled = followRuleEnabled,
    doNotDisturbAccessGranted = doNotDisturbAccessGranted,
    exactAlarmGranted = exactAlarmGranted,
    plannedAlarmCount = plannedAlarmCount,
    nextAlarmAtUtcMillis = nextAlarmAtUtcMillis,
    isMutingNow = isMutingNow,
    lastError = lastError
)

/**
 * 「日程静音」分区的取数与动作（E3.4 / AC-7）。
 *
 * 只读本地状态 + 转发用户操作；每个异步动作都自行处理失败并留下可见出口，
 * 不让异常冒到 UI 层（与 keepalive 分区同样的约定）。
 */
@HiltViewModel
class EventMuteViewModel @Inject constructor(
    private val coordinator: EventMuteCoordinator
) : ViewModel() {

    private val _sectionState = MutableStateFlow(
        EventMuteSectionState(
            followRuleEnabled = false,
            doNotDisturbAccessGranted = false,
            exactAlarmGranted = false,
            plannedAlarmCount = 0,
            nextAlarmAtUtcMillis = null,
            isMutingNow = false,
            lastError = null
        )
    )
    val sectionState: StateFlow<EventMuteSectionState> = _sectionState.asStateFlow()

    init {
        refresh()
    }

    /** 重新读取系统权限与本地状态（进入页面/返回前台时调用）。 */
    fun refresh() {
        _sectionState.value = coordinator.status().toSectionState()
    }

    /** 设备级开关（AC-7）：关闭后本机完全不动。 */
    fun setFollowRuleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                coordinator.setFollowRuleEnabled(enabled)
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                // coordinator 内部已记录可见状态；这里只刷新界面
            }
            _sectionState.value = coordinator.status().toSectionState()
        }
    }

    /** 手动重新拉取日程并重建闹钟计划（权限刚授予、或怀疑计划过期时用）。 */
    fun refreshPlan() {
        viewModelScope.launch {
            try {
                coordinator.refresh("settings-manual")
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                // 同上
            }
            _sectionState.value = coordinator.status().toSectionState()
        }
    }
}
