package com.pim.app.mute

import com.pim.app.mobile.logs.StructuredLogRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 工单 04（E3.2 / E3.3 / E3.5）：闹钟真正到点时的执行规则。
 *
 * 这里集中了本工单最容易写错、后果最直接的几条规则，因此每条都可以在 JVM 上断言
 * （依赖 [EventMuteSettingsAccessor]、[MuteSessionStore]、[EventMuteDevicePort] 三个窄接口）：
 *
 * 1. **设备级开关关闭 → 不再开始静音**：不拍新快照，但已有会话仍可收尾。
 * 2. **未授权勿扰访问 → 不改任何系统状态并留下可见出口**（E3.3 / AC-5），不允许静默失效。
 * 3. **开始时先拍快照再静音**（E3.5）；同一事件重复触发是幂等的（广播可能重发）。
 * 4. **重叠日程共享一个会话**：第一个开始的事件拍快照，最后一个结束的事件负责还原，
 *    避免 B 结束时把设备恢复成「A 静音中」的状态。
 * 5. **结束时没有快照 → 不碰设备**：宁可不动，也不能把用户自己设的响铃/振动覆盖掉。
 * 6. **还原必须确认成功后才清会话**；失败保留会话与快照，给出可见提示并在下次刷新重试。
 * 7. **设备级开关关闭不再跳过还原**：关闭只停止「未来的」静音，已进入的会话仍要按快照收尾
 *    （Issue #25 路径 1）；无法确认恢复时同样保留会话与可见提示。
 * 8. **收尾以真实事件结束时刻为准**（Issue #25 路径 2）：每个事件的 end 随 START 落盘，
 *    全部已结束才恢复；没有 end 的旧会话只能由刷新核实，不做 TTL 猜测。
 * 9. **尊重用户手动修改**：恢复前比对当前状态与本功能写入的静音档案；
 *    被用户改过就放弃接管、保留用户状态。
 *
 * 所有会话读写都在一个 Mutex 内：并发 START / END / refresh 对账 / 关闭开关时，
 * 「读-改-写」不会互相覆盖丢事件。
 */
