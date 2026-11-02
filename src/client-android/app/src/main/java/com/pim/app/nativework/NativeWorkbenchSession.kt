package com.pim.app.nativework

import com.pim.core.auth.TokenManager
import com.pim.core.network.ApiClientProvider
import com.pim.core.network.ApiService
import com.pim.core.network.BoundUploadSession
import com.pim.core.network.UploadSessionChangedException
import javax.inject.Inject
import javax.inject.Singleton

/** Owner/server identity plus a monotonic epoch; tokens are never stored here. */
data class NativeWorkbenchSession(
    val binding: BoundUploadSession,
    val identityGeneration: Long,
)

interface NativeWorkbenchTransport {
    fun currentSession(): NativeWorkbenchSession?
    fun isCurrent(session: NativeWorkbenchSession): Boolean
    fun apiFor(session: NativeWorkbenchSession): ApiService
}

@Singleton
class BoundNativeWorkbenchTransport @Inject constructor(
    private val tokenManager: TokenManager,
    private val clients: ApiClientProvider,
) : NativeWorkbenchTransport {
    override fun currentSession(): NativeWorkbenchSession? {
        val snapshot = tokenManager.snapshot()
        if (snapshot.tokens == null) return null
        val serverIdentity = snapshot.serverIdentity ?: return null
        val ownerId = snapshot.ownerId ?: return null
        val binding = runCatching { BoundUploadSession(serverIdentity, ownerId) }
            .getOrNull()
            ?: return null
        return NativeWorkbenchSession(
            binding = binding,
            identityGeneration = tokenManager.sessionIdentityGeneration(),
        )
    }

    override fun isCurrent(session: NativeWorkbenchSession): Boolean =
        currentSession() == session

    override fun apiFor(session: NativeWorkbenchSession): ApiService {
        if (!isCurrent(session)) throw UploadSessionChangedException()
        return clients.workbenchApiService(session.binding)
    }
}

internal class NativeWorkbenchSessionGuard(
    private val transport: NativeWorkbenchTransport,
) {
    private var loadedSession: NativeWorkbenchSession? = null

    @Synchronized
    fun beginLoad(): NativeWorkbenchSession = currentOrThrow().also { markLoaded(it) }

    @Synchronized
    fun currentOrThrow(): NativeWorkbenchSession =
        transport.currentSession() ?: throw UploadSessionChangedException()

    @Synchronized
    fun loadedSessionOrNull(): NativeWorkbenchSession? = loadedSession

    @Synchronized
    fun requireLoadedSession(): NativeWorkbenchSession {
        val loaded = loadedSession ?: throw UploadSessionChangedException()
        if (!transport.isCurrent(loaded)) throw UploadSessionChangedException()
        return loaded
    }

    @Synchronized
    fun isLoadedSession(session: NativeWorkbenchSession): Boolean = loadedSession == session

    @Synchronized
    fun canApply(session: NativeWorkbenchSession): Boolean =
        transport.isCurrent(session)

    @Synchronized
    fun markLoaded(session: NativeWorkbenchSession) {
        if (!transport.isCurrent(session)) throw UploadSessionChangedException()
        loadedSession = session
    }

    @Synchronized
    fun clear() {
        loadedSession = null
    }
}
