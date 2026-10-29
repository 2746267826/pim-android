package com.pim.core.network

import com.pim.core.auth.AuthSessionStore
import com.pim.core.auth.validOwnerId
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSessionCommitResult
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Local transport tag only. It is neither a remote DTO nor exported material, and contains no tokens. */
data class BoundUploadSession(val serverIdentity: String, val ownerId: String) {
    init {
        require(PimServerEndpoints.normalizeTrustedOrigin(serverIdentity) == serverIdentity)
        require(validOwnerId(ownerId))
    }
}

internal enum class BoundUploadRoute {
    NativeNotes,
    MobileUsage;

    fun permits(request: Request): Boolean = when (this) {
        NativeNotes -> request.url.encodedPath.startsWith("/api/v1/native-notes/")
        MobileUsage -> request.method == "POST" &&
            request.url.encodedPath == "/api/v1/mobile/usage/events"
    }
}

/** Local request metadata: the exact route family is part of the transport binding, not a caller option. */
internal data class BoundUploadRequestTag(
    val binding: BoundUploadSession,
    val route: BoundUploadRoute
)

class UploadSessionChangedException : IOException("上传绑定的账号或服务器已变化，请登录原账号后明确继续；本地数据保留")

internal class BoundUploadTagInterceptor(
    private val binding: BoundUploadSession,
    private val route: BoundUploadRoute
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder()
            .tag(BoundUploadRequestTag::class.java, BoundUploadRequestTag(binding, route))
            .build()
    )
}

internal fun interface BoundSessionCommitGate {
    fun commitIfCurrentServer(
        expectedServerIdentity: String,
        buildRequest: () -> Boolean
    ): ServerSessionCommitResult
}

internal fun authorizedUploadRequest(
    request: Request,
    tagged: BoundUploadRequestTag,
    sessions: AuthSessionStore,
    commitBoundSession: BoundSessionCommitGate
): Pair<Request, String> {
    val binding = tagged.binding
    if (PimServerEndpoints.trustedOriginOf(request.url) != binding.serverIdentity ||
        !tagged.route.permits(request)
    ) {
        throw UploadSessionChangedException()
    }
    var prepared: Pair<Request, String>? = null
    // Same lock order as login/server changes: settings -> immutable session snapshot.
    // Hold it only while constructing headers, never during network I/O or token refresh.
    val result = commitBoundSession.commitIfCurrentServer(binding.serverIdentity) {
        val snapshot = sessions.snapshot()
        val token = snapshot.tokens?.accessToken?.takeIf(String::isNotBlank)
        if (snapshot.serverIdentity != binding.serverIdentity || snapshot.ownerId != binding.ownerId || token == null) false
        else {
            prepared = request.newBuilder()
                .removeHeader("Authorization")
                .header("Authorization", "Bearer $token")
                .build() to token
            true
        }
    }
    if (result != ServerSessionCommitResult.Committed) throw UploadSessionChangedException()
    return prepared ?: throw UploadSessionChangedException()
}
