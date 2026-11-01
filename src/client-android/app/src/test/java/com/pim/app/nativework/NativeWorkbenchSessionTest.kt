package com.pim.app.nativework

import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import com.pim.core.network.UploadSessionChangedException
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeWorkbenchSessionTest {
    private val ownerA = "11111111-1111-4111-8111-111111111111"
    private val ownerB = "22222222-2222-4222-8222-222222222222"

    @Test
    fun loadedBindingCanApplyUntilOwnerOrGenerationChanges() {
        val transport = FakeTransport(session(ownerA, generation = 1L))
        val guard = NativeWorkbenchSessionGuard(transport)

        val first = guard.beginLoad()
        assertTrue(guard.canApply(first))
        assertEquals(first, guard.requireLoadedSession())

        transport.current = session(ownerB, generation = 2L)

        assertFalse(guard.canApply(first))
        assertThrows(UploadSessionChangedException::class.java) {
            guard.requireLoadedSession()
        }
    }

    @Test
    fun sameOwnerAndServerStillStalesOnIdentityEpochChange() {
        val transport = FakeTransport(session(ownerA, generation = 1L))
        val guard = NativeWorkbenchSessionGuard(transport)
        val old = guard.beginLoad()

        transport.current = session(ownerA, generation = 2L)

        assertFalse(guard.canApply(old))
        assertThrows(UploadSessionChangedException::class.java) {
            guard.requireLoadedSession()
        }
    }

    @Test
    fun explicitReloadRebindsOnlyToTheCurrentSession() {
        val transport = FakeTransport(session(ownerA, generation = 1L))
        val guard = NativeWorkbenchSessionGuard(transport)
        val first = guard.beginLoad()

        transport.current = session(ownerB, generation = 2L)
        val second = guard.beginLoad()

        assertEquals(ownerB, second.binding.ownerId)
        assertTrue(guard.canApply(second))
        assertFalse(guard.canApply(first))
        assertEquals(second, guard.requireLoadedSession())
    }

    private fun session(ownerId: String, generation: Long) = NativeWorkbenchSession(
        binding = BoundUploadSession("https://pim.example", ownerId),
        identityGeneration = generation,
    )

    private class FakeTransport(var current: NativeWorkbenchSession?) :
        NativeWorkbenchTransport {
        override fun currentSession(): NativeWorkbenchSession? = current

        override fun isCurrent(session: NativeWorkbenchSession): Boolean = current == session

        override fun apiFor(session: NativeWorkbenchSession): ApiService {
            if (!isCurrent(session)) throw UploadSessionChangedException()
            return Proxy.newProxyInstance(
                ApiService::class.java.classLoader,
                arrayOf(ApiService::class.java),
            ) { _, _, _ -> error("not used") } as ApiService
        }
    }
}
