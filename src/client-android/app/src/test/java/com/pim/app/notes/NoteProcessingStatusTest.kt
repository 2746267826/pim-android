package com.pim.app.notes

import com.pim.core.contract.NoteProcessingChainDto
import com.pim.core.contract.NoteProcessingChainState
import com.pim.core.contract.NoteProcessingState
import com.pim.core.contract.NoteProcessingStatusDto
import com.pim.core.contract.NoteProcessingStatusDtoApiResponse
import com.pim.core.contract.NoteProcessingTaskKind
import com.pim.core.network.BoundUploadSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class NoteProcessingStatusTest {
    private val noteId = "11111111-1111-4111-8111-111111111111"
    private val versionId = "22222222-2222-4222-8222-222222222222"
    private val processingId = "33333333-3333-4333-8333-333333333333"
    private val ownerId = "44444444-4444-4444-8444-444444444444"
    private val otherOwnerId = "55555555-5555-4555-8555-555555555555"
    private val server = "https://synthetic.invalid"
    private val target = NoteProcessingTarget(
        BoundUploadSession(server, ownerId),
        "alice",
        generation = 7L,
    )

    @Test
    fun projectionKeepsNoteVersionBindingAndDistinguishesAsrFromDerived() {
        val view = status(
            asr = chain(
                kind = NoteProcessingTaskKind.ASR,
                state = NoteProcessingChainState.RUNNING,
                completed = 2,
                total = 5,
                phase = "transcribing",
            ),
            derived = chain(
                kind = NoteProcessingTaskKind.DERIVED,
                state = NoteProcessingChainState.WAITINGFORASR,
                completed = 0,
                total = 2,
                waitingReason = "等待转写完成",
            ),
            state = NoteProcessingState.PROCESSING,
        ).toNoteProcessingStatusView(noteId, versionId)

        assertEquals(noteId, view.noteId)
        assertEquals(versionId, view.versionId)
        assertEquals("处理中", noteProcessingStateLabel(view.state))
        assertEquals("转写（ASR）：处理中 · 2/5 项 · 阶段 transcribing",
            noteProcessingChainLine("转写（ASR）", view.asr))
        assertEquals("整理（派生）：等待转写完成 · 0/2 项 · 等待转写完成",
            noteProcessingChainLine("整理（派生）", view.derived))
        assertEquals("材料上传回执：整版已确认", noteProcessingMaterialLine("整版已确认"))
    }

    @Test
    fun projectionRejectsWrongNoteVersionOrChainKind() {
        val wrongNote = runCatching {
            status().toNoteProcessingStatusView(
                "99999999-9999-4999-8999-999999999999",
                versionId,
            )
        }.exceptionOrNull()
        assertTrue(wrongNote?.message.orEmpty().contains("noteId"))

        val wrongVersion = runCatching {
            status().toNoteProcessingStatusView(
                noteId,
                "99999999-9999-4999-8999-999999999999",
            )
        }.exceptionOrNull()
        assertTrue(wrongVersion?.message.orEmpty().contains("versionId"))

        val wrongKind = runCatching {
            status(asr = chain(
                kind = NoteProcessingTaskKind.DERIVED,
                state = NoteProcessingChainState.QUEUED,
            )).toNoteProcessingStatusView(noteId, versionId)
        }.exceptionOrNull()
        assertTrue(wrongKind?.message.orEmpty().contains("ASR"))

        val wrongDerivedKind = runCatching {
            status(derived = chain(
                kind = NoteProcessingTaskKind.ASR,
                state = NoteProcessingChainState.QUEUED,
            )).toNoteProcessingStatusView(noteId, versionId)
        }.exceptionOrNull()
        assertTrue(wrongDerivedKind?.message.orEmpty().contains("DERIVED"))
    }

    @Test
    fun contractJsonDecodesTheActualAsrAndDerivedWireFields() {
        val json = """
            {
              "artifacts": [],
              "asr": {
                "completedItems": 2,
                "errors": ["synthetic ASR warning"],
                "failedItems": 1,
                "hasResult": false,
                "kind": "Asr",
                "phase": "transcribing",
                "revision": null,
                "state": "Running",
                "taskCreated": true,
                "taskId": "$processingId",
                "totalItems": 5,
                "waitingReason": null
              },
              "autoProcessingEnabled": true,
              "autoStarted": true,
              "createdAt": "2026-11-23T00:00:00Z",
              "derived": {
                "completedItems": 0,
                "errors": [],
                "failedItems": 0,
                "hasResult": false,
                "kind": "Derived",
                "phase": null,
                "revision": null,
                "state": "WaitingForAsr",
                "taskCreated": false,
                "taskId": null,
                "totalItems": 2,
                "waitingReason": "等待转写完成"
              },
              "incrementalScopeSupported": true,
              "issues": [
                {"code": "asr-retry", "message": "synthetic retry", "task": "Asr"}
              ],
              "manualStartAvailable": true,
              "noteId": "$noteId",
              "pendingReason": null,
              "processingId": "$processingId",
              "scope": "incremental",
              "state": "Processing",
              "updatedAt": "2026-11-23T00:01:00Z",
              "versionId": "$versionId"
            }
        """.trimIndent()

        val view = NoteJson.decodeFromString<NoteProcessingStatusDto>(json)
            .toNoteProcessingStatusView(noteId, versionId)

        assertEquals(NoteProcessingState.PROCESSING, view.state)
        assertEquals(NoteProcessingTaskKind.ASR, view.asr.kind)
        assertEquals(NoteProcessingChainState.RUNNING, view.asr.state)
        assertEquals(NoteProcessingTaskKind.DERIVED, view.derived.kind)
        assertEquals(NoteProcessingChainState.WAITINGFORASR, view.derived.state)
        assertEquals("asr-retry", view.issues.single().code)
        assertEquals("转写（ASR）：处理中 · 2/5 项 · 失败 1 项 · synthetic ASR warning",
            noteProcessingChainLine("转写（ASR）", view.asr))
    }

    @Test
    fun refreshUsesPinnedOwnerNoteAndVersionAndPublishesOnlyMatchingStatus() = runBlocking {
        var observedTarget: NoteProcessingTarget? = null
        var observedNoteId: String? = null
        var observedVersionId: String? = null
        val repository = NoteProcessingRepository(
            targetProvider = { target },
            transportFactory = { selected ->
                observedTarget = selected
                NoteProcessingTransport { requestedNoteId, requestedVersionId ->
                    observedNoteId = requestedNoteId
                    observedVersionId = requestedVersionId
                    status()
                }
            },
            nowMillis = { 42L },
        )

        val entry = repository.refresh(noteId, versionId, target.binding, target.generation)

        assertEquals(target, observedTarget)
        assertEquals(noteId, observedNoteId)
        assertEquals(versionId, observedVersionId)
        assertEquals(noteId, entry.status?.noteId)
        assertEquals(versionId, entry.status?.versionId)
        assertEquals(42L, entry.observedAtMillis)
        assertNull(entry.problem)
        assertEquals(entry, repository.entry(target.binding, noteId, versionId, target.generation))
        assertNull(repository.entry(BoundUploadSession(server, otherOwnerId), noteId, versionId, target.generation))
        assertNull(repository.entry(BoundUploadSession("https://other.invalid", ownerId), noteId, versionId, target.generation))
    }

    @Test
    fun responseForAnotherVersionIsRejectedWithoutPublishingIt() = runBlocking {
        val repository = NoteProcessingRepository(
            targetProvider = { target },
            transportFactory = {
                NoteProcessingTransport { _, _ ->
                    status(versionId = "99999999-9999-4999-8999-999999999999")
                }
            },
            nowMillis = { 42L },
        )

        val entry = repository.refresh(noteId, versionId, target.binding, target.generation)

        assertNull(entry.status)
        assertTrue(entry.problem.orEmpty().contains("固定版本"))
    }

    @Test
    fun ownerChangeDuringReadDiscardsTheOldOwnersResponse() = runBlocking {
        var current = target
        val other = NoteProcessingTarget(BoundUploadSession(server, otherOwnerId), "bob", generation = 8L)
        val repository = NoteProcessingRepository(
            targetProvider = { current },
            transportFactory = {
                NoteProcessingTransport { _, _ ->
                    current = other
                    status()
                }
            },
            nowMillis = { 42L },
        )

        val entry = repository.refresh(noteId, versionId, target.binding, target.generation)

        assertNull(entry.status)
        assertTrue(entry.problem.orEmpty().contains("账号或服务器"))
        assertNull(repository.entry(other.binding, noteId, versionId, other.generation))
    }

    @Test
    fun sameBindingWithANewSessionGenerationDiscardsTheOldResponse() = runBlocking {
        var current = target
        val repository = NoteProcessingRepository(
            targetProvider = { current },
            transportFactory = {
                NoteProcessingTransport { _, _ ->
                    current = target.copy(generation = target.generation + 1)
                    status()
                }
            },
            nowMillis = { 42L },
        )

        val entry = repository.refresh(noteId, versionId, target.binding, target.generation)

        assertNull(entry.status)
        assertTrue(entry.problem.orEmpty().contains("账号或服务器"))
        assertNull(repository.entry(target.binding, noteId, versionId, target.generation + 1))
    }

    @Test
    fun serverErrorIsVisibleAndKeepsTheLastPublishedStatus() = runBlocking {
        var fail = false
        val repository = NoteProcessingRepository(
            targetProvider = { target },
            transportFactory = {
                NoteProcessingTransport { _, _ ->
                    if (!fail) status()
                    else throw HttpException(
                        Response.error<Any>(
                            503,
                            "".toResponseBody("application/json".toMediaType()),
                        ),
                    )
                }
            },
            nowMillis = { 42L },
        )

        repository.refresh(noteId, versionId, target.binding, target.generation)
        fail = true
        val failed = repository.refresh(noteId, versionId, target.binding, target.generation)

        assertNotNull(failed.status)
        assertEquals(NoteProcessingState.PARTIALLYCOMPLETED, failed.status?.state)
        assertTrue(failed.problem.orEmpty().contains("HTTP 503"))
        assertTrue(failed.problem.orEmpty().contains("上次状态保留"))
    }

    @Test
    fun serverEnvelopeErrorIsVisibleWithoutPublishingPartialData() = runBlocking {
        val repository = NoteProcessingRepository(
            targetProvider = { target },
            transportFactory = {
                NoteProcessingTransport { _, _ ->
                    throw NoteProcessingServerException("synthetic processing rejection")
                }
            },
            nowMillis = { 42L },
        )

        val entry = repository.refresh(noteId, versionId, target.binding, target.generation)

        assertNull(entry.status)
        assertTrue(entry.problem.orEmpty().contains("synthetic processing rejection"))
    }

    @Test
    fun publishingOneSessionNeverRemovesAnotherSessionsCachedStatus() = runBlocking {
        val bob = NoteProcessingTarget(
            BoundUploadSession(server, otherOwnerId),
            "bob",
            generation = 9L,
        )
        var current: NoteProcessingTarget? = bob
        var failing = false
        var onRead: (() -> Unit)? = null
        val repository = NoteProcessingRepository(
            targetProvider = { current },
            transportFactory = {
                NoteProcessingTransport { _, _ ->
                    onRead?.invoke()
                    if (failing) {
                        throw HttpException(
                            Response.error<Any>(
                                503,
                                "".toResponseBody("application/json".toMediaType()),
                            ),
                        )
                    }
                    status()
                }
            },
            nowMillis = { 42L },
        )

        val bobEntry = repository.refresh(noteId, versionId, bob.binding, bob.generation)
        assertNotNull(bobEntry.status)

        current = target
        val aliceEntry = repository.refresh(noteId, versionId, target.binding, target.generation)
        assertNotNull(aliceEntry.status)
        assertEquals(bobEntry, repository.entry(bob.binding, noteId, versionId, bob.generation))

        failing = true
        val failed = repository.refresh(noteId, versionId, target.binding, target.generation)
        assertNotNull(failed.status)
        assertTrue(failed.problem.orEmpty().contains("HTTP 503"))
        assertEquals(bobEntry, repository.entry(bob.binding, noteId, versionId, bob.generation))

        failing = false
        current = target
        onRead = { current = bob }
        val late = repository.refresh(noteId, versionId, target.binding, target.generation)
        assertNotNull(late.status)
        assertTrue(late.problem.orEmpty().contains("账号或服务器"))
        assertEquals(bobEntry, repository.entry(bob.binding, noteId, versionId, bob.generation))

        onRead = null
        current = bob
        val stale = repository.refresh(noteId, versionId, target.binding, target.generation)
        assertNotNull(stale.problem)
        assertEquals(bobEntry, repository.entry(bob.binding, noteId, versionId, bob.generation))
    }

    @Test
    fun temporarilyUnreadableTargetKeepsLastStatusAndShowsProblem() = runBlocking {
        var current: NoteProcessingTarget? = target
        val repository = NoteProcessingRepository(
            targetProvider = { current },
            transportFactory = {
                NoteProcessingTransport { _, _ -> status() }
            },
            nowMillis = { 42L },
        )

        val cached = repository.refresh(noteId, versionId, target.binding, target.generation)
        current = null
        val unreadable = repository.refresh(noteId, versionId, target.binding, target.generation)

        assertEquals(cached.status, unreadable.status)
        assertTrue(unreadable.problem.orEmpty().contains("暂时无法确认"))
        assertFalse(unreadable.syncing)
    }

    @Test
    fun realResponseWrapperKeepsLastStatusOnBusinessErrorOrMissingData() = runBlocking {
        val ok = NoteJson.decodeFromString<NoteProcessingStatusDtoApiResponse>(
            """
                {
                  "code": 0,
                  "data": ${NoteJson.encodeToString(status())},
                  "message": "ok",
                  "timestamp": "2026-11-23T00:02:00Z"
                }
            """.trimIndent(),
        )
        var response = ok
        val repository = NoteProcessingRepository(
            targetProvider = { target },
            transportFactory = {
                noteProcessingStatusTransport { _, _ -> response }
            },
            nowMillis = { 42L },
        )

        val cached = repository.refresh(noteId, versionId, target.binding, target.generation)
        assertEquals(NoteProcessingState.PARTIALLYCOMPLETED, cached.status?.state)

        response = NoteProcessingStatusDtoApiResponse(
            code = 4001,
            data = status(state = NoteProcessingState.COMPLETED),
            message = "synthetic business rejection",
            timestamp = "2026-11-23T00:03:00Z",
        )
        val rejected = repository.refresh(noteId, versionId, target.binding, target.generation)
        assertEquals(cached.status, rejected.status)
        assertTrue(rejected.problem.orEmpty().contains("synthetic business rejection"))

        response = NoteProcessingStatusDtoApiResponse(
            code = 0,
            data = null,
            message = "synthetic empty data",
            timestamp = "2026-11-23T00:04:00Z",
        )
        val empty = repository.refresh(noteId, versionId, target.binding, target.generation)
        assertEquals(cached.status, empty.status)
        assertTrue(empty.problem.orEmpty().contains("synthetic empty data"))
    }

    @Test
    fun wrongDerivedKindKeepsLastSuccessfulStatus() = runBlocking {
        var derivedKind = NoteProcessingTaskKind.DERIVED
        val repository = NoteProcessingRepository(
            targetProvider = { target },
            transportFactory = {
                NoteProcessingTransport { _, _ ->
                    status(derived = chain(kind = derivedKind, state = NoteProcessingChainState.QUEUED))
                }
            },
            nowMillis = { 42L },
        )

        val cached = repository.refresh(noteId, versionId, target.binding, target.generation)
        derivedKind = NoteProcessingTaskKind.ASR
        val rejected = repository.refresh(noteId, versionId, target.binding, target.generation)

        assertEquals(cached.status, rejected.status)
        assertTrue(rejected.problem.orEmpty().contains("不匹配"))
    }

    @Test
    fun incompleteOlderSchemaFailsClosedWithoutReplacingStatus() = runBlocking {
        var oldSchema = false
        val repository = NoteProcessingRepository(
            targetProvider = { target },
            transportFactory = {
                noteProcessingStatusTransport { _, _ ->
                    if (oldSchema) {
                        NoteJson.decodeFromString<NoteProcessingStatusDtoApiResponse>(
                            """
                                {
                                  "code": 0,
                                  "data": {"noteId": "$noteId", "versionId": "$versionId"},
                                  "message": "ok",
                                  "timestamp": "2026-11-23T00:03:00Z"
                                }
                            """.trimIndent(),
                        )
                    } else {
                        NoteProcessingStatusDtoApiResponse(
                            code = 0,
                            data = status(),
                            message = "ok",
                            timestamp = "2026-11-23T00:02:00Z",
                        )
                    }
                }
            },
            nowMillis = { 42L },
        )

        val cached = repository.refresh(noteId, versionId, target.binding, target.generation)
        oldSchema = true
        val rejected = repository.refresh(noteId, versionId, target.binding, target.generation)

        assertEquals(cached.status, rejected.status)
        assertTrue(rejected.problem.orEmpty().contains("格式不完整"))
    }

    @Test
    fun explicitCleanupDropsOnlySupersededGenerations() = runBlocking {
        val bob = NoteProcessingTarget(
            BoundUploadSession(server, otherOwnerId),
            "bob",
            generation = 7L,
        )
        var current: NoteProcessingTarget? = bob
        val repository = NoteProcessingRepository(
            targetProvider = { current },
            transportFactory = {
                NoteProcessingTransport { _, _ -> status() }
            },
            nowMillis = { 42L },
        )

        repository.refresh(noteId, versionId, bob.binding, bob.generation)
        current = bob.copy(generation = 8L)
        repository.refresh(noteId, versionId, bob.binding, bob.generation)
        current = target
        repository.refresh(noteId, versionId, target.binding, target.generation)

        assertNotNull(repository.entry(bob.binding, noteId, versionId, 7L))
        assertNotNull(repository.entry(bob.binding, noteId, versionId, 8L))
        assertNotNull(repository.entry(target.binding, noteId, versionId, target.generation))

        repository.discardSupersededGenerations(bob.binding, 8L)

        assertNull(repository.entry(bob.binding, noteId, versionId, 7L))
        assertNotNull(repository.entry(bob.binding, noteId, versionId, 8L))
        assertNotNull(repository.entry(target.binding, noteId, versionId, target.generation))
    }

    private fun status(
        noteId: String = this.noteId,
        versionId: String = this.versionId,
        asr: NoteProcessingChainDto = chain(
            kind = NoteProcessingTaskKind.ASR,
            state = NoteProcessingChainState.SUCCEEDED,
            completed = 4,
            total = 4,
            hasResult = true,
        ),
        derived: NoteProcessingChainDto = chain(
            kind = NoteProcessingTaskKind.DERIVED,
            state = NoteProcessingChainState.QUEUED,
            completed = 0,
            total = 2,
        ),
        state: NoteProcessingState = NoteProcessingState.PARTIALLYCOMPLETED,
    ) = NoteProcessingStatusDto(
        artifacts = emptyList(),
        asr = asr,
        autoProcessingEnabled = true,
        autoStarted = true,
        createdAt = "2026-11-23T00:00:00Z",
        derived = derived,
        incrementalScopeSupported = true,
        issues = emptyList(),
        manualStartAvailable = true,
        noteId = noteId,
        pendingReason = null,
        processingId = processingId,
        scope = "incremental",
        state = state,
        updatedAt = "2026-11-23T00:01:00Z",
        versionId = versionId,
    )

    private fun chain(
        kind: NoteProcessingTaskKind,
        state: NoteProcessingChainState,
        completed: Int = 0,
        total: Int = 0,
        hasResult: Boolean = false,
        phase: String? = null,
        waitingReason: String? = null,
    ) = NoteProcessingChainDto(
        completedItems = completed,
        errors = emptyList(),
        failedItems = 0,
        hasResult = hasResult,
        kind = kind,
        phase = phase,
        revision = null,
        state = state,
        taskCreated = true,
        taskId = processingId,
        totalItems = total,
        waitingReason = waitingReason,
    )
}
