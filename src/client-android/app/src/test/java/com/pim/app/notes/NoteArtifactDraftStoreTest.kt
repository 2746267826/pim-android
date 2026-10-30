package com.pim.app.notes

import com.pim.app.TestPimApp
import com.pim.core.network.BoundUploadSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteArtifactDraftStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val owner = "11111111-1111-4111-8111-111111111111"
    private val otherOwner = "22222222-2222-4222-8222-222222222222"
    private val binding = BoundUploadSession("https://pim.example", owner)
    private val otherBinding = BoundUploadSession("https://pim.example", otherOwner)
    private val otherServerBinding = BoundUploadSession("https://other.example", owner)
    private val noteId = "33333333-3333-4333-8333-333333333333"
    private val otherNoteId = "55555555-5555-4555-8555-555555555555"
    private val materialVersionId = "44444444-4444-4444-8444-444444444444"
    private val otherMaterialVersionId = "66666666-6666-4666-8666-666666666666"

    private fun store() = NoteArtifactDraftStore.at(temp.root)

    @Test fun scopeAndBindingAreIsolatedAcrossRestartAndArtifactKinds() {
        val full = store().getOrCreate(binding, noteId, materialVersionId, NoteArtifactKind.FullRecord)
        val analysis = store().getOrCreate(binding, noteId, materialVersionId, NoteArtifactKind.Analysis)
        val other = store().getOrCreate(otherBinding, noteId, materialVersionId, NoteArtifactKind.FullRecord)
        val otherServer = store().getOrCreate(otherServerBinding, noteId, materialVersionId, NoteArtifactKind.FullRecord)
        val otherNote = store().getOrCreate(binding, otherNoteId, materialVersionId, NoteArtifactKind.FullRecord)
        val otherMaterial = store().getOrCreate(binding, noteId, otherMaterialVersionId, NoteArtifactKind.FullRecord)

        assertEquals(setOf(full.localDraftId, analysis.localDraftId, otherNote.localDraftId, otherMaterial.localDraftId),
            store().drafts(binding).map { it.localDraftId }.toSet())
        assertEquals(listOf(other.localDraftId), store().drafts(otherBinding).map { it.localDraftId })
        assertEquals(listOf(otherServer.localDraftId), store().drafts(otherServerBinding).map { it.localDraftId })
        assertEquals(full, store().read(binding, full.localDraftId))
        assertEquals(full.localDraftId,
            store().getOrCreate(binding, noteId, materialVersionId, NoteArtifactKind.FullRecord).localDraftId)
        assertThrows(IllegalArgumentException::class.java) {
            store().read(otherBinding, full.localDraftId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().read(otherServerBinding, full.localDraftId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().save(otherBinding, full.localDraftId, 0, "cross-binding write")
        }
    }

    @Test fun explicitSaveCreatesLocalRevisionAndStaleSavePreservesBothTexts() {
        val created = store().getOrCreate(
            binding, noteId, materialVersionId, NoteArtifactKind.FullRecord, initialText = "generated",
        )
        val saved = (store().save(binding, created.localDraftId, 0, "manual one", nowMillis = 2_000)
            as NoteArtifactDraftWriteResult.Written).draft
        assertEquals(1, saved.localRevision)
        assertEquals("manual one", saved.text)
        assertEquals("manual one", saved.savedText)
        assertFalse(saved.dirty)

        val conflict = store().save(binding, created.localDraftId, 0, "stale attempt", nowMillis = 3_000)
            as NoteArtifactDraftWriteResult.Conflict
        assertEquals("manual one", conflict.preserved.current.text)
        assertEquals("stale attempt", conflict.preserved.attemptedText)
        assertEquals("manual one", store().read(binding, created.localDraftId).text)
        assertEquals(listOf("stale attempt"), store().conflicts(binding).map { it.attemptedText })
    }

    @Test fun dirtyTextSurvivesRestartAndRemoteObservationCannotOverwriteIt() {
        val created = store().getOrCreate(binding, noteId, materialVersionId, NoteArtifactKind.FullRecord)
        val dirty = (store().stageText(binding, created.localDraftId, 0, "unfinished manual edit", 2_000)
            as NoteArtifactDraftWriteResult.Written).draft
        assertTrue(dirty.dirty)
        assertEquals(1, dirty.localRevision)
        assertEquals("unfinished manual edit", store().read(binding, created.localDraftId).text)

        val observed = store().observeRemote(
            binding,
            created.localDraftId,
            NoteArtifactRemoteSnapshot(
                text = "new generated candidate",
                baseServerArtifactVersionId = "server/version-opaque-1",
                expectedServerRevision = "server-revision-opaque-7",
                observedAtMillis = 3_000,
            ),
        )
        assertEquals("unfinished manual edit", observed.text)
        assertEquals("", observed.savedText)
        assertEquals("new generated candidate", observed.pendingServer?.text)
        assertTrue(observed.dirty)

        val blocked = store().acceptRemote(binding, created.localDraftId, 1, 4_000)
        assertTrue(blocked is NoteArtifactRemoteAcceptResult.BlockedByDirty)
        assertEquals("unfinished manual edit", store().read(binding, created.localDraftId).text)

        val saved = store().save(binding, created.localDraftId, 1, "finished manual edit", 5_000)
            as NoteArtifactDraftWriteResult.Written
        assertEquals(2, saved.draft.localRevision)
        assertEquals("finished manual edit", saved.draft.savedText)
        assertFalse(saved.draft.dirty)
        assertEquals("new generated candidate", saved.draft.pendingServer?.text)
    }

    @Test fun cleanRemoteCandidateRequiresExplicitAcceptanceAndKeepsOpaqueServerIds() {
        val created = store().getOrCreate(
            binding,
            noteId,
            materialVersionId,
            NoteArtifactKind.Analysis,
            initialText = "old generated result",
            baseServerArtifactVersionId = "old/opaque",
            expectedServerRevision = "rev-old",
        )
        store().observeRemote(
            binding,
            created.localDraftId,
            NoteArtifactRemoteSnapshot(
                text = "new generated result",
                baseServerArtifactVersionId = "opaque server id that is not parsed",
                expectedServerRevision = "revision/v2",
                observedAtMillis = 2_000,
            ),
        )
        assertEquals("old generated result", store().read(binding, created.localDraftId).text)

        val accepted = store().acceptRemote(binding, created.localDraftId, 0, 3_000)
            as NoteArtifactRemoteAcceptResult.Accepted
        assertEquals("new generated result", accepted.draft.text)
        assertEquals("opaque server id that is not parsed", accepted.draft.baseServerArtifactVersionId)
        assertEquals("revision/v2", accepted.draft.expectedServerRevision)
        assertEquals(1, accepted.draft.localRevision)
        assertFalse(accepted.draft.dirty)
        assertNull(accepted.draft.pendingServer)
    }

    @Test fun staleRemoteAcceptanceDoesNotOverwriteTheDraft() {
        val created = store().getOrCreate(binding, noteId, materialVersionId, NoteArtifactKind.FullRecord)
        store().observeRemote(
            binding,
            created.localDraftId,
            NoteArtifactRemoteSnapshot("remote", observedAtMillis = 2_000),
        )
        store().save(binding, created.localDraftId, 0, "newer manual save", 3_000)

        val result = store().acceptRemote(binding, created.localDraftId, 0, 4_000)
        assertTrue(result is NoteArtifactRemoteAcceptResult.StaleRevision)
        assertEquals("newer manual save", store().read(binding, created.localDraftId).text)
    }

    @Test fun twoStoreInstancesSerializeCasAndOnlyOneWriterWins() {
        val created = store().getOrCreate(binding, noteId, materialVersionId, NoteArtifactKind.FullRecord)
        val first = store()
        val second = store()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf(
                executor.submit<NoteArtifactDraftWriteResult> {
                    start.await()
                    first.save(binding, created.localDraftId, 0, "writer one", 2_000)
                },
                executor.submit<NoteArtifactDraftWriteResult> {
                    start.await()
                    second.save(binding, created.localDraftId, 0, "writer two", 2_000)
                },
            )
            start.countDown()
            val results = attempts.map { it.get() }
            assertEquals(1, results.count { it is NoteArtifactDraftWriteResult.Written })
            val conflict = results.single { it is NoteArtifactDraftWriteResult.Conflict }
                as NoteArtifactDraftWriteResult.Conflict
            val winner = results.single { it is NoteArtifactDraftWriteResult.Written }
                as NoteArtifactDraftWriteResult.Written
            assertEquals(winner.draft.text, store().read(binding, created.localDraftId).text)
            assertEquals(winner.draft.text, conflict.preserved.current.text)
            assertTrue(conflict.preserved.attemptedText in setOf("writer one", "writer two"))
            assertEquals(listOf(conflict.preserved.attemptedText),
                store().conflicts(binding).map { it.attemptedText })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun twoStoreInstancesSilentlyOverwriteNewerDirtyTextWhenStageDoesNotAdvanceRevision() {
        val created = store().getOrCreate(binding, noteId, materialVersionId, NoteArtifactKind.FullRecord)
        val first = store()
        val second = store()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = listOf(
                executor.submit<NoteArtifactDraftWriteResult> {
                    start.await()
                    first.stageText(binding, created.localDraftId, 0, "dirty one", 2_000)
                },
                executor.submit<NoteArtifactDraftWriteResult> {
                    start.await()
                    second.stageText(binding, created.localDraftId, 0, "dirty two", 2_000)
                },
            )
            start.countDown()
            val results = attempts.map { it.get() }
            assertEquals(1, results.count { it is NoteArtifactDraftWriteResult.Written })
            val conflict = results.single { it is NoteArtifactDraftWriteResult.Conflict }
                as NoteArtifactDraftWriteResult.Conflict
            val winner = results.single { it is NoteArtifactDraftWriteResult.Written }
                as NoteArtifactDraftWriteResult.Written
            assertEquals(1, winner.draft.localRevision)
            assertTrue(winner.draft.dirty)
            assertEquals(winner.draft.text, store().read(binding, created.localDraftId).text)
            assertEquals(winner.draft.text, conflict.preserved.current.text)
            assertFalse(conflict.preserved.attemptedText == winner.draft.text)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun staleStageExpectedRevisionPreservesNewerDirtyTextAndSameTextIsIdempotent() {
        val created = store().getOrCreate(binding, noteId, materialVersionId, NoteArtifactKind.FullRecord)
        val first = store().stageText(binding, created.localDraftId, 0, "first dirty", 2_000)
            as NoteArtifactDraftWriteResult.Written
        assertEquals(1, first.draft.localRevision)

        val same = store().stageText(binding, created.localDraftId, 1, "first dirty", 3_000)
            as NoteArtifactDraftWriteResult.Written
        assertEquals(1, same.draft.localRevision)
        assertTrue(same.draft.dirty)

        val second = store().stageText(binding, created.localDraftId, 1, "second dirty", 4_000)
            as NoteArtifactDraftWriteResult.Written
        assertEquals(2, second.draft.localRevision)

        val stale = store().stageText(binding, created.localDraftId, 1, "stale overwrite", 5_000)
            as NoteArtifactDraftWriteResult.Conflict
        assertEquals("second dirty", stale.preserved.current.text)
        assertEquals("stale overwrite", stale.preserved.attemptedText)
        assertEquals("second dirty", store().read(binding, created.localDraftId).text)
    }

    @Test fun invalidUriPathInjectionAndEmptyUuidAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundUploadSession("https://pim.example/api", owner)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoundUploadSession("https://user:pass@pim.example", owner)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().getOrCreate(binding, "../outside", materialVersionId, NoteArtifactKind.FullRecord)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().getOrCreate(
                binding,
                "00000000-0000-0000-0000-000000000000",
                materialVersionId,
                NoteArtifactKind.FullRecord,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store().read(binding, "../outside")
        }
    }

    @Test fun damagedCheckpointIsReportedAndNeverReadAsSuccessful() {
        val created = store().getOrCreate(binding, noteId, materialVersionId, NoteArtifactKind.FullRecord)
        val file = File(temp.root, "note-artifact-drafts").walkTopDown()
            .single { it.isFile && it.name == "${created.localDraftId}.json" }
        writeVerifiedMaterialJson(file, "{damaged")

        val state = store().load(binding)
        assertTrue(state.loaded)
        assertTrue(state.loadFailed)
        assertTrue(state.drafts.isEmpty())
        assertThrows(Exception::class.java) {
            store().read(binding, created.localDraftId)
        }
        assertThrows(Exception::class.java) {
            store().getOrCreate(binding, noteId, materialVersionId, NoteArtifactKind.FullRecord)
        }
        assertEquals("{damaged", readMaterialFile(file))
    }
}
