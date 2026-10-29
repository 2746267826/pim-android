package com.pim.core.network

import com.pim.core.auth.AuthMode
import com.pim.core.auth.AuthSessionStore
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class AuthInterceptor private constructor(
    private val sessionStore: AuthSessionStore,
    private val refreshCoordinator: AuthRefreshCoordinator,
    private val commitBoundSession: BoundSessionCommitGate?
) : Interceptor {
    constructor(
        sessionStore: AuthSessionStore,
        refreshCoordinator: AuthRefreshCoordinator,
        settingsStore: ServerSettingsStore? = null
    ) : this(
        sessionStore,
        refreshCoordinator,
        settingsStore?.let { settings ->
            BoundSessionCommitGate { expectedServerIdentity, buildRequest ->
                settings.commitSessionIfCurrentServer(expectedServerIdentity, buildRequest)
            }
        }
    )

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val bound = original.tag(BoundUploadRequestTag::class.java)
        if (bound != null) authorizeBoundUpload(original, bound)
        val authMode = original.tag(AuthMode::class.java) ?: AuthMode.Required
        if (authMode == AuthMode.Anonymous) {
            if (bound != null) throw UploadSessionChangedException()
            return chain.proceed(
                original.newBuilder()
                    .removeHeader(AUTHORIZATION)
                    .build()
            )
        }

        val serverIdentity = bound?.binding?.serverIdentity ?: PimServerEndpoints.trustedOriginOf(original.url)
        runBlocking { refreshCoordinator.refreshIfExpired(serverIdentity) }
        val boundFirst = bound?.let { authorizeBoundUpload(original, it) }
        val firstAccessToken = boundFirst?.second ?: sessionStore
            .accessTokenForServerIdentity(serverIdentity)
            .nonblank()
        val firstResponse = chain.proceed(boundFirst?.first ?: original.withAccessToken(firstAccessToken))
        if (firstResponse.code != 401) return firstResponse

        val unauthorizedResponse = firstResponse.newBuilder()
            .body(ByteArray(0).toResponseBody(firstResponse.body?.contentType()))
            .build()
        firstResponse.close()
        val refreshed = runBlocking {
            refreshCoordinator.refreshAfterUnauthorized(firstAccessToken, serverIdentity)
        }
        if (!refreshed) return unauthorizedResponse

        unauthorizedResponse.close()
        val boundRetry = bound?.let { authorizeBoundUpload(original, it) }
        val refreshedAccessToken = boundRetry?.second ?: sessionStore
            .accessTokenForServerIdentity(serverIdentity)
            .nonblank()
        return chain.proceed(boundRetry?.first ?: original.withAccessToken(refreshedAccessToken))
    }

    private fun authorizeBoundUpload(
        request: okhttp3.Request,
        bound: BoundUploadRequestTag
    ): Pair<okhttp3.Request, String> {
        val gate = commitBoundSession ?: throw UploadSessionChangedException()
        return authorizedUploadRequest(request, bound, sessionStore, gate)
    }

    private fun okhttp3.Request.withAccessToken(accessToken: String?): okhttp3.Request {
        return newBuilder()
            .removeHeader(AUTHORIZATION)
            .apply {
                if (accessToken != null) header(AUTHORIZATION, "Bearer $accessToken")
            }
            .build()
    }

    private fun String?.nonblank(): String? = this?.takeIf(String::isNotBlank)

    internal companion object {
        const val AUTHORIZATION = "Authorization"

        fun withBoundSessionCommitGate(
            sessionStore: AuthSessionStore,
            refreshCoordinator: AuthRefreshCoordinator,
            commitBoundSession: BoundSessionCommitGate
        ): AuthInterceptor = AuthInterceptor(sessionStore, refreshCoordinator, commitBoundSession)
    }
}
