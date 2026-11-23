package com.pim.app.notes

import com.pim.core.contract.NoteProcessingChainDto
import com.pim.core.contract.NoteProcessingChainState
import com.pim.core.contract.NoteProcessingState
import com.pim.core.contract.NoteProcessingStatusDto
import com.pim.core.contract.NoteProcessingTaskKind
import com.pim.core.network.BoundUploadSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

        val entry = repository.refresh(noteId, versionId, target.binding)

        assertEquals(target, observedTarget)
        assertEquals(noteId, observedNoteId)
        assertEquals(versionId, observedVersionId)
        assertEquals(noteId, entry.status?.noteId)
        assertEquals(versionId, entry.status?.versionId)
        assertEquals(42L, entry.observedAtMillis)
        assertNull(entry.problem)
        assertEquals(entry, repository.entry(target.binding, noteId, versionId))
        assertNull(repository.entry(BoundUploadSession(server, otherOwnerId), noteId, versionId))
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

        val entry = repository.refresh(noteId, versionId, target.binding)

        assertNull(entry.status)
        assertTrue(entry.problem.orEmpty().contains("固定版本"))
    }

    @Test
    fun ownerChangeDuringReadDiscardsTheOldOwnersResponse() = runBlocking {
        var current = target
        val other = NoteProcessingTarget(BoundUploadSession(server, otherOwnerId), "bob")
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

        val entry = repository.refresh(noteId, versionId, target.binding)

        assertNull(entry.status)
        assertTrue(entry.problem.orEmpty().contains("账号或服务器"))
        assertNull(repository.entry(other.binding, noteId, versionId))
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
