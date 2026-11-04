package com.pim.app.notes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pim.app.TestPimApp
import com.pim.core.network.BoundUploadSession
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestPimApp::class)
class NoteAutoSubmitStoreTest {
    private val a = NoteUploadTarget(BoundUploadSession("https://notes.example", "11111111-1111-4111-8111-111111111111"), "Alice")
    private val b = NoteUploadTarget(BoundUploadSession("https://other.example", "22222222-2222-4222-8222-222222222222"), "Bob")
    private val versionId = "33333333-3333-4333-8333-333333333333"

    @Before fun clearPreferences() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("native_note_upload_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store() = NoteAutoSubmitStore(ApplicationProvider.getApplicationContext())

    @Test fun authorizationAndIntentSurviveRecreationAndRevokeDurably() {
        assertTrue(store().authorizeForVersion(a, versionId))
        val restored = store()
        assertEquals(NoteAutoSubmitAuthorization(a.binding.serverIdentity, a.binding.ownerId), restored.authorization())
        assertEquals(NoteAutoSubmitIntent(versionId, a.binding.serverIdentity, a.binding.ownerId, a.name), restored.intent(versionId))
        val (revoked, pending) = restored.revoke()
        assertTrue(revoked)
        assertEquals(setOf(versionId), pending)
        assertNull(store().authorization())
        assertNull(store().intent(versionId))
    }

    @Test fun sameTargetRetainsIntentButChangingTargetClearsIt() {
        val local = store()
        assertTrue(local.authorizeForVersion(a, versionId))
        assertTrue(local.authorize(a))
        assertNotNull(local.intent(versionId))
        assertTrue(local.authorize(b))
        assertNull(local.intent(versionId))
        assertEquals(NoteAutoSubmitAuthorization(b.binding.serverIdentity, b.binding.ownerId), local.authorization())
    }
}
