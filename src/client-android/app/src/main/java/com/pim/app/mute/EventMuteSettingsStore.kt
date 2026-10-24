package com.pim.app.mute

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/**
 * 工单 04（E3.4 / E3.5）的持久化状态。
 *
 * 与 [com.pim.app.keepalive.KeepAliveSettingsStore] 的关系（E3.4 要求说明）：
 * - **互相独立、互不覆盖**：keepalive store 管的是「叫醒节奏/红点」，本 store 管的是
 *   「本设备是否遵循静音规则 + 静音会话 + 最近一次计划」；两个开关没有联动，
 *   关掉静音规则不会影响保活，反之亦然（AC-7 的「关闭后设备完全不动」因此可自证）。
 * - **依赖方向是单向的**：静音功能挂在既有 keepalive 精确闹钟链上（每次叫醒顺带刷新计划），
 *   但 keepalive 不知道静音功能存在，所以它的既有行为与测试完全不受影响。
 * - 落盘同样用 `commit()`：静音会话就是为「进程被杀之后还要能还原」而存在的，
 *   异步 `apply()` 在写到一半被杀时会丢状态，设备就永远停在静音。
 */
@Singleton
class EventMuteSettingsStore @Inject constructor(
    @ApplicationContext context: Context
) : EventMuteSettingsAccessor, MuteSessionStore {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isFollowRuleEnabled(): Boolean =
        preferences.getBoolean(KEY_FOLLOW_RULE_ENABLED, DEFAULT_FOLLOW_RULE_ENABLED)

    override fun setFollowRuleEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FOLLOW_RULE_ENABLED, enabled).commit()
    }

    override fun readPersistedStatus(): PersistedEventMuteStatus = PersistedEventMuteStatus(
        plannedAlarmCount = preferences.getInt(KEY_PLANNED_COUNT, 0),
        nextAlarmAtUtcMillis = preferences.getLong(KEY_NEXT_ALARM_AT, 0L).takeIf { it > 0L },
        activeMuteEventIds = read()?.eventIds ?: emptySet(),
        lastRefreshAtUtcMillis = preferences.getLong(KEY_LAST_REFRESH_AT, 0L).takeIf { it > 0L },
        lastError = preferences.getString(KEY_LAST_ERROR, null)
    )

    override fun recordRefresh(
        planned: Int,
        nextAlarmAtUtcMillis: Long?,
        atUtcMillis: Long,
        error: String?
    ) {
        val editor = preferences.edit()
            .putInt(KEY_PLANNED_COUNT, planned)
            .putLong(KEY_LAST_REFRESH_AT, atUtcMillis)
        if (nextAlarmAtUtcMillis != null) {
            editor.putLong(KEY_NEXT_ALARM_AT, nextAlarmAtUtcMillis)
        } else {
            editor.remove(KEY_NEXT_ALARM_AT)
        }
        if (error == null) editor.remove(KEY_LAST_ERROR) else editor.putString(KEY_LAST_ERROR, error)
        editor.commit()
    }

    override fun recordError(message: String?) {
        val editor = preferences.edit()
        if (message == null) editor.remove(KEY_LAST_ERROR) else editor.putString(KEY_LAST_ERROR, message)
        editor.commit()
    }

    // ---- 静音会话 ----

    override fun read(): MuteSession? {
        val raw = preferences.getString(KEY_SESSION, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val ids = json.getJSONArray(KEY_SESSION_EVENT_IDS)
            val eventIds = (0 until ids.length()).map { ids.getString(it) }.toSet()
            if (eventIds.isEmpty()) return null
            val eventEnds = mutableMapOf<String, Long>()
            if (json.has(KEY_SESSION_EVENT_ENDS)) {
                val ends = json.getJSONObject(KEY_SESSION_EVENT_ENDS)
                for (id in eventIds) {
                    if (ends.has(id)) eventEnds[id] = ends.getLong(id)
                }
            }
            val appliedState = if (json.has(KEY_SESSION_APPLIED_RINGER)) {
                DeviceMuteState(
                    ringerMode = json.getInt(KEY_SESSION_APPLIED_RINGER),
                    interruptionFilter = json.getInt(KEY_SESSION_APPLIED_FILTER)
                )
            } else {
                null
            }
            MuteSession(
                eventIds = eventIds,
                state = DeviceMuteState(
                    ringerMode = json.getInt(KEY_SESSION_RINGER),
                    interruptionFilter = json.getInt(KEY_SESSION_FILTER)
                ),
                enteredAtUtcMillis = json.getLong(KEY_SESSION_ENTERED_AT),
                pendingRestoreReason = json.optString(KEY_SESSION_PENDING_RESTORE).takeIf { it.isNotEmpty() },
                eventEndsUtcMillis = eventEnds,
                appliedState = appliedState
            )
        } catch (_: Exception) {
            // 解析失败按「没有会话」处理：宁可漏恢复，也不能把设备改到一个瞎猜的状态
            null
        }
    }

    override fun write(session: MuteSession?) {
        val editor = preferences.edit()
        if (session == null) {
            editor.remove(KEY_SESSION)
        } else {
            val json = JSONObject()
            json.put(KEY_SESSION_EVENT_IDS, JSONArray(session.eventIds.toList()))
            json.put(KEY_SESSION_RINGER, session.state.ringerMode)
            json.put(KEY_SESSION_FILTER, session.state.interruptionFilter)
            json.put(KEY_SESSION_ENTERED_AT, session.enteredAtUtcMillis)
            val ends = JSONObject()
            for ((id, end) in session.eventEndsUtcMillis) {
                if (id in session.eventIds) ends.put(id, end)
            }
            if (ends.length() > 0) json.put(KEY_SESSION_EVENT_ENDS, ends)
            session.appliedState?.let {
                json.put(KEY_SESSION_APPLIED_RINGER, it.ringerMode)
                json.put(KEY_SESSION_APPLIED_FILTER, it.interruptionFilter)
            }
            if (session.pendingRestoreReason != null) {
                json.put(KEY_SESSION_PENDING_RESTORE, session.pendingRestoreReason)
            }
            editor.putString(KEY_SESSION, json.toString())
        }
        check(editor.commit()) { "静音会话未能可靠保存，停止更改设备状态" }
    }

    companion object {
        const val PREFS_NAME = "pim_event_mute"

        /**
         * 默认**关闭**：在用户明确打开「本设备是否遵循该规则」之前，本机铃声/免打扰一律不动。
         * 装上新版本就自动改用户手机行为是不能接受的（也会让 AC-7 的语义含糊）。
         */
        const val DEFAULT_FOLLOW_RULE_ENABLED = false

        private const val KEY_FOLLOW_RULE_ENABLED = "follow_rule_enabled"
        private const val KEY_PLANNED_COUNT = "planned_count"
        private const val KEY_NEXT_ALARM_AT = "next_alarm_at"
        private const val KEY_LAST_REFRESH_AT = "last_refresh_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_SESSION = "mute_session"
        private const val KEY_SESSION_EVENT_IDS = "event_ids"
        private const val KEY_SESSION_RINGER = "ringer_mode"
        private const val KEY_SESSION_FILTER = "interruption_filter"
        private const val KEY_SESSION_ENTERED_AT = "entered_at"
        private const val KEY_SESSION_PENDING_RESTORE = "pending_restore_reason"
        private const val KEY_SESSION_EVENT_ENDS = "event_ends"
        private const val KEY_SESSION_APPLIED_RINGER = "applied_ringer_mode"
        private const val KEY_SESSION_APPLIED_FILTER = "applied_interruption_filter"
    }
}
