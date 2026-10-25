package com.pim.app.recording

import kotlin.math.abs
import kotlin.math.min

internal class RecordingEngine(
    private val store: RecordingFileStore,
    private val policy: RecordingPolicy = RecordingPolicy(),
    nowMillis: Long = System.currentTimeMillis()
) {
    var session: RecordingSession = store.create(nowMillis)
        private set
    private var output: PcmWavFile? = null
    private var sinceCheckpoint = 0L

    fun append(input: ShortArray, count: Int): Float {
        check(session.phase == RecordingPhase.Recording)
        require(count in 1..input.size)
        var offset = 0
        var peak = 0
        while (offset < count) {
            if (session.currentSegmentSamples == policy.segmentSamples) {
                closeChunk()
                session = session.copy(currentSegment = session.currentSegment + 1)
            }
            if (output == null) openChunk()
            val writer = checkNotNull(output)
            val capacity = min(policy.chunkSamples - writer.samples, policy.segmentSamples - session.currentSegmentSamples)
            val take = min((count - offset).toLong(), capacity).toInt()
            writer.append(input, offset, take)
            for (i in offset until offset + take) peak = maxOf(peak, abs(input[i].toInt()))
            val last = session.chunks.last().copy(samples = writer.samples)
            session = session.copy(chunks = session.chunks.dropLast(1) + last)
            offset += take
            sinceCheckpoint += take
            if (writer.samples == policy.chunkSamples || session.currentSegmentSamples == policy.segmentSamples) {
                closeChunk()
            } else if (sinceCheckpoint >= policy.checkpointSamples) checkpoint()
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    fun pause() {
        check(session.phase == RecordingPhase.Recording)
        closeChunk()
        session = session.copy(phase = RecordingPhase.Paused)
        store.save(session)
    }

    fun resume() {
        check(session.phase == RecordingPhase.Paused)
        session = session.copy(phase = RecordingPhase.Recording)
        store.save(session)
    }

    fun finish(problem: RecordingProblem? = null) {
        if (!session.active) return
        var failure = problem
        try { closeChunk() } catch (_: Exception) { failure = RecordingProblem.Storage }
        if (session.samples == 0L && failure == null) failure = RecordingProblem.Empty
        session = session.copy(
            phase = if (failure == null) RecordingPhase.Completed else RecordingPhase.Interrupted,
            problem = failure
        )
        store.save(session)
    }

    private fun openChunk() {
        val index = session.chunks.count { it.segment == session.currentSegment } + 1
        val chunk = RecordingChunk(session.currentSegment, index)
        output = PcmWavFile(store.audioFile(session.id, chunk))
        session = session.copy(chunks = session.chunks + chunk)
        store.save(session)
    }

    private fun checkpoint() {
        output?.checkpoint()
        store.save(session)
        sinceCheckpoint = 0
    }

    private fun closeChunk() {
        val previous = output
        output = null
        previous?.close()
        store.save(session)
        sinceCheckpoint = 0
    }
}
