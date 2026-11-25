package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.contract.NoteTaskSuggestionDto
import com.pim.core.contract.NoteTaskSuggestionKindDto
import com.pim.core.contract.NoteTaskSuggestionStatusDto
import com.pim.core.contract.UpdateNoteTaskSuggestionRequest
import com.pim.core.network.BoundUploadSession
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteTaskSuggestionRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    private val source = NoteTaskSuggestionSource(
        noteId = NOTE_ID,
        materialVersionId = MATERIAL_ID,
        artifactId = ARTIFACT_ID,
        versionId = VERSION_ID,
        contentSha256 = "a".repeat(64),
        inputSha256 = "b".repeat(64),
        manifestSha256 = "c".repeat(64),
    )
    private val binding = BoundUploadSession("https://pim.example", OWNER_ID)
    private var target = NoteTaskSuggestionTarget(binding, "tester", identityGeneration = 7)
    private val transport = FakeSuggestionTransport()

    @Test
    fun refreshKeepsDirtyDraftWhenRemoteSuggestionBecomesTerminal() = runBlocking {
        val repository = repository()
        repository.open(source)
        val initial = repository.state.value.items.single()
        val scope = requireNotNull(repository.state.value.actionScope)
        repository.stageDraft(
            scope,
            SUGGESTION_ID,
            NoteTaskSuggestionEdit(
                title = "Locally edited title",
                description = initial.description,
                priority = initial.priority,
                due = initial.due,
            ),
        )

        transport.item = suggestion(NoteTaskSuggestionStatusDto.DISMISSED)
        repository.refresh(source)

        val refreshed = repository.state.value.items.single()
        assertEquals(NoteTaskSuggestionStatusDto.DISMISSED, refreshed.remote.status)
        assertNotNull(refreshed.draft)
        assertEquals("Locally edited title", refreshed.draft?.title)
        assertEquals(true, refreshed.draft?.dirty)
        assertNotNull(NoteTaskSuggestionStore.at(temp.root).draft(source, binding, SUGGESTION_ID))
    }

    @Test
    fun dismissRejectsDirtyDraftBeforeSendingRequest() = runBlocking {
        val repository = repository()
        repository.open(source)
        val initial = repository.state.value.items.single()
        val scope = requireNotNull(repository.state.value.actionScope)
        repository.stageDraft(
            scope,
            SUGGESTION_ID,
            NoteTaskSuggestionEdit(
                title = "Unsaved title",
                description = initial.description,
                priority = initial.priority,
                due = initial.due,
            ),
        )

        val rejected = runCatching { repository.dismiss(scope, SUGGESTION_ID) }.exceptionOrNull()
        assertNotNull(rejected)
        assertEquals(0, transport.dismissCalls)
        assertEquals("Unsaved title", repository.state.value.items.single().draft?.title)
    }

    @Test
    fun unchangedRemoteDueDateDoesNotCreateDirtyDraft() = runBlocking {
        transport.item = suggestion(NoteTaskSuggestionStatusDto.DRAFT).copy(due = "2026-11-02T08:30:00Z")
        val repository = repository()
        repository.open(source)
        val item = repository.state.value.items.single()
        val scope = requireNotNull(repository.state.value.actionScope)

        repository.stageDraft(
            scope,
            SUGGESTION_ID,
            NoteTaskSuggestionEdit(
                title = item.title,
                description = item.description,
                priority = item.priority,
                due = suggestionDueInput(transport.item.due),
            ),
        )

        assertEquals(null, repository.state.value.items.single().draft)
        assertEquals(null, NoteTaskSuggestionStore.at(temp.root).draft(source, binding, SUGGESTION_ID))
    }

    @Test
    fun switchingSourceDuringBlockedRefreshClearsOldItemsAndRejectsStaleMutations() = runBlocking {
        val repository = repository()
        repository.open(source)
        val oldScope = requireNotNull(repository.state.value.actionScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.blockVersionId = source.versionId
        transport.verifyEntered = entered
        transport.verifyRelease = release

        val staleRefresh = async(Dispatchers.Default) { repository.refresh(source) }
        entered.await()
        assertEquals(emptyList<NoteTaskSuggestionItem>(), repository.state.value.items)
        assertEquals(null, repository.state.value.actionScope)
        withTimeout(1_000) {
            assertNotNull(runCatching { repository.confirm(oldScope, SUGGESTION_ID) }.exceptionOrNull())
            assertNotNull(runCatching {
                repository.stageDraft(
                    oldScope,
                    SUGGESTION_ID,
                    NoteTaskSuggestionEdit("blocked stale edit", "", 5, ""),
                )
            }.exceptionOrNull())
        }
        assertEquals(0, transport.confirmCalls)
        assertEquals(0, transport.updateCalls)
        val nextSource = source.copy(
            versionId = OTHER_VERSION_ID,
            contentSha256 = "d".repeat(64),
            inputSha256 = "e".repeat(64),
            manifestSha256 = "f".repeat(64),
        )
        val openingNext = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            repository.open(nextSource)
        }
        assertEquals(nextSource, repository.state.value.source)
        assertEquals(true, repository.state.value.loading)
        assertEquals(emptyList<NoteTaskSuggestionItem>(), repository.state.value.items)
        assertEquals(null, repository.state.value.actionScope)

        release.complete(Unit)
        staleRefresh.await()
        openingNext.await()
        assertEquals(nextSource, repository.state.value.source)
        assertEquals(1, repository.state.value.items.size)

        val rejectedConfirm = runCatching { repository.confirm(oldScope, SUGGESTION_ID) }.exceptionOrNull()
        val rejectedEdit = runCatching {
            repository.stageDraft(
                oldScope,
                SUGGESTION_ID,
                NoteTaskSuggestionEdit("wrong source", "", 5, ""),
            )
        }.exceptionOrNull()
        assertNotNull(rejectedConfirm)
        assertNotNull(rejectedEdit)
        assertEquals(0, transport.confirmCalls)
        assertEquals(0, transport.updateCalls)
    }

    @Test
    fun ownerSessionChangeDuringBlockedRefreshClearsOldScopeAndRejectsStaleMutations() = runBlocking {
        val repository = repository()
        repository.open(source)
        val oldScope = requireNotNull(repository.state.value.actionScope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.blockVersionId = source.versionId
        transport.verifyEntered = entered
        transport.verifyRelease = release

        val refresh = async(Dispatchers.Default) { repository.refresh(source) }
        entered.await()
        assertEquals(emptyList<NoteTaskSuggestionItem>(), repository.state.value.items)
        assertEquals(null, repository.state.value.actionScope)
        withTimeout(1_000) {
            assertNotNull(runCatching { repository.confirm(oldScope, SUGGESTION_ID) }.exceptionOrNull())
            assertNotNull(runCatching {
                repository.stageDraft(
                    oldScope,
                    SUGGESTION_ID,
                    NoteTaskSuggestionEdit("blocked stale owner edit", "", 5, ""),
                )
            }.exceptionOrNull())
        }
        assertEquals(0, transport.confirmCalls)
        assertEquals(0, transport.updateCalls)
        target = target.copy(
            binding = BoundUploadSession(binding.serverIdentity, OTHER_OWNER_ID),
            identityGeneration = target.identityGeneration + 1,
        )
        release.complete(Unit)
        refresh.await()

        assertEquals(emptyList<NoteTaskSuggestionItem>(), repository.state.value.items)
        assertEquals(null, repository.state.value.actionScope)
        withTimeout(1_000) {
            assertNotNull(runCatching { repository.confirm(oldScope, SUGGESTION_ID) }.exceptionOrNull())
            assertNotNull(runCatching {
                repository.stageDraft(
                    oldScope,
                    SUGGESTION_ID,
                    NoteTaskSuggestionEdit("wrong owner", "", 5, ""),
                )
            }.exceptionOrNull())
        }
        assertEquals(0, transport.confirmCalls)
        assertEquals(0, transport.updateCalls)
    }

    @Test
    fun ownerSessionChangeInvalidatesVisibleItemsBeforeARefreshIsStarted() = runBlocking {
        val repository = repository()
        repository.open(source)
        val oldScope = requireNotNull(repository.state.value.actionScope)
        target = target.copy(
            binding = BoundUploadSession(binding.serverIdentity, OTHER_OWNER_ID),
            identityGeneration = target.identityGeneration + 1,
        )

        repository.invalidateIfTargetChanged()

        assertEquals(emptyList<NoteTaskSuggestionItem>(), repository.state.value.items)
        assertEquals(null, repository.state.value.actionScope)
        assertNotNull(runCatching { repository.confirm(oldScope, SUGGESTION_ID) }.exceptionOrNull())
        assertEquals(0, transport.confirmCalls)
    }

    @Test
    fun lostConfirmReceiptRetriesWithTheSamePersistedRequestId() = runBlocking {
        val repository = repository()
        repository.open(source)
        val scope = requireNotNull(repository.state.value.actionScope)
        transport.failConfirmOnce = true
        val store = NoteTaskSuggestionStore.at(temp.root)

        repository.confirm(scope, SUGGESTION_ID)
        val pending = requireNotNull(repository.state.value.items.single().draft)
        val firstRequestId = requireNotNull(pending.confirmRequestId)
        assertEquals(pending.expectedRevision, pending.confirmRevision)
        assertEquals(firstRequestId, store.draft(source, binding, SUGGESTION_ID)?.confirmRequestId)
        assertEquals(1, transport.confirmCalls)

        repository.confirm(scope, SUGGESTION_ID)

        assertEquals(2, transport.confirmCalls)
        assertEquals(listOf(firstRequestId, firstRequestId), transport.confirmRequestIds)
        assertEquals(null, repository.state.value.items.single().draft)
    }

    @Test
    fun failedCasSavePreservesDraftUntilExplicitlyRebasedToVerifiedRevision() = runBlocking {
        val repository = repository()
        repository.open(source)
        val scope = requireNotNull(repository.state.value.actionScope)
        repository.stageDraft(
            scope,
            SUGGESTION_ID,
            NoteTaskSuggestionEdit("Edited title", "Edited body", 9, ""),
        )
        transport.failUpdateOnce = true

        repository.saveDraft(scope, SUGGESTION_ID)

        val retained = requireNotNull(repository.state.value.items.single().draft)
        assertEquals(true, retained.dirty)
        assertEquals("Edited title", retained.title)
        assertEquals("revision-1", retained.expectedRevision)
        assertEquals(1, transport.updateCalls)
        assertEquals(listOf("revision-1"), transport.expectedRevisions)

        transport.item = transport.item.copy(revision = "revision-2")
        repository.refresh(source)
        assertEquals(true, repository.state.value.items.single().hasConflict)
        assertEquals("revision-1", repository.state.value.items.single().draft?.expectedRevision)

        val conflictScope = requireNotNull(repository.state.value.actionScope)
        assertNotNull(runCatching { repository.saveDraft(conflictScope, SUGGESTION_ID) }.exceptionOrNull())
        assertEquals(1, transport.updateCalls)
        assertEquals(listOf("revision-1"), transport.expectedRevisions)
        repository.rebaseDraftAfterConflict(conflictScope, SUGGESTION_ID)
        assertEquals("revision-2", repository.state.value.items.single().draft?.expectedRevision)
        assertEquals(true, repository.state.value.items.single().draft?.dirty)
        repository.saveDraft(conflictScope, SUGGESTION_ID)

        assertEquals(listOf("revision-1", "revision-2"), transport.expectedRevisions)
        assertEquals(null, repository.state.value.items.single().draft)
        assertEquals("revision-3", repository.state.value.items.single().remote.revision)
    }

    @Test
    fun taskSavePreservesUnchangedCalendarBookAndDue() = runBlocking {
        transport.item = suggestion(
            NoteTaskSuggestionStatusDto.DRAFT,
            due = "2026-11-21T19:00:00+08:00",
            calendarId = CALENDAR_ID,
            taskBookId = TASK_BOOK_ID,
        )
        val repository = repository()
        repository.open(source)
        val item = repository.state.value.items.single()
        val scope = requireNotNull(repository.state.value.actionScope)

        repository.stageDraft(
            scope,
            SUGGESTION_ID,
            NoteTaskSuggestionEdit(
                title = "只改标题",
                description = item.description,
                priority = item.priority,
                due = item.due,
            ),
        )
        repository.saveDraft(scope, SUGGESTION_ID)

        val request = requireNotNull(transport.lastUpdate)
        assertEquals(CALENDAR_ID, request.calendarId)
        assertEquals(TASK_BOOK_ID, request.taskBookId)
        assertEquals(Instant.parse("2026-11-21T19:00:00+08:00").toString(), request.due)
        assertEquals(null, request.start)
        assertEquals(null, request.end)
        assertEquals(null, request.timeZoneId)
    }

    @Test
    fun taskTitleEditPreservesServerSubMinuteDuePrecision() = runBlocking {
        transport.item = suggestion(
            NoteTaskSuggestionStatusDto.DRAFT,
            due = "2026-11-21T19:00:30+08:00",
            calendarId = CALENDAR_ID,
            taskBookId = TASK_BOOK_ID,
        )
        val repository = repository()
        repository.open(source)
        val item = repository.state.value.items.single()
        val scope = requireNotNull(repository.state.value.actionScope)

        repository.stageDraft(
            scope,
            SUGGESTION_ID,
            NoteTaskSuggestionEdit(
                title = "只改任务标题（保留秒）",
                description = item.description,
                priority = item.priority,
                due = item.due,
            ),
        )
        repository.saveDraft(scope, SUGGESTION_ID)

        val request = requireNotNull(transport.lastUpdate)
        assertEquals("2026-11-21T11:00:30Z", request.due)
        assertEquals(CALENDAR_ID, request.calendarId)
        assertEquals(TASK_BOOK_ID, request.taskBookId)
    }

    @Test
    fun eventSavePreservesUnmodifiedTimesZoneAndCalendarWhenOnlyTheTitleChanges() = runBlocking {
        transport.item = eventSuggestion()
        val repository = repository()
        repository.open(source)
        val item = repository.state.value.items.single()
        val scope = requireNotNull(repository.state.value.actionScope)

        repository.stageDraft(
            scope,
            SUGGESTION_ID,
            NoteTaskSuggestionEdit(
                title = "只改日程标题",
                description = item.description,
                priority = item.priority,
                due = "",
                start = item.start,
                end = item.end,
                timeZoneId = item.timeZoneId,
            ),
        )
        repository.saveDraft(scope, SUGGESTION_ID)

        val request = requireNotNull(transport.lastUpdate)
        assertEquals("2026-11-21T11:00:00Z", request.start)
        assertEquals("2026-11-21T12:00:00Z", request.end)
        assertEquals("Asia/Shanghai", request.timeZoneId)
        assertEquals(CALENDAR_ID, request.calendarId)
        assertEquals(null, request.due)
        assertEquals(null, request.taskBookId)
    }

    @Test
    fun eventTitleEditPreservesServerSubMinutePrecision() = runBlocking {
        transport.item = eventSuggestion(
            start = "2026-11-21T11:00:30Z",
            end = "2026-11-21T12:00:45Z",
        )
        val repository = repository()
        repository.open(source)
        val item = repository.state.value.items.single()
        val scope = requireNotNull(repository.state.value.actionScope)

        repository.stageDraft(
            scope,
            SUGGESTION_ID,
            NoteTaskSuggestionEdit(
                title = "只改日程标题（保留秒）",
                description = item.description,
                priority = item.priority,
                start = item.start,
                end = item.end,
                timeZoneId = item.timeZoneId,
            ),
        )
        repository.saveDraft(scope, SUGGESTION_ID)

        val request = requireNotNull(transport.lastUpdate)
        assertEquals("2026-11-21T11:00:30Z", request.start)
        assertEquals("2026-11-21T12:00:45Z", request.end)
        assertEquals("Asia/Shanghai", request.timeZoneId)
    }

    @Test
    fun eventSaveRejectsANonIanaZoneBeforeSending() = runBlocking {
        transport.item = eventSuggestion()
        val repository = repository()
        repository.open(source)
        val item = repository.state.value.items.single()
        val scope = requireNotNull(repository.state.value.actionScope)

        repository.stageDraft(
            scope,
            SUGGESTION_ID,
            NoteTaskSuggestionEdit(
                title = item.title,
                description = item.description,
                priority = item.priority,
                due = "",
                start = item.start,
                end = item.end,
                timeZoneId = "China Standard Time",
            ),
        )
        repository.saveDraft(scope, SUGGESTION_ID)

        assertEquals(0, transport.updateCalls)
        assertTrue(repository.state.value.message.orEmpty().contains("IANA"))
        assertEquals("China Standard Time", repository.state.value.items.single().draft?.timeZoneId)
    }

    @Test
    fun eventEditConvertsNewWallClockWithTheDeclaredIanaZone() = runBlocking {
        transport.item = eventSuggestion()
        val repository = repository()
        repository.open(source)
        val item = repository.state.value.items.single()
        val scope = requireNotNull(repository.state.value.actionScope)

        repository.stageDraft(
            scope,
            SUGGESTION_ID,
            NoteTaskSuggestionEdit(
                title = item.title,
                description = item.description,
                priority = item.priority,
                due = "",
                start = "2026-11-22T09:00",
                end = "2026-11-22T10:00",
                timeZoneId = "America/New_York",
            ),
        )
        repository.saveDraft(scope, SUGGESTION_ID)

        val request = requireNotNull(transport.lastUpdate)
        assertEquals("2026-11-22T14:00:00Z", request.start)
        assertEquals("2026-11-22T15:00:00Z", request.end)
        assertEquals("America/New_York", request.timeZoneId)
        assertEquals(CALENDAR_ID, request.calendarId)
    }

    @Test
    fun eventConfirmationReceiptUsesEventIdAndAllowsAMissingTaskId() = runBlocking {
        transport.item = eventSuggestion()
        val repository = repository()
        repository.open(source)
        val scope = requireNotNull(repository.state.value.actionScope)

        repository.confirm(scope, SUGGESTION_ID)

        val confirmed = repository.state.value.items.single().remote
        assertEquals(NoteTaskSuggestionStatusDto.CONFIRMED, confirmed.status)
        assertEquals(EVENT_ID, confirmed.eventId)
        assertEquals(null, confirmed.taskId)
        assertEquals(EVENT_ID, repository.state.value.items.single().remote.eventId)
    }

    @Test
    fun eventConfirmRequiresACompleteRangeBeforeSending() = runBlocking {
        transport.item = eventSuggestion(end = null)
        val repository = repository()
        repository.open(source)
        val scope = requireNotNull(repository.state.value.actionScope)

        assertNotNull(runCatching { repository.confirm(scope, SUGGESTION_ID) }.exceptionOrNull())

        assertEquals(0, transport.confirmCalls)
        assertEquals(null, repository.state.value.items.single().draft)
    }

    @Test
    fun lostEventConfirmReceiptRetriesWithTheSameRequestId() = runBlocking {
        transport.item = eventSuggestion()
        val repository = repository()
        repository.open(source)
        val scope = requireNotNull(repository.state.value.actionScope)
        transport.failConfirmOnce = true

        repository.confirm(scope, SUGGESTION_ID)
        val firstRequestId = requireNotNull(repository.state.value.items.single().draft?.confirmRequestId)
        repository.confirm(scope, SUGGESTION_ID)

        assertEquals(2, transport.confirmCalls)
        assertEquals(listOf(firstRequestId, firstRequestId), transport.confirmRequestIds)
        assertEquals(EVENT_ID, repository.state.value.items.single().remote.eventId)
        assertEquals(null, repository.state.value.items.single().draft)
    }

    @Test
    fun legacyDraftJsonWithoutEventFieldsStillReads() {
        val store = NoteTaskSuggestionStore.at(temp.root)
        store.putDraft(
            source,
            binding,
            NoteTaskSuggestionDraft(
                suggestionId = SUGGESTION_ID,
                expectedRevision = "revision-1",
                title = "旧版本草稿",
                description = "旧版本说明",
                priority = 5,
                due = "2026-11-21T19:00",
                dirty = true,
                updatedAtMillis = 1_000,
            ),
        )
        val file = requireNotNull(
            File(temp.root, "note-task-suggestions").listFiles()
                ?.singleOrNull { it.isFile && it.extension == "json" },
        )
        val record = Json.parseToJsonElement(file.readText()).jsonObject
        val legacyDraft = buildJsonObject {
            record.getValue("drafts").jsonArray.single().jsonObject.forEach { (key, value) ->
                if (key !in setOf("start", "end", "timeZoneId")) put(key, value)
            }
        }
        val legacyRecord = buildJsonObject {
            record.forEach { (key, value) ->
                if (key == "drafts") {
                    put(
                        "drafts",
                        buildJsonArray {
                            add(legacyDraft)
                        },
                    )
                } else {
                    put(key, value)
                }
            }
        }
        writeVerifiedMaterialJson(file, legacyRecord.toString())

        val read = store.read(source, binding).drafts.single()
        assertEquals("旧版本草稿", read.title)
        assertEquals("", read.start)
        assertEquals("", read.end)
        assertEquals("", read.timeZoneId)
    }

    private fun repository() = NoteTaskSuggestionRepository(
        store = NoteTaskSuggestionStore.at(temp.root),
        targetProvider = { target },
        factory = NoteTaskSuggestionTransportFactory { transport },
    )

    private fun suggestion(
        status: NoteTaskSuggestionStatusDto,
        kind: NoteTaskSuggestionKindDto = NoteTaskSuggestionKindDto.TASK,
        selectedSource: NoteTaskSuggestionSource = source,
        due: String? = null,
        calendarId: String? = null,
        taskBookId: String? = null,
        start: String? = null,
        end: String? = null,
        timeZoneId: String? = null,
        taskId: String? = null,
        eventId: String? = null,
    ) = NoteTaskSuggestionDto(
        artifactId = ARTIFACT_ID,
        calendarId = calendarId,
        createdAt = "2026-11-02T00:00:00Z",
        due = due,
        end = end,
        eventId = eventId,
        extractionId = EXTRACTION_ID,
        id = SUGGESTION_ID,
        kind = kind,
        noteId = NOTE_ID,
        ordinal = 0,
        priority = 5,
        revision = "revision-1",
        sourceContentSha256 = selectedSource.contentSha256,
        sourceInputSha256 = selectedSource.inputSha256,
        sourceManifestSha256 = selectedSource.manifestSha256,
        start = start,
        status = status,
        taskBookId = taskBookId,
        taskId = taskId,
        timeZoneId = timeZoneId,
        title = "Server title",
        updatedAt = "2026-11-02T00:00:00Z",
        versionId = selectedSource.versionId,
    )

    private fun eventSuggestion(
        status: NoteTaskSuggestionStatusDto = NoteTaskSuggestionStatusDto.DRAFT,
        start: String? = "2026-11-21T11:00:00Z",
        end: String? = "2026-11-21T12:00:00Z",
        timeZoneId: String? = "Asia/Shanghai",
    ) = suggestion(
        status = status,
        kind = NoteTaskSuggestionKindDto.EVENT,
        calendarId = CALENDAR_ID,
        start = start,
        end = end,
        timeZoneId = timeZoneId,
    )

    private inner class FakeSuggestionTransport : NoteTaskSuggestionTransport {
        var item = suggestion(NoteTaskSuggestionStatusDto.DRAFT)
        var dismissCalls = 0
        var confirmCalls = 0
        var updateCalls = 0
        var lastUpdate: UpdateNoteTaskSuggestionRequest? = null
        val confirmRequestIds = mutableListOf<String>()
        val expectedRevisions = mutableListOf<String>()
        var failConfirmOnce = false
        var failUpdateOnce = false
        var blockVersionId: String? = null
        var verifyEntered: CompletableDeferred<Unit>? = null
        var verifyRelease: CompletableDeferred<Unit>? = null

        override suspend fun verifySource(source: NoteTaskSuggestionSource): NoteTaskSuggestionSourceContent {
            if (blockVersionId == source.versionId) {
                blockVersionId = null
                verifyEntered?.complete(Unit)
                verifyRelease?.await()
            }
            return NoteTaskSuggestionSourceContent(source, "fixed server content")
        }

        override suspend fun destinations() = emptyList<NoteTaskSuggestionDestination>()

        override suspend fun list(source: NoteTaskSuggestionSource) = listOf(
            if (item.versionId == source.versionId) item
            else suggestion(NoteTaskSuggestionStatusDto.DRAFT, selectedSource = source),
        )

        override suspend fun extraction(source: NoteTaskSuggestionSource, id: String): Nothing =
            error("no extraction is persisted in these tests")

        override suspend fun startExtraction(
            request: com.pim.core.contract.StartNoteTaskSuggestionExtractionRequest,
        ): Nothing = error("unexpected extraction request")

        override suspend fun update(
            source: NoteTaskSuggestionSource,
            id: String,
            request: UpdateNoteTaskSuggestionRequest,
        ): NoteTaskSuggestionDto {
            updateCalls++
            expectedRevisions += request.expectedRevision
            lastUpdate = request
            if (failUpdateOnce) {
                failUpdateOnce = false
                throw IOException("simulated lost CAS response")
            }
            item = item.copy(
                calendarId = request.calendarId,
                description = request.description,
                due = request.due,
                end = request.end,
                priority = request.priority,
                revision = "revision-3",
                start = request.start,
                taskBookId = request.taskBookId,
                timeZoneId = request.timeZoneId,
                title = request.title,
            )
            return item
        }

        override suspend fun dismiss(
            source: NoteTaskSuggestionSource,
            item: NoteTaskSuggestionDto,
        ): NoteTaskSuggestionDto {
            dismissCalls++
            return item.copy(status = NoteTaskSuggestionStatusDto.DISMISSED)
        }

        override suspend fun confirm(
            source: NoteTaskSuggestionSource,
            item: NoteTaskSuggestionDto,
            requestId: String,
        ): NoteTaskSuggestionDto {
            confirmCalls++
            confirmRequestIds += requestId
            val confirmed = if (item.kind == NoteTaskSuggestionKindDto.EVENT) {
                item.copy(
                    status = NoteTaskSuggestionStatusDto.CONFIRMED,
                    taskId = null,
                    eventId = EVENT_ID,
                )
            } else {
                item.copy(status = NoteTaskSuggestionStatusDto.CONFIRMED, taskId = TASK_ID)
            }
            if (failConfirmOnce) {
                failConfirmOnce = false
                this.item = confirmed
                throw IOException("simulated lost confirmation receipt")
            }
            this.item = confirmed
            return this.item
        }
    }

    private companion object {
        const val OWNER_ID = "11111111-1111-4111-8111-111111111111"
        const val OTHER_OWNER_ID = "88888888-8888-4888-8888-888888888888"
        const val NOTE_ID = "22222222-2222-4222-8222-222222222222"
        const val MATERIAL_ID = "33333333-3333-4333-8333-333333333333"
        const val ARTIFACT_ID = "44444444-4444-4444-8444-444444444444"
        const val VERSION_ID = "55555555-5555-4555-8555-555555555555"
        const val EXTRACTION_ID = "66666666-6666-4666-8666-666666666666"
        const val SUGGESTION_ID = "77777777-7777-4777-8777-777777777777"
        const val OTHER_VERSION_ID = "99999999-9999-4999-8999-999999999999"
        const val TASK_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val EVENT_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val CALENDAR_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val TASK_BOOK_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
    }
}
