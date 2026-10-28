package com.pim.core.network

import com.pim.core.auth.AuthSessionStore
import com.pim.core.auth.validOwnerId
import com.pim.core.settings.PimServerEndpoints
import com.pim.core.settings.ServerSessionCommitResult
import com.pim.core.settings.ServerSettingsStore
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

class UploadSessionChangedException : IOException("上传绑定的账号或服务器已变化，请登录原账号后明确继续；固定材料保留")

internal class BoundUploadTagInterceptor(private val binding: BoundUploadSession) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
        chain.request().newBuilder().tag(BoundUploadSession::class.java, binding).build()
    )
}

internal fun authorizedUploadRequest(request: Request, binding: BoundUploadSession,
    sessions: AuthSessionStore, settings: ServerSettingsStore?): Pair<Request, String> {
    if (PimServerEndpoints.trustedOriginOf(request.url) != binding.serverIdentity ||
        !request.url.encodedPath.startsWith("/api/v1/native-notes/")) throw UploadSessionChangedException()
    var prepared: Pair<Request, String>? = null
    // Same lock order as login/server changes: settings -> immutable session snapshot.
    // Hold it only while constructing headers, never during network I/O or token refresh.
    val result = settings?.commitSessionIfCurrentServer(binding.serverIdentity) {
        val snapshot = sessions.snapshot()
        val token = snapshot.tokens?.accessToken?.takeIf(String::isNotBlank)
        if (snapshot.serverIdentity != binding.serverIdentity || snapshot.ownerId != binding.ownerId || token == null) false
        else {
            prepared = request.newBuilder().removeHeader("Authorization").header("Authorization", "Bearer $token").build() to token
            true
        }
    }
    if (result != ServerSessionCommitResult.Committed) throw UploadSessionChangedException()
    return prepared ?: throw UploadSessionChangedException()
}
