package com.pim.core.network

import com.pim.core.auth.AuthRefreshOperation
import com.pim.core.auth.AuthRefreshResult
import com.pim.core.auth.AuthSessionSnapshot
import com.pim.core.auth.AuthSessionStore
import com.pim.core.auth.AuthTokens
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSessionCommitResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BoundUploadSessionTest {
    private lateinit var server: MockWebServer
    private lateinit var serverIdentity: String
    private lateinit var sessions: FakeAuthSessionStore
    private lateinit var gate: FakeBoundSessionCommitGate

    private val ownerA = "11111111-1111-4111-8111-111111111111"
    private val ownerB = "22222222-2222-4222-8222-222222222222"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        serverIdentity = PimServerEndpoints.trustedOriginOf(server.url("/"))
        sessions = FakeAuthSessionStore(serverIdentity, ownerA, "access-a", "refresh-a")
        gate = FakeBoundSessionCommitGate(serverIdentity)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun mobileUsageBoundRequestUsesOwnerATokenAtHeaderInjection() {
        server.enqueue(MockResponse().setResponseCode(200))

        boundClient(BoundUploadRoute.MobileUsage, gate).execute(uploadRequest()).use { response ->
            assertEquals(200, response.code)
        }

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/mobile/usage/events", recorded.path)
        assertEquals("Bearer access-a", recorded.getHeader("Authorization"))
    }

    @Test
    fun concurrentOwnerSwitchBeforeHeaderInjectionBlocksWithoutSendingA() {
        val beforeAuthEntered = CountDownLatch(1)
        val releaseAuth = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val binding = BoundUploadSession(serverIdentity, ownerA)
        val client = OkHttpClient.Builder()
            .addInterceptor(BoundUploadTagInterceptor(binding, BoundUploadRoute.MobileUsage))
            .addInterceptor { chain ->
                beforeAuthEntered.countDown()
                check(releaseAuth.await(5, TimeUnit.SECONDS))
                chain.proceed(chain.request())
            }
            .addInterceptor(AuthInterceptor.withBoundSessionCommitGate(
                sessions,
                AuthRefreshCoordinator(sessions, AuthRefreshOperation { _, _ -> AuthRefreshResult.Rejected }),
                gate
            ))
            .build()

        try {
            val call = executor.submit<Unit> {
                client.execute(uploadRequest()).close()
            }
            assertTrue(beforeAuthEntered.await(5, TimeUnit.SECONDS))
            sessions.switchOwner(ownerB, "access-b", "refresh-b")
            releaseAuth.countDown()

            try {
                call.get(5, TimeUnit.SECONDS)
                fail("Owner switch must block the request")
            } catch (error: ExecutionException) {
                assertTrue(error.cause is UploadSessionChangedException)
            }
            assertEquals(0, server.requestCount)
        } finally {
            releaseAuth.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun serverSwitchAtAtomicHeaderInjectionBlocksWithoutSendingA() {
        gate.switchServer("https://server-b.example")

        try {
            boundClient(BoundUploadRoute.MobileUsage, gate).execute(uploadRequest()).close()
            fail("Server switch must block the request")
        } catch (_: UploadSessionChangedException) {
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun requestToDifferentCanonicalOriginIsRejectedBeforeNetwork() {
        val other = MockWebServer()
        other.start()
        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(BoundUploadTagInterceptor(
                    BoundUploadSession(serverIdentity, ownerA),
                    BoundUploadRoute.MobileUsage
                ))
                .addInterceptor(AuthInterceptor.withBoundSessionCommitGate(
                    sessions,
                    AuthRefreshCoordinator(sessions, AuthRefreshOperation { _, _ -> AuthRefreshResult.Rejected }),
                    gate
                ))
                .build()
            val otherRequest = uploadRequest().newBuilder()
                .url(other.url("/api/v1/mobile/usage/events"))
                .build()

            try {
                client.execute(otherRequest).close()
                fail("A request to another canonical origin must be blocked")
            } catch (_: UploadSessionChangedException) {
            }

            assertEquals(0, server.requestCount)
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun accountSwitchDuring401RecoveryNeverRetriesAWithBToken() {
        server.enqueue(MockResponse().setResponseCode(401))
        val refresh = AuthRefreshOperation { _, _ ->
            sessions.switchOwner(ownerB, "access-b", "refresh-b")
            AuthRefreshResult.Rejected
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(BoundUploadTagInterceptor(
                BoundUploadSession(serverIdentity, ownerA),
                BoundUploadRoute.MobileUsage
            ))
            .addInterceptor(AuthInterceptor.withBoundSessionCommitGate(
                sessions,
                AuthRefreshCoordinator(sessions, refresh),
                gate
            ))
            .build()

        try {
            client.execute(uploadRequest()).close()
            fail("Account switch during 401 recovery must block the retry")
        } catch (_: UploadSessionChangedException) {
        }

        assertEquals(1, server.requestCount)
        assertEquals("Bearer access-a", server.takeRequest().getHeader("Authorization"))
        assertEquals(ownerB, sessions.snapshot().ownerId)
    }

    @Test
    fun sameOwner401RefreshRetriesWithCurrentATokenAndDoesNotPersistOldTokenInBinding() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))
        val refresh = AuthRefreshOperation { _, _ ->
            AuthRefreshResult.Success(
                AuthTokens("access-a2", "refresh-a2", Long.MAX_VALUE),
                ownerA,
                "alice"
            )
        }
        val binding = BoundUploadSession(serverIdentity, ownerA)
        val client = OkHttpClient.Builder()
            .addInterceptor(BoundUploadTagInterceptor(binding, BoundUploadRoute.MobileUsage))
            .addInterceptor(AuthInterceptor.withBoundSessionCommitGate(
                sessions,
                AuthRefreshCoordinator(sessions, refresh),
                gate
            ))
            .build()

        client.execute(uploadRequest()).use { response ->
            assertEquals(200, response.code)
        }

        assertEquals(2, server.requestCount)
        assertEquals("Bearer access-a", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer access-a2", server.takeRequest().getHeader("Authorization"))
        assertEquals("access-a2", sessions.snapshot().tokens?.accessToken)
        assertEquals(BoundUploadSession(serverIdentity, ownerA), binding)
    }

    @Test
    fun wrongPathAndWrongMethodAreRejectedBeforeNetwork() {
        val client = boundClient(BoundUploadRoute.MobileUsage, gate)
        val wrongPath = Request.Builder()
            .url(server.url("/api/v1/mobile/usage/events/extra"))
            .post("{}".toRequestBody())
            .build()
        val wrongMethod = Request.Builder()
            .url(server.url("/api/v1/mobile/usage/events"))
            .get()
            .build()

        for (request in listOf(wrongPath, wrongMethod)) {
            try {
                client.execute(request).close()
                fail("Wrong path or method must be rejected")
            } catch (_: UploadSessionChangedException) {
            }
        }

        assertEquals(0, server.requestCount)
    }

    @Test
    fun nativeNotesBoundClientStillWorksAndRemainsSeparatedFromMobileUsage() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = boundClient(BoundUploadRoute.NativeNotes, gate)
        val nativeNotes = Request.Builder()
            .url(server.url("/api/v1/native-notes/versions"))
            .post("{}".toRequestBody())
            .build()

        client.execute(nativeNotes).use { response ->
            assertEquals(200, response.code)
        }
        val recorded = server.takeRequest()
        assertEquals("/api/v1/native-notes/versions", recorded.path)
        assertEquals("Bearer access-a", recorded.getHeader("Authorization"))

        try {
            client.execute(uploadRequest()).close()
            fail("Native-notes client must not call mobile usage")
        } catch (_: UploadSessionChangedException) {
        }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun mobileUsageRouteIsExactPostPathAndNativeNotesRuleRemainsUnchanged() {
        val mobileUsage = Request.Builder()
            .url(server.url("/api/v1/mobile/usage/events"))
            .post("{}".toRequestBody())
            .build()
        assertTrue(BoundUploadRoute.MobileUsage.permits(mobileUsage))
        assertFalse(BoundUploadRoute.NativeNotes.permits(mobileUsage))
        assertFalse(BoundUploadRoute.MobileUsage.permits(
            mobileUsage.newBuilder().url(server.url("/api/v1/mobile/usage/events/extra")).build()
        ))
        assertFalse(BoundUploadRoute.MobileUsage.permits(
            mobileUsage.newBuilder().get().build()
        ))

        val nativeNotes = Request.Builder()
            .url(server.url("/api/v1/native-notes/versions"))
            .post("{}".toRequestBody())
            .build()
        assertTrue(BoundUploadRoute.NativeNotes.permits(nativeNotes))
        assertFalse(BoundUploadRoute.MobileUsage.permits(nativeNotes))
    }

    private fun uploadRequest(): Request = Request.Builder()
        .url(server.url("/api/v1/mobile/usage/events"))
        .post("{}".toRequestBody())
        .build()

    private fun boundClient(
        route: BoundUploadRoute,
        commitGate: BoundSessionCommitGate
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BoundUploadTagInterceptor(
            BoundUploadSession(serverIdentity, ownerA),
            route
        ))
        .addInterceptor(AuthInterceptor.withBoundSessionCommitGate(
            sessions,
            AuthRefreshCoordinator(sessions, AuthRefreshOperation { _, _ -> AuthRefreshResult.Rejected }),
            commitGate
        ))
        .build()

    private fun OkHttpClient.execute(request: Request) = newCall(request).execute()

    private class FakeBoundSessionCommitGate(initialServer: String) : BoundSessionCommitGate {
        private val lock = Any()
        private var currentServer = initialServer

        fun switchServer(serverIdentity: String) {
            synchronized(lock) {
                currentServer = serverIdentity
            }
        }

        override fun commitIfCurrentServer(
            expectedServerIdentity: String,
            buildRequest: () -> Boolean
        ): ServerSessionCommitResult = synchronized(lock) {
            if (currentServer != expectedServerIdentity) {
                ServerSessionCommitResult.ServerChanged
            } else if (buildRequest()) {
                ServerSessionCommitResult.Committed
            } else {
                ServerSessionCommitResult.SaveFailed
            }
        }
    }

    private class FakeAuthSessionStore(
        serverIdentity: String,
        ownerId: String,
        accessToken: String,
        refreshToken: String
    ) : AuthSessionStore {
        private val lock = Any()
        private var current = AuthSessionSnapshot(
            tokens = AuthTokens(accessToken, refreshToken, Long.MAX_VALUE),
            serverIdentity = serverIdentity,
            ownerId = ownerId,
            ownerName = ownerId.take(4)
        )

        override fun snapshot(): AuthSessionSnapshot = synchronized(lock) { current }

        override fun save(
            accessToken: String,
            refreshToken: String,
            expiresAtUtcMillis: Long,
            serverIdentity: String
        ): Boolean = synchronized(lock) {
            current = AuthSessionSnapshot(
                tokens = AuthTokens(accessToken, refreshToken, expiresAtUtcMillis),
                serverIdentity = serverIdentity
            )
            true
        }

        override fun saveOwned(
            accessToken: String,
            refreshToken: String,
            expiresAtUtcMillis: Long,
            serverIdentity: String,
            ownerId: String,
            ownerName: String?
        ): Boolean = synchronized(lock) {
            current = AuthSessionSnapshot(
                tokens = AuthTokens(accessToken, refreshToken, expiresAtUtcMillis),
                serverIdentity = serverIdentity,
                ownerId = ownerId,
                ownerName = ownerName
            )
            true
        }

        fun switchOwner(ownerId: String, accessToken: String, refreshToken: String) {
            synchronized(lock) {
                current = current.copy(
                    tokens = AuthTokens(accessToken, refreshToken, Long.MAX_VALUE),
                    ownerId = ownerId,
                    ownerName = ownerId.take(4)
                )
            }
        }

        override fun clear(): Boolean = synchronized(lock) {
            current = AuthSessionSnapshot(null)
            true
        }
    }
}
