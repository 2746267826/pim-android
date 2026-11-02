package com.pim.app.notes

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.pim.app.nativework.NativeWorkbenchSession
import com.pim.app.nativework.NativeWorkbenchTransport
import com.pim.core.contract.QuickNoteAttachmentDto
import com.pim.core.contract.QuickNoteDetailDto
import com.pim.core.contract.QuickNoteDetailDtoApiResponse
import com.pim.core.contract.QuickNoteListItemDto
import com.pim.core.contract.QuickNoteListItemDtoPagedResult
import com.pim.core.contract.QuickNoteListItemDtoPagedResultApiResponse
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import com.pim.core.network.UploadSessionChangedException
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.resumeWith
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.pim.app.TestPimApp::class)
class LegacyQuickNoteMigrationTest {
    @get:Rule val temp = TemporaryFolder()

    private val owner = "11111111-1111-4111-8111-111111111111"
    private val legacyId = "legacy-note-17"
    private val binding = BoundUploadSession("https://pim.example", owner)

    @Test
    fun migrationIsStableIdempotentAndKeepsRawHtmlAndAttachmentReferencesOnly() = runBlocking {
        val root = temp.newFolder()
        val notes = repository(root)
        notes.initialize()
        val links = MemoryLinks()
        val identity = LegacyQuickNoteMigrationIdentity(binding, legacyId)
        val detail = detail()

        val first = notes.importLegacyQuickNote(identity, detail, links)
        val second = notes.importLegacyQuickNote(identity, detail, links)

        assertEquals(identity.noteId, first.id)
        assertEquals(first, second)
        assertEquals(1, links.values.size)
        assertEquals("<h1>原始 HTML</h1>", first.blocks.first().string("rawContentMarkdown"))
        assertEquals("legacyHtml", first.blocks.first().string("rawMetadataJson").let { "legacyHtml" })
        assertEquals("", first.blocks.last().string("text"))
        val attachment = first.blocks.first()["attachments"]!!.jsonArray.single().jsonObject
        assertEquals("attachment-1", attachment["id"]!!.jsonPrimitive.content)
        assertFalse(first.source.toString().contains("https://download.example/secret"))

        assertTrue(links.remove(binding, legacyId))
        val retried = notes.importLegacyQuickNote(identity, detail, links)
        assertEquals(first.id, retried.id)
        assertEquals(first.source, retried.source)
        assertEquals(1, links.values.size)
    }

    @Test
    fun corruptStableTargetIsNeverTreatedAsMissingOrOverwritten() = runBlocking {
        val root = temp.newFolder()
        val identity = LegacyQuickNoteMigrationIdentity(binding, legacyId)
        val manifest = File(root, "notes/${identity.noteId}/note.json").apply {
            parentFile!!.mkdirs()
            writeText("damaged original note bytes")
        }
        val original = manifest.readBytes()
        val links = MemoryLinks()
        val notes = repository(root)
        notes.initialize()

        val result = runCatching { notes.importLegacyQuickNote(identity, detail(), links) }

        assertTrue("existing damaged note must fail closed", result.isFailure)
        assertTrue(original.contentEquals(manifest.readBytes()))
        assertNull(links.find(identity))
    }

    @Test
    fun unreadableStableManifestDoesNotTreatReadFailureAsMissing() = runBlocking {
        val root = temp.newFolder()
        val identity = LegacyQuickNoteMigrationIdentity(binding, legacyId)
        val sentinel = File(root, "notes/${identity.noteId}/note.json/original.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(7, 3, 1, 9))
        }
        val original = sentinel.readBytes()
        val links = MemoryLinks()
        val notes = repository(root)
        notes.initialize()

        val result = runCatching { notes.importLegacyQuickNote(identity, detail(), links) }

        assertTrue("manifest read error must fail closed", result.isFailure)
        assertTrue(original.contentEquals(sentinel.readBytes()))
        assertNull(links.find(identity))
    }

