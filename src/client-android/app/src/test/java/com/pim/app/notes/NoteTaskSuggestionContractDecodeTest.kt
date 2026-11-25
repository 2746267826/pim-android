package com.pim.app.notes

import com.pim.core.contract.NoteTaskSuggestionDto
import com.pim.core.contract.NoteTaskSuggestionKindDto
import com.pim.core.contract.NoteTaskSuggestionStatusDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NoteTaskSuggestionContractDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun currentServerShapeDecodesEventFieldsAndIgnoresUnknownFields() {
        val dto = json.decodeFromString<NoteTaskSuggestionDto>(
            """
            {
              "id": "11111111-1111-4111-8111-111111111111",
              "extractionId": "22222222-2222-4222-8222-222222222222",
              "ordinal": 1,
              "noteId": "33333333-3333-4333-8333-333333333333",
              "artifactId": "44444444-4444-4444-8444-444444444444",
              "versionId": "55555555-5555-4555-8555-555555555555",
              "sourceContentSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "sourceInputSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "sourceManifestSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
              "title": "产品评审",
              "kind": "Event",
              "status": "Draft",
              "priority": 5,
              "start": "2026-11-21T11:00:00Z",
              "end": "2026-11-21T12:00:00Z",
              "timeZoneId": "Asia/Shanghai",
              "calendarId": "66666666-6666-4666-8666-666666666666",
              "eventId": null,
              "revision": "77777777-7777-4777-8777-777777777777",
              "createdAt": "2026-11-20T00:00:00Z",
              "updatedAt": "2026-11-20T00:00:00Z",
              "futureField": {"nested": true}
            }
            """.trimIndent(),
        )

        assertEquals(NoteTaskSuggestionKindDto.EVENT, dto.kind)
        assertEquals(NoteTaskSuggestionStatusDto.DRAFT, dto.status)
        assertEquals("2026-11-21T11:00:00Z", dto.start)
        assertEquals("2026-11-21T12:00:00Z", dto.end)
        assertEquals("Asia/Shanghai", dto.timeZoneId)
        assertNull(dto.eventId)
        assertNull(dto.taskId)
    }

    @Test
    fun legacyServerPayloadWithoutKindFailsClosedInsteadOfAssumingTask() {
        val incomplete = """
            {
              "id": "11111111-1111-4111-8111-111111111111",
              "extractionId": "22222222-2222-4222-8222-222222222222",
              "ordinal": 0,
              "noteId": "33333333-3333-4333-8333-333333333333",
              "artifactId": "44444444-4444-4444-8444-444444444444",
              "versionId": "55555555-5555-4555-8555-555555555555",
              "sourceContentSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "sourceInputSha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "sourceManifestSha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
              "title": "旧服务端建议",
              "status": "Draft",
              "priority": 5,
              "revision": "77777777-7777-4777-8777-777777777777",
              "createdAt": "2026-11-20T00:00:00Z",
              "updatedAt": "2026-11-20T00:00:00Z"
            }
        """.trimIndent()

        assertThrows(SerializationException::class.java) {
            json.decodeFromString<NoteTaskSuggestionDto>(incomplete)
        }
    }

    @Test
    fun kindWireValuesMatchTheContract() {
        assertEquals("\"Task\"", json.encodeToString(NoteTaskSuggestionKindDto.TASK))
        assertEquals("\"Event\"", json.encodeToString(NoteTaskSuggestionKindDto.EVENT))
    }
}
