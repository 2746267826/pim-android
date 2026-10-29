package com.pim.app.notes

import com.pim.app.recording.RecordingSession
import com.pim.core.contract.AiAsrDestinationConsent
import com.pim.core.contract.NoteAsrDestinationDto
import com.pim.core.contract.NoteAsrSegmentDto
import com.pim.core.contract.NoteAsrSourceDto
import com.pim.core.contract.NoteAsrTaskDto
import com.pim.core.network.BoundUploadSession
import java.io.File
import kotlinx.serialization.encodeToString

data class NoteAsrTasksState(
    val loaded: Boolean = false,
    val tasks: List<NoteAsrTaskRecord> = emptyList(),
    val loadFailed: Boolean = false,
)

class NoteAsrTaskStore(private val root: File) {
    fun load(): NoteAsrTasksState = synchronized(lock) {
        if (!root.exists()) return@synchronized NoteAsrTasksState(loaded = true)
        var damaged = false
        val tasks = checkNotNull(root.listFiles())
            .filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.bak")) }
            .map { it.name.removeSuffix(".bak").removeSuffix(".json") }
            .distinct()
            .mapNotNull { id ->
                try { read(id) } catch (_: Exception) { damaged = true; null }
            }
            .sortedByDescending { it.createdAtMillis }
        NoteAsrTasksState(loaded = true, tasks = tasks, loadFailed = damaged)
    }

    fun tasks(): List<NoteAsrTaskRecord> = load().tasks