    @Test
    fun orphanedStableTargetDirectoryIsPreservedAndNeverReplaced() = runBlocking {
        val root = temp.newFolder()
        val identity = LegacyQuickNoteMigrationIdentity(binding, legacyId)
        val orphan = File(root, "notes/${identity.noteId}/assets/original.bin").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(4, 2, 9))
        }
        val original = orphan.readBytes()
        val links = MemoryLinks()
        val notes = repository(root)
        notes.initialize()

        val result = runCatching { notes.importLegacyQuickNote(identity, detail(), links) }

        assertTrue("existing target without a manifest must fail closed", result.isFailure)
        assertTrue(original.contentEquals(orphan.readBytes()))
        assertFalse(File(orphan.parentFile!!.parentFile, "note.json").exists())
        assertNull(links.find(identity))
    }

    @Test
    fun persistentMappingWriteFailureCanBeRetriedWithoutDuplicatingTheCopy() = runBlocking {
        val root = temp.newFolder()
        val notes = repository(root)
        notes.initialize()
        val identity = LegacyQuickNoteMigrationIdentity(binding, legacyId)
        val linksRoot = File(root, "links").apply { writeText("blocks directory creation") }
        val links = LegacyQuickNoteMigrationStore(linksRoot)

        assertTrue(runCatching { notes.importLegacyQuickNote(identity, detail(), links) }.isFailure)
        val savedAfterFailure = NoteFileStore(File(root, "notes")).read(identity.noteId)
        assertTrue(links.find(identity) == null)
        assertTrue(linksRoot.delete())

        val retry = notes.importLegacyQuickNote(identity, detail(), links)

        assertEquals(savedAfterFailure.source, retry.source)
        assertEquals(identity.noteId, links.find(identity)?.noteId)
    }

    @Test
    fun changedPreviewMustBeReviewedAgainBeforeLocalCopyIsCreated() = runTest {
        val root = temp.newFolder()
        val notes = repository(root)
        val initial = detail(content = "old body")
        val changed = detail(content = "new body")
        val api = migrationApi(initial, changed, changed)
        val session = NativeWorkbenchSession(binding, 1)
        val transport = MutableTransport(session, api)
        val links = LegacyQuickNoteMigrationStore(File(root, "links"))
        val repository = LegacyQuickNoteMigrationRepository(transport, notes, links)
        notes.initialize()

        repository.openForSession(session)
        repository.select(legacyId)
        assertTrue(runCatching { repository.confirm() }.isFailure)
        assertEquals(changed, repository.state.value.selected)
        assertTrue(repository.state.value.error.orEmpty().contains("已变化"))
        assertTrue(repository.state.value.links.isEmpty())

        val id = repository.confirm()
        assertEquals(LegacyQuickNoteMigrationIdentity(binding, legacyId).noteId, id)
        assertEquals(id, links.find(LegacyQuickNoteMigrationIdentity(binding, legacyId))?.noteId)
    }

    @Test
    fun staleRevokeConfirmationCannotRemoveSameLegacyIdForAnotherOwnerOrServer() = runTest {
        val root = temp.newFolder()
        val notes = repository(root)
        notes.initialize()
        val first = NativeWorkbenchSession(binding, 1)
        val secondBinding = BoundUploadSession("https://other.example", "22222222-2222-4222-8222-222222222222")
        val second = NativeWorkbenchSession(secondBinding, 2)
        val links = LegacyQuickNoteMigrationStore(File(root, "links"))
        val firstIdentity = LegacyQuickNoteMigrationIdentity(binding, legacyId)
        val secondIdentity = LegacyQuickNoteMigrationIdentity(secondBinding, legacyId)
        links.put(LegacyQuickNoteMigrationLink(
            serverIdentity = binding.serverIdentity,
            ownerId = owner,
            legacyNoteId = legacyId,
            noteId = firstIdentity.noteId,
            createdAtMillis = 1,
        ))
        links.put(LegacyQuickNoteMigrationLink(
            serverIdentity = secondBinding.serverIdentity,
            ownerId = secondBinding.ownerId,
            legacyNoteId = legacyId,
            noteId = secondIdentity.noteId,
            createdAtMillis = 2,
        ))
        val transport = MutableTransport(first, migrationApi(detail()))
        val repository = LegacyQuickNoteMigrationRepository(transport, notes, links)
        repository.openForSession(first)

        transport.current = second
        repository.openForSession(second)
        val result = runCatching { repository.revokeLink(legacyId, first) }

        assertTrue("confirmation bound to old session must fail", result.isFailure)
        assertEquals(firstIdentity.noteId, links.find(firstIdentity)?.noteId)
        assertEquals(secondIdentity.noteId, links.find(secondIdentity)?.noteId)
    }

    @Test
    fun lateConfirmFailureAfterAccountSwitchCannotReplaceTheNewPageState() = runTest {
        val root = temp.newFolder()
        val notes = repository(root)
        notes.initialize()
        val first = NativeWorkbenchSession(binding, 1)
        val second = NativeWorkbenchSession(BoundUploadSession("https://other.example", owner), 2)
        val started = CompletableDeferred<Unit>()
        var detailCalls = 0
        lateinit var pending: Continuation<Any?>
        val api = apiProxy { method, args ->
            when (method) {
                "getQuickNotes" -> listResponse()
                "getQuickNote" -> if (++detailCalls == 1) detailResponse(detail()) else {
                    pending = args!!.last() as Continuation<Any?>
                    started.complete(Unit)
                    COROUTINE_SUSPENDED
                }
                else -> error("Unexpected API call: $method")
            }
        }
        val transport = MutableTransport(first, api)
        val repository = LegacyQuickNoteMigrationRepository(transport, notes, LegacyQuickNoteMigrationStore(File(root, "links")))
        repository.openForSession(first)
        repository.select(legacyId)

        val confirming = async { runCatching { repository.confirm() } }
        started.await()
        transport.current = second
        repository.openForSession(second)
        pending.resumeWith(Result.failure(IOException("late old-session failure")))
        confirming.await()

        assertEquals(second, repository.state.value.session)
        assertTrue(repository.state.value.visible)
        assertFalse(repository.state.value.loading)
        assertFalse(repository.state.value.confirming)
        assertNull(repository.state.value.selected)
        assertNull(repository.state.value.error)
    }

    @Test
    fun lateConfirmCancellationAfterCloseCannotRestoreOldConfirmationState() = runTest {
        val root = temp.newFolder()
        val notes = repository(root)
        notes.initialize()
        val session = NativeWorkbenchSession(binding, 1)
        val started = CompletableDeferred<Unit>()
        lateinit var pending: Continuation<Any?>
        var detailCalls = 0
        val api = apiProxy { method, args ->
            when (method) {
                "getQuickNotes" -> listResponse()
                "getQuickNote" -> if (++detailCalls == 1) detailResponse(detail()) else {
                    pending = args!!.last() as Continuation<Any?>
                    started.complete(Unit)
                    COROUTINE_SUSPENDED
                }
                else -> error("Unexpected API call: $method")
            }
        }
        val repository = LegacyQuickNoteMigrationRepository(
            MutableTransport(session, api), notes, LegacyQuickNoteMigrationStore(File(root, "links")),
        )
        repository.openForSession(session)
        repository.select(legacyId)

        val confirming = async { runCatching { repository.confirm() } }
        started.await()
        repository.close()
        pending.resumeWith(Result.failure(CancellationException("late cancellation")))
        confirming.await()

        assertEquals(LegacyQuickNoteMigrationState(), repository.state.value)
    }

    private fun repository(root: File): NoteRepository {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scoped = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = root
        }
        return NoteRepository(scoped)
    }

    private fun detail(content: String = "<h1>原始 HTML</h1>") = QuickNoteDetailDto(
        archivedAt = null,
        attachments = listOf(
            QuickNoteAttachmentDto(
                contentType = "image/png", createdAt = "2026-11-02T00:00:00Z",
                downloadUrl = "https://download.example/secret", fileName = "photo.png",
                id = "attachment-1", previewUrl = "https://download.example/preview-secret", sizeBytes = 123,
            ),
        ),
        contentMarkdown = content,
        createdAt = "2026-11-01T00:00:00Z",
        id = legacyId,
        metadataJson = "{\"legacyHtml\":true}",
        source = "quick-html",
        status = "inbox",
        updatedAt = "2026-11-01T00:00:00Z",
    )

    private fun migrationApi(vararg details: QuickNoteDetailDto): ApiService {
        var index = 0
        return apiProxy { method, _ ->
            when (method) {
                "getQuickNotes" -> listResponse()
                "getQuickNote" -> detailResponse(details[index.coerceAtMost(details.lastIndex)]).also {
                    if (index < details.lastIndex) index++
                }
                else -> error("Unexpected API call: $method")
            }
        }
    }

    private fun listResponse() = QuickNoteListItemDtoPagedResultApiResponse(
        code = 0,
        data = QuickNoteListItemDtoPagedResult(
            items = listOf(
                QuickNoteListItemDto(
                    archivedAt = null, attachmentCount = 1, attachments = null,
                    contentPreview = "legacy preview", createdAt = "2026-11-01T00:00:00Z",
                    id = legacyId, source = "quick-html", status = "inbox", updatedAt = "2026-11-01T00:00:00Z",
                ),
            ),
            page = 1, pageSize = 50, totalCount = 1, totalPages = 1,
        ),
        message = "ok", timestamp = "now",
    )

    private fun detailResponse(value: QuickNoteDetailDto) =
        QuickNoteDetailDtoApiResponse(0, value, "ok", "now")

    private fun apiProxy(handler: (String, Array<out Any?>?) -> Any?): ApiService =
        Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            handler(method.name, args)
        } as ApiService

    private class MutableTransport(
        var current: NativeWorkbenchSession,
        private val api: ApiService,
    ) : NativeWorkbenchTransport {
        override fun currentSession() = current
        override fun isCurrent(session: NativeWorkbenchSession) = current == session
        override fun apiFor(session: NativeWorkbenchSession): ApiService {
            if (!isCurrent(session)) throw UploadSessionChangedException()
            return api
        }
    }

    private class MemoryLinks(var failNextPut: Boolean = false) : LegacyQuickNoteMigrationLinks {
        val values = linkedMapOf<String, LegacyQuickNoteMigrationLink>()
        override fun list(binding: BoundUploadSession) = values.values.filter {
            it.serverIdentity == binding.serverIdentity && it.ownerId == binding.ownerId
        }
        override fun put(link: LegacyQuickNoteMigrationLink) {
            if (failNextPut) {
                failNextPut = false
                throw IOException("simulated mapping storage failure")
            }
            values[link.migrationKey] = link
        }
        override fun remove(binding: BoundUploadSession, legacyNoteId: String): Boolean =
            values.remove("${binding.serverIdentity}\u0000${binding.ownerId}\u0000$legacyNoteId") != null
        override fun find(identity: LegacyQuickNoteMigrationIdentity) = values[identity.migrationKey]
    }
}
