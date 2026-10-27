package com.pim.app.recording

import android.Manifest
import android.app.NotificationManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class RecordingCaptureTest {
    private val compose = createAndroidComposeRule<RecordingProbeActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(*(
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    ))).around(compose)
    private val repository get() = compose.activity.repository

    @After fun stopOnlyThisTestRecording() {
        repository.state.value.session?.takeIf { it.active }?.let {
            RecordingController.finish(compose.activity, it.id)
            compose.waitUntil(10_000) { !repository.state.value.active }
        }
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @Test fun actualCaptureSurvivesBackgroundAndScreenOffThenFinalizesPlayableWav() {
        start()
        val before = repository.state.value.session!!.samples
        val notifications = compose.activity.getSystemService(NotificationManager::class.java)
        compose.waitUntil(5000) { notifications.activeNotifications.any { it.id == RecordingNotifications.ID } }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        shell("input keyevent KEYCODE_SLEEP")
        SystemClock.sleep(1200)
        assertTrue(repository.state.value.session!!.samples > before)
        assertTrue(repository.state.value.active)
        val notification = notifications.activeNotifications.single { it.id == RecordingNotifications.ID }.notification
        assertEquals("正在录音", notification.extras.getString("android.title"))
        notification.actions.last().actionIntent.send()
        compose.waitUntil(10_000) { !repository.state.value.active }
        val saved = repository.state.value.session!!
        assertEquals(RecordingPhase.Completed, saved.phase)
        assertTrue(saved.durationMillis > 1000)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(repository.audioFiles(saved).first().absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(16_000, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) > 0)
        } finally { extractor.release() }
        val player = MediaPlayer()
        try {
            player.setDataSource(repository.audioFiles(saved).first().absolutePath)
            player.prepare()
            player.start()
            compose.waitUntil(5000) { player.currentPosition > 0 }
        } finally { player.release() }
    }

    @Test fun pauseStopsSampleClockAndNotificationResumeRestartsActualInput() {
        start()
        val id = repository.state.value.session!!.id
        compose.onNodeWithTag("recording-pause-resume").performClick()
        compose.waitUntil(10_000) { repository.state.value.session?.phase == RecordingPhase.Paused }
        val samples = repository.state.value.session!!.samples
        SystemClock.sleep(500)
        assertEquals(samples, repository.state.value.session!!.samples)
        assertTrue(repository.state.value.peaks.isEmpty())
        val manager = compose.activity.getSystemService(NotificationManager::class.java)
        compose.waitUntil(5000) {
            manager.activeNotifications.any { it.id == RecordingNotifications.ID && it.notification.extras.getString("android.title") == "录音已暂停" }
        }
        manager.activeNotifications.single { it.id == RecordingNotifications.ID }.notification.actions.first().actionIntent.send()
        compose.waitUntil(10_000) { repository.state.value.session?.samples?.let { it > samples } == true }
        assertEquals(id, repository.state.value.session!!.id)
        assertEquals(1, repository.state.value.session!!.currentSegment)
        compose.onNodeWithTag("recording-finish").performClick()
        compose.waitUntil(10_000) { repository.state.value.session?.phase == RecordingPhase.Completed }
    }

    @Test fun staleNotificationCommandCannotStopCurrentRecording() {
        start()
        val current = repository.state.value.session!!.id
        RecordingController.finish(compose.activity, UUID.randomUUID().toString())
        SystemClock.sleep(400)
        assertEquals(current, repository.state.value.session!!.id)
        assertTrue(repository.state.value.active)
        RecordingController.finish(compose.activity, current)
        compose.waitUntil(10_000) { repository.state.value.session?.phase == RecordingPhase.Completed }
    }

    @Test fun inactiveResumeCannotStartCaptureAndActivityRecreationKeepsTheSameActiveSession() {
        compose.waitForIdle()
        val directory = File(compose.activity.noBackupFilesDir, "recordings")
        val previous = directory.listFiles().orEmpty().map { it.name }.toSet()
        RecordingController.resume(compose.activity, UUID.randomUUID().toString())
        SystemClock.sleep(400)
        assertFalse(repository.state.value.active)
        assertEquals(previous, directory.listFiles().orEmpty().map { it.name }.toSet())
        start()
        val current = repository.state.value.session!!
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { repository.state.value.session?.samples?.let { it > current.samples } == true }
        assertEquals(current.id, repository.state.value.session!!.id)
        assertEquals(RecordingPhase.Recording, repository.state.value.session!!.phase)
        RecordingController.finish(compose.activity, current.id)
        compose.waitUntil(10_000) { repository.state.value.session?.phase == RecordingPhase.Completed }
    }

    private fun start() {
        compose.onNodeWithTag("recording-start").performClick()
        compose.waitUntil(15_000) { repository.state.value.session?.let { it.active && it.samples > 0 } == true }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
    }
}
