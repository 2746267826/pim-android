package com.pim.app.notes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NotePresentationTest {
    @Test fun optionalOrUnrecognizedCreationMetadataDoesNotCrashTheLibraryOrMutateTheSource() {
        val source = NoteDocument.create()
        listOf(null, JsonPrimitive("unknown"), JsonObject(emptyMap())).forEach { metadata ->
            val document = source.copy(source = JsonObject(source.source - "createdAtMillis" +
                if (metadata != null) mapOf("createdAtMillis" to metadata) else emptyMap()))
            document.validate()
            val before = document.source
            assertEquals("", noteDate(document))
            assertEquals(before, document.source)
        }
    }
    @Test fun validCreationTimeProducesACompactNonEmptyLocalLabel() {
        assertTrue(noteDate(NoteDocument.create()).isNotBlank())
    }
}