@Singleton
class EventMuteExecutor internal constructor(
    private val settings: EventMuteSettingsAccessor,
    private val sessions: MuteSessionStore,
    private val device: EventMuteDevicePort,
    private val logs: StructuredLogRepository,
    private val nowUtcMillis: () -> Long
) {
    private val mutex = Mutex()

    @Inject
    constructor(
        settings: EventMuteSettingsAccessor,
        sessions: MuteSessionStore,
        device: EventMuteDevicePort,
        logs: StructuredLogRepository
    ) : this(settings, sessions, device, logs, System::currentTimeMillis)

    suspend fun onAlarm(eventId: String, action: MuteAction, eventEndUtcMillis: Long? = null) {
        mutex.withLock {
            // 开关判断必须与后续动作在同一把锁内：否则「关闭开关 → finishNow 清会话」
            // 与「迟到的 START」交错时，START 会在收尾之后又把设备静音且无人负责还原。
            if (!settings.isFollowRuleEnabled()) {
                logs.info(LOG_TAG, "本设备未开启「遵循日程静音规则」，忽略 $action（$eventId）")
                // 开关关闭只停止未来动作；已进入的静音会话仍需按快照收尾（Issue #25 路径 1）。
                if (action == MuteAction.END) {
                    handleEnd(eventId)
                }
                return
            }
            when (action) {
                MuteAction.START -> handleStart(eventId, eventEndUtcMillis)
                MuteAction.END -> handleEnd(eventId)
            }
        }
    }

    private suspend fun handleStart(eventId: String, eventEndUtcMillis: Long?) {
        if (eventEndUtcMillis != null && eventEndUtcMillis <= nowUtcMillis()) {
            logs.info(LOG_TAG, "日程实际已结束，忽略迟到的开始动作")
            return
        }
        var existing = sessions.read()
        if (existing?.pendingRestoreReason != null) {
            finishSession(existing, reason = "新日程开始前先完成上次还原")
            existing = sessions.read()
            if (existing != null) return
        }
        if (existing != null && existing.eventIds.contains(eventId)) {
            // 幂等：广播重发或补开时不要覆盖已记录的「进入前状态」
            // 但 end 是后来刷新才能核实的事实，缺了要补
            if (eventEndUtcMillis != null && !existing.eventEndsUtcMillis.containsKey(eventId)) {
                sessions.write(existing.copy(eventEndsUtcMillis = existing.eventEndsUtcMillis + (eventId to eventEndUtcMillis)))
            }
            logs.info(LOG_TAG, "事件已在静音会话中，忽略重复的开始动作（$eventId）")
            return
        }

        if (!device.hasDoNotDisturbAccess()) {
            // E3.3 / AC-5：必须可见，不能静默失效
            settings.recordError("未授予「勿扰访问」权限，无法在日程期间静音")
            logs.warn(LOG_TAG, "未授予勿扰访问权限，跳过静音（$eventId）")
            return
        }

        if (existing != null) {
            // 重叠：复用已有会话与快照，最后一个结束的事件负责还原
            val ends = if (eventEndUtcMillis != null) {
                existing.eventEndsUtcMillis + (eventId to eventEndUtcMillis)
            } else {
                existing.eventEndsUtcMillis
            }
            sessions.write(existing.copy(eventIds = existing.eventIds + eventId, eventEndsUtcMillis = ends))
            logs.info(LOG_TAG, "日程与进行中的静音会话重叠，加入会话（$eventId）")
            settings.recordError(null)
            return
        }

        val state = readDeviceState() ?: return
        val session = MuteSession(
            eventIds = setOf(eventId),
            state = state,
            enteredAtUtcMillis = nowUtcMillis(),
            eventEndsUtcMillis = if (eventEndUtcMillis != null) {
                mapOf(eventId to eventEndUtcMillis)
            } else {
                emptyMap()
            },
            appliedState = device.mutedState()
        )
        // 快照和预期状态必须先可靠落盘；写入失败时绝不能先改设备。
        sessions.write(session)
        val applied = device.applyMuted()
        if (applied) {
            settings.recordError(null)
            logs.info(
                LOG_TAG,
                "日程开始：已静音（进入前：ringerMode=${state.ringerMode}，" +
                    "filter=${state.interruptionFilter}）"
            )
        } else {
            // 可能只写成功一条系统通路，记录此次操作后的状态，供本地重试安全回滚。
            val observed = readDeviceState()
            val failReason = "静音未成功确认，需恢复进入前状态"
            sessions.write(session.copy(pendingRestoreReason = failReason, appliedState = observed ?: session.appliedState))
            settings.recordError("$failReason，将在下次刷新重试")
            logs.warn(LOG_TAG, failReason)
        }
    }

    private suspend fun handleEnd(eventId: String) {
        val session = sessions.read()
        if (session == null) {
            // 没有快照：不碰设备（否则会把用户自己的设置覆盖成响铃）
            logs.info(LOG_TAG, "没有进入前状态记录，结束时不改动本机状态（$eventId）")
            return
        }
        if (!session.eventIds.contains(eventId)) {
            logs.info(LOG_TAG, "事件不在当前静音会话中，忽略结束动作（$eventId）")
            return
        }

        val remaining = session.eventIds - eventId
        if (remaining.isNotEmpty()) {
            sessions.write(session.copy(eventIds = remaining, eventEndsUtcMillis = session.eventEndsUtcMillis.filterKeys { it in remaining }))
            if (session.pendingRestoreReason == null) settings.recordError(null)
            logs.info(LOG_TAG, "仍有日程处于静音会话中，暂不恢复（剩余 ${remaining.size} 条）")
            return
        }

        finishSession(session, reason = "日程结束（$eventId）")
    }

    /**
     * 收尾（只在 mutex 内调用）：先确认「这台设备还归本功能管」再还原。
     * 当前状态仍等于本功能写入的静音档案 → 恢复快照；
     * 被用户手动改过 → 放弃接管，保留用户状态，清会话并留说明；
     * 恢复调用失败 → 保留会话与可见提示，下次刷新重试。
     *
     * 部分恢复失败时记录操作后的真实状态；下一次也必须比对它，不能因为待重试就
     * 无条件覆盖用户的新设置。旧会话无凭据时只按固定静音档案保守比对。
     */
    private suspend fun finishSession(session: MuteSession, reason: String) {
        val currentState = readDeviceState() ?: return
        val applied = session.appliedState ?: device.mutedState()
        if (currentState != applied || currentState == session.state) {
            sessions.write(null)
            settings.recordError(null)
            logs.info(
                LOG_TAG,
                "$reason：设备已恢复或状态已被修改（当前 ringer=${currentState.ringerMode}，" +
                    "filter=${currentState.interruptionFilter}），保留用户设置，退出接管"
            )
            return
        }

        if (!device.hasDoNotDisturbAccess()) {
            val failReason = "缺少「勿扰访问」权限，无法还原"
            sessions.write(session.copy(pendingRestoreReason = failReason))
            settings.recordError("$failReason，将在下次刷新重试")
            logs.warn(LOG_TAG, "$reason：还原失败（$failReason），会话保留待重试")
            return
        }

        when (val result = device.restore(session.state)) {
            is RestoreResult.Restored -> {
                sessions.write(null)
                settings.recordError(null)
                logs.info(
                    LOG_TAG,
                    "$reason：已恢复进入前状态（ringerMode=${session.state.ringerMode}，" +
                        "filter=${session.state.interruptionFilter}）"
                )
            }
            is RestoreResult.Blocked -> {
                // 不能先清快照假成功：保留会话，下次刷新/叫醒重试
                val failReason = result.reason ?: "系统还原调用未成功"
                val observed = readDeviceState()
                sessions.write(session.copy(pendingRestoreReason = failReason, appliedState = observed ?: applied))
                settings.recordError("$failReason，将在下次刷新重试")
                logs.warn(LOG_TAG, "$reason：还原失败（$failReason），会话保留待重试")
            }
        }
    }

    private suspend fun readDeviceState(): DeviceMuteState? = try {
        device.currentState()
    } catch (ex: kotlinx.coroutines.CancellationException) {
        throw ex
    } catch (ex: Exception) {
        settings.recordError("无法读取本机铃声/勿扰状态，已停止后续修改；请检查系统权限后重试")
        logs.warn(LOG_TAG, "无法确认设备状态：${ex::class.java.simpleName}")
        null
    }

    /**
     * 离线对账（不需要网络）：只回收「会话内全部事件的真实 end 已过」的会话。
     * 「全部」是逐事件判断：eventIds 里每一个都必须有 end，缺任何一个都不动——
     * 已知 A 结束、未知 B 还在进行时提前恢复会打断 B。
     * - 有任一事件未到 end：不动（可能还在静音）；
     * - 全部已结束：走与 END 相同的收尾（所有权比对 → 确认成功才清）；
     * - 旧会话缺 end：没有已知事实，宁可不动，也不能猜「大概结束了」。
     *   补 end 只能由刷新拉到真实日程后经 [reconcileWithKnownEnds] 完成。
     */
    suspend fun reconcileExpiredSession() {
        mutex.withLock {
            val session = sessions.read() ?: return
            if (session.pendingRestoreReason != null) {
                // 上次收尾已决定退出接管但还原失败：重试还原，不等 end。
                finishSession(session, reason = "重试未完成的还原（离线对账）")
                return
            }
            if (session.eventEndsUtcMillis.isEmpty()) return
            if (session.eventIds.any { !session.eventEndsUtcMillis.containsKey(it) }) return
            val now = nowUtcMillis()
            if (session.eventIds.any { session.eventEndsUtcMillis.getValue(it) > now }) return
            finishSession(session, reason = "日程已全部结束（END 闹钟丢失后的离线对账）")
        }
    }

    /**
     * 刷新成功后的对账：用刚拉到的真实日程数据补旧会话缺失的 end，再离线收尾。
     * 只补会话内事件、只信取得的真实数据；分页没覆盖到的事件不能被当成已结束。
     */
    suspend fun reconcileWithKnownEnds(knownEnds: Map<String, Long>) {
        mutex.withLock {
            val session = sessions.read() ?: return
            val patched = session.eventIds.associateWith { id -> knownEnds[id] }
                .filterValues { it != null }
                .mapValues { it.value!! }
            val merged = session.copy(eventEndsUtcMillis = session.eventEndsUtcMillis + patched)
            if (session.pendingRestoreReason != null) {
                // 上次收尾已决定退出接管但还原失败：刷新后立即重试，不等事件 end。
                finishSession(merged, reason = "重试未完成的还原（刷新对账）")
                return
            }
            val now = nowUtcMillis()
            // 逐事件判断：仍有会话事件没有核实到 end → 只保留已核实的事实，不收尾。
            if (merged.eventIds.any { !merged.eventEndsUtcMillis.containsKey(it) }) {
                sessions.write(merged)
                settings.recordError("静音会话涉及 %d 个日程，但本次未能取得其结束时间，暂无法自动收尾".format(session.eventIds.size))
                return
            }
            if (merged.eventIds.any { merged.eventEndsUtcMillis.getValue(it) > now }) {
                sessions.write(merged)
                return
            }
            finishSession(merged, reason = "日程已全部结束（真实结束时刻经刷新核实）")
        }
    }

    /**
     * 立即收尾（关闭开关时）：不等真实 end——开关关闭即退出接管。
     * 恢复失败时保留会话与可见提示，后续 refresh（对账路径）可重试。
     */
    suspend fun finishNow() {
        mutex.withLock {
            val session = sessions.read() ?: return
            finishSession(session, reason = "已关闭「遵循日程静音规则」")
        }
    }

    /**
     * 是否存在「已决定退出接管但还原尚未成功」的会话。编排层刷新成功后用它决定
     * 是否保留可见的重试提示；成功收尾时 executor 自己已把提示清掉。
     */
    suspend fun hasPendingRestore(): Boolean = mutex.withLock {
        sessions.read()?.pendingRestoreReason != null
    }

    companion object {
        const val LOG_TAG = "eventmute"
    }
}
