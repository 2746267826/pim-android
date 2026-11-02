package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.contract.NoteTaskSuggestionDto
import com.pim.core.contract.NoteTaskSuggestionStatusDto
import com.pim.core.contract.UpdateNoteTaskSuggestionRequest
import com.pim.core.network.BoundUploadSession
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
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
    private val binding = BoundUploadSession("https://pim.example/api/v1/", OWNER_ID)
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
            title = "Locally edited title",
            description = initial.description,
            priority = initial.priority,
            due = initial.due,
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
            title = "Unsaved title",
            description = initial.description,
            priority = initial.priority,
            due = initial.due,
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
            title = item.title,
            description = item.description,
            priority = item.priority,
            due = suggestionDueInput(transport.item.due),
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
                repository.stageDraft(oldScope, SUGGESTION_ID, "blocked stale edit", "", 5, "")
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
            repository.stageDraft(oldScope, SUGGESTION_ID, "wrong source", "", 5, "")
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
                repository.stageDraft(oldScope, SUGGESTION_ID, "blocked stale owner edit", "", 5, "")
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
                repository.stageDraft(oldScope, SUGGESTION_ID, "wrong owner", "", 5, "")
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
        repository.stageDraft(scope, SUGGESTION_ID, "Edited title", "Edited body", 9, "")
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

    private fun repository() = NoteTaskSuggestionRepository(
        store = NoteTaskSuggestionStore.at(temp.root),
        targetProvider = { target },
        factory = NoteTaskSuggestionTransportFactory { transport },
    )

    private fun suggestion(
        status: NoteTaskSuggestionStatusDto,
        selectedSource: NoteTaskSuggestionSource = source,
    ) = NoteTaskSuggestionDto(
        artifactId = ARTIFACT_ID,
        createdAt = "2026-11-02T00:00:00Z",
        extractionId = EXTRACTION_ID,
        id = SUGGESTION_ID,
        noteId = NOTE_ID,
        ordinal = 0,
        priority = 5,
        revision = "revision-1",
        sourceContentSha256 = selectedSource.contentSha256,
        sourceInputSha256 = selectedSource.inputSha256,
        sourceManifestSha256 = selectedSource.manifestSha256,
        status = status,
        title = "Server title",
        updatedAt = "2026-11-02T00:00:00Z",
        versionId = selectedSource.versionId,
    )

    private inner class FakeSuggestionTransport : NoteTaskSuggestionTransport {
        var item = suggestion(NoteTaskSuggestionStatusDto.DRAFT)
        var dismissCalls = 0
        var confirmCalls = 0
        var updateCalls = 0
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
            if (item.versionId == source.versionId) item else suggestion(NoteTaskSuggestionStatusDto.DRAFT, source),
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
            if (failUpdateOnce) {
                failUpdateOnce = false
                throw IOException("simulated lost CAS response")
            }
            item = item.copy(
                description = request.description,
                priority = request.priority,
                revision = "revision-3",
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
            if (failConfirmOnce) {
                failConfirmOnce = false
                this.item = item.copy(status = NoteTaskSuggestionStatusDto.CONFIRMED, taskId = TASK_ID)
                throw IOException("simulated lost confirmation receipt")
            }
            this.item = item.copy(status = NoteTaskSuggestionStatusDto.CONFIRMED, taskId = TASK_ID)
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
    }
}
