package com.pim.app.recording

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

object RecordingController {
    internal const val START = "com.pim.app.recording.START"
    internal const val PAUSE = "com.pim.app.recording.PAUSE"
    internal const val RESUME = "com.pim.app.recording.RESUME"
    internal const val FINISH = "com.pim.app.recording.FINISH"
    internal const val SESSION = "recording_session"
    internal const val NOTE = "recording_note"

    fun start(activity: Activity, noteId: String? = null): RecordingProblem? {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return RecordingProblem.Permission
        }
        return try {
            ContextCompat.startForegroundService(activity, intent(activity, START).putExtra(NOTE, noteId))
            null
        } catch (_: Exception) { RecordingProblem.Foreground }
    }

    fun pause(context: Context, id: String) = send(context, PAUSE, id)
    fun resume(context: Context, id: String) = send(context, RESUME, id)
    fun finish(context: Context, id: String) = send(context, FINISH, id)

    internal fun intent(context: Context, action: String, id: String? = null): Intent =
        Intent(context, RecordingService::class.java).setAction(action).putExtra(SESSION, id).apply {
            if (id != null) data = Uri.Builder().scheme("pim-recording").authority("control").appendPath(id).build()
        }

    private fun send(context: Context, action: String, id: String): RecordingProblem? = try {
        context.startService(intent(context, action, id)); null
    } catch (_: Exception) { RecordingProblem.Foreground }
}
