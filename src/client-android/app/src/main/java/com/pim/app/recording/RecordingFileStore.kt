package com.pim.app.recording

import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class RecordingFileStore(private val root: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun create(now: Long, noteId: String? = null): RecordingSession {
        val session = RecordingSession(UUID.randomUUID().toString(), now, noteId = noteId)
        check(directory(session.id).mkdirs()) { "Cannot create recording directory" }
        save(session)
        return session
    }

    fun save(session: RecordingSession) {
        validate(session)
        val atomic = AtomicFile(File(directory(session.id), "manifest.json"))
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(session).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    fun audioFile(id: String, chunk: RecordingChunk): File {
        require(chunk.segment > 0 && chunk.index > 0)
        return File(directory(id), chunk.fileName)
    }

    fun archives(recover: Boolean): List<RecordingArchive> = root.listFiles().orEmpty()
        .filter { it.isDirectory && validId(it.name) }
        .sortedByDescending { it.lastModified() }
        .map { folder ->
            try {
                val session = json.decodeFromString<RecordingSession>(
                    AtomicFile(File(folder, "manifest.json")).readFully().toString(Charsets.UTF_8)
                )
                require(session.id == folder.name)
                validate(session)
                if (!recover) RecordingArchive(session.id, session, damaged = session.problem == RecordingProblem.Damaged)
                else recover(session)
            } catch (_: Exception) {
                RecordingArchive(folder.name, null, damaged = true)
            }
        }

    private fun recover(session: RecordingSession): RecordingArchive {
        var damaged = session.problem == RecordingProblem.Damaged
        val known = session.chunks.associateBy { it.fileName }
        val files = directory(session.id).listFiles().orEmpty().filter { it.extension == "wav" }
        val found = files.mapNotNull { file ->
            val match = CHUNK_NAME.matchEntire(file.name)
            if (match == null) { damaged = true; return@mapNotNull null }
            val chunk = RecordingChunk(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            try { chunk.copy(samples = PcmWavFile.recover(file)) }
            catch (_: Exception) { damaged = true; known[file.name] ?: chunk }
        }.sortedWith(compareBy({ it.segment }, { it.index }))
        val missing = known.filterKeys { name -> found.none { it.fileName == name } }.values
        if (missing.isNotEmpty()) damaged = true
        val problem = when {
            damaged -> RecordingProblem.Damaged
            session.active -> RecordingProblem.ProcessEnded
            else -> session.problem
        }
        val repaired = session.copy(
            chunks = (found + missing).sortedWith(compareBy({ it.segment }, { it.index })),
            currentSegment = maxOf(session.currentSegment, found.maxOfOrNull { it.segment } ?: 1),
            phase = if (session.active || damaged) RecordingPhase.Interrupted else session.phase,
            problem = problem
        )
        if (repaired != session) save(repaired)
        return RecordingArchive(session.id, repaired, damaged)
    }

    private fun directory(id: String): File {
        require(validId(id)) { "Invalid recording identity" }
        return File(root, id)
    }

    private fun validate(session: RecordingSession) {
        require(session.schemaVersion == 1 && session.currentSegment > 0)
        require(session.noteId == null || validId(session.noteId))
        require(session.chunks.all { it.segment in 1..session.currentSegment && it.index > 0 && it.samples >= 0 })
        require(session.chunks.map { it.fileName }.distinct().size == session.chunks.size)
    }

    companion object {
        private val CHUNK_NAME = Regex("s([0-9]{4,})-c([0-9]{6,})\\.wav")
        private fun validId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    }
}
