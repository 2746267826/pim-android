package com.pim.app.mute

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import com.pim.core.contract.CalendarResponseListApiResponse
import com.pim.core.contract.EventResponse
import com.pim.core.contract.EventResponsePagedResult
import com.pim.core.contract.EventResponsePagedResultApiResponse
import com.pim.core.network.ApiService
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventMuteCoordinatorTest {
    private val now = 1_700_000_000_000L
    private lateinit var store: EventMuteSettingsStore
    private lateinit var subject: EventMuteCoordinator
    private val device = FakeDevice()
    private var apiCalls = 0
    private var offline = false
    private var events = emptyList<EventResponse>()
    private var scheduleResult: EventMuteAlarmScheduler.Result = EventMuteAlarmScheduler.Result.Scheduled(0, null)
    private var cancelCalls = 0

    private class FakeDevice : EventMuteDevicePort {
        var state = DeviceMuteState(0, 4)
        var access = true
        var restoreCalls = 0
        override fun hasDoNotDisturbAccess() = access
        override fun currentState() = state
        override fun mutedState() = DeviceMuteState(0, 4)
        override fun applyMuted(): Boolean { state = mutedState(); return true }
        override fun restore(state: DeviceMuteState): RestoreResult {
            restoreCalls++
            this.state = state
            return RestoreResult.Restored
        }
    }

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(EventMuteSettingsStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        store = EventMuteSettingsStore(context)
        store.setFollowRuleEnabled(true)
        val logs = StructuredLogRepository(context, TrackingSettingsStore(
            context.getSharedPreferences("mute-coordinator-test", Context.MODE_PRIVATE)
        )) { now }
        val api = Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, _ ->
            apiCalls++
            check(!offline) { "network unavailable" }
            when (method.name) {
                "getCalendars" -> CalendarResponseListApiResponse(0, emptyList(), "ok", "fixture")
                "getEvents" -> EventResponsePagedResultApiResponse(0, EventResponsePagedResult(events, 1, 100, events.size, 1), "ok", "fixture")
                else -> error("Unexpected API call: ${method.name}")
            }
        } as ApiService
        val scheduler = object : EventMuteSchedulePort {
            override suspend fun schedule(plan: List<PlannedMuteAlarm>) = scheduleResult
            override suspend fun cancelAll() { cancelCalls++ }
            override fun hasExactAlarmPermission() = true
        }
        val executor = EventMuteExecutor(store, store, device, logs) { now }
        subject = EventMuteCoordinator(api, store, scheduler, device, executor, logs) { now }
    }

    private fun seed(end: Long? = now - 1, pending: String? = null) {
        store.write(MuteSession(setOf("event"), DeviceMuteState(1, 1), now - 3600000,
            pendingRestoreReason = pending,
            eventEndsUtcMillis = end?.let { mapOf("event" to it) }.orEmpty(),
            appliedState = device.mutedState()))
    }

    @Test
    fun `关闭跟随立即恢复未来结束的会话`() = runTest {
        seed(now + 3600000)
        subject.setFollowRuleEnabled(false)
        assertFalse(store.isFollowRuleEnabled())
        assertNull(store.read())
        assertEquals(DeviceMuteState(1, 1), device.state)
        assertEquals(1, cancelCalls)
        assertEquals(0, apiCalls)
    }

    @Test
    fun `关闭后权限失败的恢复在下次刷新重试且不依赖网络`() = runTest {
        seed(now + 3600000)
        device.access = false
        subject.setFollowRuleEnabled(false)
        assertNotNull(store.read()?.pendingRestoreReason)
        assertTrue(store.readPersistedStatus().lastError?.contains("下次刷新重试") == true)

        offline = true
        device.access = true
        subject.refresh("retry-disabled")
        assertNull(store.read())
        assertEquals(1, device.restoreCalls)
        assertEquals(0, apiCalls)
        assertNull(store.readPersistedStatus().lastError)
    }

    @Test
    fun `网络失败前先恢复本地已结束的会话`() = runTest {
        seed()
        offline = true
        subject.refresh("offline")
        assertNull(store.read())
        assertEquals(DeviceMuteState(1, 1), device.state)
        assertEquals(1, device.restoreCalls)
        assertTrue(store.readPersistedStatus().lastError?.contains("network unavailable") == true)
    }

    @Test
    fun `网络失败不能擦掉尚未恢复的提醒`() = runTest {
        seed()
        device.access = false
        offline = true
        subject.refresh("offline-no-access")
        assertNotNull(store.read()?.pendingRestoreReason)
        assertTrue(store.readPersistedStatus().lastError?.contains("静音还原尚未完成") == true)
    }

    @Test
    fun `刷新成功不能覆盖旧会话结束时间待核实提示`() = runTest {
        seed(end = null)
        subject.refresh("legacy")
        assertNotNull(store.read())
        assertEquals(0, device.restoreCalls)
        assertTrue(store.readPersistedStatus().lastError?.contains("未能取得其结束时间") == true)
    }

    @Test
    fun `核实真实结束后恢复且不遗留旧的失败提示`() = runTest {
        seed(end = null)
        store.recordError("old error")
        events = listOf(EventResponse(calendarId = "calendar", dtStart = Instant.ofEpochMilli(now - 3600000).toString(),
            dtEnd = Instant.ofEpochMilli(now - 1).toString(), id = "event", source = "local", status = "confirmed", title = "fixture", uid = "fixture"))
        subject.refresh("known-end")
        assertNull(store.read())
        assertEquals(1, device.restoreCalls)
        assertNull(store.readPersistedStatus().lastError)
    }

    @Test
    fun `日程恢复成功不掩盖后续计划登记失败`() = runTest {
        seed()
        scheduleResult = EventMuteAlarmScheduler.Result.PermissionMissing
        subject.refresh("alarm-permission")
        assertNull(store.read())
        assertTrue(store.readPersistedStatus().lastError?.contains("闹钟和提醒") == true)
    }
}
