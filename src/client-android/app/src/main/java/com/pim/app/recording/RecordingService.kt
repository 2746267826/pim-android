package com.pim.app.recording

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class RecordingService : Service() {
    @Inject lateinit var repository: RecordingRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Pair<String, String?>>(Channel.UNLIMITED)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (!started) {
            if (action != RecordingController.START) { stopSelf(); return START_NOT_STICKY }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                repository.report(RecordingProblem.Permission); stopSelf(); return START_NOT_STICKY
            }
            RecordingNotifications.createChannel(this)
            try {
                val notification = RecordingNotifications.build(this, RecordingState(preparing = true))
                if (Build.VERSION.SDK_INT >= 30) startForeground(RecordingNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else startForeground(RecordingNotifications.ID, notification)
            } catch (_: Exception) {
                repository.report(RecordingProblem.Foreground); stopSelf(); return START_NOT_STICKY
            }
            started = true
            scope.launch { capture(intent?.getStringExtra(RecordingController.NOTE)) }
        } else if (action != null && action != RecordingController.START) {
            commands.trySend(action to intent.getStringExtra(RecordingController.SESSION))
        }
        return START_NOT_STICKY
    }

    private suspend fun capture(noteId: String?) {
        var input: AndroidPcmInput? = null
        var failure: RecordingProblem? = null
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PIM:recording").apply {
            setReferenceCounted(false)
        }
        try {
            try { repository.start(noteId) } catch (_: Exception) { failure = RecordingProblem.Storage; return }
            input = AndroidPcmInput { commands.trySend(SILENCED to null) }
            input.start()
            wake.acquire()
            val buffer = ShortArray(RecordingPolicy.SAMPLE_RATE / 10)
            var lastNotification = 0L
            var lastInput = SystemClock.elapsedRealtime()
            while (true) {
                currentCoroutineContext().ensureActive()
                val command = if (repository.state.value.session?.phase == RecordingPhase.Paused) commands.receive()
                    else commands.tryReceive().getOrNull()
                if (command != null) {
                    val session = checkNotNull(repository.state.value.session)
                    if (command.first == SILENCED) { failure = RecordingProblem.Silenced; break }
                    // Old notification actions must never control a later recording.
                    if (command.second != session.id) continue
                    when (command.first) {
                        RecordingController.FINISH -> break
                        RecordingController.PAUSE -> if (session.phase == RecordingPhase.Recording) {
                            input.pause()
                            try { repository.pause() } catch (_: Exception) { failure = RecordingProblem.Storage; break }
                            if (wake.isHeld) wake.release()
                        }
                        RecordingController.RESUME -> if (session.phase == RecordingPhase.Paused) {
                            try { repository.resume() } catch (_: Exception) { failure = RecordingProblem.Storage; break }
                            input.start(); wake.acquire(); lastInput = SystemClock.elapsedRealtime()
                        }
                    }
                    lastNotification = 0
                }
                if (repository.state.value.session?.phase == RecordingPhase.Recording) {
                    val count = input.read(buffer)
                    if (count < 0) { failure = RecordingProblem.Input; break }
                    if (count > 0) {
                        try { repository.append(buffer, count) }
                        catch (_: Exception) { failure = RecordingProblem.Storage; break }
                        lastInput = SystemClock.elapsedRealtime()
                    } else if (SystemClock.elapsedRealtime() - lastInput > 5000) {
                        failure = RecordingProblem.Input; break
                    } else delay(10)
                }
                if (SystemClock.elapsedRealtime() - lastNotification >= 1000) {
                    notifyState()
                    lastNotification = SystemClock.elapsedRealtime()
                }
            }
        } catch (_: CancellationException) { failure = RecordingProblem.ProcessEnded }
        catch (_: Exception) { failure = RecordingProblem.Input }
        finally {
            try { input?.close() } catch (_: Exception) { if (failure == null) failure = RecordingProblem.Input }
            if (wake.isHeld) wake.release()
            repository.finish(failure)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (repository.state.value.problem != null) notifyState(interrupted = true)
            stopSelf()
        }
    }

    private fun notifyState(interrupted: Boolean = false) {
        try {
            getSystemService(NotificationManager::class.java).notify(RecordingNotifications.ID,
                RecordingNotifications.build(this, repository.state.value, interrupted))
        } catch (_: SecurityException) {
            // Notification permission can be revoked during capture; the probe shows its actual visibility.
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object { private const val SILENCED = "input-silenced" }
}
