package com.pim.app.notes

import android.graphics.Bitmap
import com.pim.app.recording.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class NoteMaterialStore(
    private val root: File,
    private val notes: NoteFileStore,
    private val recordings: RecordingFileStore
) {
    private val blobs = NoteMaterialBlobs(File(root, "blobs"))

    fun create(note: NoteDocument, sessions: List<RecordingSession>): NoteMaterialInput = synchronized(diskLock) {
        note.validate()
        val candidate = NoteMaterialInput(newNoteId(), System.currentTimeMillis(), note.source,
            sessions.sortedWith(compareBy({ it.createdAtMillis }, { it.id })))
        validate(candidate)
        // Duplicate taps/retries reuse a durable request, including its original recording set.
        val key = identity(candidate)
        for (folder in folders()) {
            val old = runCatching { read(folder.name) }.getOrNull() ?: continue
            if (identity(old) == key) return@synchronized old
        }
        writeMaterialFile(inputFile(candidate.id)) { it.write(NoteJson.encodeToString(candidate).toByteArray(Charsets.UTF_8)) }
        candidate
    }

    fun entries(): List<NoteMaterialEntry> = synchronized(diskLock) {
        folders().map { folder ->
            try {
                val input = read(folder.name)
                val manifest = if (materialFileExists(manifestFile(input.id))) manifest(input) else null
                val statusFile = File(folder, "status.json")
                val status = if (materialFileExists(statusFile)) NoteJson.decodeFromString<NoteMaterialStatus>(readMaterialFile(statusFile)) else null
                val phase = if (status?.phase == NoteMaterialPhase.Failed) NoteMaterialPhase.Failed
                    else if (manifest != null) NoteMaterialPhase.Ready else NoteMaterialPhase.Preparing
                NoteMaterialEntry(input.id, input.document.id, input.document.revision, input.createdAtMillis, phase, status?.problem,
                    manifest?.files?.size ?: 0, manifest?.files?.sumOf { it.sizeBytes } ?: 0,
                    input.recordings.size, input.recordings.sumOf { it.chunks.map { chunk -> chunk.segment }.distinct().size }, input.recordings)
            } catch (_: Exception) {
                NoteMaterialEntry(folder.name, null, null, null, NoteMaterialPhase.Failed, NoteMaterialProblem.Unreadable)
            }
        }.sortedByDescending { it.createdAtMillis ?: Long.MAX_VALUE }
    }

    fun read(id: String): NoteMaterialInput = synchronized(diskLock) {
        NoteJson.decodeFromString<NoteMaterialInput>(readMaterialFile(inputFile(id))).also {
            require(it.id == id); validate(it)
        }
    }

    fun prepare(id: String, checkActive: () -> Unit = {}): NoteMaterialManifest = synchronized(diskLock) {
        val input = read(id)
        if (materialFileExists(manifestFile(id))) {
            val committed = manifest(input)
            committed.files.forEach { blobs.verify(it, checkActive) }
            status(id, NoteMaterialStatus(NoteMaterialPhase.Ready))
            return@synchronized committed
        }
        val journalFile = File(directory(id), "files.json")
        val pinned = if (materialFileExists(journalFile)) NoteJson.decodeFromString<List<NoteMaterialFile>>(readMaterialFile(journalFile)).toMutableList()
            else mutableListOf()
        val sources = sources(input)
        require(pinned.map { it.path }.distinct().size == pinned.size)
        require(pinned.all { file -> sources.any { it.matches(file) } }) { "Unknown staged material" }
        for (source in sources) {
            checkActive()
            val existing = pinned.singleOrNull { it.path == source.path }
            if (existing != null) { blobs.verify(existing, checkActive); continue }
            val content = source.open()
            val size = content.size
            val hash = blobs.pin(size, source.expectedHash, checkActive, content.open)
            pinned += source.descriptor(hash, size)
            writeMaterialFile(journalFile) { it.write(NoteJson.encodeToString(pinned).toByteArray(Charsets.UTF_8)) }
        }
        val result = NoteMaterialManifest(id, input.document.id, input.document.revision,
            materialDigest(NoteJson.encodeToString(input)), sources.map { source -> pinned.single { it.path == source.path } })
        // This is the only completeness marker. Partial journals are never exported as complete.
        writeMaterialFile(manifestFile(id)) { it.write(NoteJson.encodeToString(result).toByteArray(Charsets.UTF_8)) }
        status(id, NoteMaterialStatus(NoteMaterialPhase.Ready))
        result
    }

    fun retry(id: String) = synchronized(diskLock) {
        read(id)
        status(id, NoteMaterialStatus(NoteMaterialPhase.Preparing))
    }

    fun fail(id: String, problem: NoteMaterialProblem) = synchronized(diskLock) {
        status(id, NoteMaterialStatus(NoteMaterialPhase.Failed, problem))
    }

    fun export(id: String, output: OutputStream, checkActive: () -> Unit = {}) = synchronized(diskLock) {
        val input = read(id)
        val committed = manifest(input)
        committed.files.forEach { blobs.verify(it, checkActive) }
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, write: () -> Unit) { zip.putNextEntry(ZipEntry(path)); write(); zip.closeEntry() }
            entry("material-version.json") { zip.write(NoteJson.encodeToString(input).toByteArray(Charsets.UTF_8)) }
            entry("materials.json") { zip.write(NoteJson.encodeToString(committed).toByteArray(Charsets.UTF_8)) }
            committed.files.forEach { file ->
                checkActive()
                entry(file.path) {
                    blobs.file(file.sha256).inputStream().use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { checkActive(); val count = stream.read(buffer); if (count < 0) break; zip.write(buffer, 0, count) }
                    }
                }
            }
        }
    }

    private fun manifest(input: NoteMaterialInput): NoteMaterialManifest {
        val result = NoteJson.decodeFromString<NoteMaterialManifest>(readMaterialFile(manifestFile(input.id)))
        require(result.schemaVersion == 1 && result.versionId == input.id && result.noteId == input.document.id && result.revision == input.document.revision)
        require(result.inputSha256 == materialDigest(NoteJson.encodeToString(input)))
        val sources = sources(input)
        require(result.files.size == sources.size && sources.zip(result.files).all { (source, file) -> source.matches(file) }) { "Incomplete material manifest" }
        return result
    }

    private fun validate(input: NoteMaterialInput) {
        require(input.schemaVersion == 1 && validNoteId(input.id))
        input.document.validate()
        require(input.recordings.map { it.id }.distinct().size == input.recordings.size)
        input.recordings.forEach { session ->
            require(session.schemaVersion == 1 && validNoteId(session.id) && session.noteId == input.document.id)
            require(!session.active && session.problem != RecordingProblem.Damaged && session.problem != RecordingProblem.Storage)
            require(session.samples > 0 && session.currentSegment > 0)
            require(session.chunks.all { it.samples >= 0 && it.segment in 1..session.currentSegment && it.index > 0 })
            require(session.chunks.map { it.fileName }.distinct().size == session.chunks.size)
        }
    }

    private fun sources(input: NoteMaterialInput): List<Source> {
        val note = input.document
        val result = mutableListOf(bytes("note.json", NoteMaterialKind.Document, note.source.toString()),
            bytes("document.md", NoteMaterialKind.Markdown, notes.markdown(note)))
        note.blocks.forEach { block -> when (block.string("kind")) {
            "markdown" -> Unit
            "photo" -> {
                val hash = block.string("sha256")
                require(validMaterialDigest(hash) && block.string("mimeType") in setOf("image/jpeg", "image/png", "image/webp"))
                result += Source(NoteFileStore.photoPath(block), NoteMaterialKind.Photo, blockId = block.string("id"), expectedHash = hash) {
                    val file = notes.photoFile(note.id, block.string("assetId"))
                    require(file.isFile) { "Missing photo" }
                    Content(file.length()) { file.inputStream() }
                }
            }
            "ink" -> result += Source(NoteFileStore.inkPath(block), NoteMaterialKind.InkPreview, blockId = block.string("id")) {
                // Render from the frozen vector, without rewriting the live document's preview cache.
                val output = ByteArrayOutputStream()
                val bitmap = InkRendering.bitmap(inkOf(block))
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) } finally { bitmap.recycle() }
                val data = output.toByteArray()
                Content(data.size.toLong()) { data.inputStream() }
            }
            else -> error("Unknown extension prevents complete material version")
        } }
        input.recordings.forEach { session ->
            val prefix = "recordings/${session.id}"
            result += bytes("$prefix/manifest.json", NoteMaterialKind.RecordingManifest, NoteJson.encodeToString(session), session.id)
            session.chunks.forEach { chunk ->
                result += Source("$prefix/${chunk.fileName}", NoteMaterialKind.AudioChunk, recordingId = session.id,
                    segment = chunk.segment, chunkIndex = chunk.index, samples = chunk.samples) {
                    val file = recordings.audioFile(session.id, chunk)
                    require(file.isFile) { "Missing recording chunk" }
                    PcmWavFile.validate(file, chunk.samples)
                    Content(file.length()) { file.inputStream() }
                }
            }
        }
        require(result.map { it.path }.distinct().size == result.size) { "Duplicate material paths" }
        return result
    }

    private fun identity(input: NoteMaterialInput) = noteMaterialIdentity(input.document, input.recordings)

    private fun bytes(path: String, kind: NoteMaterialKind, text: String, recordingId: String? = null) =
        Source(path, kind, recordingId = recordingId, expectedHash = materialDigest(text)) {
            val data = text.toByteArray(Charsets.UTF_8)
            Content(data.size.toLong()) { data.inputStream() }
        }

    private data class Content(val size: Long, val open: () -> InputStream)
    private data class Source(
        val path: String, val kind: NoteMaterialKind, val blockId: String? = null,
        val recordingId: String? = null, val segment: Int? = null, val chunkIndex: Int? = null,
        val samples: Long? = null, val expectedHash: String? = null, val open: () -> Content
    ) {
        fun descriptor(hash: String, size: Long) = NoteMaterialFile(path, kind, hash, size, blockId, recordingId, segment, chunkIndex, samples)
        fun matches(file: NoteMaterialFile) = validMaterialDigest(file.sha256) && file.sizeBytes >= 0 &&
            descriptor(file.sha256, file.sizeBytes) == file && (expectedHash == null || expectedHash == file.sha256)
    }

    private fun folders(): List<File> = if (!root.exists()) emptyList() else checkNotNull(root.listFiles()) { "Cannot list material versions" }
        .filter { it.isDirectory && validNoteId(it.name) }
    private fun directory(id: String): File { require(validNoteId(id)); return File(root, id) }
    private fun inputFile(id: String) = File(directory(id), "input.json")
    private fun manifestFile(id: String) = File(directory(id), "manifest.json")
    private fun status(id: String, value: NoteMaterialStatus) = writeMaterialFile(File(directory(id), "status.json")) {
        it.write(NoteJson.encodeToString(value).toByteArray(Charsets.UTF_8))
    }

    companion object {
        // Worker and editor stores share one disk transaction boundary in this single-process app.
        private val diskLock = Any()
        fun at(noBackupDirectory: File) = NoteMaterialStore(File(noBackupDirectory, "note-material-versions"),
            NoteFileStore(File(noBackupDirectory, "notes")), RecordingFileStore(File(noBackupDirectory, "recordings")))
    }
}
