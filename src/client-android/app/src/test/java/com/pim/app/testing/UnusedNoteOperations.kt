package com.pim.app.testing

import com.pim.core.contract.*
import com.pim.core.network.ApiService
import okhttp3.RequestBody

/** Legacy endpoint fixtures must fail if they unexpectedly start any native-note network work. */
internal interface UnusedNoteOperations : ApiService {
    override suspend fun beginNoteMaterialUpload(request: BeginNoteMaterialUploadRequest): NoteMaterialUploadDtoApiResponse = error("Unexpected note upload")
    override suspend fun getNoteMaterialVersion(versionId: String): NoteMaterialVersionDtoApiResponse = error("Unexpected note read")
    override suspend fun uploadNoteMaterialPart(versionId: String, fileIndex: Int, partIndex: Int, sha256: String, body: RequestBody): StringApiResponse = error("Unexpected note bytes")
    override suspend fun completeNoteMaterialUpload(versionId: String): NoteMaterialUploadDtoApiResponse = error("Unexpected note completion")
    override suspend fun getNoteAsrSources(offset: Int, limit: Int): NoteAsrSourcesDtoApiResponse = error("Unexpected ASR sources")
    override suspend fun getNoteAsrDestinations(): NoteAsrDestinationsDtoApiResponse = error("Unexpected ASR destinations")
    override suspend fun getNoteAsrTasks(): NoteAsrTaskDtoIReadOnlyListApiResponse = error("Unexpected ASR list")
    override suspend fun startNoteAsrTask(request: StartNoteAsrRequest): NoteAsrTaskDtoApiResponse = error("Unexpected ASR start")
    override suspend fun getNoteAsrTask(taskId: String): NoteAsrTaskDtoApiResponse = error("Unexpected ASR task")
    override suspend fun getNoteAsrSegments(taskId: String, offset: Int, limit: Int): NoteAsrSegmentsDtoApiResponse = error("Unexpected ASR segments")
    override suspend fun stopNoteAsrTask(taskId: String): NoteAsrTaskDtoApiResponse = error("Unexpected ASR stop")
    override suspend fun retryNoteAsrTask(taskId: String, request: RetryNoteAsrRequest): NoteAsrTaskDtoApiResponse = error("Unexpected ASR retry")
    override suspend fun getNoteDerivedLimits(): NoteDerivedLimitsDtoApiResponse = error("Unexpected derived limits")
    override suspend fun getNoteDerivedDestinations(): NoteDerivedDestinationsDtoApiResponse = error("Unexpected derived destinations")
    override suspend fun getNoteDerivedSources(offset: Int, limit: Int): NoteAsrSourcesDtoApiResponse = error("Unexpected derived sources")
    override suspend fun getNoteDerivedTasks(noteId: String?): NoteDerivedTaskDtoIReadOnlyListApiResponse = error("Unexpected derived list")
    override suspend fun startNoteDerivedTask(request: StartNoteDerivedTaskRequest): NoteDerivedTaskDtoApiResponse = error("Unexpected derived start")
    override suspend fun getNoteDerivedTask(taskId: String): NoteDerivedTaskDtoApiResponse = error("Unexpected derived task")
    override suspend fun getNoteDerivedSteps(taskId: String, offset: Int, limit: Int): NoteDerivedStepsDtoApiResponse = error("Unexpected derived steps")
    override suspend fun stopNoteDerivedTask(taskId: String): NoteDerivedTaskDtoApiResponse = error("Unexpected derived stop")
    override suspend fun retryNoteDerivedTask(taskId: String, request: RetryNoteDerivedTaskRequest): NoteDerivedTaskDtoApiResponse = error("Unexpected derived retry")
    override suspend fun getNoteDerivedArtifacts(noteId: String): NoteDerivedArtifactDtoIReadOnlyListApiResponse = error("Unexpected derived artifacts")
    override suspend fun getNoteDerivedArtifact(artifactId: String): NoteDerivedArtifactDtoApiResponse = error("Unexpected derived artifact")
    override suspend fun getNoteDerivedVersion(artifactId: String, versionId: String): NoteDerivedVersionDtoApiResponse = error("Unexpected derived version")
    override suspend fun saveNoteDerivedManualRevision(artifactId: String, request: SaveNoteDerivedManualRevisionRequest): NoteDerivedMutationDtoApiResponse = error("Unexpected derived manual revision")
    override suspend fun selectNoteDerivedVersion(artifactId: String, request: SelectNoteDerivedVersionRequest): NoteDerivedArtifactDtoApiResponse = error("Unexpected derived select")
}
