package com.pim.app.testing

import com.pim.core.contract.*
import com.pim.core.network.ApiService
import okhttp3.RequestBody

/** Legacy endpoint fixtures must fail if they unexpectedly start any native-note network work. */
internal interface UnusedNoteOperations : ApiService {
    override suspend fun getTasksPaged(
        inbox: Boolean?,
        search: String?,
        calendarId: String?,
        status: String?,
        priority: Int?,
        plannedFrom: String?,
        plannedTo: String?,
        dueFrom: String?,
        dueTo: String?,
        page: Int?,
        pageSize: Int?
    ): TaskResponsePagedResultApiResponse = error("Unexpected paged task read")
    override suspend fun getTaskBooks(): TaskBookDtoIReadOnlyListApiResponse = error("Unexpected task-book read")
    override suspend fun createTaskBook(request: CreateTaskBookRequest): TaskBookDtoApiResponse = error("Unexpected task-book create")
    override suspend fun getManagedDevices(sortBy: String?): DeviceListDtoIReadOnlyListApiResponse = error("Unexpected device list")
    override suspend fun renameManagedDevice(deviceId: String, request: DeviceRenameRequest): DeviceDtoApiResponse = error("Unexpected device rename")
    override suspend fun getManagedDeviceDeletePreview(deviceId: String): DeviceDeletePreviewDtoApiResponse = error("Unexpected device delete preview")
    override suspend fun deleteManagedDevice(deviceId: String): DeviceDeleteResultDtoApiResponse = error("Unexpected device delete")
    override suspend fun getKnowledgeCategoryTree(): CategoryTreeNodeListApiResponse = error("Unexpected category tree")
    override suspend fun saveKnowledgeCategory(request: CategorySaveRequest): CategoryTreeNodeApiResponse = error("Unexpected category save")
    override suspend fun deleteKnowledgeCategory(id: String): StringApiResponse = error("Unexpected category delete")
    override suspend fun getAppKnowledgeApps(search: String?): AppKnowledgeAppDtoListApiResponse = error("Unexpected app knowledge list")
    override suspend fun getAppKnowledgeContexts(appId: String): AppKnowledgeContextDtoListApiResponse = error("Unexpected app knowledge contexts")
    override suspend fun saveAppKnowledgeContext(request: SaveAppKnowledgeContextRequest): AppKnowledgeContextDtoApiResponse = error("Unexpected app knowledge save")
    override suspend fun deleteAppKnowledgeContext(id: String): StringApiResponse = error("Unexpected app knowledge delete")
    override suspend fun getQuickNotes(status: String?, search: String?, page: Int?, pageSize: Int?): QuickNoteListItemDtoPagedResultApiResponse = error("Unexpected quick-note list")
    override suspend fun createQuickNote(request: CreateQuickNoteRequest): QuickNoteDetailDtoApiResponse = error("Unexpected quick-note create")
    override suspend fun getQuickNote(id: String): QuickNoteDetailDtoApiResponse = error("Unexpected quick-note read")
    override suspend fun updateQuickNote(id: String, request: UpdateQuickNoteRequest): QuickNoteDetailDtoApiResponse = error("Unexpected quick-note update")
    override suspend fun deleteQuickNote(id: String): StringApiResponse = error("Unexpected quick-note delete")
    override suspend fun archiveQuickNote(id: String): QuickNoteDetailDtoApiResponse = error("Unexpected quick-note archive")
    override suspend fun processQuickNote(id: String): QuickNoteDetailDtoApiResponse = error("Unexpected quick-note process")
    override suspend fun restoreQuickNote(id: String, request: RestoreQuickNoteRequest): QuickNoteDetailDtoApiResponse = error("Unexpected quick-note restore")
    override suspend fun getReminders(): ReminderResponseIReadOnlyListApiResponse = error("Unexpected reminder list")
    override suspend fun dismissReminder(id: String): ReminderResponseApiResponse = error("Unexpected reminder dismiss")
    override suspend fun snoozeReminder(id: String, scheduledAt: String): ReminderResponseApiResponse = error("Unexpected reminder snooze")
    override suspend fun getHabits(): HabitRoutineDtoIReadOnlyListApiResponse = error("Unexpected habit list")
    override suspend fun archiveHabit(id: String): HabitRoutineDtoApiResponse = error("Unexpected habit archive")
    override suspend fun getPendingOperationConfirmations(): OperationConfirmationDtoIReadOnlyListApiResponse = error("Unexpected confirmation list")
    override suspend fun getOperationConfirmation(id: String): OperationConfirmationDtoApiResponse = error("Unexpected confirmation detail")
    override suspend fun rejectOperationConfirmation(id: String): OperationConfirmationDtoApiResponse = error("Unexpected confirmation rejection")
    override suspend fun listNoteTaskSuggestions(noteId: String): NoteTaskSuggestionListDtoApiResponse = error("Unexpected task-suggestion list")
    override suspend fun startNoteTaskSuggestionExtraction(request: StartNoteTaskSuggestionExtractionRequest): NoteTaskSuggestionExtractionDtoApiResponse = error("Unexpected task-suggestion extraction")
    override suspend fun getNoteTaskSuggestionExtraction(id: String): NoteTaskSuggestionExtractionDtoApiResponse = error("Unexpected task-suggestion extraction read")
    override suspend fun getNoteTaskSuggestion(id: String): NoteTaskSuggestionDtoApiResponse = error("Unexpected task-suggestion read")
    override suspend fun updateNoteTaskSuggestion(id: String, request: UpdateNoteTaskSuggestionRequest): NoteTaskSuggestionDtoApiResponse = error("Unexpected task-suggestion update")
    override suspend fun confirmNoteTaskSuggestion(id: String, request: ConfirmNoteTaskSuggestionRequest): NoteTaskSuggestionDtoApiResponse = error("Unexpected task-suggestion confirmation")
    override suspend fun dismissNoteTaskSuggestion(id: String, request: DismissNoteTaskSuggestionRequest): NoteTaskSuggestionDtoApiResponse = error("Unexpected task-suggestion dismissal")
    override suspend fun getSystemStatus(): SystemStatusDetailDtoApiResponse = error("Unexpected system status")
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
