package com.pim.app.nativework.quicknotes

import com.pim.app.nativework.NativeWorkbenchRequestException
import com.pim.core.contract.QuickNoteAttachmentDto
import com.pim.core.contract.QuickNoteDetailDto
import com.pim.core.contract.QuickNoteDetailDtoApiResponse
import com.pim.core.contract.QuickNoteListItemDtoPagedResult
import com.pim.core.contract.QuickNoteListItemDtoPagedResultApiResponse
import com.pim.core.contract.RestoreQuickNoteRequest
import com.pim.core.contract.StringApiResponse
import com.pim.core.contract.UpdateQuickNoteRequest
import com.pim.core.network.ApiService
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyQuickNoteRepositoryEnvelopeTest {
    @Test
    fun everyOperationRejectsNonzeroCodeEvenWhenDataIsPresent() {
        OPERATIONS.forEach { operation ->
            val calls = mutableListOf<String>()
            val repository = repositoryFor(operation, code = 41, includeData = true, calls = calls)

            val error = assertThrows(NativeWorkbenchRequestException::class.java) {
                runBlocking { invoke(repository, operation) }
            }

            assertTrue("$operation should report the server message", error.message.orEmpty().contains("rejected"))
            assertEquals("Only $operation should be called", listOf(operation), calls)
        }
    }

    @Test
    fun everyOperationRejectsZeroCodeWhenDataIsNull() {
        OPERATIONS.forEach { operation ->
            val calls = mutableListOf<String>()
            val repository = repositoryFor(operation, code = 0, includeData = false, calls = calls)

            assertThrows("$operation must reject an empty success envelope", NativeWorkbenchRequestException::class.java) {
                runBlocking { invoke(repository, operation) }
            }
            assertEquals("Only $operation should be called", listOf(operation), calls)
        }
    }

    @Test
    fun validContractEnvelopesPreserveLegacyLifecycleAndAttachmentIdentity() = runBlocking {
        val calls = mutableListOf<String>()
        var updateRequest: UpdateQuickNoteRequest? = null
        var restoreRequest: RestoreQuickNoteRequest? = null
        val api = fakeApi { method, args ->
            calls += method
            when (method) {
                "getQuickNotes" -> listResponse(0, true)
                "getQuickNote", "createQuickNote", "updateQuickNote",
                "archiveQuickNote", "processQuickNote", "restoreQuickNote" -> {
                    if (method == "updateQuickNote") updateRequest = args[1] as UpdateQuickNoteRequest
                    if (method == "restoreQuickNote") restoreRequest = args[1] as RestoreQuickNoteRequest
                    detailResponse(0, true, if (method == "updateQuickNote") editableNote() else legacyHtmlNote())
                }
                "deleteQuickNote" -> StringApiResponse(0, "deleted", "ok", "now")
                else -> error("Unexpected quick-note operation: $method")
            }
        }
        val repository = LegacyQuickNoteRepository(api)

        repository.load()
        repository.get(legacyHtmlNote().id)
        repository.create("new note")
        repository.update(editableNote(), "edited note")
        repository.archive(legacyHtmlNote())
        repository.process(legacyHtmlNote())
        repository.restore(legacyHtmlNote())
        repository.delete(legacyHtmlNote())

        assertEquals(OPERATIONS, calls)
        assertEquals(listOf("image-attachment"), updateRequest?.attachmentIds)
        assertEquals("edited note", updateRequest?.contentMarkdown)
        assertEquals("inbox", restoreRequest?.status)
        assertTrue(legacyHtmlNote().isLegacyQuickNoteHtml())
    }

    @Test
    fun legacyHtmlUpdateRemainsReadOnlyAndDoesNotSendARequest() = runBlocking {
        var calls = 0
        val repository = LegacyQuickNoteRepository(
            fakeApi { method, _ ->
                calls++
                error("Unexpected API call: $method")
            },
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.update(legacyHtmlNote(), "plain text replacement") }
        }
        assertEquals(0, calls)
    }

    private suspend fun invoke(repository: LegacyQuickNoteRepository, operation: String) {
        when (operation) {
            "getQuickNotes" -> repository.load()
            "getQuickNote" -> repository.get(legacyHtmlNote().id)
            "createQuickNote" -> repository.create("new note")
            "updateQuickNote" -> repository.update(editableNote(), "edited note")
            "archiveQuickNote" -> repository.archive(legacyHtmlNote())
            "processQuickNote" -> repository.process(legacyHtmlNote())
            "restoreQuickNote" -> repository.restore(legacyHtmlNote())
            "deleteQuickNote" -> repository.delete(legacyHtmlNote())
            else -> error("Unknown quick-note operation: $operation")
        }
    }

    private fun repositoryFor(
        expectedOperation: String,
        code: Int,
        includeData: Boolean,
        calls: MutableList<String>,
    ): LegacyQuickNoteRepository = LegacyQuickNoteRepository(
        fakeApi { method, _ ->
            calls += method
            check(method == expectedOperation) { "Expected $expectedOperation, got $method" }
            when (method) {
                "getQuickNotes" -> listResponse(code, includeData)
                "deleteQuickNote" -> StringApiResponse(
                    code = code,
                    data = if (includeData) "deleted" else null,
                    message = MESSAGE,
                    timestamp = "now",
                )
                else -> detailResponse(code, includeData)
            }
        },
    )

    private fun listResponse(code: Int, includeData: Boolean) =
        QuickNoteListItemDtoPagedResultApiResponse(
            code = code,
            data = if (includeData) QuickNoteListItemDtoPagedResult(emptyList(), 1, 50, 0, 0) else null,
            message = MESSAGE,
            timestamp = "now",
        )

    private fun detailResponse(
        code: Int,
        includeData: Boolean,
        note: QuickNoteDetailDto = legacyHtmlNote(),
    ) = QuickNoteDetailDtoApiResponse(
        code = code,
        data = if (includeData) note else null,
        message = MESSAGE,
        timestamp = "now",
    )

    private fun fakeApi(handler: (String, List<Any?>) -> Any): ApiService =
        Proxy.newProxyInstance(
            ApiService::class.java.classLoader,
            arrayOf(ApiService::class.java),
        ) { proxy, method, rawArgs ->
            when (method.name) {
                "toString" -> "LegacyQuickNoteEnvelopeFakeApi"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === rawArgs?.firstOrNull()
                else -> handler(method.name, rawArgs?.dropLast(1).orEmpty())
            }
        } as ApiService

    private fun editableNote() = note().copy(
        attachments = listOf(
            QuickNoteAttachmentDto(
                contentType = "image/png",
                createdAt = "2026-11-01T00:00:00Z",
                downloadUrl = "/attachments/image",
                fileName = "image.png",
                id = "image-attachment",
                sizeBytes = 12,
            ),
        ),
        contentMarkdown = "ordinary note",
        metadataJson = "{}",
        source = "manual",
    )

    private fun legacyHtmlNote() = note().copy(
        attachments = listOf(
            QuickNoteAttachmentDto(
                contentType = "text/html",
                createdAt = "2026-11-01T00:00:00Z",
                downloadUrl = "/attachments/legacy",
                fileName = "legacy.html",
                id = "legacy-attachment",
                sizeBytes = 32,
            ),
            editableNote().attachments.single(),
        ),
        contentMarkdown = "<article>legacy</article>",
        metadataJson = """{"legacyHtml":"<article>legacy</article>","identity":"legacy-id"}""",
        source = "quick-html",
    )

    private fun note() = QuickNoteDetailDto(
        attachments = emptyList(),
        contentMarkdown = "note",
        createdAt = "2026-11-01T00:00:00Z",
        id = "note-1",
        metadataJson = "{}",
        source = "manual",
        status = "inbox",
        updatedAt = "2026-11-01T00:00:00Z",
    )

    private companion object {
        const val MESSAGE = "rejected by server"
        val OPERATIONS = listOf(
            "getQuickNotes",
            "getQuickNote",
            "createQuickNote",
            "updateQuickNote",
            "archiveQuickNote",
            "processQuickNote",
            "restoreQuickNote",
            "deleteQuickNote",
        )
    }
}
