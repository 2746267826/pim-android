package com.pim.app.mute

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pim.app.mobile.logs.StructuredLogRepository
import com.pim.app.settings.TrackingSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 在隔离测试设备上检查 AudioService 的内部模式及真实勿扰联动。 */
@RunWith(AndroidJUnit4::class)
class EventMutePlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val device = AndroidEventMuteDevice(context)
    private var originalRinger = AudioManager.RINGER_MODE_NORMAL
    private var originalFilter = NotificationManager.INTERRUPTION_FILTER_ALL
    private var originallyGranted = false

    @Before
    fun prepareDevice() {
        originalRinger = audio.ringerMode
        originalFilter = notifications.currentInterruptionFilter
        originallyGranted = notifications.isNotificationPolicyAccessGranted
        shell("cmd notification allow_dnd ${context.packageName}")
        if (Build.VERSION.SDK_INT >= 33) {
            shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        assertTrue(notifications.isNotificationPolicyAccessGranted)
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @After
    fun restoreDevice() {
        audio.ringerMode = originalRinger
        notifications.setInterruptionFilter(originalFilter)
        if (!originallyGranted) shell("cmd notification disallow_dnd ${context.packageName}")
    }

    @Test
    fun vibrateToSilentAlsoSetsInternalRingerSilent() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertTrue(device.applyMuted())
        SystemClock.sleep(300)

        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
        val dump = shell("dumpsys audio")
        assertTrue(
            "AudioService must report internal SILENT after entering from VIBRATE:\n$dump",
            Regex("(?im)^\\s*- mode \\(internal\\) = SILENT\\s*$").containsMatchIn(dump)
        )
    }

    @Test
    fun bypassNotificationStopsVibratingAfterEnteringFromVibrate() {
        val channelId = "mute-platform-probe-${System.nanoTime()}"
        val channel = NotificationChannel(channelId, "静音回归验证", NotificationManager.IMPORTANCE_HIGH).apply {
            setBypassDnd(true)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 5_000)
            setSound(null, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        }
        notifications.createNotificationChannel(channel)
        assertTrue(notifications.getNotificationChannel(channelId).canBypassDnd())
        fun post(id: Int) {
            notifications.notify(id, Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("静音回归验证")
                .build())
        }
        val noCurrentVibration = Regex("(?:mCurrentVibration|CurrentVibration):\\s*null")
        try {
            audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            post(72_001)
            SystemClock.sleep(500)
            val controlDump = shell("dumpsys vibrator_manager")
            assertTrue("The notification must vibrate before muting:\n$controlDump",
                !noCurrentVibration.containsMatchIn(controlDump))
            notifications.cancel(72_001)
            SystemClock.sleep(1_000)

            assertTrue(device.applyMuted())
            SystemClock.sleep(300)
            post(72_002)
            SystemClock.sleep(500)

            assertTrue(notifications.activeNotifications.any { it.id == 72_002 })
            val mutedDump = shell("dumpsys vibrator_manager")
            assertTrue("The bypass notification must stay quiet after muting:\n$mutedDump",
                noCurrentVibration.containsMatchIn(mutedDump))
        } finally {
            notifications.cancel(72_001)
            notifications.cancel(72_002)
            notifications.deleteNotificationChannel(channelId)
        }
    }

    @Test
    fun userDndChangesSurviveEventEnd() = runBlocking {
        for (initialRinger in listOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE)) {
            for (filter in listOf(
                NotificationManager.INTERRUPTION_FILTER_ALARMS,
                NotificationManager.INTERRUPTION_FILTER_NONE
            )) {
                notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                audio.ringerMode = initialRinger
                val settings = MemorySettings()
                val executor = executor(settings)
                executor.onAlarm("platform-probe", MuteAction.START, System.currentTimeMillis() + 60_000)
                assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
                assertTrue(settings.session?.appliedState?.interruptionFilter != null)
                notifications.setInterruptionFilter(filter)
                SystemClock.sleep(300)

                executor.onAlarm("platform-probe", MuteAction.END)
                SystemClock.sleep(300)

                assertEquals("Preserve the user's DND filter", filter, notifications.currentInterruptionFilter)
                assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
                assertNull(settings.session)
            }
        }
    }

    @Test
    fun unchangedSessionRestoresOriginalRinger() = runBlocking {
        for (initialRinger in listOf(
            AudioManager.RINGER_MODE_NORMAL,
            AudioManager.RINGER_MODE_VIBRATE,
            AudioManager.RINGER_MODE_SILENT
        )) {
            audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
            notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            audio.ringerMode = initialRinger
            val settings = MemorySettings()
            val executor = executor(settings)

            executor.onAlarm("platform-probe", MuteAction.START, System.currentTimeMillis() + 60_000)
            assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
            executor.onAlarm("platform-probe", MuteAction.END)

            assertEquals(initialRinger, audio.ringerMode)
            assertNull(settings.session)
        }
    }

    private fun executor(settings: MemorySettings): EventMuteExecutor {
        val logs = StructuredLogRepository(
            context,
            TrackingSettingsStore(context.getSharedPreferences("mute-platform-test", Context.MODE_PRIVATE))
        )
        return EventMuteExecutor(settings, settings, device, logs)
    }

    private class MemorySettings : EventMuteSettingsAccessor, MuteSessionStore {
        var session: MuteSession? = null
        private var enabled = true
        private var error: String? = null
        override fun read() = session
        override fun write(session: MuteSession?) { this.session = session }
        override fun isFollowRuleEnabled() = enabled
        override fun setFollowRuleEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun recordError(message: String?) { error = message }
        override fun readPersistedStatus() = PersistedEventMuteStatus(
            0, null, session?.eventIds.orEmpty(), null, error
        )
        override fun recordRefresh(planned: Int, nextAlarmAtUtcMillis: Long?, atUtcMillis: Long, error: String?) {
            this.error = error
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
