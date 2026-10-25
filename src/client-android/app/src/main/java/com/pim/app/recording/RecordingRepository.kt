package com.pim.app.recording

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class RecordingRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = RecordingFileStore(File(context.noBackupFilesDir, "recordings"))
    private val mutableState = MutableStateFlow(RecordingState())
    val state = mutableState.asStateFlow()
    private var initialized = false
    private var engine: RecordingEngine? = null

    @Synchronized
    fun initialize() {
        if (initialized) return
        val archives = store.archives(recover = true)
        mutableState.value = RecordingState(
            session = archives.firstOrNull()?.session,
            problem = archives.firstOrNull()?.let { if (it.damaged) RecordingProblem.Damaged else it.session?.problem },
            archives = archives
        )
        initialized = true
    }

    @Synchronized
    internal fun start(noteId: String? = null) {
        initialize()
        check(engine?.session?.active != true)
        mutableState.value = mutableState.value.copy(preparing = true, problem = null, peaks = emptyList())
        engine = RecordingEngine(store, noteId = noteId)
        publish()
    }

    @Synchronized
    internal fun append(samples: ShortArray, count: Int) {
        val peak = checkNotNull(engine).append(samples, count)
        publish((state.value.peaks + peak).takeLast(32))
    }

    @Synchronized
    internal fun pause() { checkNotNull(engine).pause(); publish(emptyList()) }

    @Synchronized
    internal fun resume() { checkNotNull(engine).resume(); publish(emptyList()) }

    @Synchronized
    internal fun finish(problem: RecordingProblem? = null) {
        if (engine == null) {
            report(problem ?: RecordingProblem.Input)
            return
        }
        try {
            engine?.finish(problem)
            publish(emptyList())
            mutableState.value = state.value.copy(archives = store.archives(recover = false))
        } catch (_: Exception) {
            mutableState.value = state.value.copy(
                session = engine?.session?.copy(phase = RecordingPhase.Interrupted, problem = RecordingProblem.Storage),
                preparing = false, peaks = emptyList(), problem = RecordingProblem.Storage
            )
        } finally { engine = null }
    }

    @Synchronized
    internal fun report(problem: RecordingProblem) {
        mutableState.value = state.value.copy(preparing = false, problem = problem, peaks = emptyList())
    }

    fun audioFiles(session: RecordingSession): List<File> = session.chunks
        .filter { it.samples > 0 }.map { store.audioFile(session.id, it) }

    private fun publish(peaks: List<Float> = emptyList()) {
        val current = engine?.session ?: state.value.session
        mutableState.value = state.value.copy(
            session = current, preparing = false, peaks = peaks, problem = current?.problem
        )
    }
}
