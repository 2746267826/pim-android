package com.pim.app.keepalive

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.app.data.AppDatabase
import com.pim.app.keepalive.ui.KeepAliveSectionReadState
import com.pim.app.keepalive.ui.KeepAliveViewModel
import com.pim.app.keepalive.ui.keepAliveReadFailureText
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class KeepAliveViewModelRefreshTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Application
    private lateinit var database: AppDatabase
    private lateinit var logs: StructuredLogRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        logs = StructuredLogRepository(
            context,
            TrackingSettingsStore(
                context.getSharedPreferences("keepalive-viewmodel-tracking", Context.MODE_PRIVATE)
            ),
        ) { 1_000_000L }
        File(context.filesDir, "logs").deleteRecursively()
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, "logs").deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun refreshFailureReplacesOldStateWithSafeFixedText() {
        val realPreferences = context.getSharedPreferences("pim_keepalive", Context.MODE_PRIVATE).apply {
            edit()
                .putBoolean("keepalive.enabled", true)
                .putInt("keepalive.configured_interval_minutes", 60)
                .putInt("keepalive.effective_interval_minutes", 60)
                .commit()
        }
        val scriptedPreferences = FailingReadPreferences(realPreferences)
        val storeContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                if (name == "pim_keepalive") scriptedPreferences else super.getSharedPreferences(name, mode)
        }
        val viewModel = createViewModel(KeepAliveSettingsStore(storeContext))

        await { viewModel.sectionState.value.readState == KeepAliveSectionReadState.Loaded }
        assertTrue(viewModel.sectionState.value.enabled)

        scriptedPreferences.failReads = true
        viewModel.refresh()
        await { viewModel.sectionState.value.readState == KeepAliveSectionReadState.Failed }

        val state = viewModel.sectionState.value
        val expectedText = keepAliveReadFailureText()
        assertEquals(KeepAliveSectionReadState.Failed, state.readState)
        assertFalse("failure state must not retain the old enabled state", state.enabled)
        assertEquals("无法读取", state.scheduleStatusText)
        assertEquals(expectedText, state.healthAlert)
        assertEquals(expectedText, viewModel.healthAlert.value)
        assertFalse(state.healthAlert.orEmpty().contains("sensitive"))
        assertFalse(state.healthAlert.orEmpty().contains("token", ignoreCase = true))
    }

    private fun createViewModel(settingsStore: KeepAliveSettingsStore): KeepAliveViewModel {
        val settingsAccessor = FakeSettingsAccessor(KeepAliveSettings.defaults())
        val notifications = FakeNotifications()
        val scheduler = FakeScheduler()
        val ledger = KeepAliveLedger(database.forensicEventDao(), logs)
        val health = KeepAliveHealthMonitor(
            ledger = ledger,
            notifications = notifications,
            settings = settingsAccessor,
            logs = logs,
            nowUtcMillis = { 1_000_000L },
        )
        val chain = WakeExecutionChain(
            ledger = FakeFulfillmentRecorder(),
            settingsStore = settingsAccessor,
            environment = FakeWakeEnvironment(),
            logs = logs,
            nowUtcMillis = { 1_000_000L },
        )
        val coordinator = KeepAliveCoordinator(
            settingsStore = settingsAccessor,
            scheduler = scheduler,
            executionChain = chain,
            ledger = ledger,
            health = health,
            notifications = notifications,
            logs = logs,
            nowUtcMillis = { 1_000_000L },
        )
        val permissionChecker = ExactAlarmPermissionChecker(context, scheduler, logs)
        val guidanceDetector = GuidanceDetector(context, permissionChecker, logs)
        return KeepAliveViewModel(
            settingsStore = settingsStore,
            coordinator = coordinator,
            ledger = ledger,
            guidanceDetector = guidanceDetector,
            permissionChecker = permissionChecker,
            health = health,
            logs = logs,
        )
    }

    private fun await(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            shadowOf(Looper.getMainLooper()).idle()
            mainDispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(5)
        }
    }

    private class FailingReadPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        @Volatile
        var failReads: Boolean = false

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            if (failReads) throw IllegalStateException("sensitive token path")
            return delegate.getBoolean(key, defValue)
        }
    }

    private class FakeSettingsAccessor(
        private var current: KeepAliveSettings,
    ) : KeepAliveSettingsAccessor {
        override fun read(): KeepAliveSettings = current
        override fun write(settings: KeepAliveSettings): KeepAliveSettings {
            current = settings
            return current
        }
    }

    private class FakeScheduler : KeepAliveSchedulePort {
        override suspend fun scheduleNext(intervalMinutes: Int, enabled: Boolean): KeepAliveSchedulePort.Result =
            KeepAliveSchedulePort.Result.Scheduled(1_000_000L, intervalMinutes)

        override suspend fun cancel() = Unit
        override fun hasExactAlarmPermission(): Boolean = true
    }

    private class FakeNotifications : KeepAliveNotificationPort {
        override fun isEnabled(): Boolean = true
        override suspend fun ensureResident() = Unit
        override suspend fun updateLastWake(atUtcMillis: Long?) = Unit
        override suspend fun dismissResident() = Unit
    }

    private class FakeWakeEnvironment : WakeEnvironment {
        override suspend fun isPaused(): Boolean = false
        override suspend fun isManualSessionActive(): Boolean = false
        override fun isForegroundServiceRunning(): Boolean = false
        override suspend fun requestSyncNow(): Boolean = true
        override suspend fun startForegroundService(): Boolean = true
        override suspend fun captureSinglePointFallback(): Boolean = true
    }

    private class FakeFulfillmentRecorder : AlarmFulfillmentRecorder {
        override suspend fun recordFulfillment(record: AlarmFulfillmentRecord): Boolean = true
    }
}
