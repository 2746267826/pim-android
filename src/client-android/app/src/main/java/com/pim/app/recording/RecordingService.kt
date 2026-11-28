package com.pim.app.recording

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
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
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (!started) {
            if (action != RecordingController.START) { stopSelf(); return START_NOT_STICKY }
            RecordingPermissionsInspector.inspect(this).startProblem()?.let {
                repository.report(it); stopSelf(); return START_NOT_STICKY
            }
            if (!RecordingStoragePolicy.canStart(noBackupFilesDir.usableSpace)) {
                repository.report(RecordingProblem.Storage); stopSelf(); return START_NOT_STICKY
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
            commands.trySend(Command(action, intent.getStringExtra(RecordingController.SESSION)))
        }
        return START_NOT_STICKY
    }

    private suspend fun capture(noteId: String?) {
        var input: AndroidPcmInput? = null
        var failure: RecordingProblem? = null
        var resumeAttempted = false
        var pendingContinue = false
        var resumed = false
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PIM:recording").apply {
            setReferenceCounted(false)
        }
        try {
            try { repository.start(noteId) } catch (error: Exception) {
                failure = RecordingStoragePolicy.problemFor(error); return
            }
            val buffer = ShortArray(RecordingPolicy.SAMPLE_RATE / 10)
            var lastNotification = 0L
            capture@ while (true) {
                val inputToken = Any()
                val inputSession = checkNotNull(repository.state.value.session)
                val opened = try {
                    AndroidPcmInput {
                        commands.trySend(Command(INPUT_LOST, inputSession.id, inputToken))
                    }
                } catch (error: Exception) {
                    failure = RecordingStoragePolicy.problemFor(error); null
                }
                if (opened != null) {
                    try {
                        opened.start()
                        input = opened
                    } catch (error: Exception) {
                        try { opened.close() } catch (_: Exception) {}
                        failure = RecordingStoragePolicy.problemFor(error)
                        input = null
                    }
                } else {
                    input = null
                }
                if (input == null) {
                    if (resumeAfterInterruption(failure, resumeAttempted)) {
                        resumeAttempted = true
                        pendingContinue = true
                        continue@capture
                    }
                    break@capture
                }
                if (pendingContinue) {
                    try {
                        repository.continueAfterInterruption()
                        pendingContinue = false
                        resumed = true
                        failure = null
                        lastNotification = 0
                    } catch (_: Exception) {
                        failure = RecordingProblem.Storage
                        try { input?.close() } catch (_: Exception) {}
                        input = null
                        break@capture
                    }
                }
                wake.acquire()
                var lastInput = SystemClock.elapsedRealtime()
                var lastHealthCheck = lastInput
                var finished = false
                recording@ while (true) {
                    currentCoroutineContext().ensureActive()
                    val active = input ?: break@recording
                    val command = if (repository.state.value.session?.phase == RecordingPhase.Paused) {
                        commands.receive()
                    } else commands.tryReceive().getOrNull()
                    if (command != null) {
                        val session = checkNotNull(repository.state.value.session)
                        if (command.action == INPUT_LOST && command.inputToken !== inputToken) continue@recording
                        if (command.action == INPUT_LOST && session.phase == RecordingPhase.Paused) continue@recording
                        if (command.action == INPUT_LOST) { failure = RecordingProblem.Silenced; break@recording }
                        // Old notification actions must never control a later recording.
                        if (command.sessionId != session.id) continue@recording
                        when (command.action) {
                            RecordingController.FINISH -> { finished = true; break@recording }
                            RecordingController.PAUSE -> if (session.phase == RecordingPhase.Recording) {
                                active.pause()
                                try { repository.pause() } catch (_: Exception) {
                                    failure = RecordingProblem.Storage; break@recording
                                }
                                if (wake.isHeld) wake.release()
                            }
                            RecordingController.RESUME -> if (session.phase == RecordingPhase.Paused) {
                                try { repository.resume() } catch (_: Exception) {
                                    failure = RecordingProblem.Storage; break@recording
                                }
                                active.start(); wake.acquire(); lastInput = SystemClock.elapsedRealtime()
                            }
                        }
                        lastNotification = 0
                    }
                    if (repository.state.value.session?.phase == RecordingPhase.Recording) {
                        if (SystemClock.elapsedRealtime() - lastHealthCheck >= 1000) {
                            lastHealthCheck = SystemClock.elapsedRealtime()
                            val permissions = RecordingPermissionsInspector.inspect(this)
                            if (permissions.microphoneGranted.not()) {
                                failure = RecordingProblem.Permission; break@recording
                            }
                            val blocked = permissions.startProblem()
                            if (blocked != null) {
                                failure = blocked
                                break@recording
                            }
                            if (!RecordingStoragePolicy.canContinue(noBackupFilesDir.usableSpace)) {
                                failure = RecordingProblem.Storage; break@recording
                            }
                        }
                        val count = try {
                            active.read(buffer)
                        } catch (_: Exception) {
                            failure = RecordingProblem.Input
                            break@recording
                        }
                        if (count < 0) { failure = RecordingProblem.Input; break@recording }
                        if (count > 0) {
                            try { repository.append(buffer, count) }
                            catch (error: Exception) { failure = RecordingStoragePolicy.problemFor(error); break@recording }
                            lastInput = SystemClock.elapsedRealtime()
                        } else if (SystemClock.elapsedRealtime() - lastInput > 5000) {
                            failure = RecordingProblem.Input; break@recording
                        } else delay(10)
                    }
                    if (SystemClock.elapsedRealtime() - lastNotification >= 1000) {
                        notifyState(resumed = resumed)
                        resumed = false
                        lastNotification = SystemClock.elapsedRealtime()
                    }
                }
                try { input?.close() } catch (_: Exception) { if (failure == null && !finished) failure = RecordingProblem.Input }
                input = null
                if (wake.isHeld) wake.release()
                if (finished) { failure = null; break@capture }
                if (resumeAfterInterruption(failure, resumeAttempted)) {
                    resumeAttempted = true
                    pendingContinue = true
                    continue@capture
                }
                break@capture
            }
        } catch (_: CancellationException) { failure = RecordingProblem.ProcessEnded }
        catch (error: Exception) { failure = RecordingStoragePolicy.problemFor(error) }
        finally {
            try { input?.close() } catch (_: Exception) { if (failure == null) failure = RecordingProblem.Input }
            if (wake.isHeld) wake.release()
            repository.finish(interruptionReason(failure))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (repository.state.value.problem != null) notifyState(interrupted = true)
            stopSelf()
        }
    }

    private fun resumeAfterInterruption(problem: RecordingProblem?, attempted: Boolean): Boolean {
        if (problem == null) return false
        return RecordingRecoveryPolicy.shouldRetry(
            problem = problem,
            automaticAttempts = if (attempted) 1 else 0,
            permissions = RecordingPermissionsInspector.inspect(this),
            storageAvailable = RecordingStoragePolicy.canContinue(noBackupFilesDir.usableSpace),
        )
    }

    private fun interruptionReason(problem: RecordingProblem?): RecordingProblem? {
        if (problem == null) return null
        if (problem != RecordingProblem.Input && problem != RecordingProblem.Silenced) return problem
        RecordingPermissionsInspector.inspect(this).startProblem()?.let { return it }
        if (!RecordingStoragePolicy.canContinue(noBackupFilesDir.usableSpace)) return RecordingProblem.Storage
        return problem
    }

    private fun notifyState(interrupted: Boolean = false, resumed: Boolean = false) {
        try {
            getSystemService(NotificationManager::class.java).notify(RecordingNotifications.ID,
                RecordingNotifications.build(this, repository.state.value, interrupted, resumed))
        } catch (_: SecurityException) {
            // Notification permission can be revoked during capture; the probe shows its actual visibility.
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class Command(
        val action: String,
        val sessionId: String?,
        val inputToken: Any? = null,
    )

    companion object { private const val INPUT_LOST = "input-silenced" }
}
