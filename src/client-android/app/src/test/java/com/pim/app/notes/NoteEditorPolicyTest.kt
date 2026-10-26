package com.pim.app.notes

import org.junit.Assert.*
import org.junit.Test

class NoteEditorPolicyTest {
    @Test fun onlyThreeExactOfflineEditorResourcesAreAccessible() {
        assertTrue(NoteEditorPolicy.allowed(NoteEditorPolicy.PAGE))
        assertTrue(NoteEditorPolicy.allowed("${NoteEditorPolicy.ORIGIN}/assets/note-editor/editor.js"))
        assertTrue(NoteEditorPolicy.allowed("${NoteEditorPolicy.ORIGIN}/assets/note-editor/editor.css"))
        for (url in listOf(
            "http://appassets.androidplatform.net/assets/note-editor/index.html",
            "https://example.com/assets/note-editor/index.html",
            "https://appassets.androidplatform.net.evil.test/assets/note-editor/index.html",
            "https://private@appassets.androidplatform.net/assets/note-editor/index.html",
            "${NoteEditorPolicy.ORIGIN}:443/assets/note-editor/index.html",
            "${NoteEditorPolicy.PAGE}?token=secret", "${NoteEditorPolicy.PAGE}#frame",
            "${NoteEditorPolicy.ORIGIN}/assets/note-editor/../private.json",
            "${NoteEditorPolicy.ORIGIN}/assets/note-editor/%69ndex.html",
            "${NoteEditorPolicy.ORIGIN}/assets/private.json", "file:///data/private/note.json", "content://private/note"
        )) assertFalse(url, NoteEditorPolicy.allowed(url))
    }
}