    fun createIntent(
        source: NoteAsrSourceDto,
        material: NoteMaterialInput?,
        binding: BoundUploadSession,
        ownerName: String,
        destination: AiAsrDestinationConsent,
        language: String,
        destinationInfo: NoteAsrDestinationDto? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteAsrTaskRecord = synchronized(lock) {
        validateDestination(destination)
        if (destinationInfo != null) {
            require(destination == destinationInfo.asAsrConsent())
        }
        require(source.versionId == source.versionId.lowercase() && validNoteId(source.versionId))
        require(source.noteId == source.noteId.lowercase() && validNoteId(source.noteId))
        require(source.totalSamples > 0 && source.sampleRate > 0 && source.recordings > 0)
        require(material == null || material.document.id == source.noteId)
        require(language.isNotBlank() && language.length <= 64)
        val existing = tasks()
        val sameIntent = existing.firstOrNull {
            it.versionId == source.versionId && it.binding == binding &&
                it.destination == destination && it.language == language &&
                it.localPhase in PENDING_INTENT_PHASES
        }
        if (sameIntent != null) return@synchronized sameIntent
        require(existing.size < MAX_TASKS) { "本机保留的转写任务已达 $MAX_TASKS 个" }
        val taskId = newNoteId()
        val records = material?.recordings.orEmpty().map { it.sourceSnapshot() }
        val record = NoteAsrTaskRecord(
            taskId = taskId,
            versionId = source.versionId,
            noteId = source.noteId,
            sourceTitle = source.title.take(500),
            versionRevision = material?.document?.revision,
            versionCreatedAt = source.createdAt,
            versionIsCurrent = source.isCurrent,
            versionHasConflict = source.hasConflict,
            sourceRecordings = records,
            totalSamples = source.totalSamples,
            sampleRate = source.sampleRate,
            serverIdentity = binding.serverIdentity,
            ownerId = binding.ownerId,
            ownerName = ownerName.take(200),
            expectedUserId = binding.ownerId,
            language = language,
            destination = destination,
            destinationDisplayName = destinationInfo?.displayName.orEmpty().take(200),
            destinationModel = destinationInfo?.model.orEmpty().take(200),
            destinationLanguages = destinationInfo?.languages.orEmpty().distinct(),
            destinationBackgroundAvailable = destinationInfo?.backgroundProcessingAvailable,
            localPhase = NoteAsrIntentPhase.Prepared,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
        )
        write(record)
        record
    }

    fun importRemote(
        task: NoteAsrTaskDto,
        binding: BoundUploadSession,
        ownerName: String,
        material: NoteMaterialInput?,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteAsrTaskRecord = synchronized(lock) {
        validateTask(task)
        require(validNoteId(task.taskId) && validNoteId(task.noteId) && validNoteId(task.versionId))
        require(material == null || material.document.id == task.noteId)
        val old = if (materialFileExists(path(task.taskId))) read(task.taskId) else null
        if (old != null) {
            require(old.binding == binding && old.noteId == task.noteId && old.versionId == task.versionId &&
                old.destination == task.destination) { "服务器任务与本地固定身份不一致" }
        } else require(tasks().size < MAX_TASKS) { "本机保留的转写任务已达 $MAX_TASKS 个" }
        val record = old?.copy(
            task = task,
            localPhase = NoteAsrIntentPhase.Accepted,
            pendingRetry = old.pendingRetry?.takeIf { it.expectedRevision == task.revision },
            lastProblem = null,
            updatedAtMillis = nowMillis,
        ) ?: NoteAsrTaskRecord(
            taskId = task.taskId,
            versionId = task.versionId,
            noteId = task.noteId,
            sourceTitle = material?.document?.title.orEmpty(),
            versionRevision = material?.document?.revision,
            sourceRecordings = material?.recordings.orEmpty().map { it.sourceSnapshot() },
            totalSamples = task.totalSamples,
            sampleRate = task.sampleRate,
            serverIdentity = binding.serverIdentity,
            ownerId = binding.ownerId,
            ownerName = ownerName.take(200),
            expectedUserId = binding.ownerId,
            language = task.language,
            destination = task.destination,
            localPhase = NoteAsrIntentPhase.Accepted,
            task = task,
            createdAtMillis = parseAsrMillis(task.createdAt, nowMillis),
            updatedAtMillis = nowMillis,
        )
        write(record)
        record
    }

    fun read(id: String): NoteAsrTaskRecord = synchronized(lock) {
        require(validNoteId(id))
        val record = NoteJson.decodeFromString<NoteAsrTaskRecord>(readMaterialFile(path(id)))
        validate(record)
        require(record.taskId == id)
        record
    }

    fun update(id: String, change: (NoteAsrTaskRecord) -> NoteAsrTaskRecord): NoteAsrTaskRecord = synchronized(lock) {
        val before = read(id)
        val after = change(before)
        require(after.taskId == before.taskId && after.versionId == before.versionId && after.noteId == before.noteId &&
            after.serverIdentity == before.serverIdentity && after.ownerId == before.ownerId &&
            after.expectedUserId == before.expectedUserId && after.destination == before.destination &&
            after.language == before.language && after.createdAtMillis == before.createdAtMillis)
        validate(after)
        write(after)
        after
    }

    fun mergeSegments(
        id: String,
        incoming: List<NoteAsrSegmentDto>,
        expectedTotal: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): NoteAsrTaskRecord = update(id) { record ->
        require(record.task?.totalSegments == expectedTotal)
        incoming.forEach { segment ->
            require(segment.index in 0 until expectedTotal)
            require(segment.samples >= 0 && segment.recordingOffsetSamples >= 0 &&
                segment.timelineOffsetSamples >= 0 && segment.sourceOffsetSamples >= 0)
        }
        val merged = (record.segments + incoming)
            .associateBy { it.index }
            .values
            .sortedBy { it.index }
        require(merged.size <= expectedTotal)
        record.copy(
            task = record.task?.copy(totalSegments = expectedTotal),
            segments = merged,
            segmentsComplete = merged.size == expectedTotal &&
                merged.map { it.index }.toSet() == (0 until expectedTotal).toSet(),
            updatedAtMillis = nowMillis,
        )
    }

    private fun write(record: NoteAsrTaskRecord) {
        validate(record)
        val json = NoteJson.encodeToString(record)
        writeMaterialFile(path(record.taskId)) { it.write(json.toByteArray(Charsets.UTF_8)) }
        check(readMaterialFile(path(record.taskId)) == json) { "转写任务检查点未确认落盘" }
    }

    private fun validate(record: NoteAsrTaskRecord) {
        require(validNoteId(record.taskId) && validNoteId(record.versionId) && validNoteId(record.noteId))
        require(record.expectedUserId == record.ownerId)
        record.binding
        validateDestination(record.destination)
        require(record.totalSamples > 0 && record.sampleRate > 0 && record.language.isNotBlank())
        require(record.destinationDisplayName.length <= 200 && record.destinationModel.length <= 200)
        require(record.destinationLanguages.size <= 64 &&
            record.destinationLanguages.distinct().size == record.destinationLanguages.size &&
            record.destinationLanguages.all { it.isNotBlank() })
        require(record.sourceRecordings.map { it.id }.distinct().size == record.sourceRecordings.size)
        require(record.sourceRecordings.all { validNoteId(it.id) && it.samples >= 0 && it.recordingSegments > 0 })
        require(record.segments.size <= 5_000 && record.segments.map { it.index }.distinct().size == record.segments.size)
        require(record.segments.all { it.index >= 0 && it.samples >= 0 })
        require(!record.segmentsComplete || record.task != null)
        record.task?.let {
            validateTask(it)
            require(it.taskId == record.taskId && it.noteId == record.noteId && it.versionId == record.versionId &&
                it.destination == record.destination && it.language == record.language)
            require(record.segments.size <= it.totalSegments)
        }
        record.pendingRetry?.let {
            require(validNoteId(it.retryId) && validNoteId(it.expectedRevision) && it.segmentIndexes.isNotEmpty() &&
                it.segmentIndexes.size <= 5_000 && it.segmentIndexes.distinct().size == it.segmentIndexes.size)
        }
        require(record.updatedAtMillis >= record.createdAtMillis)
    }

    private fun validateDestination(destination: AiAsrDestinationConsent) {
        require(destination.confirmed)
        val endpoint = runCatching { java.net.URI(destination.endpoint) }.getOrNull()
        require(endpoint != null && endpoint.scheme in setOf("https", "http") &&
            !endpoint.host.isNullOrBlank() && endpoint.userInfo == null)
        require(validNoteId(destination.modelId) && validNoteId(destination.modelRevision))
        require(destination.modelId.length <= 200 && destination.modelRevision.length <= 200 &&
            destination.presetId.length <= 200 && destination.presetVersion.length <= 200)
        require(destination.modelId.isNotBlank() && destination.modelRevision.isNotBlank() &&
            destination.presetId.isNotBlank() && destination.presetVersion.isNotBlank())
    }

    private fun validateTask(task: NoteAsrTaskDto) {
        validateDestination(task.destination)
        require(task.totalSegments in 1..5_000 && task.completedSegments in 0..task.totalSegments &&
            task.failedSegments in 0..task.totalSegments)
        require(task.totalSamples > 0 && task.completedSamples in 0..task.totalSamples && task.sampleRate > 0)
        require(task.phase.isNotBlank() && task.revision.isNotBlank() && task.language.isNotBlank())
        require(validMaterialDigest(task.inputSha256.lowercase()) && validMaterialDigest(task.manifestSha256.lowercase()))
    }

    private fun path(id: String): File {
        require(validNoteId(id))
        return File(root, "$id.json")
    }

    companion object {
        const val MAX_TASKS = 200
        private val lock = Any()
        private val PENDING_INTENT_PHASES = setOf(
            NoteAsrIntentPhase.Prepared,
            NoteAsrIntentPhase.Submitting,
            NoteAsrIntentPhase.VerificationPending,
        )
        fun at(noBackupDirectory: File) = NoteAsrTaskStore(File(noBackupDirectory, "note-asr-tasks"))
    }
}

private fun RecordingSession.sourceSnapshot() = NoteAsrRecordingSource(
    id = id,
    createdAtMillis = createdAtMillis,
    recordingSegments = chunks.map { it.segment }.distinct().size.coerceAtLeast(1),
    samples = samples,
)

private fun parseAsrMillis(value: String, fallback: Long): Long =
    runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(fallback)
